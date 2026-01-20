// app/src/main/java/com/kazumaproject/hiraganahandwritekeyboard/input_method/ui/widgets/KeyboardKeySpec.kt
package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController

enum class KeyboardKeyGesture {
    TAP,
    FLICK_UP,
    FLICK_DOWN,
    LONG_TAP,
    LONG_FLICK_UP,
    LONG_FLICK_DOWN
}

sealed class KeyboardKeySpec {
    abstract val keyId: String
    abstract val weight: Float
    abstract val enabled: Boolean
    abstract val minHeightDp: Int
    abstract val flickThresholdDp: Int

    /** RowsView が付与するタッチ処理（長押し/フリック）を有効にするか */
    abstract val enableGestures: Boolean

    /** 長押し中リピートを有効にするか */
    abstract val repeatOnLongPress: Boolean

    /** リピート間隔 */
    abstract val repeatIntervalMs: Long

    /**
     * RowsView が生成する Button の見た目。
     * - backgroundResId: 例) あなたの layer-list drawable
     * - textColorResId: 例) @color/clay_text を使う（要求により resId で管理）
     */
    data class ButtonStyle(
        @DrawableRes val backgroundResId: Int? = null,
        @ColorRes val textColorResId: Int? = null
    )

    data class ButtonKey(
        override val keyId: String,
        val text: String,
        override val weight: Float = 1f,
        override val enabled: Boolean = true,
        override val minHeightDp: Int = 44,

        // 既存API：タップ/クリック時の動作
        val onClick: (ImeController) -> Unit,

        // 追加：上下フリック（必要なキーだけ指定）
        val onFlickUp: ((ImeController) -> Unit)? = null,
        val onFlickDown: ((ImeController) -> Unit)? = null,

        // 追加：長押し（RowsView側で扱う）
        override val enableGestures: Boolean = true,
        override val repeatOnLongPress: Boolean = true,
        override val repeatIntervalMs: Long = 60L,
        override val flickThresholdDp: Int = 18,

        // ★追加：このキーだけ見た目を上書きしたい場合（nullなら RowsView のデフォルト）
        val style: ButtonStyle? = null
    ) : KeyboardKeySpec()

    data class CustomViewKey(
        override val keyId: String,
        override val weight: Float = 1f,
        override val enabled: Boolean = true,
        override val minHeightDp: Int = 44,

        val createView: (Context, ViewGroup, ImeController) -> View,

        /**
         * CustomView に RowsView 側でジェスチャーを付与したい場合だけ指定する。
         * CursorNavView のように View 自身がタッチ処理する場合は null のままにする。
         */
        val onGesture: ((ImeController, KeyboardKeyGesture) -> Unit)? = null,

        override val enableGestures: Boolean = (onGesture != null),
        override val repeatOnLongPress: Boolean = true,
        override val repeatIntervalMs: Long = 60L,
        override val flickThresholdDp: Int = 18
    ) : KeyboardKeySpec()
}
