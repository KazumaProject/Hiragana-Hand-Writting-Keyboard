package com.kazumaproject.hiraganahandwritekeyboard.input_method

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kazumaproject.hiraganahandwritekeyboard.R
import com.kazumaproject.hiraganahandwritekeyboard.input_method.domain.KeyboardAction
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.KeyboardPlugin
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.KeyboardRegistry
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.adapters.CandidateAdapter
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.keyboard_plugins.HandwriteKeyboardPlugin
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.keyboard_plugins.NumberKeyboardPlugin
import com.kazumaproject.kana_kanji_converter.KanaKanjiConverter
import com.kazumaproject.kana_kanji_converter.NativeCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

class HiraganaImeService : InputMethodService() {

    companion object {
        private const val PREF_NAME = "ime_prefs"
        private const val KEY_PANEL_W = "panel_width_px"
        private const val KEY_PANEL_H = "panel_height_px"
        private const val KEY_RESIZE_MODE = "resize_mode"
        private const val KEY_INPUT_MODE = "input_mode" // "direct" or "preedit"
        private const val KEY_KEYBOARD_ID = "keyboard_id" // plugin id

        // status 表示/非表示（デフォルト false）
        private const val KEY_SHOW_STATUS_TEXT = "show_status_text"

        // キーボード切替UIモード（デフォルト: toprow）
        private const val KEY_KEYBOARD_SELECTOR_MODE =
            "keyboard_selector_mode" // "toprow" or "tabs"
    }

    private enum class InputMode { DIRECT, PREEDIT }
    private enum class HSide { NONE, LEFT, RIGHT }
    private enum class VSide { NONE, TOP, BOTTOM }

    private enum class KeyboardSelectorMode { TOPROW, TABS }

    /**
     * プリエディット装飾の範囲モード
     * - SPLIT_AT_CURSOR: 背景=左(0..cursor), 下線=右(cursor..len)
     * - CURSOR_CHAR: 背景=カーソル位置の1文字, 下線=全体
     * - CUSTOM: 手動指定した範囲
     */
    private enum class PreeditDecorMode { SPLIT_AT_CURSOR, CURSOR_CHAR, CUSTOM }

    /** end は「排他的」(start..endExclusive) */
    private data class SpanRange(val start: Int, val endExclusive: Int) {
        fun isEmpty(): Boolean = endExclusive <= start
    }

    private lateinit var prefs: SharedPreferences

    private var panel: View? = null

    private var statusText: TextView? = null
    private var resizeModeBtn: Button? = null
    private var inputModeBtn: Button? = null

    private var keyboardSelectorMode: KeyboardSelectorMode = KeyboardSelectorMode.TOPROW

    // ★ TOPROWモード用の「トグルでキーボード切替」
    private var toggleKeyboardTopRowBtn: Button? = null

    // candidates
    private var candidateRecycler: RecyclerView? = null
    private var candidateAdapter: CandidateAdapter? = null

    // topRow を候補エリア内で表示/非表示する
    private var topRow: ViewGroup? = null

    private var keyboardTypeRow: LinearLayout? = null
    private var keyboardContainer: ViewGroup? = null

    // resize handles (8)
    private var handleLeft: View? = null
    private var handleRight: View? = null
    private var handleTop: View? = null
    private var handleBottom: View? = null
    private var handleTopLeft: View? = null
    private var handleTopRight: View? = null
    private var handleBottomLeft: View? = null
    private var handleBottomRight: View? = null

    // composing (preedit)
    private val composing = StringBuilder()
    private var cursor = 0 // 0..composing.length
    private var inputMode: InputMode = InputMode.PREEDIT

    // Candidate Mode (Preedit のサブ状態)
    private var inCandidateMode: Boolean = false

    // ★ NativeCandidate に変更
    private var lastCandidates: List<NativeCandidate> = emptyList()

    // CandidateMode プレビュー用（未確定のまま置換するための「元」）
    private var candidatePreviewBaseText: String? = null
    private var candidatePreviewBaseCursor: Int = 0

    // preedit spans
    private var decorMode: PreeditDecorMode = PreeditDecorMode.SPLIT_AT_CURSOR
    private var bgRange: SpanRange = SpanRange(0, 0)
    private var ulRange: SpanRange = SpanRange(0, 0)
    private var customBgRange: SpanRange = SpanRange(0, 0)
    private var customUlRange: SpanRange = SpanRange(0, 0)

    // keyboard plugin system
    private val registry = KeyboardRegistry(
        plugins = listOf(
            HandwriteKeyboardPlugin(),
            NumberKeyboardPlugin()
        )
    )
    private var currentKeyboardId: String = "handwrite"
    private var currentPlugin: KeyboardPlugin? = null

    // tab buttons
    private val tabButtons = mutableMapOf<String, Button>()

    // 現在のキーボードView内の「Space / Enter」ボタン参照（存在すれば）
    private var btnSpaceInKeyboard: Button? = null
    private var btnEnterInKeyboard: Button? = null

