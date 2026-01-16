package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui

class KeyboardRegistry(private val plugins: List<KeyboardPlugin>) {
    private val map = plugins.associateBy { it.id }

    fun all(): List<KeyboardPlugin> = plugins

    fun getOrDefault(id: String, defaultId: String = plugins.first().id): KeyboardPlugin {
        return map[id] ?: map[defaultId] ?: plugins.first()
    }

    fun indexOf(id: String): Int {
        return plugins.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
    }

    fun nextId(currentId: String): String {
        val idx = indexOf(currentId)
        val next = (idx + 1) % plugins.size
        return plugins[next].id
    }
}
