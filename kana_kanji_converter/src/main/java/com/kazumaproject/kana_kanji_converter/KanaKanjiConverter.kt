package com.kazumaproject.kana_kanji_converter

import android.content.Context
import java.io.File
import java.io.FileOutputStream

class KanaKanjiConverter(
    private val assetDir: String = "kk_dict"
) {
    @Volatile
    private var initialized: Boolean = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return

        val yomiTermid = copyAssetToFiles(context, "$assetDir/yomi_termid.louds")
        val tango = copyAssetToFiles(context, "$assetDir/tango.louds")
        val tokens = copyAssetToFiles(context, "$assetDir/token_array.bin")
        val pos = copyAssetToFiles(context, "$assetDir/pos_table.bin")
        val conn = copyAssetToFiles(context, "$assetDir/connection_single_column.bin")

        NativeKanaKanji.nativeInit(
            yomiTermid.absolutePath,
            tango.absolutePath,
            tokens.absolutePath,
            pos.absolutePath,
            conn.absolutePath
        )

        initialized = true
    }

    fun convert(
        queryUtf8: String,
        nBest: Int = 10,
        beamWidth: Int = 20,
        showBunsetsu: Boolean = true,
        yomiMode: Int = 3,
        predK: Int = 1,
        showPred: Boolean = true,
        showOmit: Boolean = true,
        predLenPenalty: Int = 200,
        yomiLimit: Int = 200,
        finalLimit: Int = 50,
        dedup: Boolean = true,
        globalDedup: Boolean = true
    ): Array<NativeCandidate> {
        if (!initialized) {
            throw IllegalStateException("KanaKanjiConverter is not initialized. Call init(context) first.")
        }
        return NativeKanaKanji.nativeConvert(
            queryUtf8 = queryUtf8,
            nBest = nBest,
            beamWidth = beamWidth,
            showBunsetsu = showBunsetsu,
            yomiMode = yomiMode,
            predK = predK,
            showPred = showPred,
            showOmit = showOmit,
            predLenPenalty = predLenPenalty,
            yomiLimit = yomiLimit,
            finalLimit = finalLimit,
            dedup = dedup,
            globalDedup = globalDedup
        )
    }

    private fun copyAssetToFiles(context: Context, assetPath: String): File {
        val outFile = File(context.filesDir, assetPath) // filesDir/kk_dict/...
        val parent = outFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()

        // 既に同サイズならコピー省略（毎回コピーしない）
        // NOTE: InputStream.available() は「総サイズ」を保証しませんが、
        // 今の運用で問題が出ていないなら踏襲し、必要なら後で堅牢化できます。
        context.assets.open(assetPath).use { input ->
            val assetLen = input.available().toLong()
            if (outFile.exists() && outFile.length() == assetLen) {
                return outFile
            }
        }

        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

}
