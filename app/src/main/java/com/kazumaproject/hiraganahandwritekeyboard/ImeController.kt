package com.kazumaproject.hiraganahandwritekeyboard

interface ImeController {
    val isPreedit: Boolean
    fun dispatch(action: KeyboardAction)
}
