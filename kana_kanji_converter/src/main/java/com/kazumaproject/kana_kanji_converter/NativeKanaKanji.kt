package com.kazumaproject.kana_kanji_converter

object NativeKanaKanji {
    init {
        System.loadLibrary("kana_kanji")
    }

    @JvmStatic
    external fun nativeInit(
        yomiTermidPath: String,
        tangoPath: String,
        tokensPath: String,
        posPath: String,
        connPath: String,
    )

    @JvmStatic
    external fun nativeConvert(
        queryUtf8: String,
        nBest: Int,
        beamWidth: Int,
        showBunsetsu: Boolean,
        yomiMode: Int,
        predK: Int,
        showPred: Boolean,
        showOmit: Boolean,
        predLenPenalty: Int,
        yomiLimit: Int,
        finalLimit: Int,
        dedup: Boolean,
        globalDedup: Boolean,
    ): Array<NativeCandidate>
}
