package com.kazumaproject.hiraganahandwritekeyboard.input_method.domain

sealed class KeyboardAction {
    data class InputText(val text: String) : KeyboardAction()
    data object Backspace : KeyboardAction()
    data object Enter : KeyboardAction()
    data class MoveCursor(val delta: Int) : KeyboardAction() // Preedit用
    data object Noop : KeyboardAction()
}
