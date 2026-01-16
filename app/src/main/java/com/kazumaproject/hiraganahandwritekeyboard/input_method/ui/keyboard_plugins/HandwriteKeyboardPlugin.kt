package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.keyboard_plugins

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.graphics.createBitmap
import com.kazumaproject.hiraganahandwritekeyboard.R
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.DrawingView
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.DualDrawingComposerView
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.HiraCtcRecognizer
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.utils.BitmapPreprocessor
import com.kazumaproject.hiraganahandwritekeyboard.input_method.domain.KeyboardAction
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.KeyboardPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 要件実装:
 * - onStrokeCommitted 毎に推論 → IME の Preedit 末尾を「置換」で更新（InputTextを送る）
 * - 反対側に触れた瞬間（onStrokeStarted）に、それまでの末尾置換を「確定」（= 置換対象から外す）
 *   かつ 直前側 DrawingView をクリア
 * - ConcurrentModificationException 対策として export は Main スレッドで行う
 */
class HandwriteKeyboardPlugin : KeyboardPlugin {

    override val id: String = "handwrite"
    override val displayName: String = "Handwrite"

    // ---- coroutine scope (plugin lifetime) ----
    private val pluginJob: Job = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(pluginJob + Dispatchers.Main.immediate)

    // ---- recognizer ----
    private var recognizer: HiraCtcRecognizer? = null

    // ---- debounce jobs per side ----
    private var inferJobA: Job? = null
    private var inferJobB: Job? = null

    // ---- active side ----
    private var activeSide: DualDrawingComposerView.Side = DualDrawingComposerView.Side.A

    // ---- 「置換」対象の末尾スロット長（active side のみ） ----
    private var activePreviewLen: Int = 0

    // ---- generation token（古い推論結果で上書きしない） ----
    private var genA: Long = 0L
    private var genB: Long = 0L

    override fun createView(parent: ViewGroup, controller: ImeController): View {
        val v =
            LayoutInflater.from(parent.context).inflate(R.layout.keyboard_handwrite, parent, false)

        val dual: DualDrawingComposerView = v.findViewById(R.id.dualDrawing)

        val btnSpace: Button = v.findViewById(R.id.btnSpace)
        val btnEnter: Button = v.findViewById(R.id.btnEnter)
        val btnBackspace: Button = v.findViewById(R.id.btnBackspace)
        val btnClear: Button = v.findViewById(R.id.btnClear)
        val btnA: Button = v.findViewById(R.id.btnA)

        // --- init recognizer lazily ---
        if (recognizer == null) {
            recognizer = HiraCtcRecognizer(
                context = parent.context.applicationContext,
                modelAssetName = DEFAULT_MODEL_ASSET,
                modelFilePath = null,
                vocabAssetName = DEFAULT_VOCAB_ASSET
            )
        }

        // --- buttons: forward to IME controller ---
        btnSpace.setOnClickListener { controller.dispatch(KeyboardAction.InputText(" ")) }
        btnEnter.setOnClickListener { controller.dispatch(KeyboardAction.Enter) }
        btnBackspace.setOnClickListener { controller.dispatch(KeyboardAction.Backspace) }

        btnClear.setOnClickListener {
            dual.clearBoth()
            cancelInferJobs()
            // 「置換中」だった末尾スロットも破棄した扱いにする（以後 Backspace で消さない）
            activePreviewLen = 0
            // generation を進めて古い推論を無効化
            genA++
            genB++
        }

        // btnA の意味はあなたが決めるものなので削除しない（ここでは元のまま「何もしない」ではなく例として「あ」）
        btnA.setOnClickListener { controller.dispatch(KeyboardAction.InputText("あ")) }

        // --- DualDrawing callbacks ---
        dual.onStrokeStarted = { side ->
            if (side != activeSide) {
                // 反対側に触れた瞬間に「確定」:
                // - これまでの末尾置換を終了（= 置換対象から外す）
                activePreviewLen = 0

                // - 直前側をクリア（あなたの要件）
                val prev = activeSide
                drawingViewFor(dual, prev).clearCanvas()

                // - 直前側の推論を無効化（古い結果で上書きしない）
                bumpGeneration(prev)
                cancelInferJobFor(prev)

                activeSide = side
                // 新しい side での置換スロットは未開始
                // activePreviewLen はすでに 0
            } else {
                // 同じ side を続けて触っただけなら何もしない
            }
        }

        dual.onStrokeCommitted = { side ->
            // stroke確定 -> 推論して IME の末尾を置換更新
            scheduleInferAndReplaceTail(dual, side, controller)
        }

        return v
    }

    override fun onDestroy() {
        cancelInferJobs()
        pluginJob.cancel()
    }

    // ---------------- infer + replace tail ----------------

