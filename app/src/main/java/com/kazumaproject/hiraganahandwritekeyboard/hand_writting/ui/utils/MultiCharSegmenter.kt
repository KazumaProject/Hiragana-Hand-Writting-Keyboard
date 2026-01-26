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
 *
 * 実装方針:
 * - まずインク bbox を切り出し
 * - 高さを segTargetH に縮小（列投影を軽くする）
 * - x方向のインク投影（列ごとのインク有無）から "空白ギャップ" を検出して領域分割
 * - 誤分割（「い」等）を避けるため、細い領域はマージする
 */
object MultiCharSegmenter {

    data class SegmentationConfig(
        val inkThresh: Int = 245,

        // 列投影を行うための縮小高さ（小さいほど高速・粗くなる）
        val segTargetH: Int = 48,

        // 1列にインクがある判定（縮小画像上の「インク画素数」しきい値）
        val minInkPixelsPerCol: Int = 1,

        // "空白ギャップ" と見なす最小長（縮小画像上のpx）
        val minGapPx: Int = 10,

        // 分割領域の最小幅（縮小画像上のpx）
        val minSegmentWidthPx: Int = 10,

        // 細い領域は誤分割の可能性が高いのでマージ対象にする（縮小画像上のpx）
        val thinSegmentWidthPx: Int = 12,

        // セグメント間のギャップがこれ以下ならマージ（縮小画像上のpx）
        val mergeGapPx: Int = 6,

        // 元画像に戻すときの左右パディング（元画像px）
        val outPadPx: Int = 6,

        // 上限（暴走防止）
        val maxChars: Int = 12
    )

    /**
     * 複数文字を分割して Bitmap のリストを返す。
     * - 分割できない/不要なら、1要素（インクbboxで切り出したBitmap）を返す。
     */
    fun splitToCharBitmaps(
        srcWhiteBg: Bitmap,
        cfg: SegmentationConfig = SegmentationConfig()
    ): List<Bitmap> {
        val bounds: Rect = BitmapPreprocessor.findInkBounds(srcWhiteBg, cfg.inkThresh)
            ?: return emptyList()

        // インクbbox切り出し
        val crop = Bitmap.createBitmap(
            srcWhiteBg,
            bounds.left,
            bounds.top,
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1)
        )

        // 縮小（列投影用）
        val segH = cfg.segTargetH.coerceAtLeast(8)
        val segBmp = resizeKeepAspectToHeight(crop, segH)

        val rangesSeg = detectXRangesByProjection(segBmp, cfg)
        val mergedSeg = mergeRangesHeuristically(rangesSeg, cfg)

        // 分割不要（または誤分割っぽい）なら crop をそのまま返す
        val finalSeg = if (mergedSeg.size <= 1) {
            listOf(IntRange(0, segBmp.width - 1))
        } else {
            mergedSeg.take(cfg.maxChars)
        }

        // seg座標 -> crop座標へ変換して切り出し
        val scaleX = crop.width.toFloat() / segBmp.width.toFloat()
        val out = ArrayList<Bitmap>(finalSeg.size)

        for (r in finalSeg) {
            val x0 = floor(r.first * scaleX).toInt()
            val x1 = ceil((r.last + 1) * scaleX).toInt() // lastはinclusiveなので +1
            val pad = cfg.outPadPx.coerceAtLeast(0)

            val lx = (x0 - pad).coerceIn(0, crop.width)
            val rx = (x1 + pad).coerceIn(0, crop.width)
            val w = (rx - lx).coerceAtLeast(1)

            out.add(Bitmap.createBitmap(crop, lx, 0, w, crop.height))
        }

        // 中間bitmap破棄
        if (segBmp !== crop) runCatching { segBmp.recycle() }
        runCatching { crop.recycle() }

        if (out.isEmpty()) return emptyList()
        return out
    }

    /**
     * UIガイド表示用:
     * splitToCharBitmaps() と同じロジックで「分割線（x座標）」だけ推定する。
     *
     * - 返す x は srcWhiteBg 座標系（0..src.width）です
     * - 戻りが空なら「区切り無し（単一文字扱い）」です
     */
    fun estimateSplitLinesPx(
        srcWhiteBg: Bitmap,
        cfg: SegmentationConfig = SegmentationConfig()
    ): IntArray {
        val bounds: Rect = BitmapPreprocessor.findInkBounds(srcWhiteBg, cfg.inkThresh)
            ?: return intArrayOf()

        // インクbbox切り出し
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
            // 単一文字扱い -> 分割線は無し
            emptyList()
        } else {
            mergedSeg.take(cfg.maxChars)
        }

        if (finalSeg.size <= 1) {
            if (segBmp !== crop) runCatching { segBmp.recycle() }
            runCatching { crop.recycle() }
            return intArrayOf()
        }

        // seg座標 -> crop座標へ（splitToCharBitmapsと同じ変換）
        val scaleX = crop.width.toFloat() / segBmp.width.toFloat()
        val pad = cfg.outPadPx.coerceAtLeast(0)

        // 各セグメントの [lx, rx) を crop 座標で作る
        data class Seg(val lx: Int, val rx: Int)

        val segs = ArrayList<Seg>(finalSeg.size)

        for (r in finalSeg) {
            val x0 = floor(r.first * scaleX).toInt()
            val x1 = ceil((r.last + 1) * scaleX).toInt() // last inclusive -> +1

            val lx = (x0 - pad).coerceIn(0, crop.width)
            val rx = (x1 + pad).coerceIn(0, crop.width)
            val w = (rx - lx).coerceAtLeast(1)
            segs.add(Seg(lx, (lx + w).coerceAtMost(crop.width)))
        }

        // 分割線: 隣接セグメント境界の中点
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

        // 近すぎる線（同一点）を除去（念のため）
        return lines.distinct().sorted().toIntArray()
    }

    private fun resizeKeepAspectToHeight(bmp: Bitmap, targetH: Int): Bitmap {
        val w = bmp.width.coerceAtLeast(1)
        val h = bmp.height.coerceAtLeast(1)
        if (h == targetH) return bmp
        val s = targetH.toFloat() / h.toFloat()
        val newW = max(1, (w * s).toInt())
        return bmp.scale(newW, targetH)
    }

    /**
     * 列投影でインクのあるx範囲（IntRange: inclusive）を抽出
     */
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

        // 空白ギャップで区切る（gapが minGapPx 以上のときのみ分割）
        val ranges = ArrayList<IntRange>()
        var i = 0
        while (i < w) {
            // 先頭の空白をスキップ
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
                // 空白に入った。ギャップ長を数える
                var j = i
                while (j < w && !inkCol[j]) j++
                val gapLen = j - i

                if (gapLen >= cfg.minGapPx) {
                    // ここで区切る
                    ranges.add(IntRange(start, lastInk))
                    i = j
                    break
                } else {
                    // ギャップ短い → 同一文字内の可能性が高いので続行
                    i = j
                }
            }

            if (i >= w) {
                // 末尾まで行った
                ranges.add(IntRange(start, lastInk))
                break
            }
        }

        // 小さすぎる範囲を除外（ノイズ）
        return ranges.filter { (it.last - it.first + 1) >= cfg.minSegmentWidthPx }
    }

    /**
     * 誤分割を抑えるためのマージ:
     * - セグメントが細すぎる or ギャップが小さい場合に結合する
     * - 典型: 「い」「り」などが2分割されるのを抑える
     */
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

        // 2分割しかなく、両方薄いなら1文字扱いに戻す（「い」対策）
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
