// app/src/main/java/com/kazumaproject/hiraganahandwritekeyboard/input_method/ui/keyboard_plugins/HandwriteKeyboardPlugin.kt
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
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.utils.MultiCharSegmenter
import com.kazumaproject.hiraganahandwritekeyboard.input_method.domain.KeyboardAction
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.HostEvent
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.KeyboardPlugin
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets.CursorNavView
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets.KeyboardKeyRowsView
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets.KeyboardKeySpec
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets.UndoRedoView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class HandwriteKeyboardPlugin : KeyboardPlugin {

    override val id: String = "handwrite"
    override val displayName: String = "Handwrite"

    private val pluginJob: Job = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(pluginJob + Dispatchers.Main.immediate)

    private var recognizer: HiraCtcRecognizer? = null

    private var inferJobA: Job? = null
    private var inferJobB: Job? = null

    private var activeSide: DualDrawingComposerView.Side = DualDrawingComposerView.Side.A
    private var activePreviewLen: Int = 0

    private var genA: Long = 0L
    private var genB: Long = 0L

    private var adapter: CtcCandidateAdapter? = null

    private var keyRowsLeftRef: KeyboardKeyRowsView? = null
    private var keyRowsRightRef: KeyboardKeyRowsView? = null
    private var keyRowsBottomRef: KeyboardKeyRowsView? = null

    private var undoRedoViewRef: UndoRedoView? = null

    private var lastDualRef: DualDrawingComposerView? = null
    private var lastControllerRef: ImeController? = null

    private var computedKeyMinHeightDp: Int = 40

    private var lastDrawingHeightPx: Int = 0
    private var keyRowsLayoutListener: View.OnLayoutChangeListener? = null

    private var rowsCountAllVertical: Int = 4
    private var rowsCountLeftOnly: Int = 1
    private var rowsCountRightOnly: Int = 3

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

    object HandwriteCandidateVariantConfig {

        @Volatile
        var enabled: Boolean = true

        @Volatile
        var dedupeByText: Boolean = true

        @Volatile
        var variantPercentFactor: Double = 0.90

        val variants: MutableMap<String, List<String>> = linkedMapOf(
            "あ" to listOf("ぁ"),
            "い" to listOf("ぃ"),
            "う" to listOf("ぅ", "ゔ"),
            "え" to listOf("ぇ"),
            "お" to listOf("ぉ"),

            "や" to listOf("ゃ"),
            "ゆ" to listOf("ゅ"),
            "よ" to listOf("ょ"),
            "つ" to listOf("っ"),
            "わ" to listOf("ゎ"),

            "か" to listOf("が"),
            "き" to listOf("ぎ"),
            "く" to listOf("ぐ"),
            "け" to listOf("げ"),
            "こ" to listOf("ご"),

            "さ" to listOf("ざ"),
            "し" to listOf("じ"),
            "す" to listOf("ず"),
            "せ" to listOf("ぜ"),
            "そ" to listOf("ぞ"),

            "た" to listOf("だ"),
            "ち" to listOf("ぢ"),
            "つ" to listOf("づ", "っ"),
            "て" to listOf("で"),
            "と" to listOf("ど"),

            "は" to listOf("ば", "ぱ"),
            "ひ" to listOf("び", "ぴ"),
            "ふ" to listOf("ぶ", "ぷ"),
            "へ" to listOf("べ", "ぺ"),
            "ほ" to listOf("ぼ", "ぽ")
        )

        fun setVariants(base: String, list: List<String>) {
            variants[base] = list
        }

        fun removeVariants(base: String) {
            variants.remove(base)
        }
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

            dual.post {
                recalcKeyHeightsAndRebuild(
                    rootView = dual,
                    dual = dual,
                    controller = controller,
                    leftRows = left,
                    rightRows = right,
                    bottomRows = bottom,
                    mode = mode
                )
                updateUndoRedoEnabled(dual)
            }
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

        adapter = CtcCandidateAdapter { c ->
            onCandidateTapped(dual, activeSide, c, controller)
            dual.clearBoth()

            submitCandidates(emptyList())

            activePreviewLen = 0
            genA++
            genB++

            updateUndoRedoEnabled(dual)
        }
        dual.candidateList.layoutManager =
            LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        dual.candidateList.adapter = adapter
        submitCandidates(emptyList())

        val leftRows: KeyboardKeyRowsView = v.findViewById(R.id.keyRowsLeft)
        val rightRows: KeyboardKeyRowsView = v.findViewById(R.id.keyRowsRight)
        val bottomRows: KeyboardKeyRowsView? = v.findViewById(R.id.keyRowsBottom)

        keyRowsLeftRef = leftRows
        keyRowsRightRef = rightRows
        keyRowsBottomRef = bottomRows

        val mode = HandwriteUiConfig.keyRowsMode

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

        keyRowsLayoutListener?.let { l ->
            dual.viewA.removeOnLayoutChangeListener(l)
        }

        keyRowsLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            dual.viewA.post {
                recalcKeyHeightsAndRebuild(
                    rootView = v,
                    dual = dual,
                    controller = controller,
                    leftRows = leftRows,
                    rightRows = rightRows,
                    bottomRows = bottomRows,
                    mode = HandwriteUiConfig.keyRowsMode
                )
                updateUndoRedoEnabled(dual)
            }
        }
        dual.viewA.addOnLayoutChangeListener(keyRowsLayoutListener)

        v.post {
            recalcKeyHeightsAndRebuild(
                rootView = v,
                dual = dual,
                controller = controller,
                leftRows = leftRows,
                rightRows = rightRows,
                bottomRows = bottomRows,
                mode = mode
            )
            updateUndoRedoEnabled(dual)
        }

        dual.viewA.onHistoryChanged = { updateUndoRedoEnabled(dual) }
        dual.viewB.onHistoryChanged = { updateUndoRedoEnabled(dual) }

        dual.onStrokeStarted = { side ->
            if (side != activeSide) {
                activePreviewLen = 0

                val prev = activeSide
                drawingViewFor(dual, prev).clearCanvas()

                submitCandidates(emptyList())

                bumpGeneration(prev)
                cancelInferJobFor(prev)

                activeSide = side

                updateUndoRedoEnabled(dual)
            }
        }

        dual.onStrokeCommitted = { side ->
            scheduleInferReplaceAndShowCandidates(dual, side, controller)
            updateUndoRedoEnabled(dual)
        }

        dual.post { updateUndoRedoEnabled(dual) }

        return v
    }

    override fun onDestroy() {
        cancelInferJobs()
        pluginJob.cancel()

        val dual = lastDualRef
        val l = keyRowsLayoutListener
        if (dual != null && l != null) {
            dual.viewA.removeOnLayoutChangeListener(l)
        }
        keyRowsLayoutListener = null
        lastDrawingHeightPx = 0

        keyRowsLeftRef = null
        keyRowsRightRef = null
        keyRowsBottomRef = null
        lastDualRef = null
        lastControllerRef = null
        adapter = null
        undoRedoViewRef = null
    }

    override fun onHostEvent(event: HostEvent) {
        when (event) {
            HostEvent.CandidateAdapterClicked -> {
                resetInkAndCandidates()
            }
        }
    }

    private fun resetInkAndCandidates() {
        val dual = lastDualRef ?: return
        val controller = lastControllerRef

        dual.clearBoth()
        submitCandidates(emptyList())
        activePreviewLen = 0
        genA++
        genB++
        cancelInferJobs()

        if (controller != null) {
            clearActivePreviewFromIme(controller)
        }

        updateUndoRedoEnabled(dual)
    }

    private fun recalcKeyHeightsAndRebuild(
        rootView: View,
        dual: DualDrawingComposerView,
        controller: ImeController,
        leftRows: KeyboardKeyRowsView,
        rightRows: KeyboardKeyRowsView,
        bottomRows: KeyboardKeyRowsView?,
        mode: KeyRowsMode
    ) {
        val drawingHeightPx = dual.viewA.height
            .takeIf { it > 0 }
            ?: dual.viewA.layoutParams.height.takeIf { it > 0 }
            ?: dpToPx(rootView, 200f)

        if (drawingHeightPx <= 0) return
        if (drawingHeightPx == lastDrawingHeightPx && computedKeyMinHeightDp > 0) {
            return
        }
        lastDrawingHeightPx = drawingHeightPx

        val density = rootView.resources.displayMetrics.density
        val drawingHeightDp = drawingHeightPx / density

        val rowsCount = when (mode) {
            KeyRowsMode.LEFT_ONLY, KeyRowsMode.RIGHT_ONLY -> rowsCountAllVertical
            KeyRowsMode.BOTH, KeyRowsMode.BOTH_WITH_BOTTOM -> max(
                rowsCountLeftOnly,
                rowsCountRightOnly
            )

            KeyRowsMode.BOTTOM_ONLY -> 1
            KeyRowsMode.NONE -> 1
        }.coerceAtLeast(1)

        val perKeyDp = (drawingHeightDp / rowsCount.toFloat())
            .toInt()
            .coerceAtLeast(32)

        if (perKeyDp == computedKeyMinHeightDp) {
            leftRows.requestLayout()
            rightRows.requestLayout()
            bottomRows?.requestLayout()
            return
        }

        computedKeyMinHeightDp = perKeyDp

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
        bottomRows?.requestLayout()

        updateUndoRedoEnabled(dual)
    }

    private fun configureKeyRows(
        mode: KeyRowsMode,
        leftRows: KeyboardKeyRowsView,
        rightRows: KeyboardKeyRowsView,
        bottomRows: KeyboardKeyRowsView?,
        dual: DualDrawingComposerView,
        controller: ImeController,
        keyMinHeightDp: Int
    ) {
        fun undoRedoKey() = KeyboardKeySpec.CustomViewKey(
            keyId = "undo_redo",
            minHeightDp = keyMinHeightDp,
            createView = { ctx, _parent, _c ->
                UndoRedoView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setIconTextSizeSp(10f)

                    undoRedoViewRef = this

                    setListener(object : UndoRedoView.Listener {
                        override fun onUndo() {
                            onUndoRedoPressed(dual, controller, isUndo = true)
                        }

                        override fun onRedo() {
                            onUndoRedoPressed(dual, controller, isUndo = false)
                        }
                    })
                }
            }
        )

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

                submitCandidates(emptyList())

                activePreviewLen = 0
                genA++
                genB++

                updateUndoRedoEnabled(dual)
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
                                    } else {
                                        c.dispatch(KeyboardAction.MoveCursor(+1))
                                    }

                                    dual.clearBoth()
                                    cancelInferJobs()
                                    submitCandidates(emptyList())

                                    activePreviewLen = 0
                                    genA++
                                    genB++

                                    updateUndoRedoEnabled(dual)
                                }

                                CursorNavView.Action.FLICK_UP,
                                CursorNavView.Action.LONG_FLICK_UP -> {
                                    c.dispatch(KeyboardAction.MoveCursorVertical(-1))

                                    dual.clearBoth()
                                    cancelInferJobs()
                                    submitCandidates(emptyList())

                                    activePreviewLen = 0
                                    genA++
                                    genB++

                                    updateUndoRedoEnabled(dual)
                                }

                                CursorNavView.Action.FLICK_DOWN,
                                CursorNavView.Action.LONG_FLICK_DOWN -> {
                                    c.dispatch(KeyboardAction.MoveCursorVertical(+1))

                                    dual.clearBoth()
                                    cancelInferJobs()
                                    submitCandidates(emptyList())

                                    activePreviewLen = 0
                                    genA++
                                    genB++

                                    updateUndoRedoEnabled(dual)
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
            onClick = { c ->

                val dv = drawingViewFor(dual, activeSide)

                val erased = dv.eraseLastEstimatedChar(segCfg = defaultSegCfg())

                if (erased) {
                    bumpGeneration(activeSide)
                    cancelInferJobFor(activeSide)

                    if (!dv.hasInk()) {
                        submitCandidates(emptyList())
                        clearActivePreviewFromIme(controller)
                        activePreviewLen = 0
                        updateUndoRedoEnabled(dual)
                        return@ButtonKey
                    }

                    scheduleInferReplaceAndShowCandidates(dual, activeSide, controller)
                    updateUndoRedoEnabled(dual)
                    return@ButtonKey
                }

                c.dispatch(KeyboardAction.Backspace)
                if (controller.isPreedit && activePreviewLen > 0) {
                    activePreviewLen = (activePreviewLen - 1).coerceAtLeast(0)
                }

                submitCandidates(emptyList())
                updateUndoRedoEnabled(dual)
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
                submitCandidates(emptyList())

                activePreviewLen = 0
                genA++
                genB++

                updateUndoRedoEnabled(dual)
            },
            repeatOnLongPress = false
        )

        val allKeysVertical = listOf(
            listOf(undoRedoKey()),
            listOf(backspaceKey()),
            listOf(cursorNavKey()),
            listOf(spaceKey()),
            listOf(enterKey())
        )

        val allKeysHorizontal = listOf(
            listOf(
                backspaceKey(),
                cursorNavKey(),
                spaceKey(),
                enterKey()
            )
        )

        val leftOnly = listOf(
            listOf(undoRedoKey()),
            listOf(clearKey())
        )

        val rightOnly = listOf(
            listOf(backspaceKey()),
            listOf(spaceKey()),
            listOf(enterKey())
        )

        rowsCountAllVertical = allKeysVertical.size
        rowsCountLeftOnly = leftOnly.size
        rowsCountRightOnly = rightOnly.size

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

    private fun onUndoRedoPressed(
        dual: DualDrawingComposerView,
        controller: ImeController,
        isUndo: Boolean
    ) {
        val dv = if (activeSide == DualDrawingComposerView.Side.A) dual.viewA else dual.viewB

        if (isUndo) {
            dv.undo()
        } else {
            dv.redo()
        }

        updateUndoRedoEnabled(dual)

        if (!dv.hasInk()) {
            submitCandidates(emptyList())
            clearActivePreviewFromIme(controller)
            activePreviewLen = 0
            genA++
            genB++
            return
        }

        scheduleInferReplaceAndShowCandidates(dual, activeSide, controller)
    }

    private fun updateUndoRedoEnabled(dual: DualDrawingComposerView) {
        val dv = if (activeSide == DualDrawingComposerView.Side.A) dual.viewA else dual.viewB
        undoRedoViewRef?.setEnabledState(
            canUndo = dv.canUndo(),
            canRedo = dv.canRedo()
        )
    }

    private fun clearActivePreviewFromIme(controller: ImeController) {
        if (!controller.isPreedit) {
            activePreviewLen = 0
            return
        }
        val n = activePreviewLen.coerceAtLeast(0)
        repeat(n) { controller.dispatch(KeyboardAction.Backspace) }
        activePreviewLen = 0
    }

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

    private data class MultiInferResult(
        val composedText: String,
        val displayCandidates: List<CtcCandidate>
    )

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
                if (side == activeSide) submitCandidates(emptyList())
                if (side == activeSide) updateUndoRedoEnabled(dual)
                return@launch
            }

            val parts: List<Bitmap> = withContext(Dispatchers.Main.immediate) {
                dv.exportCharBitmaps(segCfg = defaultSegCfg())
            }

            if (parts.isEmpty()) {
                if (side == activeSide) submitCandidates(emptyList())
                return@launch
            }

            val result = withContext(Dispatchers.Default) {
                runInferTopKMulti(parts, topK = HandwriteCommitConfig.topK)
            }

            if (!isLatest(side, token)) return@launch

            if (side == activeSide) {
                val shown0 = expandCandidatesWithVariants(result.displayCandidates)
                val shown = shown0.map { it.copy(text = applyHardConstraints(it.text)) }
                submitCandidates(shown)
            }

            if (side != activeSide) return@launch

            val topText0 = result.composedText.trim()
            if (topText0.isBlank()) return@launch

            val topText = applyHardConstraints(topText0)
            if (topText.isBlank()) return@launch

            applyReplaceTailToIme(controller, topText)

            updateUndoRedoEnabled(dual)
        }

        if (side == DualDrawingComposerView.Side.A) inferJobA = newJob else inferJobB = newJob
    }

    /**
     * ハード制約:
     * 要件: 「、、」は「い」にしたい
     */
    private fun applyHardConstraints(text: String): String {
        if (text.isEmpty()) return text
        return text.replace("、、", "い")
    }

    /**
     * 句読点
     */
    private val bannedPunct: Set<String> = setOf("、", "。")

    private fun containsAnyPunct(text: String, punct: Set<String>): Boolean {
        val s = text.trim()
        if (s.isEmpty()) return false
        for (i in s.indices) {
            val ch = s.substring(i, i + 1)
            if (punct.contains(ch)) return true
        }
        return false
    }

    private fun containsConsecutivePunct(text: String, punct: Set<String>): Boolean {
        val s = text.trim()
        if (s.length < 2) return false
        for (i in 0 until (s.length - 1)) {
            val a = s.substring(i, i + 1)
            val b = s.substring(i + 1, i + 2)
            if (punct.contains(a) && punct.contains(b)) return true
        }
        return false
    }

    // ★追加: 句読点を除去した文字列を返す（無表示防止のフォールバックで使用）
    private fun removePunct(text: String, punct: Set<String>): String {
        val s = text.trim()
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            val ch = s.substring(i, i + 1)
            if (!punct.contains(ch)) sb.append(ch)
        }
        return sb.toString()
    }

    // ★追加: 候補の作成（dedupe）用
    private fun addUniqueByText(
        out: ArrayList<CtcCandidate>,
        seen: LinkedHashSet<String>,
        c: CtcCandidate
    ) {
        val t = c.text.trim()
        if (t.isEmpty()) return
        if (seen.add(t)) out.add(c.copy(text = t))
    }

    /**
     * ★修正済み runInferTopKMulti:
     * - 句読点が混じっても「全候補が落ちて何も表示されない」を潰す
     * - rawText に句読点があっても、applyHardConstraints で消えるなら採用（"、、"→"い"）
     * - 消えないなら、その候補だけ落とす（全落としにしない）
     * - それでも空なら、句読点除去のフォールバック候補を作る（無表示防止）
     */
    private fun runInferTopKMulti(parts: List<Bitmap>, topK: Int): MultiInferResult {
        val all: ArrayList<List<CtcCandidate>> = ArrayList(parts.size)

        for (bmp in parts) {
            val raw = runInferTopK(bmp, topK = topK)
                .filter { it.text.isNotBlank() && it.percent > 0.0 }

            all.add(raw)
            runCatching { bmp.recycle() }
        }

        if (all.isEmpty()) {
            return MultiInferResult(composedText = "", displayCandidates = emptyList())
        }

        // ---- 単独文字モード ----
        if (all.size == 1) {
            val filtered = all.first()
                .asSequence()
                .filterNot { containsConsecutivePunct(it.text, bannedPunct) }
                .toList()

            val top1Raw = filtered.firstOrNull()?.text?.trim().orEmpty()
            val top1 = applyHardConstraints(top1Raw).trim()

            return MultiInferResult(
                composedText = top1,
                displayCandidates = filtered.map { it.copy(text = applyHardConstraints(it.text).trim()) }
            )
        }

        // ---- 複数文字モード ----
        // prefix: ここでは句読点も含みうる（後で候補側で処理）
        val prefix = buildString {
            for (i in 0 until (all.size - 1)) {
                val t = all[i].firstOrNull()?.text?.trim().orEmpty()
                if (t.isBlank()) return@buildString
                append(t)
            }
        }.trim()

        if (prefix.isBlank()) {
            // prefix が作れないなら、最後だけでも返す（無表示防止）
            val last0 = all.lastOrNull().orEmpty()
            val lastFiltered = last0
                .asSequence()
                .map { it.copy(text = applyHardConstraints(it.text).trim()) }
                .filter { it.text.isNotBlank() }
                .filterNot { containsConsecutivePunct(it.text, bannedPunct) }
                .toList()

            val top = lastFiltered.firstOrNull()?.text?.trim().orEmpty()
            return MultiInferResult(composedText = top, displayCandidates = lastFiltered)
        }

        val lastRaw = all.last()
        if (lastRaw.isEmpty()) {
            return MultiInferResult(composedText = "", displayCandidates = emptyList())
        }

        val derived = ArrayList<CtcCandidate>(lastRaw.size * 2)
        val seen = LinkedHashSet<String>()

        for (c in lastRaw) {
            val tail = c.text.trim()
            if (tail.isBlank()) continue

            val rawText = (prefix + tail).trim()
            if (rawText.isBlank()) continue

            // 1) まず制約適用
            val fixed = applyHardConstraints(rawText).trim()
            if (fixed.isBlank()) continue

            val rawHasPunct = containsAnyPunct(rawText, bannedPunct) || containsConsecutivePunct(
                rawText,
                bannedPunct
            )
            val fixedHasPunct =
                containsAnyPunct(fixed, bannedPunct) || containsConsecutivePunct(fixed, bannedPunct)
            val replaced = (fixed != rawText)

            // 2) 採用判定
            // - 置換が起きて句読点が消えたならOK（例: "、、"→"い"）
            // - 置換なしで句読点が残るなら、その候補だけNG（全落としにしない）
            if (!replaced && rawHasPunct) {
                // この候補は落とす
            } else if (replaced && fixedHasPunct) {
                // 置換後も句読点が残るなら落とす
            } else {
                addUniqueByText(derived, seen, c.copy(text = fixed))
            }

            // 3) ★フォールバック: rawText に句読点が含まれていて上で落ちた場合でも、
            // 句読点を除去したバージョンを候補として追加（無表示防止）
            if (rawHasPunct) {
                val stripped = removePunct(rawText, bannedPunct).trim()
                val strippedFixed = applyHardConstraints(stripped).trim()
                if (strippedFixed.isNotBlank() && !containsAnyPunct(strippedFixed, bannedPunct)) {
                    // percent は少し下げる（句読点除去は弱い候補）
                    addUniqueByText(
                        derived,
                        seen,
                        c.copy(
                            text = strippedFixed,
                            percent = (c.percent * 0.75).coerceAtLeast(0.0)
                        )
                    )
                }
            }
        }

        if (derived.isEmpty()) {
            // 最後の最後: prefix 自体から句読点を消したもの + last のトップで作る
            val prefixStripped = removePunct(prefix, bannedPunct).trim()
            val tailTop = lastRaw.firstOrNull()?.text?.trim().orEmpty()
            val rawText = (prefixStripped + tailTop).trim()
            val fixed = applyHardConstraints(rawText).trim()
            if (fixed.isNotBlank()) {
                val only = listOf(CtcCandidate(text = fixed, percent = 1.0))
                return MultiInferResult(composedText = fixed, displayCandidates = only)
            }
            return MultiInferResult(composedText = "", displayCandidates = emptyList())
        }

        derived.sortByDescending { it.percent }
        val composedTop1 = derived.first().text.trim()

        return MultiInferResult(
            composedText = composedTop1,
            displayCandidates = derived
        )
    }

    private fun expandCandidatesWithVariants(original: List<CtcCandidate>): List<CtcCandidate> {
        if (!HandwriteCandidateVariantConfig.enabled) return original
        if (original.isEmpty()) return original

        val map = HandwriteCandidateVariantConfig.variants
        if (map.isEmpty()) return original

        val factor = HandwriteCandidateVariantConfig.variantPercentFactor
            .coerceIn(0.0, 1.0)

        val out = ArrayList<CtcCandidate>(original.size * 2)

        val seen =
            if (HandwriteCandidateVariantConfig.dedupeByText) LinkedHashSet<String>() else null

        fun addCandidate(c: CtcCandidate) {
            if (seen == null) {
                out.add(c); return
            }
            if (seen.add(c.text)) out.add(c)
        }

        for (base in original) {
            addCandidate(base)

            val baseText = base.text
            if (baseText.isEmpty()) continue

            val prefix = if (baseText.length >= 2) baseText.dropLast(1) else ""
            val lastChar = baseText.takeLast(1)

            val variants = map[lastChar].orEmpty()
            if (variants.isEmpty()) continue

            variants.forEachIndexed { idx, v ->
                val penalty = (factor - idx * 0.02).coerceIn(0.0, 1.0)
                val derived = base.copy(
                    text = prefix + v,
                    percent = (base.percent * penalty).coerceIn(0.0, 100.0)
                )
                addCandidate(derived)
            }
        }

        return out
    }

    private fun submitCandidates(list: List<CtcCandidate>) {
        val rv = lastDualRef?.candidateList
        val ad = adapter
        if (rv == null || ad == null) return

        ad.submitList(list) {
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

    object HandwriteCommitConfig {
        @Volatile
        var debounceMs: Long = 120L

        @Volatile
        var topK: Int = 6
    }

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

    @Suppress("unused")
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

            if (normalized.width < 32 || normalized.height < 32) {
                runCatching { if (normalized !== whiteBmp) normalized.recycle() }
                return emptyList()
            }

            val out = r.inferTopK(
                bitmap = normalized,
                topK = topK.coerceAtLeast(1),
            )

            runCatching { if (normalized !== whiteBmp) normalized.recycle() }

            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun dpToPx(rootView: View, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            rootView.resources.displayMetrics
        ).toInt()
    }

    private fun defaultSegCfg(): MultiCharSegmenter.SegmentationConfig {
        return MultiCharSegmenter.SegmentationConfig()
    }

    companion object {
        private const val DEFAULT_STROKE_WIDTH_PX = 32
        private const val DEFAULT_MODEL_ASSET = "model_torchscript.pt"
        private const val DEFAULT_VOCAB_ASSET = "vocab.json"
    }
}
