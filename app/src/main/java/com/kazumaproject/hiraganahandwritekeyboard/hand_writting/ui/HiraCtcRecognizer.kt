package com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.data.CtcCandidate
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.data.CtcVocab
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.utils.AssetUtil
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import androidx.core.graphics.createBitmap

class HiraCtcRecognizer(
    context: Context,
    modelAssetName: String = "model_torchscript.pt",
    modelFilePath: String? = null,
    vocabAssetName: String = "vocab.json",
    private val cfg: PreprocessConfig = PreprocessConfig()
) {
    private val module: Module = if (modelFilePath != null) {
        Module.load(modelFilePath)
    } else {
        val modelPath = AssetUtil.assetFilePath(context, modelAssetName)
        Module.load(modelPath)
    }

    private val vocab: CtcVocab

    init {
        val vocabJson =
            context.assets.open(vocabAssetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        vocab = CtcVocab.fromJsonString(vocabJson)
    }

    fun infer(bitmap: Bitmap): String {
        val prep = HiraCtcPreprocess.run(bitmap, cfg)

        val input = Tensor.fromBlob(
            prep.chw,
            longArrayOf(1, 1, prep.h.toLong(), prep.w.toLong())
        )

        val outTensor = module.forward(IValue.from(input)).toTensor()
        val shape = outTensor.shape()
        val out = outTensor.dataAsFloatArray

        val ids = greedyArgmaxIds(out, shape, prep.validTimeSteps)
        return vocab.decodeGreedy(ids)
    }

    /**
     * 1文字用途に最適化した TopK：
     * - CTCの「出力が1文字cになる確率」を DP で直接計算
     * - blank(0) を除いてランキング
     */
    fun inferTopK(
        bitmap: Bitmap,
        topK: Int = 5
    ): List<CtcCandidate> {
        val prep = HiraCtcPreprocess.run(bitmap, cfg)

        val input = Tensor.fromBlob(
            prep.chw,
            longArrayOf(1, 1, prep.h.toLong(), prep.w.toLong())
        )

        val outTensor = module.forward(IValue.from(input)).toTensor()
        val shape = outTensor.shape()
        val data = outTensor.dataAsFloatArray

        val logProbs = extractLogProbsTV(data, shape, prep.validTimeSteps) // [T][V]
        val scores = SingleCharCtcScorer.scoreAllSingleChars(logProbs, blankId = 0)
        val topIds = SingleCharCtcScorer.topKFromScores(scores, topK = topK)
        val topPct = SingleCharCtcScorer.toPercentsFromTop(topIds, scores)

        val out = topPct.map { (id, pct) ->
            val text = if (id == 0) "" else vocab.idToChar(id)
            CtcCandidate(text = text, percent = pct)
        }.filter { it.text.isNotEmpty() }

        return if (out.isNotEmpty()) out else listOf(CtcCandidate(text = "", percent = 100.0))
    }

    /**
     * ★追加（既存を壊さない）：parts（複数文字分割bitmap）を推論し、
     * 「句読点は単独入力（parts.size==1）のときのみ許可」する。
     *
     * 要件:
     * - parts.size == 1: 句読点を許可（通常通り）
     * - parts.size >= 2: bannedPunct に含まれる句読点は一切出さない
     *
     * 実装:
     * - 各セグメントで topK を取り、句読点が最有力なら「右セグメントと結合」して再推論
     * - それでも句読点しか出ないなら、そのセグメントは ""（句読点は返さない）
     *
     * 注意:
     * - 新規ファイルは作らず、このクラス内だけで完結
     * - infer()/inferTopK() の挙動は変更しない
     */
    fun inferPartsPunctuationSingleOnly(
        parts: List<Bitmap>,
        bannedPunct: Set<String> = setOf("、", "。"),
        topK: Int = 5
    ): List<String> {
        if (parts.isEmpty()) return emptyList()

        // 1文字入力のときだけ句読点OK（従来通り）
        if (parts.size == 1) {
            return listOf(infer(parts[0]))
        }

        // 2文字以上なら句読点は全面禁止
        val out = ArrayList<String>(parts.size)
        var i = 0

        while (i < parts.size) {
            val b = parts[i]
            val top = inferTopK(b, topK = topK)
            val best = top.firstOrNull()?.text.orEmpty()

            // 句読点が最有力なら、まず右結合して句読点以外を取りに行く（2セグメント消費）
            if (best in bannedPunct && i < parts.lastIndex) {
                val merged = concatHorizontalWhiteBg(b, parts[i + 1])
                try {
                    val topMerged = inferTopK(merged, topK = topK)
                    val pickMerged = pickBestNonBanned(topMerged, bannedPunct)

                    if (pickMerged.isNotEmpty()) {
                        out.add(pickMerged)
                        i += 2
                        continue
                    }

                    // 結合しても句読点しか出ない → 元セグメントで句読点以外を拾えるか試す
                    val pick = pickBestNonBanned(top, bannedPunct)
                    out.add(pick) // pick が空なら空を入れる（句読点は禁止）
                    i += 1
                    continue
                } finally {
                    runCatching { merged.recycle() }
                }
            }

            // 句読点以外なら採用
            if (best.isNotEmpty() && best !in bannedPunct) {
                out.add(best)
                i += 1
                continue
            }

            // best が空 or 句読点なら topK から句読点以外を採用（無ければ空）
            val pick = pickBestNonBanned(top, bannedPunct)
            out.add(pick)
            i += 1
        }

        return out
    }

    private fun pickBestNonBanned(top: List<CtcCandidate>, banned: Set<String>): String {
        for (c in top) {
            val t = c.text
            if (t.isNotEmpty() && t !in banned) return t
        }
        return ""
    }

    /**
     * 白背景で横結合（高さはmaxに合わせ、上下中央寄せ）
     * ※ parts の元Bitmapはリサイクルしない（呼び出し側所有）
     */
    private fun concatHorizontalWhiteBg(left: Bitmap, right: Bitmap): Bitmap {
        val h = maxOf(left.height, right.height)
        val w = left.width + right.width
        val out = createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1))

        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val dyL = ((h - left.height) / 2f)
        val dyR = ((h - right.height) / 2f)

        canvas.drawBitmap(left, 0f, dyL, null)
        canvas.drawBitmap(right, left.width.toFloat(), dyR, null)
        return out
    }

    private fun extractLogProbsTV(
        data: FloatArray,
        shape: LongArray,
        validTimeSteps: Int
    ): Array<DoubleArray> {
        val dims = shape.size
        val (tDim, vDim, layout) = when {
            dims == 3 && shape[1] == 1L -> Triple(shape[0].toInt(), shape[2].toInt(), "T1V")
            dims == 3 && shape[0] == 1L -> Triple(shape[1].toInt(), shape[2].toInt(), "1TV")
            dims == 2 -> Triple(shape[0].toInt(), shape[1].toInt(), "TV")
            else -> throw IllegalStateException("Unexpected output shape: ${shape.joinToString()}")
        }

        val T = minOf(tDim, validTimeSteps)
        val V = vDim
        val out = Array(T) { DoubleArray(V) }

        for (t in 0 until T) {
            for (v in 0 until V) {
                val idx = when (layout) {
                    "T1V" -> (t * 1 * V) + v
                    "1TV" -> (t * V) + v
                    "TV" -> (t * V) + v
                    else -> 0
                }
                out[t][v] = data[idx].toDouble()
            }
        }
        return out
    }

    private fun greedyArgmaxIds(
        data: FloatArray,
        shape: LongArray,
        validTimeSteps: Int
    ): IntArray {
        val dims = shape.size
        val (tDim, vDim, layout) = when {
            dims == 3 && shape[1] == 1L -> Triple(shape[0].toInt(), shape[2].toInt(), "T1V")
            dims == 3 && shape[0] == 1L -> Triple(shape[1].toInt(), shape[2].toInt(), "1TV")
            dims == 2 -> Triple(shape[0].toInt(), shape[1].toInt(), "TV")
            else -> throw IllegalStateException("Unexpected output shape: ${shape.joinToString()}")
        }

        val T = minOf(tDim, validTimeSteps)
        val ids = IntArray(T)

        for (t in 0 until T) {
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (v in 0 until vDim) {
                val idx = when (layout) {
                    "T1V" -> (t * 1 * vDim) + v
                    "1TV" -> (t * vDim) + v
                    "TV" -> (t * vDim) + v
                    else -> 0
                }
                val value = data[idx]
                if (value > bestVal) {
                    bestVal = value
                    bestIdx = v
                }
            }
            ids[t] = bestIdx
        }
        return ids
    }
}
