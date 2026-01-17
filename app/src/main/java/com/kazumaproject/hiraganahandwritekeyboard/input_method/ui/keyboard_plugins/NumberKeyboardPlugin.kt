package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.keyboard_plugins

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.kazumaproject.hiraganahandwritekeyboard.R
import com.kazumaproject.hiraganahandwritekeyboard.input_method.domain.KeyboardAction
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.KeyboardPlugin
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets.KeyboardKeyRowsView
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets.KeyboardKeySpec

class NumberKeyboardPlugin : KeyboardPlugin {
    override val id: String = "number"
    override val displayName: String = "123"

    override fun createView(parent: ViewGroup, controller: ImeController): View {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.keyboard_number, parent, false)

        val rows: KeyboardKeyRowsView = v.findViewById(R.id.keyRows)

        rows.setRows(
            rows = listOf(
                listOf(
                    digit("1"), digit("2"), digit("3")
                ),
                listOf(
                    digit("4"), digit("5"), digit("6")
                ),
                listOf(
                    digit("7"), digit("8"), digit("9")
                ),
                listOf(
                    KeyboardKeySpec.ButtonKey(
                        keyId = "space",
                        text = "Space",
                        onClick = { it.dispatch(KeyboardAction.InputText(" ")) }
                    ),
                    digit("0"),
                    KeyboardKeySpec.ButtonKey(
                        keyId = "enter",
                        text = "Enter",
                        onClick = { it.dispatch(KeyboardAction.Enter) }
                    ),
                    KeyboardKeySpec.ButtonKey(
                        keyId = "backspace",
                        text = "⌫",
                        onClick = { it.dispatch(KeyboardAction.Backspace) }
                    )
                )
            ),
            controller = controller
        )

        return v
    }

    private fun digit(d: String): KeyboardKeySpec {
        return KeyboardKeySpec.ButtonKey(
            keyId = "digit_$d",
            text = d,
            onClick = { it.dispatch(KeyboardAction.InputText(d)) }
        )
    }
}
