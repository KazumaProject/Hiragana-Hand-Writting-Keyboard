package com.kazumaproject.hiraganahandwritekeyboard.input_method.domain

sealed class KeyboardAction {
    data class InputText(val text: String) : KeyboardAction()
    data object Backspace : KeyboardAction()
    data object Enter : KeyboardAction()

    /** 左右（-1: LEFT, +1: RIGHT） */
    data class MoveCursor(val delta: Int) : KeyboardAction()

    /** 上下（-1: UP, +1: DOWN） */
    data class MoveCursorVertical(val delta: Int) : KeyboardAction()

    data object Noop : KeyboardAction()
}