    private val controller = object : ImeController {
        override val isPreedit: Boolean
            get() = (inputMode == InputMode.PREEDIT)

        override fun dispatch(action: KeyboardAction) {
            when (action) {

                is KeyboardAction.InputText -> {
                    // Candidate mode中のスペースは「次候補」(選択移動 + プレビュー更新)
                    if (inputMode == InputMode.PREEDIT && inCandidateMode) {
                        if (action.text == " ") {
                            moveCandidateSelectionNext()
                            return
                        }
                        // それ以外の文字入力は Candidate mode を抜けて通常処理（プレビューを元に戻す）
                        exitCandidateMode(restorePreview = true, renderAfter = false)
                    }

                    // Preeditモードでも composing が空のときの「スペース」は Direct と同等にする
                    if (inputMode == InputMode.DIRECT) {
                        commitTextDirect(action.text)
                    } else {
                        // Preedit
                        if (composing.isEmpty() && action.text == " ") {
                            commitTextDirect(" ")
                            setStatus("Direct (space)")
                            lastCandidates = emptyList()
                            updateCandidateTopRowVisibility(hasCandidates = false)
                            updateActionKeyLabels()
                        } else {
                            // Preedit & composingあり & Space かつ (bgに文字 & 候補あり) => Candidate Modeへ
                            if (action.text == " " && shouldEnterCandidateMode()) {
                                enterCandidateMode()
                                return
                            }
                            insertToComposing(action.text)
                            renderComposing()
                        }
                    }
                }

                KeyboardAction.Backspace -> {
                    // 要件: CandidateモードでbtnBackspace -> Candidateモードを抜けて選択解除（削除はしない）
                    if (inputMode == InputMode.PREEDIT && inCandidateMode) {
                        exitCandidateMode(restorePreview = true, renderAfter = true)
                        return
                    }

                    // Preeditモードでも composing が空なら Direct と同等（削除できる）
                    if (inputMode == InputMode.DIRECT) {
                        performBackspaceDirect()
                    } else {
                        if (composing.isEmpty()) {
                            performBackspaceDirect()
                            setStatus("Direct (backspace)")
                            lastCandidates = emptyList()
                            updateCandidateTopRowVisibility(hasCandidates = false)
                            updateActionKeyLabels()
                        } else {
                            performBackspacePreedit()
                        }
                    }
                }

                KeyboardAction.Enter -> {
                    if (inputMode == InputMode.PREEDIT && inCandidateMode) {
                        commitSelectedCandidateAndExit()
                        return
                    }

                    // Preeditモードでも composing が空なら Direct と同等（Enter）
                    if (inputMode == InputMode.DIRECT) {
                        commitTextDirect("\n")
                    } else {
                        if (composing.isNotEmpty()) {
                            commitAndFinishComposing()
                            composing.setLength(0)
                            cursor = 0
                            setStatus("Committed")
                            lastCandidates = emptyList()
                            updateCandidateTopRowVisibility(hasCandidates = false)
                            updateActionKeyLabels()
                        } else {
                            commitTextDirect("\n")
                            setStatus("Direct (enter)")
                            lastCandidates = emptyList()
                            updateCandidateTopRowVisibility(hasCandidates = false)
                            updateActionKeyLabels()
                        }
                    }
                }

                is KeyboardAction.MoveCursor -> {
                    if (inputMode == InputMode.PREEDIT && inCandidateMode) {
                        // Candidate mode中のカーソル移動は抜けてから（プレビューも戻す）
                        exitCandidateMode(restorePreview = true, renderAfter = false)
                    }

                    // - Direct モード: エディタ上のカーソル移動
                    // - Preedit モード:
                    //   - composing があれば preedit 内カーソル移動
                    //   - composing が空ならエディタ上のカーソル移動（Direct同等）
                    if (inputMode == InputMode.PREEDIT && composing.isNotEmpty()) {
                        cursor = clamp(cursor + action.delta, 0, composing.length)
                        renderComposing()
                    } else {
                        moveCursorInEditor(action.delta)
                    }
                }

                KeyboardAction.Noop -> Unit

                is KeyboardAction.MoveCursorVertical -> {
                    if (inputMode == InputMode.PREEDIT && inCandidateMode) {
                        exitCandidateMode(restorePreview = true, renderAfter = false)
                    }

                    // 元仕様：Direct または Preedit で composing が空のときだけ上下移動
                    if (inputMode == InputMode.DIRECT || (inputMode == InputMode.PREEDIT && composing.isEmpty())) {
                        moveCursorVerticalInEditor(action.delta)
                    }
                }
            }
        }
    }

    // --- Kana-Kanji converter (JNI) ---
    private val kkConverter = KanaKanjiConverter(assetDir = "kk_dict")

