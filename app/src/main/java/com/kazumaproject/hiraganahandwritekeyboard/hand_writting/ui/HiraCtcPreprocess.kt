package com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.math.roundToInt

data class PreprocessConfig(
    val targetH: Int = 32,
    val maxW: Int = 512,
    val inkThresh: Int = 245,
    val pasteMode: PasteMode = PasteMode.LEFT, // 学習分布に合わせて切替
    val minPasteMarginPx: Int = 0,             // 例: 8 など。余白を少し確保したい場合
    /**
     * ★追加:
     * true のとき入力を反転する（white=0.0, black=1.0）
     * false のとき従来通り（white=1.0, black=0.0）
     *
     * 学習で invert を入れたなら、推論も true にすること。
     */
    val invertInput: Boolean = true
)

enum class PasteMode { LEFT, CENTER }

data class PreprocessResult(
    val chw: FloatArray,   // [1,1,H,W] の H*W フラット
    val h: Int,
    val w: Int,
    val validTimeSteps: Int
)

object HiraCtcPreprocess {

    fun run(src: Bitmap, cfg: PreprocessConfig): PreprocessResult {
        val bbox = tightInkBBox(src, cfg.inkThresh)
            ?: throw IllegalStateException("Nothing drawn.")

        val cropped = Bitmap.createBitmap(src, bbox.left, bbox.top, bbox.width(), bbox.height())

        // 高さを targetH に合わせる（アスペクト維持）
        val resized = resizeKeepAspectToHeight(cropped, cfg.targetH)

        // 白背景 32x512 キャンバスに貼る（学習の分布に寄せる）
        val H = cfg.targetH
        val W = cfg.maxW
        val canvasBmp = createBitmap(W, H)
        canvasBmp.eraseColor(Color.WHITE)

        val pasteMargin = cfg.minPasteMarginPx.coerceAtLeast(0)
        val pasteW = resized.width.coerceAtMost(W - pasteMargin * 2).coerceAtLeast(1)
        val pasteH = resized.height.coerceAtMost(H - pasteMargin * 2).coerceAtLeast(1)

        val resized2 =
            if (pasteW == resized.width && pasteH == resized.height) resized
            else resized.scale(pasteW, pasteH)

        val dx = when (cfg.pasteMode) {
            PasteMode.LEFT -> pasteMargin
            PasteMode.CENTER -> (W - resized2.width) / 2
        }.coerceIn(0, W - resized2.width)

        val dy = ((H - resized2.height) / 2).coerceIn(0, H - resized2.height)

        // draw
        val pixels = IntArray(resized2.width * resized2.height)
        resized2.getPixels(pixels, 0, resized2.width, 0, 0, resized2.width, resized2.height)
        canvasBmp.setPixels(pixels, 0, resized2.width, dx, dy, resized2.width, resized2.height)

        // valid_w を「最終キャンバス」から推定（Pythonと整合）
        val validW = estimateValidWidthFromWhiteBg(canvasBmp, cfg.inkThresh)

        /** モデル変更に伴い 2 に変更。 **/
        val tValid = maxOf(1, validW / 2)

        // ★ここが本題：float化（必要なら反転）
        val outFloats = toWhiteBlackFloat01(canvasBmp, invert = cfg.invertInput)

        // recycle（必要なら）
        if (resized2 !== resized) runCatching { resized2.recycle() }
        runCatching { cropped.recycle() }

        return PreprocessResult(
            chw = outFloats,
            h = H,
            w = W,
            validTimeSteps = tValid
        )
    }

    private data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width() = (right - left + 1).coerceAtLeast(1)
        fun height() = (bottom - top + 1).coerceAtLeast(1)
    }

    private fun tightInkBBox(bmp: Bitmap, inkThresh: Int): RectI? {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                val c = pixels[off + x]
                val a = Color.alpha(c)
                val gray = if (a == 0) 255 else {
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)
                }
                if (gray < inkThresh) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < 0 || maxY < 0) return null
        return RectI(minX, minY, maxX, maxY)
    }

    private fun resizeKeepAspectToHeight(bmp: Bitmap, targetH: Int): Bitmap {
        val w = bmp.width.coerceAtLeast(1)
        val h = bmp.height.coerceAtLeast(1)
        if (h == targetH) return bmp
        val scale = targetH.toFloat() / h.toFloat()
        val newW = maxOf(1, (w * scale).roundToInt())
        return bmp.scale(newW, targetH)
    }

    private fun estimateValidWidthFromWhiteBg(bmp: Bitmap, inkThresh: Int): Int {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var lastInkX = -1
        for (x in 0 until w) {
            var anyInk = false
            for (y in 0 until h) {
                val c = pixels[y * w + x]
                val a = Color.alpha(c)
                val gray = if (a == 0) 255 else {
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
                }
                if (gray < inkThresh) {
                    anyInk = true
                    break
                }
            }
            if (anyInk) lastInkX = x
        }

        return if (lastInkX < 0) w else (lastInkX + 1).coerceIn(1, w)
    }

    /**
     * 画像を float[0,1] に変換する。
     *
     * invert=false: white=1.0, black=0.0（従来）
     * invert=true : white=0.0, black=1.0（反転モデル用）
     */
    private fun toWhiteBlackFloat01(bmp: Bitmap, invert: Boolean): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val floats = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = Color.alpha(c)

            // alpha==0 は「白扱い」で安定化（export透明のケース対策）
            val gray01 = if (a == 0) {
                1.0f
            } else {
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val gray = 0.299f * r + 0.587f * g + 0.114f * b
                (gray / 255.0f).coerceIn(0f, 1f)
            }

            floats[i] = if (!invert) gray01 else (1.0f - gray01)
        }
        return floats
    }
}
