package com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

private const val NEG_INF = -1e30

object SingleCharCtcScorer {

    private fun logAddExp(a: Double, b: Double): Double {
        if (a <= NEG_INF) return b
        if (b <= NEG_INF) return a
        val m = max(a, b)
        return m + ln(exp(a - m) + exp(b - m))
    }

    /**
     * logProbs: [T][V]（log-softmax済）
     * blankId: 通常0
     * 返り値: charId -> logP(output == that single char)
     *
     * DP（ラベル長1）:
     *  state0: blank
     *  state1: char
     *
     *  a0(t) = logAddExp(a0(t-1), a1(t-1)) + lp[t][blank]
     *  a1(t) = logAddExp(a0(t-1), a1(t-1)) + lp[t][char]
     *
     *  最終: logAddExp(a0(T-1), a1(T-1))
     */
    fun scoreAllSingleChars(
        logProbs: Array<DoubleArray>,
        blankId: Int = 0
    ): DoubleArray {
        require(logProbs.isNotEmpty())
        val T = logProbs.size
        val V = logProbs[0].size
        val scores = DoubleArray(V) { NEG_INF }

        // blank を除いた各文字についてDP
        for (c in 0 until V) {
            if (c == blankId) continue

            var a0 = logProbs[0][blankId] // t=0 blank
            var a1 = logProbs[0][c]       // t=0 char

            for (t in 1 until T) {
                val lpBlank = logProbs[t][blankId]
                val lpChar = logProbs[t][c]
                val totalPrev = logAddExp(a0, a1)

                val na0 = totalPrev + lpBlank
                val na1 = totalPrev + lpChar

                a0 = na0
                a1 = na1
            }

            scores[c] = logAddExp(a0, a1)
        }

        return scores
    }

    fun topKFromScores(
        scores: DoubleArray,
        topK: Int
    ): IntArray {
        return scores.indices
            .filter { scores[it] > NEG_INF / 2 }
            .sortedByDescending { scores[it] }
            .take(topK.coerceAtLeast(1))
            .toIntArray()
    }

    fun toPercentsFromTop(
        topIds: IntArray,
        scores: DoubleArray
    ): List<Pair<Int, Double>> {
        if (topIds.isEmpty()) return emptyList()
        val maxLog = topIds.maxOf { scores[it] }
        val exps = topIds.map { exp(scores[it] - maxLog) }
        val sum = exps.sum().coerceAtLeast(1e-12)
        return topIds.mapIndexed { i, id ->
            id to (100.0 * (exps[i] / sum))
        }
    }
}
