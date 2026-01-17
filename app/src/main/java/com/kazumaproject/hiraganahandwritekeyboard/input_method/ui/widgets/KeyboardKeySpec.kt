package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets

import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController

data class KeyboardKeySpec(
    val keyId: String,
    val text: String,
    val weight: Float = 1f,
    val enabled: Boolean = true,
    val minHeightDp: Int = 48,
    val onClick: (ImeController) -> Unit
)
