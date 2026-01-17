package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.keyboard_plugins

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.TypedValue
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
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets.CursorNavView
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

    // ---- keyRows refs (for runtime switching) ----
    private var keyRowsLeftRef: KeyboardKeyRowsView? = null
    private var keyRowsRightRef: KeyboardKeyRowsView? = null
    private var keyRowsBottomRef: KeyboardKeyRowsView? = null

    // ---- keep last view/controller for immediate reconfigure ----
    private var lastDualRef: DualDrawingComposerView? = null
    private var lastControllerRef: ImeController? = null

    // ---- computed key height ----
    private var computedKeyMinHeightDp: Int = 40 // 初期値（描画View計測後に上書き）

    // ---------------- key rows mode ----------------

    enum class KeyRowsMode {
        LEFT_ONLY,
        RIGHT_ONLY,
        BOTH,
        BOTTOM_ONLY,
        BOTH_WITH_BOTTOM,
        NONE
    }

    object HandwriteUiConfig {
        @Volatile
        var keyRowsMode: KeyRowsMode = KeyRowsMode.RIGHT_ONLY
    }

    fun setKeyRowsMode(mode: KeyRowsMode) {
        HandwriteUiConfig.keyRowsMode = mode

        applyKeyRowsMode(
            left = keyRowsLeftRef,
            right = keyRowsRightRef,
            bottom = keyRowsBottomRef,
            mode = mode
        )

        val dual = lastDualRef
        val controller = lastControllerRef
        val left = keyRowsLeftRef
        val right = keyRowsRightRef
        val bottom = keyRowsBottomRef
        if (dual != null && controller != null && left != null && right != null) {
            configureKeyRows(
                mode = mode,
                leftRows = left,
                rightRows = right,
                bottomRows = bottom,
                dual = dual,
                controller = controller,
                keyMinHeightDp = computedKeyMinHeightDp
            )
        }
    }

    private fun applyKeyRowsMode(
        left: KeyboardKeyRowsView?,
        right: KeyboardKeyRowsView?,
        bottom: KeyboardKeyRowsView?,
        mode: KeyRowsMode
    ) {
        when (mode) {
            KeyRowsMode.LEFT_ONLY -> {
                left?.visibility = View.VISIBLE
                right?.visibility = View.GONE
                bottom?.visibility = View.GONE
            }

            KeyRowsMode.RIGHT_ONLY -> {
                left?.visibility = View.GONE
                right?.visibility = View.VISIBLE
                bottom?.visibility = View.GONE
            }

            KeyRowsMode.BOTH -> {
                left?.visibility = View.VISIBLE
                right?.visibility = View.VISIBLE
                bottom?.visibility = View.GONE
            }

            KeyRowsMode.BOTTOM_ONLY -> {
                left?.visibility = View.GONE
                right?.visibility = View.GONE
                bottom?.visibility = View.VISIBLE
            }

            KeyRowsMode.BOTH_WITH_BOTTOM -> {
                left?.visibility = View.VISIBLE
                right?.visibility = View.VISIBLE
                bottom?.visibility = View.VISIBLE
            }

            KeyRowsMode.NONE -> {
                left?.visibility = View.GONE
                right?.visibility = View.GONE
                bottom?.visibility = View.GONE
            }
        }
    }

    override fun createView(parent: ViewGroup, controller: ImeController): View {
        val v =
            LayoutInflater.from(parent.context).inflate(R.layout.keyboard_handwrite, parent, false)

        val dual: DualDrawingComposerView = v.findViewById(R.id.dualDrawing)
        lastDualRef = dual
        lastControllerRef = controller

        if (recognizer == null) {
            recognizer = HiraCtcRecognizer(
                context = parent.context.applicationContext,
                modelAssetName = DEFAULT_MODEL_ASSET,
                modelFilePath = null,
                vocabAssetName = DEFAULT_VOCAB_ASSET
            )
        }

        adapterA = CtcCandidateAdapter { c ->
            onCandidateTapped(dual, DualDrawingComposerView.Side.A, c, controller)
        }
        adapterB = CtcCandidateAdapter { c ->
            onCandidateTapped(dual, DualDrawingComposerView.Side.B, c, controller)
        }

        dual.candidateListA.layoutManager =
            LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        dual.candidateListB.layoutManager =
            LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)

        dual.candidateListA.adapter = adapterA
        dual.candidateListB.adapter = adapterB

        submitCandidates(dual, DualDrawingComposerView.Side.A, emptyList())
        submitCandidates(dual, DualDrawingComposerView.Side.B, emptyList())

        val leftRows: KeyboardKeyRowsView = v.findViewById(R.id.keyRowsLeft)
        val rightRows: KeyboardKeyRowsView = v.findViewById(R.id.keyRowsRight)
        val bottomRows: KeyboardKeyRowsView? = v.findViewById(R.id.keyRowsBottom)

        keyRowsLeftRef = leftRows
        keyRowsRightRef = rightRows
        keyRowsBottomRef = bottomRows

        val mode = HandwriteUiConfig.keyRowsMode

        // まず初期表示
        applyKeyRowsMode(leftRows, rightRows, bottomRows, mode)
        configureKeyRows(
            mode = mode,
            leftRows = leftRows,
            rightRows = rightRows,
            bottomRows = bottomRows,
            dual = dual,
            controller = controller,
            keyMinHeightDp = computedKeyMinHeightDp
        )

        // ---- ここが本題：drawingView の実高さに合わせて keyRows の高さとキー高さを再計算 ----
        // drawingViewA/B が 200dp 固定でも、将来変えても追随できるようにする
        v.post {
            val drawingHeightPx = dual.viewA.height.takeIf { it > 0 }
                ?: dual.viewA.layoutParams.height.takeIf { it > 0 }
                ?: dpToPx(v, 200f)

            // keyRowsLeft/right のコンテナ高さを drawing と同じにする（モードによって見えている方だけでもOK）
            leftRows.layoutParams = leftRows.layoutParams.apply { height = drawingHeightPx }
            rightRows.layoutParams = rightRows.layoutParams.apply { height = drawingHeightPx }

            // 縦並び（5キー）でピッタリ埋めるために minHeightDp を算出
            val drawingHeightDp = (drawingHeightPx / v.resources.displayMetrics.density)
            val perKeyDp = (drawingHeightDp / 5f).toInt().coerceAtLeast(32) // 下限は好みで調整可
            computedKeyMinHeightDp = perKeyDp

            // 再構築（キーの minHeightDp が変わる）
            applyKeyRowsMode(leftRows, rightRows, bottomRows, mode)
            configureKeyRows(
                mode = mode,
                leftRows = leftRows,
                rightRows = rightRows,
                bottomRows = bottomRows,
                dual = dual,
                controller = controller,
                keyMinHeightDp = computedKeyMinHeightDp
            )

            leftRows.requestLayout()
            rightRows.requestLayout()
        }

        dual.onStrokeStarted = { side ->
            if (side != activeSide) {
                activePreviewLen = 0

                val prev = activeSide
                drawingViewFor(dual, prev).clearCanvas()
                submitCandidates(dual, prev, emptyList())

                bumpGeneration(prev)
                cancelInferJobFor(prev)

                activeSide = side
            }
        }

        dual.onStrokeCommitted = { side ->
            scheduleInferReplaceAndShowCandidates(dual, side, controller)
        }

        return v
    }

    override fun onDestroy() {
        cancelInferJobs()
        pluginJob.cancel()

        keyRowsLeftRef = null
        keyRowsRightRef = null
        keyRowsBottomRef = null
        lastDualRef = null
        lastControllerRef = null
    }

    // ---------------- key rows building ----------------

    private fun configureKeyRows(
        mode: KeyRowsMode,
        leftRows: KeyboardKeyRowsView,
        rightRows: KeyboardKeyRowsView,
        bottomRows: KeyboardKeyRowsView?,
        dual: DualDrawingComposerView,
        controller: ImeController,
        keyMinHeightDp: Int
    ) {
        fun clearKey() = KeyboardKeySpec.ButtonKey(
            keyId = "clear",
            text = "\uD83E\uDDF9",
            minHeightDp = keyMinHeightDp,
            onClick = {
                dual.clearBoth()
                cancelInferJobs()

                it.dispatch(KeyboardAction.Backspace)
                if (controller.isPreedit && activePreviewLen > 0) {
                    activePreviewLen = (activePreviewLen - 1).coerceAtLeast(0)
                }

                submitCandidates(dual, DualDrawingComposerView.Side.A, emptyList())
                submitCandidates(dual, DualDrawingComposerView.Side.B, emptyList())

                activePreviewLen = 0
                genA++
                genB++
            },
            repeatOnLongPress = false
        )

        fun cursorNavKey() = KeyboardKeySpec.CustomViewKey(
            keyId = "cursor_nav",
            minHeightDp = keyMinHeightDp,
            createView = { ctx, _parent, c ->
                CursorNavView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    // ★キー高さに合わせる
                    setButtonHeightDp(keyMinHeightDp.toFloat())
                    setIconTextSizeSp(8f)

                    setListener(object : CursorNavView.Listener {
                        override fun onAction(
                            side: CursorNavView.Side,
                            action: CursorNavView.Action
                        ) {
                            when (action) {
                                CursorNavView.Action.TAP,
                                CursorNavView.Action.LONG_TAP -> {
                                    if (side == CursorNavView.Side.LEFT) {
                                        c.dispatch(KeyboardAction.MoveCursor(-1))
                                        dual.clearBoth()
                                        cancelInferJobs()
                                        submitCandidates(dual, DualDrawingComposerView.Side.A, emptyList())
                                        submitCandidates(dual, DualDrawingComposerView.Side.B, emptyList())

                                        activePreviewLen = 0
                                        genA++
                                        genB++
                                    } else {
                                        c.dispatch(KeyboardAction.MoveCursor(+1))
                                        dual.clearBoth()
                                        cancelInferJobs()
                                        submitCandidates(dual, DualDrawingComposerView.Side.A, emptyList())
                                        submitCandidates(dual, DualDrawingComposerView.Side.B, emptyList())

                                        activePreviewLen = 0
                                        genA++
                                        genB++
                                    }
                                }

                                CursorNavView.Action.FLICK_UP,
                                CursorNavView.Action.LONG_FLICK_UP -> {
                                    c.dispatch(KeyboardAction.MoveCursorVertical(-1))
                                    dual.clearBoth()
                                    cancelInferJobs()
                                    submitCandidates(dual, DualDrawingComposerView.Side.A, emptyList())
                                    submitCandidates(dual, DualDrawingComposerView.Side.B, emptyList())

                                    activePreviewLen = 0
                                    genA++
                                    genB++
                                }

                                CursorNavView.Action.FLICK_DOWN,
                                CursorNavView.Action.LONG_FLICK_DOWN -> {
                                    c.dispatch(KeyboardAction.MoveCursorVertical(+1))
                                    dual.clearBoth()
                                    cancelInferJobs()
                                    submitCandidates(dual, DualDrawingComposerView.Side.A, emptyList())
                                    submitCandidates(dual, DualDrawingComposerView.Side.B, emptyList())

                                    activePreviewLen = 0
                                    genA++
                                    genB++
                                }
                            }
                        }
                    })
                }
            }
        )

        fun backspaceKey() = KeyboardKeySpec.ButtonKey(
            keyId = "backspace",
            text = "⌫",
            minHeightDp = keyMinHeightDp,
            onClick = {
                it.dispatch(KeyboardAction.Backspace)
                if (controller.isPreedit && activePreviewLen > 0) {
                    activePreviewLen = (activePreviewLen - 1).coerceAtLeast(0)
                }

                dual.clearBoth()
                cancelInferJobs()

                submitCandidates(dual, DualDrawingComposerView.Side.A, emptyList())
                submitCandidates(dual, DualDrawingComposerView.Side.B, emptyList())

                activePreviewLen = 0
                genA++
                genB++
            },
            repeatOnLongPress = true,
            repeatIntervalMs = 60L,
            onFlickUp = { it.dispatch(KeyboardAction.MoveCursorVertical(-1)) },
            onFlickDown = { it.dispatch(KeyboardAction.MoveCursorVertical(+1)) }
        )

        fun spaceKey() = KeyboardKeySpec.ButtonKey(
            keyId = "space",
            text = "␣",
            minHeightDp = keyMinHeightDp,
            onClick = {
                it.dispatch(KeyboardAction.InputText(" "))
                activePreviewLen = 0
            },
            repeatOnLongPress = false
        )

        fun enterKey() = KeyboardKeySpec.ButtonKey(
            keyId = "enter",
            text = "⏎",
            minHeightDp = keyMinHeightDp,
            onClick = {
                it.dispatch(KeyboardAction.Enter)
                activePreviewLen = 0

                dual.clearBoth()
                cancelInferJobs()

                submitCandidates(dual, DualDrawingComposerView.Side.A, emptyList())
                submitCandidates(dual, DualDrawingComposerView.Side.B, emptyList())

                activePreviewLen = 0
                genA++
                genB++
            },
            repeatOnLongPress = false
        )

        val allKeysVertical = listOf(
            //listOf(clearKey()),
            listOf(backspaceKey()),
            listOf(cursorNavKey()),
            listOf(spaceKey()),
            listOf(enterKey())
        )

        val allKeysHorizontal = listOf(
            listOf(
                //clearKey(),
                backspaceKey(),
                cursorNavKey(),
                spaceKey(),
                enterKey()
            )
        )

        val leftOnly = listOf(listOf(clearKey()))
        val rightOnly = listOf(
            listOf(backspaceKey()),
            listOf(spaceKey()),
            listOf(enterKey())
        )

        when (mode) {
            KeyRowsMode.BOTH -> {
                leftRows.setRows(rows = leftOnly, controller = controller)
                rightRows.setRows(rows = rightOnly, controller = controller)
                bottomRows?.setRows(rows = emptyList(), controller = controller)
            }

            KeyRowsMode.LEFT_ONLY -> {
                leftRows.setRows(rows = allKeysVertical, controller = controller)
                rightRows.setRows(rows = emptyList(), controller = controller)
                bottomRows?.setRows(rows = emptyList(), controller = controller)
            }

            KeyRowsMode.RIGHT_ONLY -> {
                rightRows.setRows(rows = allKeysVertical, controller = controller)
                leftRows.setRows(rows = emptyList(), controller = controller)
                bottomRows?.setRows(rows = emptyList(), controller = controller)
            }

            KeyRowsMode.BOTTOM_ONLY -> {
                leftRows.setRows(rows = emptyList(), controller = controller)
                rightRows.setRows(rows = emptyList(), controller = controller)
                bottomRows?.setRows(rows = allKeysHorizontal, controller = controller)
            }

            KeyRowsMode.BOTH_WITH_BOTTOM -> {
                leftRows.setRows(rows = leftOnly, controller = controller)
                rightRows.setRows(rows = rightOnly, controller = controller)
                bottomRows?.setRows(rows = allKeysHorizontal, controller = controller)
            }

            KeyRowsMode.NONE -> {
                leftRows.setRows(rows = emptyList(), controller = controller)
                rightRows.setRows(rows = emptyList(), controller = controller)
                bottomRows?.setRows(rows = emptyList(), controller = controller)
            }
        }
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

        bumpGeneration(side)
        cancelInferJobFor(side)

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

            val whiteBmp = withContext(Dispatchers.Main.immediate) {
                exportForInferOnMain(dv, strokeWidthPx)
            }

            val candidates = withContext(Dispatchers.Default) {
                runInferTopK(whiteBmp, topK = HandwriteCommitConfig.topK)
            }

            if (!isLatest(side, token)) return@launch

            val filtered = candidates.filter { it.text.isNotBlank() && it.percent > 0.0 }

            Timber.d("scheduleInferReplaceAndShowCandidates candidates: $candidates")
            Timber.d("scheduleInferReplaceAndShowCandidates filtered: $filtered")

            submitCandidates(dual, side, filtered)

            if (side != activeSide) return@launch

            val top1 = filtered.firstOrNull()?.text?.trim().orEmpty()
            if (top1.isBlank()) return@launch

            applyReplaceTailToIme(controller, top1)
        }

        if (side == DualDrawingComposerView.Side.A) inferJobA = newJob else inferJobB = newJob
    }

    private fun submitCandidates(
        dual: DualDrawingComposerView,
        side: DualDrawingComposerView.Side,
        list: List<CtcCandidate>
    ) {
        val rv =
            if (side == DualDrawingComposerView.Side.A) dual.candidateListA else dual.candidateListB
        val ad = if (side == DualDrawingComposerView.Side.A) adapterA else adapterB

        ad?.submitList(list) {
            rv.post { rv.scrollToPosition(0) }
        }
    }

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

    private fun dpToPx(v: View, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            v.resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val DEFAULT_STROKE_WIDTH_PX = 32
        private const val DEFAULT_MODEL_ASSET = "model_torchscript.pt"
        private const val DEFAULT_VOCAB_ASSET = "vocab.json"
    }
}
