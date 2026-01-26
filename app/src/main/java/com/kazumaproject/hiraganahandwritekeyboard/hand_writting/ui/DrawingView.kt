package com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.kazumaproject.hiraganahandwritekeyboard.R
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.utils.BitmapPreprocessor
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.utils.MultiCharSegmenter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Stroke(
        val path: Path,
        val strokeWidthPx: Float,
        val bounds: RectF
    )

    private val strokes = ArrayList<Stroke>()
    private val redoStack = ArrayList<Stroke>()

    private var currentPath: Path? = null
    private var currentStrokeWidthPx: Float = 14f

    private var lastX = 0f
    private var lastY = 0f

    /**
     * 履歴（Undo/Redo可否）が変化したときに呼ばれるコールバック
     */
    var onHistoryChanged: (() -> Unit)? = null

    /**
     * ストローク確定（ACTION_UP）したときに呼ばれる（自動推論の起点）
     */
    var onStrokeCommitted: (() -> Unit)? = null

    /**
     * ストローク開始（ACTION_DOWN）したときに呼ばれる
     * 2画面入力で「相手側を確定する」トリガとして使う
     */
    var onStrokeStarted: (() -> Unit)? = null

    /**
     * 描画内容が変化した回数（自動推論の重複実行を防ぐ）
     */
    private var changeCounter: Long = 0
    fun getChangeCounter(): Long = changeCounter

    private fun bumpChange() {
        changeCounter++
        onHistoryChanged?.invoke()
        recomputeSegmentationGuideIfNeeded(force = false)
        recomputeEstimatedCharWidthGuideIfNeeded(force = false)
    }

    private val paintTemplate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_color)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = currentStrokeWidthPx
    }

    // ---------------- Guide (restored) ----------------

    // デフォルトで表示（外枠＋中心十字）
    private var guideEnabled: Boolean = true
    private var guideShowCenterCross: Boolean = true
    private var guideShowBorder: Boolean = true

    // 例: グリッドも出したい場合に使う（0なら無効）
    private var guideGridStepPx: Int = 0

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_color)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun setGuideEnabled(enabled: Boolean) {
        guideEnabled = enabled
        invalidate()
    }

    fun setGuideCenterCrossEnabled(enabled: Boolean) {
        guideShowCenterCross = enabled
        invalidate()
    }

    fun setGuideBorderEnabled(enabled: Boolean) {
        guideShowBorder = enabled
        invalidate()
    }

    /**
     * stepPx <= 0 ならグリッド無効
     */
    fun setGuideGridStepPx(stepPx: Int) {
        guideGridStepPx = stepPx.coerceAtLeast(0)
        invalidate()
    }

    fun setGuideAlpha(alpha: Int) {
        guidePaint.alpha = alpha.coerceIn(0, 255)
        invalidate()
    }

    fun setGuideStrokeWidthPx(px: Float) {
        guidePaint.strokeWidth = px.coerceAtLeast(1f)
        invalidate()
    }

    // ---------------- Auto Segmentation Guide (optional) ----------------

    private var guideSegmentationEnabled: Boolean = false

    private val guideSegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_color)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 110
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    private var guideSegCfg: MultiCharSegmenter.SegmentationConfig =
        MultiCharSegmenter.SegmentationConfig()

    private var guideSegXs: IntArray = intArrayOf()

    private var guideSegLastComputedCounter: Long = -1L
    private var guideSegLastComputedW: Int = -1
    private var guideSegLastComputedH: Int = -1

    fun setGuideSegmentationEnabled(enabled: Boolean) {
        guideSegmentationEnabled = enabled
        if (!enabled) {
            guideSegXs = intArrayOf()
        } else {
            recomputeSegmentationGuideIfNeeded(force = true)
        }
        invalidate()
    }

    fun setGuideSegmentationConfig(cfg: MultiCharSegmenter.SegmentationConfig) {
        guideSegCfg = cfg
        recomputeSegmentationGuideIfNeeded(force = true)
        invalidate()
    }

    private fun recomputeSegmentationGuideIfNeeded(force: Boolean) {
        if (!guideSegmentationEnabled) return

        if (!hasInk()) {
            guideSegXs = intArrayOf()
            guideSegLastComputedCounter = changeCounter
            guideSegLastComputedW = width
            guideSegLastComputedH = height
            return
        }

        if (width <= 1 || height <= 1) return

        val needs = force ||
                guideSegLastComputedCounter != changeCounter ||
                guideSegLastComputedW != width ||
                guideSegLastComputedH != height

        if (!needs) return

        val white = exportStrokesBitmapWhiteBg(borderPx = 0)
        val xs = MultiCharSegmenter.estimateSplitLinesPx(white, guideSegCfg)
        runCatching { white.recycle() }

        guideSegXs = xs
            .asSequence()
            .map { it.coerceIn(0, width) }
            .distinct()
            .sorted()
            .toList()
            .toIntArray()

        guideSegLastComputedCounter = changeCounter
        guideSegLastComputedW = width
        guideSegLastComputedH = height
    }

    private fun drawSegmentationGuide(canvas: Canvas) {
        if (guideSegXs.isEmpty()) return
        val h = height.toFloat()
        for (x in guideSegXs) {
            val xf = x.toFloat()
            canvas.drawLine(xf, 0f, xf, h, guideSegPaint)
        }
    }

    // ---------------- Estimated Character Width Guide ----------------

    private var guideEstimatedCharWidthEnabled: Boolean = true
    private var guideEstimatedCharWidthShowBox: Boolean = true

    private var guideEstimatedCharWidthCfg: MultiCharSegmenter.SegmentationConfig =
        MultiCharSegmenter.SegmentationConfig()

    private val guideCharWidthLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_color)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 90
    }

    private val guideCharWidthBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_color)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 70
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private var guideCharInkBounds: Rect? = null

    private var guideCharLastComputedCounter: Long = -1L
    private var guideCharLastComputedW: Int = -1
    private var guideCharLastComputedH: Int = -1

    fun setGuideEstimatedCharWidthEnabled(enabled: Boolean) {
        guideEstimatedCharWidthEnabled = enabled
        if (!enabled) {
            guideCharInkBounds = null
        } else {
            recomputeEstimatedCharWidthGuideIfNeeded(force = true)
        }
        invalidate()
    }

    fun setGuideEstimatedCharWidthShowBox(enabled: Boolean) {
        guideEstimatedCharWidthShowBox = enabled
        invalidate()
    }

    fun setGuideEstimatedCharWidthAlpha(alpha: Int) {
        val a = alpha.coerceIn(0, 255)
        guideCharWidthLinePaint.alpha = a
        guideCharWidthBoxPaint.alpha = (a * 0.8f).toInt().coerceIn(0, 255)
        invalidate()
    }

    fun setGuideEstimatedCharWidthStrokeWidthPx(px: Float) {
        val w = px.coerceAtLeast(1f)
        guideCharWidthLinePaint.strokeWidth = w
        guideCharWidthBoxPaint.strokeWidth = w
        invalidate()
    }

    fun setGuideEstimatedCharWidthConfig(cfg: MultiCharSegmenter.SegmentationConfig) {
        guideEstimatedCharWidthCfg = cfg
        recomputeEstimatedCharWidthGuideIfNeeded(force = true)
        invalidate()
    }

    private fun recomputeEstimatedCharWidthGuideIfNeeded(force: Boolean) {
        if (!guideEstimatedCharWidthEnabled) return

        if (!hasInk()) {
            guideCharInkBounds = null
            guideCharLastComputedCounter = changeCounter
            guideCharLastComputedW = width
            guideCharLastComputedH = height
            return
        }

        if (width <= 1 || height <= 1) return

        val needs = force ||
                guideCharLastComputedCounter != changeCounter ||
                guideCharLastComputedW != width ||
                guideCharLastComputedH != height

        if (!needs) return

        val white = exportStrokesBitmapWhiteBg(borderPx = 0)
        val b = BitmapPreprocessor.findInkBounds(white, guideEstimatedCharWidthCfg.inkThresh)
        runCatching { white.recycle() }

        guideCharInkBounds = b

        guideCharLastComputedCounter = changeCounter
        guideCharLastComputedW = width
        guideCharLastComputedH = height
    }

    private fun drawEstimatedCharWidthGuide(canvas: Canvas) {
        val b = guideCharInkBounds ?: return
        if (b.width() <= 0 || b.height() <= 0) return

        val h = height.toFloat()

        val leftX = b.left.toFloat()
        val rightX = b.right.toFloat()

        canvas.drawLine(leftX, 0f, leftX, h, guideCharWidthLinePaint)
        canvas.drawLine(rightX, 0f, rightX, h, guideCharWidthLinePaint)

        if (guideEstimatedCharWidthShowBox) {
            canvas.drawRect(
                b.left.toFloat(),
                b.top.toFloat(),
                b.right.toFloat(),
                b.bottom.toFloat(),
                guideCharWidthBoxPaint
            )
        }
    }

    // ---------------- Public API ----------------

    fun setStrokeWidthPx(px: Float) {
        currentStrokeWidthPx = px.coerceAtLeast(1f)
        invalidate()
    }

    fun clearCanvas() {
        strokes.clear()
        redoStack.clear()
        currentPath = null
        invalidate()
        bumpChange()
    }

    fun canUndo(): Boolean = strokes.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        if (strokes.isEmpty()) return
        val s = strokes.removeAt(strokes.lastIndex)
        redoStack.add(s)
        invalidate()
        bumpChange()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val s = redoStack.removeAt(redoStack.lastIndex)
        strokes.add(s)
        invalidate()
        bumpChange()
    }

    /**
     * インク（ストローク）が存在するか
     */
    fun hasInk(): Boolean {
        return strokes.isNotEmpty() || currentPath != null
    }

    /**
     * ★追加: 「最後に検知した1文字（推定）」に属するストロークを削除する。
     *
     * 仕様:
     * - MultiCharSegmenter と同じロジックで、現在の描画を横方向にセグメント化し、
     *   「最後のセグメント」を最後の1文字と見なす。
     * - そのセグメントに十分重なるストローク（または中心Xが入るストローク）を削除する。
     * - 削除が発生した場合、redoStack はクリア（新しい編集で redo を無効化）する。
     *
     * 戻り値:
     * - 何かしら削除できたら true
     * - 何も削除できなければ false
     */
    fun eraseLastEstimatedChar(
        segCfg: MultiCharSegmenter.SegmentationConfig = MultiCharSegmenter.SegmentationConfig()
    ): Boolean {
        if (!hasInk()) return false

        // 描画中のパスは確定前なので、まず確定済みストロークだけで削除を行う（必要なら仕様変更可）
        if (strokes.isEmpty()) {
            // currentPath しか無いなら、「最後の1文字」扱いで全クリア
            clearCanvas()
            return true
        }

        val white = exportStrokesBitmapWhiteBg(borderPx = 0)
        val ranges: List<IntRange> = try {
            MultiCharSegmenter.estimateCharXRangesPx(white, segCfg)
        } finally {
            runCatching { white.recycle() }
        }

        // 分割が推定できない = 単一文字扱い
        if (ranges.isEmpty()) {
            val had = strokes.isNotEmpty()
            if (!had) return false
            strokes.clear()
            redoStack.clear()
            currentPath = null
            invalidate()
            bumpChange()
            return true
        }

        val last = ranges.last()
        val segL = last.first.toFloat()
        val segR = last.last.toFloat()

        fun overlapRatioX(b: RectF): Float {
            val l = max(segL, b.left)
            val r = min(segR, b.right)
            val ov = max(0f, r - l)
            val w = max(1f, b.width())
            return ov / w
        }

        val before = strokes.size

        // last セグメントに属すると推定できるストロークだけ残す
        val kept = ArrayList<Stroke>(strokes.size)
        var removedAny = false

        for (s in strokes) {
            val b = s.bounds
            val cx = (b.left + b.right) * 0.5f
            val inByCenter = (cx >= segL && cx <= segR)
            val byOverlap = overlapRatioX(b) >= 0.5f

            val remove = inByCenter || byOverlap
            if (remove) {
                removedAny = true
            } else {
                kept.add(s)
            }
        }

        if (!removedAny) {
            // ヒューリスティックで取れなかった場合の保険：最後のストロークだけ削除
            // （「最後の1文字」ではない可能性があるが、Backspace の期待動作としてはマシ）
            if (strokes.isNotEmpty()) {
                strokes.removeAt(strokes.lastIndex)
                redoStack.clear()
                invalidate()
                bumpChange()
                return true
            }
            return false
        }

        strokes.clear()
        strokes.addAll(kept)

        // 新しい編集が入ったので redo は無効化
        redoStack.clear()

        // currentPath はそのまま（通常 backspace は描画中に押されない想定）
        invalidate()
        bumpChange()

        return strokes.size != before
    }

    // ---------------- Drawing ----------------

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        recomputeSegmentationGuideIfNeeded(force = false)
        recomputeEstimatedCharWidthGuideIfNeeded(force = false)

        if (guideEnabled) {
            drawGuide(canvas)
        }

        if (guideEstimatedCharWidthEnabled) {
            drawEstimatedCharWidthGuide(canvas)
        }

        if (guideSegmentationEnabled) {
            drawSegmentationGuide(canvas)
        }

        for (s in strokes) {
            val p = Paint(paintTemplate).apply { strokeWidth = s.strokeWidthPx }
            canvas.drawPath(s.path, p)
        }

        val cp = currentPath
        if (cp != null) {
            val p = Paint(paintTemplate).apply { strokeWidth = currentStrokeWidthPx }
            canvas.drawPath(cp, p)
        }
    }

    private fun drawGuide(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        if (guideShowBorder) {
            val half = guidePaint.strokeWidth / 2f
            canvas.drawRect(half, half, w - half, h - half, guidePaint)
        }

        if (guideShowCenterCross) {
            val cx = w / 2f
            val cy = h / 2f
            canvas.drawLine(cx, 0f, cx, h, guidePaint)
            canvas.drawLine(0f, cy, w, cy, guidePaint)
        }

        val step = guideGridStepPx
        if (step > 0) {
            var x = step.toFloat()
            while (x < w) {
                canvas.drawLine(x, 0f, x, h, guidePaint)
                x += step
            }
            var y = step.toFloat()
            while (y < h) {
                canvas.drawLine(0f, y, w, y, guidePaint)
                y += step
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeSegmentationGuideIfNeeded(force = true)
        recomputeEstimatedCharWidthGuideIfNeeded(force = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onStrokeStarted?.invoke()

                currentPath = Path().apply { moveTo(x, y) }
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val cp = currentPath ?: return true
                val dx = abs(x - lastX)
                val dy = abs(y - lastY)
                if (dx >= 2f || dy >= 2f) {
                    val midX = (x + lastX) / 2f
                    val midY = (y + lastY) / 2f
                    cp.quadTo(lastX, lastY, midX, midY)
                    lastX = x
                    lastY = y
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cp = currentPath
                if (cp != null) {
                    cp.lineTo(x, y)

                    val b = RectF()
                    cp.computeBounds(b, true)

                    strokes.add(Stroke(cp, currentStrokeWidthPx, b))
                    redoStack.clear()

                    bumpChange()
                    onStrokeCommitted?.invoke()
                }
                currentPath = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    /**
     * 推論入力の安定化のため、export 時は「常に黒インク」で書き出す。
     */
    fun exportStrokesBitmapTransparent(borderPx: Int = 0): Bitmap {
        val w0 = width.coerceAtLeast(1)
        val h0 = height.coerceAtLeast(1)

        val b = borderPx.coerceAtLeast(0)
        val w = (w0 + b * 2).coerceAtLeast(1)
        val h = (h0 + b * 2).coerceAtLeast(1)

        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        fun exportPaint(strokeWidth: Float): Paint {
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.strokeWidth = strokeWidth.coerceAtLeast(1f)
            }
        }

        canvas.withTranslation(b.toFloat(), b.toFloat()) {
            for (s in strokes) {
                drawPath(s.path, exportPaint(s.strokeWidthPx))
            }

            val cp = currentPath
            if (cp != null) {
                drawPath(cp, exportPaint(currentStrokeWidthPx))
            }
        }
        return bmp
    }

    /**
     * 分割・前処理用途の「白背景 + 黒インク」
     */
    fun exportStrokesBitmapWhiteBg(borderPx: Int = 0): Bitmap {
        val w0 = width.coerceAtLeast(1)
        val h0 = height.coerceAtLeast(1)

        val b = borderPx.coerceAtLeast(0)
        val w = (w0 + b * 2).coerceAtLeast(1)
        val h = (h0 + b * 2).coerceAtLeast(1)

        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        fun exportPaint(strokeWidth: Float): Paint {
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.strokeWidth = strokeWidth.coerceAtLeast(1f)
            }
        }

        canvas.withTranslation(b.toFloat(), b.toFloat()) {
            for (s in strokes) {
                drawPath(s.path, exportPaint(s.strokeWidthPx))
            }
            val cp = currentPath
            if (cp != null) {
                drawPath(cp, exportPaint(currentStrokeWidthPx))
            }
        }
        return bmp
    }

    fun exportCharBitmaps(
        segCfg: MultiCharSegmenter.SegmentationConfig = MultiCharSegmenter.SegmentationConfig()
    ): List<Bitmap> {
        if (!hasInk()) return emptyList()

        val white = exportStrokesBitmapWhiteBg(borderPx = 0)
        val parts = MultiCharSegmenter.splitToCharBitmaps(white, segCfg)
        runCatching { white.recycle() }
        return parts
    }
}