    private fun scheduleInferAndReplaceTail(
        dual: DualDrawingComposerView,
        side: DualDrawingComposerView.Side,
        controller: ImeController
    ) {
        // generation token を採番（この時点より古い結果は捨てる）
        val token = bumpGeneration(side)

        val jobRef = if (side == DualDrawingComposerView.Side.A) inferJobA else inferJobB
        jobRef?.cancel()

        val newJob = scope.launch {
            delay(HandwriteCommitConfig.debounceMs)

            // 触っている side が変わっていたら（=確定済み）この結果は捨てる
            if (!isLatest(side, token)) return@launch

            val dv = drawingViewFor(dual, side)
            if (!dv.hasInk()) return@launch

            // export は UI状態を読むので Main スレッドで行う（ConcurrentModificationException 対策）
            val strokeWidthPx = DEFAULT_STROKE_WIDTH_PX.toFloat().coerceAtLeast(1f)

            val whiteBmp = withContext(Dispatchers.Main.immediate) {
                exportForInferOnMain(dv, strokeWidthPx)
            }

            // 推論は重いのでバックグラウンド
            val detected = withContext(Dispatchers.Default) {
                runSingleInferTop1(whiteBmp)
            }.trim()

            if (!isLatest(side, token)) return@launch
            if (detected.isBlank()) return@launch

            // 重要: 末尾置換は「アクティブ side のみ」に適用
            if (side != activeSide) return@launch

            applyReplaceTailToIme(controller, detected)
        }

        if (side == DualDrawingComposerView.Side.A) {
            inferJobA = newJob
        } else {
            inferJobB = newJob
        }
    }

    /**
     * IME（Preedit）の末尾を「置換」する。
     * - 直前に挿入した暫定文字数 activePreviewLen を Backspace で消してから InputText
     * - これを onStrokeCommitted 毎に繰り返すことで「置換更新」になる
     */
    private fun applyReplaceTailToIme(controller: ImeController, newText: String) {
        // Direct でこれをやるとエディタ本文を削る危険があるため、Preedit の時だけ置換モードを使う
        if (!controller.isPreedit) {
            controller.dispatch(KeyboardAction.InputText(newText))
            return
        }

        // 末尾の暫定スロットを削除
        repeat(activePreviewLen) {
            controller.dispatch(KeyboardAction.Backspace)
        }

        // 新しい暫定文字を挿入
        controller.dispatch(KeyboardAction.InputText(newText))

        activePreviewLen = newText.length
    }

    // ---------------- generation helpers ----------------

    private fun bumpGeneration(side: DualDrawingComposerView.Side): Long {
        return if (side == DualDrawingComposerView.Side.A) {
            genA += 1
            genA
        } else {
            genB += 1
            genB
        }
    }

    private fun isLatest(side: DualDrawingComposerView.Side, token: Long): Boolean {
        return if (side == DualDrawingComposerView.Side.A) token == genA else token == genB
    }

    // ---------------- config ----------------

    object HandwriteCommitConfig {
        /**
         * onStrokeCommitted 後、推論を走らせるまでの待ち時間
         * - 小さすぎると推論が多発、UIも重くなる
         * - 大きすぎると反映が遅い
         */
        @Volatile
        var debounceMs: Long = 120L
    }

    // ---------------- view/helpers ----------------

    private fun drawingViewFor(
        dual: DualDrawingComposerView,
        side: DualDrawingComposerView.Side
    ): DrawingView {
        return if (side == DualDrawingComposerView.Side.A) dual.viewA else dual.viewB
    }

    private fun cancelInferJobs() {
        inferJobA?.cancel(); inferJobA = null
        inferJobB?.cancel(); inferJobB = null
    }

    private fun cancelInferJobFor(side: DualDrawingComposerView.Side) {
        if (side == DualDrawingComposerView.Side.A) {
            inferJobA?.cancel(); inferJobA = null
        } else {
            inferJobB?.cancel(); inferJobB = null
        }
    }

    /**
     * ConcurrentModificationException 対策:
     * - DrawingView の stroke list は UI スレッドで更新されているため、export は Main で行う
     */
    private fun exportForInferOnMain(drawingView: DrawingView, strokeWidthPx: Float): Bitmap {
        val border = max(24, (strokeWidthPx * 2.2f).toInt())
        val strokes = drawingView.exportStrokesBitmapTransparent(borderPx = border)

        val out = createBitmap(strokes.width, strokes.height)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(strokes, 0f, 0f, null)
        return out
    }

    private fun runSingleInferTop1(whiteBmp: Bitmap): String {
        val r = recognizer ?: return ""

        return try {
            // 1) 極端に小さい入力は弾く（export結果が細すぎるケース）
            //    ※数値は保守的。必要なら調整してください。
            if (whiteBmp.width < 16 || whiteBmp.height < 16) return ""

            val normalized = BitmapPreprocessor.tightCenterSquare(
                srcWhiteBg = whiteBmp,
                inkThresh = 245,
                innerPadPx = 8,
                outerMarginPx = 24,
                // 2) 最小サイズを引き上げる（CRNNのpoolingで幅が0になるのを防ぐ）
                minSidePx = 192
            )

            // 3) ここでも最終チェック（tightCenterSquareの実装次第で保険）
            if (normalized.width < 32 || normalized.height < 32) return ""

            val candidates = r.inferTopK(
                bitmap = normalized,
                topK = 1,
                beamWidth = 25,
                perStepTop = 25
            )

            candidates.firstOrNull()?.text.orEmpty()
        } catch (_: Throwable) {
            // TorchScriptは入力条件違反で例外を投げるので、クラッシュさせず無視する
            ""
        }
    }

    companion object {
        private const val DEFAULT_STROKE_WIDTH_PX = 32

        private const val DEFAULT_MODEL_ASSET = "model_torchscript.pt"
        private const val DEFAULT_VOCAB_ASSET = "vocab.json"
    }
}
