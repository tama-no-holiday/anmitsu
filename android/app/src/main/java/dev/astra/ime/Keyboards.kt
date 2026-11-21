package dev.astra.ime

import dev.astra.ime.core.FlickDirection
import dev.astra.ime.core.FlickLayout

/** Shift for English letters: one letter in capitals, or caps lock until tapped again. */
internal enum class Shift {
    OFF, ONCE, LOCK;

    val upper: Boolean get() = this != OFF

    /** Shift key cycle: lower → one capital → caps lock → lower. */
    fun next(): Shift = when (this) {
        OFF -> ONCE
        ONCE -> LOCK
        LOCK -> OFF
    }
}

/** Static key layouts. Action ids are interpreted by AstraImeService. */
internal object Keyboards {
    const val BACKSPACE = 1
    const val SPACE = 2
    /** Unused since 0.1.7: 空白 shows 変換 while composing (Gboard/iOS style). Kept so ids stay stable. */
    const val CONVERT = 3
    const val ENTER = 4
    /** No key since 0.1.15 (the system navigation bar switches keyboards); the action still works. */
    const val SWITCH_IME = 5
    const val HIDE = 6
    const val TO_KANA = 7
    const val TO_ASCII = 8
    /** Number screen (12-key or the 123 rows). */
    const val TO_SYMBOLS = 9
    const val SHIFT = 10
    /** Symbol palette. */
    const val TO_JSYMBOLS = 11
    /** Emoji palette. */
    const val TO_EMOJI = 12
    /** ◀ ▶ keys: end any composition, then move the editor cursor by one key event. */
    const val CURSOR_LEFT = 13
    const val CURSOR_RIGHT = 14
    /** Palette bar: back to the text screen (かな, English or numbers) the palette was opened from. */
    const val TO_TEXT = 15
    /**
     * Bottom-left key on every text screen: かな ⇄ English; a hold on either opens numbers ([TO_SYMBOLS]), whose mode
     * key goes back to the screen they were opened from. Its label names the screen a tap switches to.
     */
    const val MODE_CYCLE = 16
    /** Flick up/down on ◀ or ▶: as the left/right actions, with the editor deciding the visual position. */
    const val CURSOR_UP = 17
    const val CURSOR_DOWN = 18

    /** Enter while anything of ours is composed (kana, a selected segment or English): it confirms and does nothing else. */
    const val CONFIRM = "確定"

    const val FULLWIDTH_SPACE = "　"
    const val HALFWIDTH_SPACE = " "

    /**
     * The space a key press types: full-width on the かな screen, half-width on every other one, palettes included
     * whichever screen they were opened from. Direct-input fields (passwords, raw editors) always get half-width.
     */
    fun spaceFor(mode: KeyboardMode, directInput: Boolean): String =
        if (mode == KeyboardMode.KANA && !directInput) FULLWIDTH_SPACE else HALFWIDTH_SPACE

    /** Label of the mode key on [mode]: the screen a tap switches to ([numberReturn]: where numbers go back to). */
    fun cycleLabel(mode: KeyboardMode, numberReturn: KeyboardMode = KeyboardMode.KANA): String =
        when (cycleNext(mode, numberReturn)) {
            KeyboardMode.ASCII -> "ABC"
            else -> "あ"
        }

    /**
     * Screen the mode key switches to from [mode]: かな ⇄ English, numbers back to [numberReturn] (かな or English).
     * Palettes are not in the cycle; they return via TO_TEXT.
     */
    fun cycleNext(mode: KeyboardMode, numberReturn: KeyboardMode = KeyboardMode.KANA): KeyboardMode = when (mode) {
        KeyboardMode.KANA -> KeyboardMode.ASCII
        KeyboardMode.SYMBOLS -> if (numberReturn == KeyboardMode.ASCII) KeyboardMode.ASCII else KeyboardMode.KANA
        else -> KeyboardMode.KANA
    }

    /** The mode key: a tap as [cycleNext]; on かな and English a hold opens numbers (hinted in its corner). */
    private fun modeKey(mode: KeyboardMode, numberReturn: KeyboardMode, weight: Float = 1f): Key =
        if (mode == KeyboardMode.SYMBOLS) Key(cycleLabel(mode, numberReturn), action = MODE_CYCLE, weight = weight)
        else Key(cycleLabel(mode), action = MODE_CYCLE, weight = weight, longAction = TO_SYMBOLS, longLabel = "123")

