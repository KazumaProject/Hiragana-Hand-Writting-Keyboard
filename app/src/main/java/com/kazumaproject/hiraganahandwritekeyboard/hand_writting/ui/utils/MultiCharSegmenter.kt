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
         * "に" の左払い救済が目的なら 24〜40 程度が現実的。
         */
        val maxThinMergeGapPx: Int = 40,

        /**
         * ★追加: 列投影の1次元膨張（dilation）幅
         * 0: 無効, 1〜2: 推奨。
         */
        val colDilatePx: Int = 1,

        /**
         * ★追加: 「い」などで出る“縦に長い細片”を強めに結合する最大ギャップ
         */
        val maxTallStrokeMergeGapPx: Int = 56,

        /**
         * ★追加: 縦に長いとみなす高さ比率（0.0〜1.0）
         * 例: 0.60 = 画像高さの60%以上にインクが広がる
         */
        val tallStrokeHeightRatio: Float = 0.60f,

        /**
         * ★追加: 縦に長い細片とみなす最大幅（segBmp座標系）
         */
        val tallStrokeMaxWidthPx: Int = 18,

        /**
         * ★追加: 二値化を有効にするか（推奨 true）
         */
        val enableBinarizeBeforeResize: Boolean = true,

        /**
         * ★追加（今回の要件）:
         * 複数文字（セグメント数>=2）のとき、「、」っぽい小片が単独セグメントになったら
         * “必ず右側のセグメントに結合”して、単独で推論に回らないようにする。
         */
        val attachCommaFragmentToRightInMulti: Boolean = true,

        /**
         * ★「、」っぽい小片判定: 最大幅（segBmp座標系）
         * 小さめ推奨（例 14〜20）
         */
        val commaMaxWidthPx: Int = 16,

        /**
         * ★「、」っぽい小片判定: 最大高さ比率（インクの縦スパン / 画像高さ）
         * 例: 0.35 = 高さの35%以下なら小片とみなす
         */
        val commaMaxHeightRatio: Float = 0.35f,

        /**
         * ★「、」っぽい小片判定: yMin の開始位置が下側に寄っていること
         * 例: 0.45 = 上端が高さ45%より下なら「下側の点」っぽい
         */
        val commaMinYStartRatio: Float = 0.45f,

        /**
         * ★「、」小片を右へ結合するときに許す最大ギャップ（segBmp座標系）
         * “必ず右に結合”が仕様でも、誤爆を避けたいなら上限を設ける。
         * かなり大きめでもOK（例: 64〜128）
         */
        val commaAttachMaxGapPx: Int = 128,

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

        // ★二値化→縮小（灰色を消して細線の形状崩れを抑える）
        val cropForSeg = if (cfg.enableBinarizeBeforeResize) {
            binarizeToBlackWhite(crop, cfg.inkThresh)
        } else {
            crop
        }

        val segH = cfg.segTargetH.coerceAtLeast(8)
        val segBmp = resizeKeepAspectToHeight(cropForSeg, segH)

        val rangesSegRaw = detectXRangesByProjection(segBmp, cfg)

        // ★縦長細片（「い」の左棒等）や「、」小片結合のため segBmp を渡す
        val mergedSeg = mergeRangesHeuristically(rangesSegRaw, cfg, bmpForStats = segBmp)

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

        if (segBmp !== cropForSeg) runCatching { segBmp.recycle() }
        if (cropForSeg !== crop) runCatching { cropForSeg.recycle() }
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

        val cropForSeg = if (cfg.enableBinarizeBeforeResize) {
            binarizeToBlackWhite(crop, cfg.inkThresh)
        } else {
            crop
        }

        val segH = cfg.segTargetH.coerceAtLeast(8)
        val segBmp = resizeKeepAspectToHeight(cropForSeg, segH)

        val rangesSegRaw = detectXRangesByProjection(segBmp, cfg)
        val mergedSeg = mergeRangesHeuristically(rangesSegRaw, cfg, bmpForStats = segBmp)
        val mergedSegFiltered = mergedSeg.filter { widthOf(it) >= cfg.minSegmentWidthPx }

        val finalSeg = if (mergedSegFiltered.size <= 1) {
            emptyList()
        } else {
            mergedSegFiltered.take(cfg.maxChars)
        }

        if (finalSeg.size <= 1) {
            if (segBmp !== cropForSeg) runCatching { segBmp.recycle() }
            if (cropForSeg !== crop) runCatching { cropForSeg.recycle() }
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

        if (segBmp !== cropForSeg) runCatching { segBmp.recycle() }
        if (cropForSeg !== crop) runCatching { cropForSeg.recycle() }
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

        val cropForSeg = if (cfg.enableBinarizeBeforeResize) {
            binarizeToBlackWhite(crop, cfg.inkThresh)
        } else {
            crop
        }

        val segH = cfg.segTargetH.coerceAtLeast(8)
        val segBmp = resizeKeepAspectToHeight(cropForSeg, segH)

        val rangesSegRaw = detectXRangesByProjection(segBmp, cfg)
        val mergedSeg = mergeRangesHeuristically(rangesSegRaw, cfg, bmpForStats = segBmp)
        val mergedSegFiltered = mergedSeg.filter { widthOf(it) >= cfg.minSegmentWidthPx }

        // 1文字扱いは空で返す（呼び出し側で単一文字として扱う）
        val finalSeg = if (mergedSegFiltered.size <= 1) {
            emptyList()
        } else {
            mergedSegFiltered.take(cfg.maxChars)
        }

        if (finalSeg.size <= 1) {
            if (segBmp !== cropForSeg) runCatching { segBmp.recycle() }
            if (cropForSeg !== crop) runCatching { cropForSeg.recycle() }
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

        if (segBmp !== cropForSeg) runCatching { segBmp.recycle() }
        if (cropForSeg !== crop) runCatching { cropForSeg.recycle() }
        runCatching { crop.recycle() }

        return out
    }

    private fun resizeKeepAspectToHeight(bmp: Bitmap, targetH: Int): Bitmap {
        val w = bmp.width.coerceAtLeast(1)
        val h = bmp.height.coerceAtLeast(1)
        if (h == targetH) return bmp
        val s = targetH.toFloat() / h.toFloat()
        val newW = max(1, (w * s).toInt())

        // ★filter=false で縮小時の灰色（アンチエイリアス）を出しにくくする
        return bmp.scale(newW, targetH, filter = false)
    }

    /**
     * ★追加: 二値化（白背景+黒インク）に正規化する
     */
    private fun binarizeToBlackWhite(src: Bitmap, inkThresh: Int): Bitmap {
        val w = src.width.coerceAtLeast(1)
        val h = src.height.coerceAtLeast(1)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        fun toGray(c: Int): Int {
            val a = Color.alpha(c)
            if (a == 0) return 255
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            return (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
        }

        for (i in pixels.indices) {
            val gray = toGray(pixels[i])
            pixels[i] = if (gray < inkThresh) {
                0xFF000000.toInt() // black
            } else {
                0xFFFFFFFF.toInt() // white
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
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

    /**
     * 既存互換のまま強化:
     * - bmpForStats を渡すと「縦に長い細片」「、っぽい小片」判定を行う
     */
    private fun mergeRangesHeuristically(
        ranges: List<IntRange>,
        cfg: SegmentationConfig,
        bmpForStats: Bitmap? = null
    ): List<IntRange> {
        if (ranges.size <= 1) return ranges

        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var cur = sorted[0]

        val tallMap: Map<IntRange, Boolean> = if (bmpForStats == null) emptyMap()
        else buildTallStrokeMap(sorted, bmpForStats, cfg)

        val commaMap: Map<IntRange, Boolean> = if (bmpForStats == null) emptyMap()
        else buildCommaLikeMap(sorted, bmpForStats, cfg)

        fun isTall(r: IntRange): Boolean = tallMap[r] == true
        fun isThin(r: IntRange): Boolean = widthOf(r) <= cfg.thinSegmentWidthPx
        fun isCommaLike(r: IntRange): Boolean = commaMap[r] == true

        for (k in 1 until sorted.size) {
            val nxt = sorted[k]
            val gap = nxt.first - cur.last - 1

            val curThin = isThin(cur)
            val nxtThin = isThin(nxt)
            val curTall = isTall(cur)
            val nxtTall = isTall(nxt)

            // 通常の結合条件
            val shouldMergeBase =
                (gap <= cfg.mergeGapPx) ||
                        ((curThin || nxtThin) && gap <= cfg.maxThinMergeGapPx) ||
                        ((curTall || nxtTall) && gap <= cfg.maxTallStrokeMergeGapPx)

            // ★重要：左吸い込み防止ガード（既存）
            val blockLeftAbsorb =
                (gap > cfg.mergeGapPx) &&
                        (nxtTall || nxtThin) &&
                        !(curTall || curThin)

            var shouldMerge = shouldMergeBase && !blockLeftAbsorb

            // ★今回の仕様：複数文字時は「、っぽい小片」を“単独にしない”
            // ただし、この段階では「cur が comma-like のときは右に結合する」だけを保証する。
            if (cfg.attachCommaFragmentToRightInMulti && isCommaLike(cur)) {
                // gap が極端に大きいケースだけは誤爆しやすいので上限を設ける（必要なら）
                if (gap <= cfg.commaAttachMaxGapPx) {
                    shouldMerge = true
                }
            }

            cur = if (shouldMerge) {
                IntRange(cur.first, max(cur.last, nxt.last))
            } else {
                out.add(cur)
                nxt
            }
        }
        out.add(cur)

        // 既存の「2つとも細いなら1つに」などの後処理
        if (out.size == 2) {
            val w0 = widthOf(out[0])
            val w1 = widthOf(out[1])
            if (w0 <= cfg.thinSegmentWidthPx && w1 <= cfg.thinSegmentWidthPx) {
                val merged = listOf(IntRange(out[0].first, out[1].last))
                return postAttachCommaIfNeeded(merged, cfg, commaMap)
            }
        }

        return postAttachCommaIfNeeded(out, cfg, commaMap)
    }

    /**
     * ★「、」仕様の最終保証:
     * - 2文字以上のときだけ
     * - comma-like なセグメントが残っていたら「右へ結合」
     * - 最後が comma-like の場合は右が無いので左へ結合（“単独にしない”保証）
     */
    private fun postAttachCommaIfNeeded(
        ranges: List<IntRange>,
        cfg: SegmentationConfig,
        commaMap: Map<IntRange, Boolean>
    ): List<IntRange> {
        if (!cfg.attachCommaFragmentToRightInMulti) return ranges
        if (ranges.size <= 1) return ranges
        if (commaMap.isEmpty()) return ranges

        fun isCommaLike(r: IntRange): Boolean = commaMap[r] == true

        val list = ranges.toMutableList()

        // 右へ吸収（後ろから）
        var i = list.size - 2
        while (i >= 0) {
            val r = list[i]
            if (isCommaLike(r)) {
                val right = list[i + 1]
                val gap = right.first - r.last - 1
                if (gap <= cfg.commaAttachMaxGapPx) {
                    list[i + 1] = IntRange(r.first, max(r.last, right.last))
                    list.removeAt(i)
                }
            }
            i--
        }

        // まだ最後が comma-like なら左へ（右が無いので）
        if (list.size >= 2) {
            val last = list.last()
            if (isCommaLike(last)) {
                val prevIdx = list.size - 2
                val prev = list[prevIdx]
                list[prevIdx] = IntRange(prev.first, max(prev.last, last.last))
                list.removeAt(list.lastIndex)
            }
        }

        return list
    }

    /**
     * ★追加: 「縦に長い細片」をrange単位で判定する
     */
    private fun buildTallStrokeMap(
        ranges: List<IntRange>,
        bmp: Bitmap,
        cfg: SegmentationConfig
    ): Map<IntRange, Boolean> {
        val w = bmp.width
        val h = bmp.height
        if (w <= 1 || h <= 1) return emptyMap()

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

        val out = HashMap<IntRange, Boolean>(ranges.size)
        for (r in ranges) {
            val width = widthOf(r)
            if (width > cfg.tallStrokeMaxWidthPx) {
                out[r] = false
                continue
            }

            var yMin = h
            var yMax = -1

            val xFrom = r.first.coerceIn(0, w - 1)
            val xTo = r.last.coerceIn(0, w - 1)
            for (x in xFrom..xTo) {
                for (y in 0 until h) {
                    if (isInk(pixels[y * w + x])) {
                        if (y < yMin) yMin = y
                        if (y > yMax) yMax = y
                    }
                }
            }

            val isTall = if (yMax >= 0) {
                val span = (yMax - yMin + 1).coerceAtLeast(1)
                val ratio = span.toFloat() / h.toFloat()
                ratio >= cfg.tallStrokeHeightRatio
            } else {
                false
            }

            out[r] = isTall
        }
        return out
    }

    /**
     * ★追加: 「、」っぽい小片（comma-like fragment）判定
     * 条件（目安）:
     * - 幅が小さい
     * - 縦スパンが小さい
     * - 上端が下側に寄っている（下側の点・払い）
     */
    private fun buildCommaLikeMap(
        ranges: List<IntRange>,
        bmp: Bitmap,
        cfg: SegmentationConfig
    ): Map<IntRange, Boolean> {
        val w = bmp.width
        val h = bmp.height
        if (w <= 1 || h <= 1) return emptyMap()

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

        val out = HashMap<IntRange, Boolean>(ranges.size)

        for (r in ranges) {
            val width = widthOf(r)
            if (width > cfg.commaMaxWidthPx) {
                out[r] = false
                continue
            }

            var yMin = h
            var yMax = -1

            val xFrom = r.first.coerceIn(0, w - 1)
            val xTo = r.last.coerceIn(0, w - 1)

            for (x in xFrom..xTo) {
                for (y in 0 until h) {
                    if (isInk(pixels[y * w + x])) {
                        if (y < yMin) yMin = y
                        if (y > yMax) yMax = y
                    }
                }
            }

            val isComma = if (yMax >= 0) {
                val span = (yMax - yMin + 1).coerceAtLeast(1)
                val spanRatio = span.toFloat() / h.toFloat()
                val yStartRatio = yMin.toFloat() / h.toFloat()
                (spanRatio <= cfg.commaMaxHeightRatio) && (yStartRatio >= cfg.commaMinYStartRatio)
            } else {
                false
            }

            out[r] = isComma
        }

        return out
    }

    private fun widthOf(r: IntRange): Int = (r.last - r.first + 1)
}
