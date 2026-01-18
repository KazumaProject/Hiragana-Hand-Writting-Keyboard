package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui

import android.view.View
import android.view.ViewGroup

interface KeyboardPlugin {
    val id: String
    val displayName: String

    fun createView(parent: ViewGroup, controller: ImeController): View

    fun onSelected() {}
    fun onUnselected() {}
    fun onDestroy() {}

    fun onHostEvent(event: HostEvent) {}
}