    /** Left column of the 12-key screens: 記号, ◀, 絵文字, then the mode key. */
    private fun leftColumn(mode: KeyboardMode, numberReturn: KeyboardMode = KeyboardMode.KANA) = listOf(
        Key("記号", action = TO_JSYMBOLS),
        arrow("◀", CURSOR_LEFT),
        Key("絵文字", action = TO_EMOJI),
        modeKey(mode, numberReturn),
    )

    /**
     * 12-key layout after Gboard/iOS: ◀ and ▶ in the side columns (second row), the space key reads 変換 and Enter
     * reads 確定 while [composing]. 「」 are on the や key (left/right flick, as on iOS); other brackets in 記号.
     * The system navigation bar already has hide-keyboard and IME-switch buttons, so there is no ▼ or 切替 key.
     */
    fun kana(enter: String, composing: Boolean = false): List<List<Key>> {
        val left = leftColumn(KeyboardMode.KANA)
        val right = listOf(
            backspace(1f),
            arrow("▶", CURSOR_RIGHT),
            Key(if (composing) "変換" else "空白", action = SPACE),
            Key(if (composing) CONFIRM else enter, action = ENTER),
        )
        return FlickLayout.rows.mapIndexed { i, row ->
            listOfNotNull(left.getOrNull(i)) +
                row.map { Key(it.label, flick = it, weight = 1.25f) } +
                listOfNotNull(right.getOrNull(i))
        }
    }

    /**
     * English 12-key: tap gives a key's first character, flicks left / up / right the next ones (the same order as
     * the kana keys: あ い う え). The symbol keys follow the user's Gboard 12-key (0.1.33, confirmed from their
     * screenshot): @ - _ /, ' " : ; and . , ? ! (tap, left, up, right). A down flick gives the key's digit as on a phone
     * pad (@ → 1, abc → 2 … wxyz → 9, ' → 0), so numbers need no screen switch; the . key has no down flick. # & ( ) are
     * in the 123 rows (and the 記号 palette). Hints for every direction are drawn on the key. The shift key cycles
     * lower → one capital → caps lock; there is no multi-tap, so a tap never changes text already typed.
     * While a word is composed, [wordCase] is the shift key's label: it then flips the last letter's case instead.
     */
    fun english12(shift: Shift, enter: String, wordCase: String? = null): List<List<Key>> {
        val shiftLabel = wordCase ?: when (shift) {
            Shift.OFF -> "a/A"
            Shift.ONCE -> "A ⬆"
            Shift.LOCK -> "A ⇪"
        }
        fun letters(chars: String, digit: Char) = flickText(if (shift.upper) chars.uppercase() else chars, label = null, down = digit)
        val left = leftColumn(KeyboardMode.ASCII)
        return listOf(
            listOf(left[0], flickText("@-_/", down = '1'), letters("abc", '2'), letters("def", '3'), backspace(1f)),
            listOf(left[1], letters("ghi", '4'), letters("jkl", '5'), letters("mno", '6'), arrow("▶", CURSOR_RIGHT)),
            listOf(left[2], letters("pqrs", '7'), letters("tuv", '8'), letters("wxyz", '9'), Key("空白", action = SPACE)),
            listOf(left[3], Key(shiftLabel, action = SHIFT, weight = 1.25f, highlighted = shift.upper || wordCase != null),
                flickText("'\":;", down = '0'), flickText(".,?!"), Key(enter, action = ENTER)),
        )
    }

    /**
     * Number 12-key: 1 2 3 / 4 5 6 / 7 8 9 like a phone, then a sign key, 0 and a decimal key. Flicking a digit
     * up gives the symbol above it on a US keyboard row (1 → !, 9 → (, 0 → )). The decimal key's down flick is '_'.
     * [numberReturn] as in [cycleNext].
     */
    fun numeric12(enter: String, numberReturn: KeyboardMode = KeyboardMode.KANA): List<List<Key>> {
        fun digit(d: Char, up: Char) = Key(d.toString(), flickTexts = listOf(d.toString(), null, up.toString(), null, null), weight = 1.25f)
        val left = leftColumn(KeyboardMode.SYMBOLS, numberReturn)
        return listOf(
            listOf(left[0], digit('1', '!'), digit('2', '@'), digit('3', '#'), backspace(1f)),
            listOf(left[1], digit('4', '$'), digit('5', '%'), digit('6', '^'), arrow("▶", CURSOR_RIGHT)),
            listOf(left[2], digit('7', '&'), digit('8', '*'), digit('9', '('), Key("空白", action = SPACE)),
            listOf(left[3], flickText("-+*/="), digit('0', ')'), flickText(".,:;_"), Key(enter, action = ENTER)),
        )
    }

