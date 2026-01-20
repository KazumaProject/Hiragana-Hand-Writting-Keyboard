package com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui

import android.content.Context
import android.graphics.Bitmap
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.data.CtcCandidate
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.data.CtcVocab
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui.utils.AssetUtil
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor

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
