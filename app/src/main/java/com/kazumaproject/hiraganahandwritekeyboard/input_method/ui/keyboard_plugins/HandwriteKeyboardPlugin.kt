package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.keyboard_plugins

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.createBitmap
import androidx.recyclerview.widget.LinearLayoutManager
import com.kazumaproject.hiraganahandwritekeyboard.R
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.data.CtcCandidate
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.CtcCandidateAdapter
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.DrawingView
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.DualDrawingComposerView
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.HiraCtcRecognizer
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.utils.BitmapPreprocessor
import com.kazumaproject.hiraganahandwritekeyboard.input_method.domain.KeyboardAction
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.KeyboardPlugin
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets.KeyboardKeyRowsView
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets.KeyboardKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.max

/**
 * 要件実装:
 * - onStrokeCommitted 毎に推論 → IME の Preedit 末尾を「置換」で更新（InputTextを送る）
 * - 反対側に触れた瞬間（onStrokeStarted）に、それまでの末尾置換を「確定」（= 置換対象から外す）
 *   かつ 直前側 DrawingView をクリア
 * - ConcurrentModificationException 対策として export は Main スレッドで行う
 *
 * ★拡張:
 * - 各 DrawingView の下に TopK 候補（percent>0）を横リスト表示
 * - ユーザーがタップした候補を「採用」し、IME末尾置換に反映（active side のみ）
 *
 * ★修正案A:
 * - 候補を submitList した直後に必ず先頭を表示する（初回だけ先頭が隠れる問題を潰す）
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

    // ---- candidate adapters ----
    private var adapterA: CtcCandidateAdapter? = null
    private var adapterB: CtcCandidateAdapter? = null

    override fun createView(parent: ViewGroup, controller: ImeController): View {
        val v =
            LayoutInflater.from(parent.context).inflate(R.layout.keyboard_handwrite, parent, false)

        val dual: DualDrawingComposerView = v.findViewById(R.id.dualDrawing)

        // --- init recognizer lazily ---
        if (recognizer == null) {
            recognizer = HiraCtcRecognizer(
                context = parent.context.applicationContext,
                modelAssetName = DEFAULT_MODEL_ASSET,
                modelFilePath = null,
                vocabAssetName = DEFAULT_VOCAB_ASSET
            )
        }

        // --- setup candidate lists (horizontal) ---
        adapterA = CtcCandidateAdapter { c ->
            onCandidateTapped(
                dual,
                DualDrawingComposerView.Side.A,
                c,
                controller
            )
        }
        adapterB = CtcCandidateAdapter { c ->
            onCandidateTapped(
                dual,
                DualDrawingComposerView.Side.B,
                c,
                controller
            )
        }

        dual.candidateListA.layoutManager =
            LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        dual.candidateListB.layoutManager =
            LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)

        dual.candidateListA.adapter = adapterA
        dual.candidateListB.adapter = adapterB

        // 初期は空（先頭スクロールも含めて統一）
        submitCandidates(dual, DualDrawingComposerView.Side.A, emptyList())
        submitCandidates(dual, DualDrawingComposerView.Side.B, emptyList())

        // --- shared action keys (Space/Enter/Backspace/Clear) ---
        val actionRows: KeyboardKeyRowsView = v.findViewById(R.id.keyRows)

        actionRows.setRows(
            rows = listOf(
                listOf(
                    KeyboardKeySpec(
                        keyId = "space",
                        text = "Space",
                        onClick = { it.dispatch(KeyboardAction.InputText(" ")) }
                    ),
                    KeyboardKeySpec(
                        keyId = "enter",
                        text = "Enter",
                        onClick = { it.dispatch(KeyboardAction.Enter) }
                    ),
                    KeyboardKeySpec(
                        keyId = "backspace",
                        text = "⌫",
                        onClick = { it.dispatch(KeyboardAction.Backspace) }
                    ),
                    KeyboardKeySpec(
                        keyId = "clear",
                        text = "Clear",
                        onClick = {
                            dual.clearBoth()
                            cancelInferJobs()

                            // 候補も消す
                            submitCandidates(dual, DualDrawingComposerView.Side.A, emptyList())
                            submitCandidates(dual, DualDrawingComposerView.Side.B, emptyList())

                            // 「置換中」だった末尾スロットも破棄した扱いにする（以後 Backspace で消さない）
                            activePreviewLen = 0

                            // generation を進めて古い推論を無効化
                            genA++
                            genB++
                        }
                    )
                )
            ),
            controller = controller
        )

        // --- DualDrawing callbacks ---
        dual.onStrokeStarted = { side ->
            if (side != activeSide) {
                // 反対側に触れた瞬間に「確定」
                activePreviewLen = 0

                // 直前側をクリア（要件）
                val prev = activeSide
                drawingViewFor(dual, prev).clearCanvas()

                // 直前側の候補もクリア（UI的に自然）
                submitCandidates(dual, prev, emptyList())

                // 直前側の推論を無効化
                bumpGeneration(prev)
                cancelInferJobFor(prev)

                activeSide = side
            }
        }

        dual.onStrokeCommitted = { side ->
            // stroke確定 -> 推論して IME の末尾を置換更新 + 候補表示
            scheduleInferReplaceAndShowCandidates(dual, side, controller)
        }

        return v
    }

    override fun onDestroy() {
        cancelInferJobs()
        pluginJob.cancel()
    }

    // ---------------- candidate tap ----------------

    private fun onCandidateTapped(
        dual: DualDrawingComposerView,
        side: DualDrawingComposerView.Side,
        candidate: CtcCandidate,
        controller: ImeController
    ) {
        if (candidate.text.isBlank()) return
        if (candidate.percent <= 0.0) return

        // タップされたら「その候補を採用」:
        // 1) このsideの古い推論結果で上書きされないよう generation を進める
        bumpGeneration(side)
        cancelInferJobFor(side)

        // 2) active side のときだけ IME 末尾置換を更新
        if (side != activeSide) return
        applyReplaceTailToIme(controller, candidate.text)
    }

    // ---------------- infer + replace tail + show candidates ----------------

    private fun scheduleInferReplaceAndShowCandidates(
        dual: DualDrawingComposerView,
        side: DualDrawingComposerView.Side,
        controller: ImeController
    ) {
        val token = bumpGeneration(side)

        val jobRef = if (side == DualDrawingComposerView.Side.A) inferJobA else inferJobB
        jobRef?.cancel()

        val newJob = scope.launch {
            delay(HandwriteCommitConfig.debounceMs)

            if (!isLatest(side, token)) return@launch

            val dv = drawingViewFor(dual, side)
            if (!dv.hasInk()) {
                submitCandidates(dual, side, emptyList())
                return@launch
            }

            val strokeWidthPx = DEFAULT_STROKE_WIDTH_PX.toFloat().coerceAtLeast(1f)

            // export は Main
            val whiteBmp = withContext(Dispatchers.Main.immediate) {
                exportForInferOnMain(dv, strokeWidthPx)
            }

            // 推論は Default
            val candidates = withContext(Dispatchers.Default) {
                runInferTopK(whiteBmp, topK = HandwriteCommitConfig.topK)
            }

            if (!isLatest(side, token)) return@launch

            // percent>0 のみ表示（要件）
            val filtered = candidates.filter { it.text.isNotBlank() && it.percent > 0.0 }

            Timber.d("scheduleInferReplaceAndShowCandidates candidates: $candidates")
            Timber.d("scheduleInferReplaceAndShowCandidates filtered: $filtered")

            // UIへ反映（A案：反映後に必ず先頭へ）
            submitCandidates(dual, side, filtered)

            // 末尾置換は active side のみ
            if (side != activeSide) return@launch

            // 自動採用は Top1（filteredが空なら何もしない）
            val top1 = filtered.firstOrNull()?.text?.trim().orEmpty()
            if (top1.isBlank()) return@launch

            applyReplaceTailToIme(controller, top1)
        }

        if (side == DualDrawingComposerView.Side.A) inferJobA = newJob else inferJobB = newJob
    }

    /**
     * ★修正案A: submitList の commitCallback + post で必ず先頭を表示
     */
    private fun submitCandidates(
        dual: DualDrawingComposerView,
        side: DualDrawingComposerView.Side,
        list: List<CtcCandidate>
    ) {
        val rv =
            if (side == DualDrawingComposerView.Side.A) dual.candidateListA else dual.candidateListB
        val ad = if (side == DualDrawingComposerView.Side.A) adapterA else adapterB

        // submitList は差分適用が非同期なので、commit後に先頭へ戻す
        ad?.submitList(list) {
            rv.post { rv.scrollToPosition(0) }
        }
    }

    /**
     * IME（Preedit）の末尾を「置換」する。
     */
    private fun applyReplaceTailToIme(controller: ImeController, newText: String) {
        if (!controller.isPreedit) {
            controller.dispatch(KeyboardAction.InputText(newText))
            return
        }

        repeat(activePreviewLen) {
            controller.dispatch(KeyboardAction.Backspace)
        }

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
        @Volatile
        var debounceMs: Long = 120L

        @Volatile
        var topK: Int = 6
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

    private fun exportForInferOnMain(drawingView: DrawingView, strokeWidthPx: Float): Bitmap {
        val border = max(24, (strokeWidthPx * 2.2f).toInt())
        val strokes = drawingView.exportStrokesBitmapTransparent(borderPx = border)

        val out = createBitmap(strokes.width, strokes.height)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(strokes, 0f, 0f, null)
        return out
    }

    private fun runInferTopK(whiteBmp: Bitmap, topK: Int): List<CtcCandidate> {
        val r = recognizer ?: return emptyList()

        return try {
            if (whiteBmp.width < 16 || whiteBmp.height < 16) return emptyList()

            val normalized = BitmapPreprocessor.tightCenterSquare(
                srcWhiteBg = whiteBmp,
                inkThresh = 245,
                innerPadPx = 8,
                outerMarginPx = 24,
                minSidePx = 192
            )

            if (normalized.width < 32 || normalized.height < 32) return emptyList()

            r.inferTopK(
                bitmap = normalized,
                topK = topK.coerceAtLeast(1),
                beamWidth = 25,
                perStepTop = 25
            )
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object {
        private const val DEFAULT_STROKE_WIDTH_PX = 32
        private const val DEFAULT_MODEL_ASSET = "model_torchscript.pt"
        private const val DEFAULT_VOCAB_ASSET = "vocab.json"
    }
}
