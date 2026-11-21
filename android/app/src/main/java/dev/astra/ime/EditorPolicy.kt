package dev.astra.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** KANA, ASCII (English) and SYMBOLS (numbers) are text screens; the other two are the scrolling palettes. */
internal enum class KeyboardMode {
    KANA, ASCII, SYMBOLS, JAPANESE_SYMBOLS, EMOJI;

    val isPalette: Boolean get() = this == JAPANESE_SYMBOLS || this == EMOJI
}

/** Pure decisions derived from EditorInfo. Never reads field contents. */
internal object EditorPolicy {

    fun isPassword(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * Direct-input fields bypass the engine entirely and type directly: passwords and raw
     * key-event editors (TYPE_NULL). Privacy flags alone do not disable conversion.
     */
    fun isDirectInput(info: EditorInfo): Boolean {
        val type = info.inputType
        return type == InputType.TYPE_NULL || isPassword(type)
    }

    /**
     * Whether the field allows recording and using on-device history (learning, predictions,
     * gesture samples). Refused for direct-input fields, IME_FLAG_NO_PERSONALIZED_LEARNING
     * (e.g. incognito), no-suggestion fields and URL/e-mail address fields.
     */
    fun allowsLearning(info: EditorInfo): Boolean {
        if (isDirectInput(info)) return false
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return false
        return !hasNoSuggestions(info.inputType) && !isAddress(info.inputType)
    }

    /**
     * Whether the committed text before a composition may be used for ranking ([CommittedContext]): only plain text
     * fields that also allow history, so never passwords, raw editors, private fields, no-suggestion or address
     * fields, and never number, phone or date fields. Conversion itself still runs everywhere it did.
     */
    fun allowsCommittedContext(info: EditorInfo): Boolean = isText(info.inputType) && allowsLearning(info)

    /**
     * Whether flick-slip typo correction may be offered. Off for no-suggestion and URL/e-mail
     * address fields. Live conversion is not governed by this: every non-direct field may
     * live-convert when the global switch is on.
     */
    fun allowsTypoCorrection(info: EditorInfo): Boolean =
        !isDirectInput(info) && !hasNoSuggestions(info.inputType) && !isAddress(info.inputType)

    /** Whether the composition may show a candidate before 変換 (subject to the global live switch). */
    fun allowsLiveConversion(info: EditorInfo): Boolean = !isDirectInput(info)

    /**
     * Whether digits typed on the English keys compose (and convert, 1 → ①) instead of typing at once. Only
     * ordinary text fields: never number, phone or date fields, passwords, raw editors, URL or e-mail fields.
     */
    fun composesDigits(info: EditorInfo): Boolean =
        isText(info.inputType) && !isDirectInput(info) && !isAddress(info.inputType)

    /**
     * Whether punctuation, digits and palette entries typed from the English keys (or a screen opened from them) start an
     * English composition when nothing is composed. Text fields only (URL and e-mail included, as letters there already
     * compose); never number, phone or date fields, passwords or raw editors, which keep typing at once.
     */
    fun composesSymbols(info: EditorInfo): Boolean = isText(info.inputType) && !isDirectInput(info)

    private fun isText(type: Int) =(type and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT

    private fun hasNoSuggestions(type: Int) = isText(type) && (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

    private fun isAddress(type: Int) = isText(type) && when (type and InputType.TYPE_MASK_VARIATION) {
        InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> true
        else -> false
    }

    /**
     * Whether DPAD_UP/DOWN may be sent. A text field without TYPE_TEXT_FLAG_MULTI_LINE is single-line (IME_MULTI_LINE
     * only concerns a fullscreen IME), as are number, phone and date fields: the editor cannot use the key and the
     * framework would move focus out of the field. Anything else (TYPE_NULL terminals, unknown classes, no editor
     * info) is left to the host, as is what a multi-line editor does on its first and last line.
     */
    fun allowsVerticalKeys(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return true
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> false
            else -> true
        }
    }

    fun initialMode(info: EditorInfo): KeyboardMode {
        val type = info.inputType
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME ->
                KeyboardMode.SYMBOLS
            InputType.TYPE_CLASS_TEXT -> when (type and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> KeyboardMode.ASCII
                // URL fields (e.g. the browser omnibox) are mostly used for Japanese search.
                else -> KeyboardMode.KANA
            }
            else -> KeyboardMode.ASCII
        }
    }

    /** Editor action for the enter key, or null when enter should insert a newline. */
    fun editorAction(info: EditorInfo?): Int? {
        if (info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return null
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) null else action
    }

    fun enterLabel(info: EditorInfo?): String = when (editorAction(info)) {
        null -> "⏎"
        EditorInfo.IME_ACTION_GO -> "移動"
        EditorInfo.IME_ACTION_SEARCH -> "検索"
        EditorInfo.IME_ACTION_SEND -> "送信"
        EditorInfo.IME_ACTION_NEXT -> "次へ"
        EditorInfo.IME_ACTION_PREVIOUS -> "前へ"
        EditorInfo.IME_ACTION_DONE -> "完了"
        else -> "実行"
    }
}
