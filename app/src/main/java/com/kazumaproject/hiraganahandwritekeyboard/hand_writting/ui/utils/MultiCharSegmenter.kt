package com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.scale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 1枚の手書き画像(白背景+黒インク想定)から、複数文字を「横書き」として分割する。
 */
object MultiCharSegmenter {

    data class SegmentationConfig(
        val inkThresh: Int = 245,
        val segTargetH: Int = 48,
        val minInkPixelsPerCol: Int = 1,
        val minGapPx: Int = 10,
        val minSegmentWidthPx: Int = 10,
        val thinSegmentWidthPx: Int = 12,
        val mergeGapPx: Int = 6,
        val outPadPx: Int = 6,
        val maxChars: Int = 12
    )

    fun splitToCharBitmaps(
        srcWhiteBg: Bitmap,
        cfg: SegmentationConfig = SegmentationConfig()
    ): List<Bitmap> {
        val bounds: Rect = BitmapPreprocessor.findInkBounds(srcWhiteBg, cfg.inkThresh)
            ?: return emptyList()

        val crop = Bitmap.createBitmap(
            srcWhiteBg,
            bounds.left,
            bounds.top,
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1)
        )

        val segH = cfg.segTargetH.coerceAtLeast(8)
        val segBmp = resizeKeepAspectToHeight(crop, segH)

        val rangesSeg = detectXRangesByProjection(segBmp, cfg)
        val mergedSeg = mergeRangesHeuristically(rangesSeg, cfg)

        val finalSeg = if (mergedSeg.size <= 1) {
            listOf(IntRange(0, segBmp.width - 1))
        } else {
            mergedSeg.take(cfg.maxChars)
        }

        val scaleX = crop.width.toFloat() / segBmp.width.toFloat()
        val out = ArrayList<Bitmap>(finalSeg.size)

        for (r in finalSeg) {
            val x0 = floor(r.first * scaleX).toInt()
            val x1 = ceil((r.last + 1) * scaleX).toInt()
            val pad = cfg.outPadPx.coerceAtLeast(0)

            val lx = (x0 - pad).coerceIn(0, crop.width)
            val rx = (x1 + pad).coerceIn(0, crop.width)
            val w = (rx - lx).coerceAtLeast(1)

            out.add(Bitmap.createBitmap(crop, lx, 0, w, crop.height))
        }

        if (segBmp !== crop) runCatching { segBmp.recycle() }
        runCatching { crop.recycle() }

