package com.kazumaproject.hiraganahandwritekeyboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button

class HandwriteKeyboardPlugin : KeyboardPlugin {
    override val id: String = "handwrite"
    override val displayName: String = "Handwrite"

    override fun createView(parent: ViewGroup, controller: ImeController): View {
        val v =
            LayoutInflater.from(parent.context).inflate(R.layout.keyboard_handwrite, parent, false)

        val btnA: Button = v.findViewById(R.id.btnA)
        val btnSpace: Button = v.findViewById(R.id.btnSpace)
        val btnEnter: Button = v.findViewById(R.id.btnEnter)
        val btnBackspace: Button = v.findViewById(R.id.btnBackspace)

        btnA.setOnClickListener { controller.dispatch(KeyboardAction.InputText("あ")) }
        btnSpace.setOnClickListener { controller.dispatch(KeyboardAction.InputText(" ")) }
        btnEnter.setOnClickListener { controller.dispatch(KeyboardAction.Enter) }
        btnBackspace.setOnClickListener { controller.dispatch(KeyboardAction.Backspace) }

        return v
    }
}
