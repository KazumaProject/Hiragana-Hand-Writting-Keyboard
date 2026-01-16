package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui

import com.kazumaproject.hiraganahandwritekeyboard.input_method.domain.KeyboardAction

interface ImeController {
    val isPreedit: Boolean
    fun dispatch(action: KeyboardAction)
}