        if (out.isEmpty()) return emptyList()
        return out
    }

    fun estimateSplitLinesPx(
        srcWhiteBg: Bitmap,
        cfg: SegmentationConfig = SegmentationConfig()
    ): IntArray {
        val bounds: Rect = BitmapPreprocessor.findInkBounds(srcWhiteBg, cfg.inkThresh)
            ?: return intArrayOf()

        val crop = Bitmap.createBitmap(
            srcWhiteBg,
            bounds.left,
            bounds.top,
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1)
        )

        val segH = cfg.segTargetH.coerceAtLeast(8)
        val segBmp = resizeKeepAspectToHeight(crop, segH)

        val rangesSeg = detectXRangesByProjection(segBmp, cfg)
        val mergedSeg = mergeRangesHeuristically(rangesSeg, cfg)

        val finalSeg = if (mergedSeg.size <= 1) {
            emptyList()
        } else {
            mergedSeg.take(cfg.maxChars)
        }

        if (finalSeg.size <= 1) {
            if (segBmp !== crop) runCatching { segBmp.recycle() }
            runCatching { crop.recycle() }
            return intArrayOf()
        }

        val scaleX = crop.width.toFloat() / segBmp.width.toFloat()
        val pad = cfg.outPadPx.coerceAtLeast(0)

        data class Seg(val lx: Int, val rx: Int)

        val segs = ArrayList<Seg>(finalSeg.size)

        for (r in finalSeg) {
            val x0 = floor(r.first * scaleX).toInt()
            val x1 = ceil((r.last + 1) * scaleX).toInt()

            val lx = (x0 - pad).coerceIn(0, crop.width)
            val rx = (x1 + pad).coerceIn(0, crop.width)
            val w = (rx - lx).coerceAtLeast(1)
            segs.add(Seg(lx, (lx + w).coerceAtMost(crop.width)))
        }

        val lines = IntArray(segs.size - 1)
        for (i in 0 until segs.size - 1) {
            val leftEnd = segs[i].rx
            val rightStart = segs[i + 1].lx
            val cutInCrop = ((leftEnd + rightStart) / 2).coerceIn(0, crop.width)
            val cutInSrc = (bounds.left + cutInCrop).coerceIn(0, srcWhiteBg.width)
            lines[i] = cutInSrc
        }

        if (segBmp !== crop) runCatching { segBmp.recycle() }
        runCatching { crop.recycle() }

        return lines.distinct().sorted().toIntArray()
    }

    /**
     * ★追加: 現在の描画を「文字ごとのX範囲(IntRange)」として推定して返す（srcWhiteBg座標系）。
     *
     * - splitToCharBitmaps() と同じ推定を行い、最終的な各セグメントの [xStart.xEnd] を返す。
     * - 返り値が空の場合は「分割できない（単一文字扱い）」とみなしてよい。
     */
    fun estimateCharXRangesPx(
        srcWhiteBg: Bitmap,
        cfg: SegmentationConfig = SegmentationConfig()
    ): List<IntRange> {
        val bounds: Rect = BitmapPreprocessor.findInkBounds(srcWhiteBg, cfg.inkThresh)
            ?: return emptyList()

        val crop = Bitmap.createBitmap(
            srcWhiteBg,
            bounds.left,
            bounds.top,
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1)
        )

        val segH = cfg.segTargetH.coerceAtLeast(8)
        val segBmp = resizeKeepAspectToHeight(crop, segH)

        val rangesSeg = detectXRangesByProjection(segBmp, cfg)
        val mergedSeg = mergeRangesHeuristically(rangesSeg, cfg)

        // 1文字扱いは空で返す（呼び出し側で単一文字として扱う）
        val finalSeg = if (mergedSeg.size <= 1) {
            emptyList()
        } else {
            mergedSeg.take(cfg.maxChars)
        }

        if (finalSeg.size <= 1) {
            if (segBmp !== crop) runCatching { segBmp.recycle() }
            runCatching { crop.recycle() }
            return emptyList()
        }

        val scaleX = crop.width.toFloat() / segBmp.width.toFloat()
        val pad = cfg.outPadPx.coerceAtLeast(0)

        data class Seg(val lx: Int, val rx: Int)

        val segs = ArrayList<Seg>(finalSeg.size)

        for (r in finalSeg) {
            val x0 = floor(r.first * scaleX).toInt()
            val x1 = ceil((r.last + 1) * scaleX).toInt()

            val lx = (x0 - pad).coerceIn(0, crop.width)
            val rx = (x1 + pad).coerceIn(0, crop.width)
            val w = (rx - lx).coerceAtLeast(1)
            segs.add(Seg(lx, (lx + w).coerceAtMost(crop.width)))
        }

        val out = ArrayList<IntRange>(segs.size)
        for (s in segs) {
            val startInSrc = (bounds.left + s.lx).coerceIn(0, srcWhiteBg.width - 1)
            val endInSrc = (bounds.left + (s.rx - 1)).coerceIn(0, srcWhiteBg.width - 1)
            if (endInSrc >= startInSrc) {
                out.add(IntRange(startInSrc, endInSrc))
            }
        }

        if (segBmp !== crop) runCatching { segBmp.recycle() }
        runCatching { crop.recycle() }

        return out
    }

    private fun resizeKeepAspectToHeight(bmp: Bitmap, targetH: Int): Bitmap {
        val w = bmp.width.coerceAtLeast(1)
        val h = bmp.height.coerceAtLeast(1)
        if (h == targetH) return bmp
        val s = targetH.toFloat() / h.toFloat()
        val newW = max(1, (w * s).toInt())
        return bmp.scale(newW, targetH)
    }

    private fun detectXRangesByProjection(
        bmp: Bitmap,
        cfg: SegmentationConfig
    ): List<IntRange> {
        val w = bmp.width
        val h = bmp.height
        if (w <= 1 || h <= 1) return emptyList()

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        fun isInk(c: Int): Boolean {
            val a = Color.alpha(c)
            val gray = if (a == 0) 255 else {
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            }
            return gray < cfg.inkThresh
        }

        val inkCol = BooleanArray(w)
        for (x in 0 until w) {
            var cnt = 0
            for (y in 0 until h) {
                if (isInk(pixels[y * w + x])) {
                    cnt++
                    if (cnt >= cfg.minInkPixelsPerCol) break
                }
            }
            inkCol[x] = cnt >= cfg.minInkPixelsPerCol
        }

        val ranges = ArrayList<IntRange>()
        var i = 0
        while (i < w) {
            while (i < w && !inkCol[i]) i++
            if (i >= w) break

            val start = i
            var lastInk = i

            while (i < w) {
                if (inkCol[i]) {
                    lastInk = i
                    i++
                    continue
                }
                var j = i
                while (j < w && !inkCol[j]) j++
                val gapLen = j - i

                if (gapLen >= cfg.minGapPx) {
                    ranges.add(IntRange(start, lastInk))
                    i = j
                    break
                } else {
                    i = j
                }
            }

            if (i >= w) {
                ranges.add(IntRange(start, lastInk))
                break
            }
        }

        return ranges.filter { (it.last - it.first + 1) >= cfg.minSegmentWidthPx }
    }

    private fun mergeRangesHeuristically(
        ranges: List<IntRange>,
        cfg: SegmentationConfig
    ): List<IntRange> {
        if (ranges.size <= 1) return ranges

        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var cur = sorted[0]

        fun width(r: IntRange) = (r.last - r.first + 1)

        for (k in 1 until sorted.size) {
            val nxt = sorted[k]
            val gap = nxt.first - cur.last - 1

            val curThin = width(cur) <= cfg.thinSegmentWidthPx
            val nxtThin = width(nxt) <= cfg.thinSegmentWidthPx
            val shouldMerge =
                gap <= cfg.mergeGapPx || curThin || nxtThin

            cur = if (shouldMerge) {
                IntRange(cur.first, max(cur.last, nxt.last))
            } else {
                out.add(cur)
                nxt
            }
        }
        out.add(cur)

        if (out.size == 2) {
            val w0 = width(out[0])
            val w1 = width(out[1])
            if (w0 <= cfg.thinSegmentWidthPx && w1 <= cfg.thinSegmentWidthPx) {
                return listOf(IntRange(out[0].first, out[1].last))
            }
        }

        return out
    }
}
