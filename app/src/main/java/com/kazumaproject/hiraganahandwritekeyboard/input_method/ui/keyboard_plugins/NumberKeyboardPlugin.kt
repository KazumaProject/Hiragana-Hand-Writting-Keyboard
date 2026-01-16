package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.keyboard_plugins

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.kazumaproject.hiraganahandwritekeyboard.R
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController
import com.kazumaproject.hiraganahandwritekeyboard.input_method.domain.KeyboardAction
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.KeyboardPlugin

class NumberKeyboardPlugin : KeyboardPlugin {
    override val id: String = "number"
    override val displayName: String = "123"

    override fun createView(parent: ViewGroup, controller: ImeController): View {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.keyboard_number, parent, false)

        fun bindDigit(buttonId: Int, text: String) {
            val b: Button = v.findViewById(buttonId)
            b.setOnClickListener { controller.dispatch(KeyboardAction.InputText(text)) }
        }

        bindDigit(R.id.btn0, "0")
        bindDigit(R.id.btn1, "1")
        bindDigit(R.id.btn2, "2")
        bindDigit(R.id.btn3, "3")
        bindDigit(R.id.btn4, "4")
        bindDigit(R.id.btn5, "5")
        bindDigit(R.id.btn6, "6")
        bindDigit(R.id.btn7, "7")
        bindDigit(R.id.btn8, "8")
        bindDigit(R.id.btn9, "9")

        val btnSpace: Button = v.findViewById(R.id.btnSpace)
        val btnEnter: Button = v.findViewById(R.id.btnEnter)
        val btnBackspace: Button = v.findViewById(R.id.btnBackspace)

        btnSpace.setOnClickListener { controller.dispatch(KeyboardAction.InputText(" ")) }
        btnEnter.setOnClickListener { controller.dispatch(KeyboardAction.Enter) }
        btnBackspace.setOnClickListener { controller.dispatch(KeyboardAction.Backspace) }

        return v
    }
}