    @Volatile
    private var kkReady: Boolean = false

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)

    private var candJob: Job? = null

    /**
     * 変換候補の「取り違え防止キー」
     * - lastCandKey: 最新要求のキー（進行中含む）
     * - lastCandidatesKey: lastCandidates が対応しているキー
     */
    private var lastCandKey: String = ""
    private var lastCandidatesKey: String = ""

    // --- Candidate request state (TopRow flicker prevention) ---
    @Volatile
    private var candidateRequestInFlight: Boolean = false

    @Volatile
    private var candidateInFlightKey: String = ""

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

        // JNI辞書初期化（重いのでバックグラウンド）
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                kkConverter.init(this@HiraganaImeService)
            }.onSuccess {
                kkReady = true
            }.onFailure {
                kkReady = false
            }
        }
    }

    override fun onCreateInputView(): View {
        val v = layoutInflater.inflate(R.layout.ime_view, null)

        panel = v.findViewById(R.id.imePanel)
        statusText = v.findViewById(R.id.imeStatusText)
        resizeModeBtn = v.findViewById(R.id.btnResizeMode)
        inputModeBtn = v.findViewById(R.id.btnInputMode)

        // topRow (候補エリア内)
        topRow = v.findViewById(R.id.topRow)

        // ★ TOPROWトグルボタン
        toggleKeyboardTopRowBtn = v.findViewById(R.id.btnToggleKeyboardTopRow)

        // candidates
        candidateRecycler = v.findViewById(R.id.candidateRecycler)
        setupCandidatesRecycler()

        keyboardTypeRow = v.findViewById(R.id.keyboardTypeRow)
        keyboardContainer = v.findViewById(R.id.keyboardContainer)

        // insets: navigation bar を避ける
        val basePaddingLeft = v.paddingLeft
        val basePaddingTop = v.paddingTop
        val basePaddingRight = v.paddingRight
        val basePaddingBottom = v.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                basePaddingLeft,
                basePaddingTop,
                basePaddingRight,
                basePaddingBottom + navBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(v)

        // --- resize handles bind ---
        handleLeft = v.findViewById(R.id.handleLeft)
        handleRight = v.findViewById(R.id.handleRight)
        handleTop = v.findViewById(R.id.handleTop)
        handleBottom = v.findViewById(R.id.handleBottom)
        handleTopLeft = v.findViewById(R.id.handleTopLeft)
        handleTopRight = v.findViewById(R.id.handleTopRight)
        handleBottomLeft = v.findViewById(R.id.handleBottomLeft)
        handleBottomRight = v.findViewById(R.id.handleBottomRight)

        handleLeft?.setOnTouchListener(makeResizeTouchListener(h = HSide.LEFT, v = VSide.NONE))
        handleRight?.setOnTouchListener(makeResizeTouchListener(h = HSide.RIGHT, v = VSide.NONE))
        handleTop?.setOnTouchListener(makeResizeTouchListener(h = HSide.NONE, v = VSide.TOP))
        handleBottom?.setOnTouchListener(makeResizeTouchListener(h = HSide.NONE, v = VSide.BOTTOM))
        handleTopLeft?.setOnTouchListener(makeResizeTouchListener(h = HSide.LEFT, v = VSide.TOP))
        handleTopRight?.setOnTouchListener(makeResizeTouchListener(h = HSide.RIGHT, v = VSide.TOP))
        handleBottomLeft?.setOnTouchListener(
            makeResizeTouchListener(
                h = HSide.LEFT,
                v = VSide.BOTTOM
            )
        )
        handleBottomRight?.setOnTouchListener(
            makeResizeTouchListener(
                h = HSide.RIGHT,
                v = VSide.BOTTOM
            )
        )

        // apply saved panel size
        applySavedPanelSize()

        // resize mode init
        setResizeModeEnabled(prefs.getBoolean(KEY_RESIZE_MODE, false))
        resizeModeBtn?.setOnClickListener {
            val newMode = !prefs.getBoolean(KEY_RESIZE_MODE, false)
            prefs.edit().putBoolean(KEY_RESIZE_MODE, newMode).apply()
            setResizeModeEnabled(newMode)
        }

        // input mode init
        inputMode = loadInputMode()
        updateInputModeUi()

        inputModeBtn?.setOnClickListener {
            // Candidate mode中ならまず抜ける
            if (inCandidateMode) {
                exitCandidateMode(restorePreview = true, renderAfter = false)
            }
            // Preedit -> Direct 切替時、未確定が残る事故を避けるため確定してから切替
            if (inputMode == InputMode.PREEDIT && composing.isNotEmpty()) {
                commitAndFinishComposing()
                composing.setLength(0)
                cursor = 0
            }
            inputMode = if (inputMode == InputMode.DIRECT) InputMode.PREEDIT else InputMode.DIRECT
            saveInputMode(inputMode)
            updateInputModeUi()
        }

        // keyboard registry init
        currentKeyboardId =
            prefs.getString(KEY_KEYBOARD_ID, registry.all().first().id) ?: registry.all().first().id

        // タブUIはモードがTABSのときだけ構築しても良いが、初期化として一度作る
        buildKeyboardTypeTabs()

        // selector mode init (default: TOPROW)
        keyboardSelectorMode = loadKeyboardSelectorMode()
        applyKeyboardSelectorModeUi()

        // ★ TOPROWトグル：順送りでキーボード切替
        toggleKeyboardTopRowBtn?.setOnClickListener {
            selectNextKeyboardInRegistry()
        }

        // 初期キーボード選択（表示）
        selectKeyboard(currentKeyboardId, savePref = false)

        // status text visibility default = GONE
        applyStatusVisibilityFromPref()
        setStatus("Ready")

        // 初期状態：候補なしなので topRow 表示
        lastCandidates = emptyList()
        lastCandKey = ""
        lastCandidatesKey = ""
        updateCandidateTopRowVisibility(hasCandidates = false)
        updateActionKeyLabels()

        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        // フォーカス切替等で composing が残っている場合は「確定」ではなく「破棄」(消す)
        if (composing.isNotEmpty()) {
            clearComposingInEditor()
        }
        composing.setLength(0)
        cursor = 0

        inCandidateMode = false
        candidatePreviewBaseText = null
        candidatePreviewBaseCursor = 0
        candidateAdapter?.setSelectedIndex(-1)

        lastCandidates = emptyList()
        lastCandKey = ""
        lastCandidatesKey = ""

        setStatus("Input started")
        updateInputModeUi()
        applyKeyboardSelectorModeUi()
        updateTopRowToggleLabel()
    }

    override fun onDestroy() {
        super.onDestroy()
        candJob?.cancel()
        serviceJob.cancel()
        currentPlugin?.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // root を作り直す運用なら（あなたが「コードに変えた」側の想定）
        val newRoot = onCreateInputView()
        setInputView(newRoot)

        // 重要：新しい keyboardContainer は空なので、必ず再生成
        selectKeyboard(currentKeyboardId, savePref = false, forceRecreate = true)

        // ついでにサイズ制約も再適用（回転後の画面サイズで clamp される）
        applySavedPanelSize()
    }

    /**
     * imeStatusText の表示/非表示を「プログラム内でのみ」切り替えるためのAPI。
     */
    fun setStatusTextVisible(visible: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_STATUS_TEXT, visible).apply()
        statusText?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ---------------- Status helpers ----------------

    private fun applyStatusVisibilityFromPref() {
        val visible = prefs.getBoolean(KEY_SHOW_STATUS_TEXT, false) // default: false
        statusText?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setStatus(msg: String) {
        statusText?.text = msg
    }

    // ---------------- Editor cursor move ----------------

    private fun moveCursorInEditor(delta: Int) {
        if (delta == 0) return
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val times = kotlin.math.abs(delta)
        for (_i in 0 until times) {
            sendDownUpKeyEvents(keyCode)
        }
        setStatus("Cursor moved (${if (delta < 0) "LEFT" else "RIGHT"}) x$times")
    }

    private fun moveCursorVerticalInEditor(delta: Int) {
        if (delta == 0) return
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        val times = kotlin.math.abs(delta)
        for (_i in 0 until times) {
            sendDownUpKeyEvents(keyCode)
        }
        setStatus("Cursor moved (${if (delta < 0) "UP" else "DOWN"}) x$times")
    }

    // ---------------- Keyboard Selector Mode ----------------

    private fun loadKeyboardSelectorMode(): KeyboardSelectorMode {
        return when (prefs.getString(KEY_KEYBOARD_SELECTOR_MODE, "toprow")) {
            "tabs" -> KeyboardSelectorMode.TABS
            else -> KeyboardSelectorMode.TOPROW
        }
    }

    private fun saveKeyboardSelectorMode(mode: KeyboardSelectorMode) {
        prefs.edit()
            .putString(
                KEY_KEYBOARD_SELECTOR_MODE,
                if (mode == KeyboardSelectorMode.TABS) "tabs" else "toprow"
            )
            .apply()
    }

    /**
     * 要件：
     * - デフォルトは TOPROW（タブは出さない）
     * - TABS のときは topRow のプラグイン切替ボタン（トグル）を出さない
     */
    private fun applyKeyboardSelectorModeUi() {
        val tabs = keyboardTypeRow
        val toggle = toggleKeyboardTopRowBtn

        if (keyboardSelectorMode == KeyboardSelectorMode.TABS) {
            tabs?.visibility = View.VISIBLE
            toggle?.visibility = View.GONE
        } else {
            tabs?.visibility = View.GONE
            //toggle?.visibility = View.VISIBLE
        }

        updateKeyboardUiState()
        updateTopRowToggleLabel()
    }

    /**
     * TOPROWトグルの表示ラベルを、現在選択中のプラグインに合わせる
     */
    private fun updateTopRowToggleLabel() {
        val btn = toggleKeyboardTopRowBtn ?: return
        if (keyboardSelectorMode != KeyboardSelectorMode.TOPROW) return

        val p = registry.getOrDefault(currentKeyboardId)
        btn.text = p.displayName
    }

    /**
     * registry内を順送りで次のプラグインへ
     */
    private fun selectNextKeyboardInRegistry() {
        val list = registry.all()
        if (list.isEmpty()) return

        val idx = list.indexOfFirst { it.id == currentKeyboardId }
        val nextIdx = if (idx >= 0) (idx + 1) % list.size else 0
        val nextId = list[nextIdx].id
        selectKeyboard(nextId, savePref = true)
    }

    // ---------------- Candidate / TopRow visibility policy ----------------

    /**
     * 要件：
     * - Preeditモード かつ composingに文字がある かつ bgに文字がある かつ 候補がある => 候補を表示（topRowは非表示）
     * - Preeditモード かつ composingに文字がある かつ bgが空 かつ ulにのみ文字がある => 何も表示しない（topRowも候補も非表示）
     * - 候補要求中（loading）で bgに文字がある => 何も表示しない（topRowも候補も非表示）※チラつき防止
     * - それ以外 => topRow表示（候補は非表示）
     */
    private fun updateCandidateTopRowVisibility(
        hasCandidates: Boolean,
        isLoading: Boolean = false
    ) {
        val isPreeditWithText = (inputMode == InputMode.PREEDIT) && composing.isNotEmpty()

        val bgEmptyUlOnly =
            isPreeditWithText && bgRange.isEmpty() && !ulRange.isEmpty()

        val showCandidates =
            isPreeditWithText && !bgRange.isEmpty() && hasCandidates

        val loadingWithBg =
            isPreeditWithText && !bgRange.isEmpty() && isLoading

        when {
            showCandidates -> {
                candidateRecycler?.visibility = View.VISIBLE
                topRow?.visibility = View.GONE
            }

            bgEmptyUlOnly -> {
                candidateRecycler?.visibility = View.GONE
                topRow?.visibility = View.GONE
            }

            loadingWithBg -> {
                candidateRecycler?.visibility = View.GONE
                topRow?.visibility = View.GONE
            }

            else -> {
                candidateRecycler?.visibility = View.GONE
                topRow?.visibility = View.VISIBLE
            }
        }
    }

    // ---------------- Candidates (RecyclerView) ----------------

    private fun notifyPluginCandidateAdapterClicked() {
        currentPlugin?.onHostEvent(
            com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.HostEvent.CandidateAdapterClicked
        )
    }

    private fun setupCandidatesRecycler() {
        val rv = candidateRecycler ?: return

        val lm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rv.layoutManager = lm

        fun scrollToIndexIfNeeded(index: Int) {
            if (index < 0) return

            rv.post {
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()

                // すでに表示されているなら何もしない（無駄なスクロール抑制）
                if (first >= 0 && last >= 0 && index in first..last) return@post

                rv.smoothScrollToPosition(index)
            }
        }

        candidateAdapter = CandidateAdapter(
            onClick = { candidate ->
                notifyPluginCandidateAdapterClicked()
                if (inputMode != InputMode.PREEDIT) return@CandidateAdapter

                if (inCandidateMode) {
                    val idx = candidateAdapter?.indexOfSurface(candidate.surface) ?: 0
                    candidateAdapter?.setSelectedIndex(if (idx >= 0) idx else 0)
                    previewSelectedCandidateIfNeeded()
                } else {
                    applyCandidateAndCarryUnderline(candidate)
                }
            },
            onSelectedIndexChanged = { idx ->
                scrollToIndexIfNeeded(idx)
            }
        )

        rv.adapter = candidateAdapter
        rv.visibility = View.GONE
    }

    /**
     * 候補要求キー（取り違え防止用）
     * 候補自体は bgSub にのみ依存するが、UI整合性のため composing/cursor/len も含める。
     */
    private fun makeCandidateKey(bgSub: String, fullLen: Int, cursorPos: Int): String {
        return "$bgSub#$cursorPos#$fullLen"
    }

    /**
     * 現在の composing から bgRange 部分（読み）を安全に取り出す。
     */
    private fun extractBgSubstring(fullText: String): String {
        val b = bgRange
        if (b.isEmpty()) return ""
        return try {
            fullText.substring(b.start, b.endExclusive)
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * 変換候補は「かな漢字変換結果のみ」。
     * 以前入っていた「ひらがな/カタカナ候補」は要件により廃止。
     */
    private fun computeCandidatesForBgSync(bgSub: String): List<NativeCandidate> {
        if (bgSub.isBlank()) return emptyList()
        if (!kkReady) return emptyList()

        val yomi = toHiragana(bgSub)

        val results = runCatching {
            kkConverter.convert(
                queryUtf8 = yomi,
                nBest = 4,
                beamWidth = 20,
                showBunsetsu = true,
                yomiMode = 3,
                showPred = false,
                showOmit = true,
                yomiLimit = 200,
                finalLimit = 50,
            )
        }.getOrNull() ?: return emptyList()

        // dedup: surface ベース（必要なら yomi/score も含めて調整）
        val out = ArrayList<NativeCandidate>(results.size)
        val seen = HashSet<String>(results.size)
        for (c in results) {
            val s = c.surface
            if (s.isNullOrBlank()) continue
            if (seen.add(s)) out.add(c)
        }
        return out
    }

    /**
     * 非同期で候補を要求して UI に反映する。
     * - renderComposing() は「要求を出す」だけ
     * - UIへの反映はこの関数の Main スレッド部分に集約
     */
    private fun requestCandidatesAsync(fullText: String, bgSub: String) {
        // Preedit 以外・空文字は候補なし
        if (inputMode != InputMode.PREEDIT || fullText.isEmpty() || bgSub.isEmpty()) {
            candidateRequestInFlight = false
            candidateInFlightKey = ""

            lastCandidates = emptyList()
            lastCandKey = ""
            lastCandidatesKey = ""

            candidateAdapter?.submit(emptyList())
            updateCandidateTopRowVisibility(hasCandidates = false, isLoading = false)
            updateActionKeyLabels()
            return
        }

        val key = makeCandidateKey(bgSub, fullText.length, cursor)
        lastCandKey = key

        // ★ loading 開始（TopRowのチラつき防止）
        candidateRequestInFlight = true
        candidateInFlightKey = key

        val hasReadyNow = (lastCandidatesKey == key) && lastCandidates.isNotEmpty()
        updateCandidateTopRowVisibility(hasCandidates = hasReadyNow, isLoading = true)
        updateActionKeyLabels()

        candJob?.cancel()
        candJob = serviceScope.launch(Dispatchers.Default) {
            val list = computeCandidatesForBgSync(bgSub)

            withContext(Dispatchers.Main) {
                // 途中で状態が変わっていたら捨てる
                if (lastCandKey != key) return@withContext
                if (inputMode != InputMode.PREEDIT) return@withContext
                if (composing.toString() != fullText) return@withContext

                // ★ loading 終了
                candidateRequestInFlight = false
                candidateInFlightKey = ""

                lastCandidates = list
                lastCandidatesKey = key
                candidateAdapter?.submit(list)
                candidateRecycler?.scrollToPosition(0)
                Timber.d("requestCandidatesAsync: $list")

                // CandidateMode 整合性（CandidateMode中に候補を作り直さない設計だが保険）
                if (inCandidateMode) {
                    if (list.isEmpty() || bgRange.isEmpty()) {
                        inCandidateMode = false
                        candidatePreviewBaseText = null
                        candidatePreviewBaseCursor = 0
                        candidateAdapter?.setSelectedIndex(-1)
                    } else {
                        val curIdx = candidateAdapter?.getSelectedIndex() ?: -1
                        val keepIdx = if (curIdx in list.indices) curIdx else 0
                        candidateAdapter?.setSelectedIndex(keepIdx)
                    }
                }

                updateCandidateTopRowVisibility(
                    hasCandidates = list.isNotEmpty(),
                    isLoading = false
                )
                updateActionKeyLabels()
            }
        }
    }

    // ---------------- ここが今回の本質（prefixだけ変換する） ----------------

    /**
     * bgRange の「先頭何文字」を置換対象にするか決める。
     * - bgText をひらがな化したものが candYomi で startsWith するなら、その長さ分だけを対象にする
     * - 合わない場合は bg 全体を対象にする（安全フォールバック）
     */
    private fun decideBgPrefixEndExclusive(
        fullText: String,
        bg: SpanRange,
        cand: NativeCandidate
    ): Int {
        if (bg.isEmpty()) return bg.endExclusive

        val bgText = try {
            fullText.substring(bg.start, bg.endExclusive)
        } catch (_: Throwable) {
            return bg.endExclusive
        }

        val candYomi = toHiragana(cand.yomi).trim()
        if (candYomi.isEmpty()) return bg.endExclusive

        val hiraBg = toHiragana(bgText)

        return if (hiraBg.startsWith(candYomi) && candYomi.length <= bgText.length) {
            bg.start + candYomi.length
        } else {
            bg.endExclusive
        }
    }

    /**
     * 候補タップ:
     * - bgRange 全体ではなく「candidate.yomi 相当のprefix だけ」を candidate.surface に置換して確定
     * - 残り（例: 「の」）は次の preedit として残す
     */
    private fun applyCandidateAndCarryUnderline(candidate: NativeCandidate) {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return

        val originalText = composing.toString()
        val originalLen = originalText.length

        updateDecorRangesForRender(originalLen)
        val bg = bgRange
        if (bg.isEmpty()) return

        val replaceEndExclusive = decideBgPrefixEndExclusive(originalText, bg, candidate)

        val replaced = StringBuilder(originalText).apply {
            replace(bg.start, replaceEndExclusive, candidate.surface)
        }.toString()

        // 確定するのは「候補surfaceの末尾まで」
        val commitEndExclusive = clamp(bg.start + candidate.surface.length, 0, replaced.length)
        val commitPart = replaced.substring(0, commitEndExclusive)

        // 残りは次の preedit
        val nextPreedit = if (commitEndExclusive < replaced.length) {
            replaced.substring(commitEndExclusive)
        } else {
            ""
        }

        ic.beginBatchEdit()

        clearComposingInEditor()
        ic.commitText(commitPart, 1)

        composing.setLength(0)
        composing.append(nextPreedit)
        cursor = composing.length

        inCandidateMode = false
        candidatePreviewBaseText = null
        candidatePreviewBaseCursor = 0
        candidateAdapter?.setSelectedIndex(-1)

        // 候補状態を破棄（次の renderComposing で新規要求）
        lastCandidates = emptyList()
        lastCandKey = ""
        lastCandidatesKey = ""

        if (composing.isNotEmpty()) {
            renderComposing()
        } else {
            candidateAdapter?.submit(emptyList())
            updateCandidateTopRowVisibility(hasCandidates = false)
            updateActionKeyLabels()
            setStatus("Preedit: empty")
        }

        ic.endBatchEdit()
    }

    // ひらがな<->カタカナ変換（Unicodeオフセット）
    private fun toKatakana(src: String): String {
        val sb = StringBuilder(src.length)
        for (ch in src) {
            val code = ch.code
            if (code in 0x3041..0x3096) {
                sb.append((code + 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun toHiragana(src: String): String {
        val sb = StringBuilder(src.length)
        for (ch in src) {
            val code = ch.code
            if (code in 0x30A1..0x30F6) {
                sb.append((code - 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ---------------- Keyboard plugin host ----------------

    private fun buildKeyboardTypeTabs() {
        val row = keyboardTypeRow ?: return
        row.removeAllViews()
        tabButtons.clear()

        val plugins = registry.all()
        for (p in plugins) {
            val btn = Button(this).apply {
                text = p.displayName
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(40f)).apply {
                    weight = 1f
                }
                minHeight = dpToPx(40f)
                setOnClickListener { selectKeyboard(p.id, savePref = true) }
            }
            row.addView(btn)
            tabButtons[p.id] = btn
        }
    }

    private fun selectKeyboard(id: String, savePref: Boolean, forceRecreate: Boolean = false) {
        val container = keyboardContainer ?: return
        val newPlugin = registry.getOrDefault(id)

        val containerHasView = container.childCount > 0
        val sameKeyboard = (currentKeyboardId == newPlugin.id && currentPlugin != null)

        if (!forceRecreate && sameKeyboard && containerHasView) {
            updateKeyboardUiState()
            updateTopRowToggleLabel()
            updateActionKeyLabels()
            return
        }

        currentPlugin?.onUnselected()
        container.removeAllViews()

        val view = newPlugin.createView(container, controller)
        container.addView(view)

        currentPlugin = newPlugin
        currentKeyboardId = newPlugin.id

        if (savePref) {
            prefs.edit().putString(KEY_KEYBOARD_ID, currentKeyboardId).apply()
        }

        newPlugin.onSelected()
        updateKeyboardUiState()
        updateTopRowToggleLabel()
        updateActionKeyLabels()
    }

    private fun updateKeyboardUiState() {
        for ((id, btn) in tabButtons) {
            val selected = (id == currentKeyboardId)
            btn.isEnabled = !prefs.getBoolean(KEY_RESIZE_MODE, false)
            btn.alpha = if (selected) 1.0f else 0.6f
        }
    }

    // ---------------- Input Mode ----------------

    private fun loadInputMode(): InputMode {
        return when (prefs.getString(KEY_INPUT_MODE, "preedit")) {
            "preedit" -> InputMode.PREEDIT
            else -> InputMode.DIRECT
        }
    }

    private fun saveInputMode(mode: InputMode) {
        prefs.edit()
            .putString(KEY_INPUT_MODE, if (mode == InputMode.PREEDIT) "preedit" else "direct")
            .apply()
    }

    private fun updateInputModeUi() {
        inputModeBtn?.text = if (inputMode == InputMode.PREEDIT) "Preedit" else "Direct"

        if (inputMode == InputMode.DIRECT) {
            inCandidateMode = false
            candidatePreviewBaseText = null
            candidatePreviewBaseCursor = 0
            candidateAdapter?.setSelectedIndex(-1)

            setStatus("Direct")
            if (composing.isNotEmpty()) {
                clearComposingInEditor()
                composing.setLength(0)
                cursor = 0
            }

            lastCandidates = emptyList()
            lastCandKey = ""
            lastCandidatesKey = ""

            candidateAdapter?.submit(emptyList())
            updateCandidateTopRowVisibility(hasCandidates = false)
            updateActionKeyLabels()
        } else {
            setStatus("Preedit: composing=${composing.length}, cursor=$cursor")
            renderComposing()
        }
    }

    // ---------------- Candidate Mode helpers ----------------

    private fun currentCandidateKeyForNow(text: String): String {
        val bgSub = extractBgSubstring(text)
        if (bgSub.isEmpty()) return ""
        return makeCandidateKey(bgSub, text.length, cursor)
    }

    private fun shouldEnterCandidateMode(): Boolean {
        if (inputMode != InputMode.PREEDIT) return false
        if (composing.isEmpty()) return false
        if (inCandidateMode) return false
        if (bgRange.isEmpty()) return false

        val keyNow = currentCandidateKeyForNow(composing.toString())
        return keyNow.isNotEmpty() && (lastCandidatesKey == keyNow) && lastCandidates.isNotEmpty()
    }

    private fun enterCandidateMode() {
        if (inputMode != InputMode.PREEDIT) return
        if (composing.isEmpty()) return
        if (bgRange.isEmpty()) return

        val keyNow = currentCandidateKeyForNow(composing.toString())
        if (keyNow.isEmpty() || lastCandidatesKey != keyNow || lastCandidates.isEmpty()) return

        inCandidateMode = true

        candidatePreviewBaseText = composing.toString()
        candidatePreviewBaseCursor = cursor

        candidateAdapter?.setSelectedIndex(0)
        updateActionKeyLabels()

        previewSelectedCandidateIfNeeded()
        setStatus("CandidateMode: selected=0")
    }

    private fun exitCandidateMode(restorePreview: Boolean, renderAfter: Boolean) {
        if (!inCandidateMode) return

        inCandidateMode = false
        candidateAdapter?.setSelectedIndex(-1)

        if (restorePreview) {
            val base = candidatePreviewBaseText
            if (base != null) {
                composing.setLength(0)
                composing.append(base)
                cursor = clamp(candidatePreviewBaseCursor, 0, composing.length)
            }
        }

        candidatePreviewBaseText = null
        candidatePreviewBaseCursor = 0

        updateActionKeyLabels()

        if (renderAfter) {
            renderComposing()
        }
    }

    private fun moveCandidateSelectionNext() {
        if (!inCandidateMode) return
        val idx = candidateAdapter?.moveNextWrap() ?: -1
        updateActionKeyLabels()

        previewSelectedCandidateIfNeeded()

        if (idx >= 0) setStatus("CandidateMode: selected=$idx")
    }

    private fun commitSelectedCandidateAndExit() {
        if (!inCandidateMode) return

        val selected = candidateAdapter?.getSelected()
        if (selected == null) {
            exitCandidateMode(restorePreview = true, renderAfter = true)
            return
        }

        candidatePreviewBaseText = null
        candidatePreviewBaseCursor = 0

        applyCandidateAndCarryUnderline(selected)

        inCandidateMode = false
        candidateAdapter?.setSelectedIndex(-1)
        updateActionKeyLabels()
    }

    /**
     * CandidateMode中：selectedIndex に合わせて bg 部分を「未確定のまま」置換して表示する。
     * - 置換の基準は CandidateMode開始時の composing（candidatePreviewBaseText）
     * - 置換は bg 全体ではなく「selected.yomi の prefix 分だけ」
     */
    private fun previewSelectedCandidateIfNeeded() {
        if (!inCandidateMode) return
        val base = candidatePreviewBaseText ?: return
        val selected = candidateAdapter?.getSelected() ?: return

        val baseLen = base.length
        val baseCursor = clamp(candidatePreviewBaseCursor, 0, baseLen)

        // 現在の composing/cursor を退避
        val prevText = composing.toString()
        val prevCursor = cursor

        // base を前提に bg/ul を計算するために一時的に差し替え
        composing.setLength(0)
        composing.append(base)
        cursor = baseCursor
        updateDecorRangesForRender(baseLen)

        val bg = bgRange
        if (bg.isEmpty()) {
            composing.setLength(0)
            composing.append(prevText)
            cursor = prevCursor
            return
        }

        val replaceEndExclusive = decideBgPrefixEndExclusive(base, bg, selected)

        val replaced = StringBuilder(base).apply {
            replace(bg.start, replaceEndExclusive, selected.surface)
        }.toString()

        // プレビュー中のカーソルは「surface直後」に置く（変換境界が分かりやすい）
        val newCursor = clamp(bg.start + selected.surface.length, 0, replaced.length)

        composing.setLength(0)
        composing.append(replaced)
        cursor = newCursor

        renderComposing()
    }

    private fun updateActionKeyLabels() {
        val spaceBtn = btnSpaceInKeyboard
        val enterBtn = btnEnterInKeyboard

        val textNow = composing.toString()
        val canConvert = run {
            if (inputMode != InputMode.PREEDIT) return@run false
            if (textNow.isEmpty()) return@run false
            if (bgRange.isEmpty()) return@run false
            val keyNow = currentCandidateKeyForNow(textNow)
            keyNow.isNotEmpty() && (lastCandidatesKey == keyNow) && lastCandidates.isNotEmpty()
        }

        when {
            inCandidateMode -> {
                spaceBtn?.text = "次候補"
                enterBtn?.text = "確定"
            }

            canConvert -> {
                spaceBtn?.text = "変換"
            }

            else -> {
                // 何もしない（キーボード側の既定テキストを保持）
            }
        }
    }

    // ---------------- Preedit / composing ----------------

    private fun insertToComposing(text: String) {
        val idx = clamp(cursor, 0, composing.length)
        composing.insert(idx, text)
        cursor = idx + text.length
    }

    private fun performBackspacePreedit() {
        if (composing.isEmpty() || cursor <= 0) return

        composing.deleteCharAt(cursor - 1)
        cursor -= 1

        if (composing.isEmpty()) {
            clearComposingInEditor()
            inCandidateMode = false
            candidatePreviewBaseText = null
            candidatePreviewBaseCursor = 0

            lastCandidates = emptyList()
            lastCandKey = ""
            lastCandidatesKey = ""

            candidateAdapter?.submit(emptyList())
            updateCandidateTopRowVisibility(hasCandidates = false)
            updateActionKeyLabels()
            setStatus("Preedit: empty")
            return
        }

        renderComposing()
    }

    private fun renderComposing() {
        val ic = currentInputConnection ?: return

        if (composing.isEmpty()) {
            clearComposingInEditor()
            inCandidateMode = false
            candidatePreviewBaseText = null
            candidatePreviewBaseCursor = 0

            candidateRequestInFlight = false
            candidateInFlightKey = ""

            lastCandidates = emptyList()
            lastCandKey = ""
            lastCandidatesKey = ""

            candidateAdapter?.submit(emptyList())
            updateCandidateTopRowVisibility(hasCandidates = false, isLoading = false)
            updateActionKeyLabels()
            setStatus("Preedit: empty")
            return
        }

        val text = composing.toString()
        val len = text.length

        updateDecorRangesForRender(len)

        val bgSub = extractBgSubstring(text)

        // ★ CandidateMode中は候補要求しない
        if (!inCandidateMode) {
            requestCandidatesAsync(fullText = text, bgSub = bgSub)
        }

        val keyNow = if (bgSub.isNotEmpty()) makeCandidateKey(bgSub, len, cursor) else ""

        val isLoadingNow =
            (!inCandidateMode) && candidateRequestInFlight && (candidateInFlightKey == keyNow)

        val hasCandidatesNow =
            (!inCandidateMode) &&
                    keyNow.isNotEmpty() &&
                    (lastCandidatesKey == keyNow) &&
                    lastCandidates.isNotEmpty()

        val effectiveHasCandidates =
            if (inCandidateMode) lastCandidates.isNotEmpty() else hasCandidatesNow
        val effectiveLoading = if (inCandidateMode) false else isLoadingNow

        // CandidateMode維持の整合性
        if (inCandidateMode) {
            if (lastCandidates.isEmpty() || bgRange.isEmpty()) {
                inCandidateMode = false
                candidatePreviewBaseText = null
                candidatePreviewBaseCursor = 0
                candidateAdapter?.setSelectedIndex(-1)
            } else {
                val curIdx = candidateAdapter?.getSelectedIndex() ?: -1
                val keepIdx = if (curIdx in lastCandidates.indices) curIdx else 0
                candidateAdapter?.setSelectedIndex(keepIdx)
            }
        }

        updateCandidateTopRowVisibility(
            hasCandidates = effectiveHasCandidates,
            isLoading = effectiveLoading
        )
        updateActionKeyLabels()

        val spannable = buildComposingSpannable(text, bgRange, ulRange)
        ic.setComposingText(spannable, 1)

        val req = ExtractedTextRequest().apply {
            token = 0
            flags = 0
            hintMaxChars = 0
            hintMaxLines = 0
        }
        val extracted = ic.getExtractedText(req, 0)
        if (extracted != null) {
            val composingStart = extracted.selectionEnd - len
            val desiredAbs = composingStart + clamp(cursor, 0, len)
            ic.setSelection(desiredAbs, desiredAbs)
        } else {
            val newCursorPosition = clamp(cursor, 0, len) - len
            ic.setComposingText(spannable, newCursorPosition)
        }

        setStatus(
            "Preedit: \"$text\" (cursor=$cursor, bg=[${bgRange.start},${bgRange.endExclusive}), ul=[${ulRange.start},${ulRange.endExclusive}), candMode=$inCandidateMode, loading=$effectiveLoading)"
        )
    }

    private fun updateDecorRangesForRender(len: Int) {
        val c = clamp(cursor, 0, len)

        when (decorMode) {
            PreeditDecorMode.SPLIT_AT_CURSOR -> {
                bgRange = SpanRange(0, c)
                ulRange = SpanRange(c, len)
            }

            PreeditDecorMode.CURSOR_CHAR -> {
                val bgStart = c
                val bgEnd = min(c + 1, len)
                bgRange = SpanRange(bgStart, bgEnd)
                ulRange = SpanRange(0, len)
            }

            PreeditDecorMode.CUSTOM -> {
                bgRange = normalizeRange(customBgRange, len)
                ulRange = normalizeRange(customUlRange, len)
            }
        }
    }

    private fun normalizeRange(r: SpanRange, len: Int): SpanRange {
        val s = clamp(r.start, 0, len)
        val e = clamp(r.endExclusive, 0, len)
        return if (e <= s) SpanRange(0, 0) else SpanRange(s, e)
    }

    private fun buildComposingSpannable(
        text: String,
        bg: SpanRange,
        ul: SpanRange
    ): SpannableString {
        val s = SpannableString(text)
        val len = text.length
        if (len <= 0) return s

        if (!ul.isEmpty()) {
            s.setSpan(
                UnderlineSpan(),
                ul.start,
                ul.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        if (!bg.isEmpty()) {
            val bgColor = Color.argb(120, 59, 130, 246)
            s.setSpan(
                BackgroundColorSpan(bgColor),
                bg.start,
                bg.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return s
    }

    private fun clearComposingInEditor() {
        val ic = currentInputConnection ?: return
        ic.setComposingText("", 1)
        ic.finishComposingText()
    }

    private fun commitAndFinishComposing() {
        val ic = currentInputConnection ?: return
        val text = composing.toString()
        ic.commitText(text, 1)
        ic.finishComposingText()
    }

    // ---------------- Direct mode helpers ----------------

    private fun commitTextDirect(text: String) {
        val ic: InputConnection = currentInputConnection ?: return
        ic.commitText(text, 1)
    }

    private fun performBackspaceDirect() {
        val ic: InputConnection = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    // ---------------- Resize mode ----------------

    private fun setResizeModeEnabled(enabled: Boolean) {
        panel?.setBackgroundResource(
            if (enabled) R.drawable.ime_panel_bg_resize else R.drawable.ime_panel_bg
        )

        val vis = if (enabled) View.VISIBLE else View.GONE
        handleLeft?.visibility = vis
        handleRight?.visibility = vis
        handleTop?.visibility = vis
        handleBottom?.visibility = vis
        handleTopLeft?.visibility = vis
        handleTopRight?.visibility = vis
        handleBottomLeft?.visibility = vis
        handleBottomRight?.visibility = vis

        keyboardContainer?.alpha = if (enabled) 0.6f else 1.0f
        keyboardTypeRow?.alpha = if (enabled) 0.6f else 1.0f
        candidateRecycler?.alpha = if (enabled) 0.6f else 1.0f
        topRow?.alpha = if (enabled) 0.6f else 1.0f
        toggleKeyboardTopRowBtn?.alpha = if (enabled) 0.6f else 1.0f

        setChildrenEnabled(keyboardContainer, !enabled)
        setChildrenEnabled(keyboardTypeRow, !enabled)

        candidateRecycler?.isEnabled = !enabled
        inputModeBtn?.isEnabled = !enabled
        toggleKeyboardTopRowBtn?.isEnabled = !enabled
        resizeModeBtn?.text = if (enabled) "終了" else ""

        updateKeyboardUiState()
    }

    private fun setChildrenEnabled(group: ViewGroup?, enabled: Boolean) {
        if (group == null) return
        group.isEnabled = enabled
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            c.isEnabled = enabled
            if (c is ViewGroup) setChildrenEnabled(c, enabled)
        }
    }

    private fun makeResizeTouchListener(h: HSide, v: VSide): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startW = 0
            private var startH = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val p = panel ?: return false
                val lp = (p.layoutParams ?: return false)

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startW = if (lp.width > 0) lp.width else p.width
                        startH = if (lp.height > 0) lp.height else p.height
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startRawX).toInt()
                        val dy = (event.rawY - startRawY).toInt()

                        var newW = when (h) {
                            HSide.RIGHT -> startW + dx
                            HSide.LEFT -> startW - dx
                            HSide.NONE -> startW
                        }

                        var newH = when (v) {
                            VSide.BOTTOM -> startH + dy
                            VSide.TOP -> startH - dy
                            VSide.NONE -> startH
                        }

                        val b = getPanelBoundsPx()
                        newW = clamp(newW, b.minW, b.maxW)
                        newH = clamp(newH, b.minH, b.maxH)

                        lp.width = newW
                        lp.height = newH
                        p.layoutParams = lp
                        p.requestLayout()

                        savePanelSize(newW, newH)
                        return true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        val finalW = (p.layoutParams?.width ?: p.width)
                        val finalH = (p.layoutParams?.height ?: p.height)
                        savePanelSize(finalW, finalH)
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun applySavedPanelSize() {
        val p = panel ?: return
        val b = getPanelBoundsPx()

        val savedW = prefs.getInt(KEY_PANEL_W, -1)
        val savedH = prefs.getInt(KEY_PANEL_H, -1)

        val defaultW = clamp((resources.displayMetrics.widthPixels * 0.95f).toInt(), b.minW, b.maxW)
        val defaultH = clamp(dpToPx(360f), b.minH, b.maxH)

        val w = if (savedW > 0) clamp(savedW, b.minW, b.maxW) else defaultW
        val h = if (savedH > 0) clamp(savedH, b.minH, b.maxH) else defaultH

        val lp = p.layoutParams ?: ViewGroup.LayoutParams(w, h)
        lp.width = w
        lp.height = h
        p.layoutParams = lp
        p.requestLayout()
    }

    private data class Bounds(val minW: Int, val maxW: Int, val minH: Int, val maxH: Int)

    private fun getPanelBoundsPx(): Bounds {
        val dm = resources.displayMetrics

        val margin = dpToPx(24f)
        val minW = dpToPx(240f)
        val maxW = max(minW, dm.widthPixels - margin)

        val minH = dpToPx(160f)
        val maxH = max(minH, (dm.heightPixels * 0.60f).toInt())

        return Bounds(minW, maxW, minH, maxH)
    }

    private fun savePanelSize(w: Int, h: Int) {
        prefs.edit()
            .putInt(KEY_PANEL_W, w)
            .putInt(KEY_PANEL_H, h)
            .apply()
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = min(max(v, lo), hi)

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }
}
