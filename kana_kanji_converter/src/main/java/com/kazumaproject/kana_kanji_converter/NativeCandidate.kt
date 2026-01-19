package com.kazumaproject.kana_kanji_converter

/**
 * JNI から埋められるシンプルな DTO。
 * data class にせず、public field にして JNI で直接 set する（高速・簡単）。
 */
class NativeCandidate {
    @JvmField
    var surface: String = ""
    @JvmField
    var yomi: String = ""
    @JvmField
    var score: Int = 0
    @JvmField
    var src: String = ""
    @JvmField
    var type: Int = 0
    @JvmField
    var hasLR: Boolean = false
    @JvmField
    var l: Int = 0
    @JvmField
    var r: Int = 0
}