    /** QWERTY rows; [wordCase] as in [english12]. */
    fun letters(shift: Shift, enter: String, wordCase: String? = null): List<List<Key>> = listOf(
        chars("qwertyuiop", shift.upper),
        listOf(spacer()) + chars("asdfghjkl", shift.upper) + spacer(),
        listOf(Key(wordCase ?: when (shift) { Shift.OFF -> "⇧"; Shift.ONCE -> "⬆"; Shift.LOCK -> "⇪" }, action = SHIFT, weight = 1.5f,
            highlighted = shift.upper || wordCase != null)) + chars("zxcvbnm", shift.upper) + backspace(1.5f),
        fullBottom(KeyboardMode.ASCII, enter, period = true),
    )

    /** The 123 rows (number screen when the 12-key layout is off); [numberReturn] as in [cycleNext]. */
    fun symbols(enter: String, numberReturn: KeyboardMode = KeyboardMode.KANA): List<List<Key>> = listOf(
        chars("1234567890", false),
        chars("@#$%&-+()/", false),
        chars("*\"':;!?,_", false) + backspace(1.5f),
        fullBottom(KeyboardMode.SYMBOLS, enter, period = false, numberReturn = numberReturn),
    )

    /**
     * Fixed bottom row under a palette: back to the text screen it was opened from ([backLabel]), the other palette,
     * space, ⌫ (repeats) and Enter. Categories and the scrolling entries sit above it.
     */
    fun paletteBar(backLabel: String, emoji: Boolean, enter: String): List<List<Key>> = listOf(
        listOf(
            Key(backLabel, action = TO_TEXT, weight = 1.3f),
            if (emoji) Key("記号", action = TO_JSYMBOLS, weight = 1.3f) else Key("絵文字", action = TO_EMOJI, weight = 1.3f),
            Key("空白", action = SPACE, weight = 3f),
            backspace(1.3f),
            Key(enter, action = ENTER, weight = 1.6f),
        ),
    )

    /**
     * Text flick key from up to five characters in direction order (center, left, up, right, down). The key top shows
     * [label] (the tap character unless given) and a small hint for every flick direction.
     */
    private fun flickText(chars: String, label: String? = chars.take(1), down: Char? = null): Key {
        val texts = FlickDirection.entries.map { d ->
            if (d == FlickDirection.DOWN && down != null) down.toString() else chars.getOrNull(d.ordinal)?.toString()
        }
        return Key(label ?: chars, flickTexts = texts, weight = 1.25f)
    }

    /**
     * Bottom row of the full-row screens: mode key (bottom left), 記号, 絵文字, space, [period] '.', Enter. The period key
     * flicks left to '-' and right to '_' (kept from 0.1.18), up to ',' and down to '?' (0.1.33, from the 12-key's
     * confirmed . , ? ! key; not a Gboard QWERTY reference), hinted on the key, so QWERTY needs no screen switch for them.
     */
    private fun fullBottom(mode: KeyboardMode, enter: String, period: Boolean, numberReturn: KeyboardMode = KeyboardMode.KANA) = listOfNotNull(
        modeKey(mode, numberReturn, weight = 1.3f),
        Key("記号", action = TO_JSYMBOLS, weight = 1.3f),
        Key("絵文字", action = TO_EMOJI, weight = 1.3f),
        Key("空白", action = SPACE, weight = if (period) 3.1f else 4.1f),
        if (period) Key(".", flickTexts = listOf(".", "-", ",", "_", "?")) else null,
        Key(enter, action = ENTER, weight = 1.6f),
    )

    private fun chars(source: String, upper: Boolean): List<Key> = source.map {
        val s = if (upper) it.uppercaseChar().toString() else it.toString()
        Key(s, text = s)
    }

    private fun backspace(weight: Float) = Key("⌫", action = BACKSPACE, weight = weight, repeat = true)

    /** Tap: the key's own direction; flick: any of the four, once; hold: repeat its own (see [KeyboardView]). */
    private fun arrow(label: String, action: Int) = Key(
        label, action = action, repeat = true,
        upAction = CURSOR_UP, downAction = CURSOR_DOWN, leftAction = CURSOR_LEFT, rightAction = CURSOR_RIGHT,
    )

    private fun spacer() = Key("", weight = 0.5f)
}
