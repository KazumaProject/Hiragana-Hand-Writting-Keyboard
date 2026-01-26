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

        /**
         * 列を「インクあり」とみなすための最小インク画素数
         */
        val minInkPixelsPerCol: Int = 1,

        /**
         * ギャップ（空白）がこのpx以上なら「切れ目」と判定
         */
        val minGapPx: Int = 10,

        /**
         * ★変更: ここは「結合後」に適用する最小セグメント幅として使う
         * （"に" の左払い等、結合前の細セグメントを落とさないため）
         */
        val minSegmentWidthPx: Int = 10,

        /**
         * ★追加: 結合前の“生”セグメントをノイズとして捨てる最小幅（小さめ推奨）
         * 1 だと単発ノイズも拾いやすいので 2〜3 が無難。
         */
        val minRawSegmentWidthPx: Int = 2,

        /**
         * 細いセグメント扱いする幅（これ以下は「細い」とみなす）
         */
        val thinSegmentWidthPx: Int = 12,

        /**
         * 通常結合するギャップ閾値
         */
        val mergeGapPx: Int = 6,

        /**
         * ★追加: 「細いセグメントだから結合する」を許す最大ギャップ
         * これが無いと、細い文字が混ざった時に別文字まで誤結合しやすい。
         * "に" の左払い救済が目的なら 24〜40 程度が現実的。
         */
        val maxThinMergeGapPx: Int = 40,

        /**
         * ★追加: 列投影の1次元膨張（dilation）幅
         * 細線がスケール/アンチエイリアスで欠けるのを救う。
         * 0: 無効, 1〜2: 推奨。
         */
        val colDilatePx: Int = 1,

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

        val rangesSegRaw = detectXRangesByProjection(segBmp, cfg)
        val mergedSeg = mergeRangesHeuristically(rangesSegRaw, cfg)

        // ★ここで初めて「最小幅」を適用（結合後）
        val mergedSegFiltered = mergedSeg.filter { widthOf(it) >= cfg.minSegmentWidthPx }

        val finalSeg = if (mergedSegFiltered.size <= 1) {
            listOf(IntRange(0, segBmp.width - 1))
        } else {
            mergedSegFiltered.take(cfg.maxChars)
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

        val rangesSegRaw = detectXRangesByProjection(segBmp, cfg)
        val mergedSeg = mergeRangesHeuristically(rangesSegRaw, cfg)
        val mergedSegFiltered = mergedSeg.filter { widthOf(it) >= cfg.minSegmentWidthPx }

        val finalSeg = if (mergedSegFiltered.size <= 1) {
            emptyList()
        } else {
            mergedSegFiltered.take(cfg.maxChars)
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
     * 現在の描画を「文字ごとのX範囲(IntRange)」として推定して返す（srcWhiteBg座標系）。
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

        val rangesSegRaw = detectXRangesByProjection(segBmp, cfg)
        val mergedSeg = mergeRangesHeuristically(rangesSegRaw, cfg)
        val mergedSegFiltered = mergedSeg.filter { widthOf(it) >= cfg.minSegmentWidthPx }

        // 1文字扱いは空で返す（呼び出し側で単一文字として扱う）
        val finalSeg = if (mergedSegFiltered.size <= 1) {
            emptyList()
        } else {
            mergedSegFiltered.take(cfg.maxChars)
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

        // 列ごとのインク量
        val colCount = IntArray(w)
        for (x in 0 until w) {
            var cnt = 0
            for (y in 0 until h) {
                if (isInk(pixels[y * w + x])) cnt++
            }
            colCount[x] = cnt
        }

        // 列のインク有無
        val inkCol0 = BooleanArray(w)
        val minCnt = cfg.minInkPixelsPerCol.coerceAtLeast(1)
        for (x in 0 until w) inkCol0[x] = colCount[x] >= minCnt

        // ★1D dilation: 細線の欠落を救う
        val inkCol = if (cfg.colDilatePx <= 0) {
            inkCol0
        } else {
            val d = cfg.colDilatePx
            val out = BooleanArray(w)
            for (x in 0 until w) {
                var any = false
                val from = (x - d).coerceAtLeast(0)
                val to = (x + d).coerceAtMost(w - 1)
                for (k in from..to) {
                    if (inkCol0[k]) {
                        any = true
                        break
                    }
                }
                out[x] = any
            }
            out
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

        // ★ここでは最小限だけ落とす（結合前に落としすぎない）
        return ranges.filter { widthOf(it) >= cfg.minRawSegmentWidthPx }
    }

    private fun mergeRangesHeuristically(
        ranges: List<IntRange>,
        cfg: SegmentationConfig
    ): List<IntRange> {
        if (ranges.size <= 1) return ranges

        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var cur = sorted[0]

        for (k in 1 until sorted.size) {
            val nxt = sorted[k]
            val gap = nxt.first - cur.last - 1

            val curThin = widthOf(cur) <= cfg.thinSegmentWidthPx
            val nxtThin = widthOf(nxt) <= cfg.thinSegmentWidthPx

            val shouldMerge =
                (gap <= cfg.mergeGapPx) ||
                        ((curThin || nxtThin) && gap <= cfg.maxThinMergeGapPx)

            cur = if (shouldMerge) {
                IntRange(cur.first, max(cur.last, nxt.last))
            } else {
                out.add(cur)
                nxt
            }
        }
        out.add(cur)

        if (out.size == 2) {
            val w0 = widthOf(out[0])
            val w1 = widthOf(out[1])
            if (w0 <= cfg.thinSegmentWidthPx && w1 <= cfg.thinSegmentWidthPx) {
                return listOf(IntRange(out[0].first, out[1].last))
            }
        }

        return out
    }

    private fun widthOf(r: IntRange): Int = (r.last - r.first + 1)
}
