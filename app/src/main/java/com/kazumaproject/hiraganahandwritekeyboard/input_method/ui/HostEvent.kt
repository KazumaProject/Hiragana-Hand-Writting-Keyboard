package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui

sealed class HostEvent {
    /**
     * HiraganaImeService 側の CandidateAdapter がクリックされた（変換候補選択など）
     */
    data object CandidateAdapterClicked : HostEvent()
}
