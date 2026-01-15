package com.kazumaproject.hiraganahandwritekeyboard

sealed class KeyboardAction {
    data class InputText(val text: String) : KeyboardAction()
    object Backspace : KeyboardAction()
    object Enter : KeyboardAction()
    data class MoveCursor(val delta: Int) : KeyboardAction() // Preedit用
    object Noop : KeyboardAction()
}
