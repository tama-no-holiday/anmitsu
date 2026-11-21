package dev.astra.ime

import android.os.Looper
import android.text.InputType as T
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import dev.astra.ime.core.FlickDirection
import dev.astra.ime.core.FlickKey
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 0.1.15: mode key cycle, direct 記号/絵文字 keys, and English words composed with candidates from the bundled
 * OSS-derived index (assets/english/english-words.tsv). The real service and the real index; only the editor is fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class EnglishInputTest {
    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        DebugSettings.setOssEnabled(context, false)
        DebugSettings.setZenzEnabled(context, false)
        DebugSettings.setLiveConversion(context, false)
        LayoutSettings.setEnglish12Key(context, true)
        LayoutSettings.setNumeric12Key(context, true)
    }

    // --- Pure word logic ----------------------------------------------------------------------------

    @Test fun theCaseKeyFlipsOnlyTheLastCharacterAndOnlyIfItIsALetter() {
        val w = EnglishWord()
        "Akasak".forEach(w::type)
        w.type('a')
        assertEquals("a→A", w.caseLabel())
        assertTrue(w.toggleLastCase())
        assertEquals("AkasakA", w.shown) // the mixed case typed before stays
        assertEquals("A→a", w.caseLabel())
        assertTrue(w.toggleLastCase())
        assertEquals("Akasaka", w.shown)
        // After punctuation or a digit nothing changes, and no earlier letter is looked for.
        w.insert(".")
        assertEquals(null, w.caseLabel())
        assertFalse(w.toggleLastCase())
        w.insert("1")
        assertFalse(w.toggleLastCase())
        assertEquals("Akasaka.1", w.shown)
        w.type('x')
        assertTrue(w.toggleLastCase())
        assertEquals("Akasaka.1X", w.shown)
        assertFalse(EnglishWord().toggleLastCase())
    }

    @Test fun insertionsKeepTheirTextAndBackspaceRemovesOneEntryWhole() {
        val w = EnglishWord()
        "hello".forEach(w::type)
        w.insert(",")
        w.insert("👨‍👩‍👧") // one ZWJ sequence, one entry
        assertEquals("hello,👨‍👩‍👧", w.shown)
        assertEquals("", w.typed) // no word at the caret after a symbol
        assertTrue(w.backspace())
        assertEquals("hello,", w.shown)
        assertTrue(w.backspace())
        assertEquals("hello", w.typed) // the word is at the caret again
        assertTrue(w.backspace())
        assertEquals("hell", w.shown)
        repeat(4) { assertTrue(w.backspace()) }
        assertTrue(w.isEmpty)
        assertFalse(w.backspace()) // nothing before the caret
    }

    private fun word(vararg entries: String) = EnglishWord().apply { entries.forEach { if (EnglishWord.isLetter(it)) type(it[0]) else insert(it) } }
    private fun letters(s: String) = s.map { it.toString() }.toTypedArray()

    /** Review follow-up: combinations stay bounded and distinct however many parts the composition has. */
    @Test fun combinedFormsAreBoundedForLongMixedCompositions() {
        val forms = { s: String ->
            listOf(dev.astra.ime.core.Supplement.Widths.full(s)) +
                (if (s.all { it in '0'..'9' }) listOf("①", "一") else emptyList()) +
                if (s == "()" || s == "[]") listOf("「」", "【】", "〔〕") else emptyList()
        }
        val long = word(*"a-b(c)1-2-3[d]e.f,g!h?9".map { it.toString() }.toTypedArray())
        val list = WholeCandidates.of(long, { null }, forms, widths = true).list
        assertEquals("a-b(c)1-2-3[d]e.f,g!h?9", list.first())
        assertTrue(list.size <= WholeCandidates.MAX)
        assertEquals(list.size, list.toSet().size)
        assertTrue(list.all { it.codePointCount(0, it.length) == list.first().length }) // every candidate is the whole text
        // Two parts changed together exist even here (the caret's neighbours first).
        assertTrue(list.toString(), list.any { c -> c.indices.count { c[it] != list.first()[it] } >= 2 && c != dev.astra.ime.core.Supplement.Widths.full(list.first()) })
    }

    @Test fun candidatesKeepCanonicalMixedCaseAndStyleLowercaseSpellings() {
        assertEquals(listOf("openai", "OpenAI", "Openai", "OPENAI"), EnglishWord.wordCandidates("openai", listOf("OpenAI"), emptyList()))
        assertEquals(listOf("OPENAI", "OpenAI", "openai", "Openai"), EnglishWord.wordCandidates("OPENAI", listOf("OpenAI"), emptyList()))
        assertEquals(listOf("Appl", "Apple", "appl", "APPL"), EnglishWord.wordCandidates("Appl", emptyList(), listOf("apple")))
        val apple = WholeCandidates.of(word(*letters("Appl")), { dev.astra.ime.oss.EnglishLexicon.Result(emptyList(), listOf("apple")) }, { emptyList() }, widths = true)
        assertEquals(listOf("Appl", "Apple", "appl", "APPL"), apple.list)
        assertEquals(setOf("Apple"), apple.predictions)
        // 0.1.34: a word after a symbol: candidates are for the whole composition, as typed first.
        val after = WholeCandidates.of(word("a", ".", "j", "p"), { null }, { emptyList() }, widths = true).list
        assertEquals("a.jp", after.first())
        assertTrue(after.toString(), after.containsAll(listOf("A.Jp", "A.JP", "ａ．ｊｐ")))
        // One punctuation run alone: itself, then its alternatives, each once (0.1.33).
        assertEquals(listOf("!"), WholeCandidates.of(word("!"), { null }, { emptyList() }, widths = true).list)
        assertEquals(listOf("(", "（", "「"), WholeCandidates.of(word("("), { null }, { listOf("（", "(", "「", "（") }, widths = true).list)
        assertTrue(WholeCandidates.of(word("👍"), { null }, { listOf("x") }, widths = true).list.isEmpty())
    }

    @Test fun tokensAreRunsOfOneKindAndTheCaretSitsBetweenEntries() {
        val w = word("(", ")", "h", "i", "「", "」", "1", "2", "👍")
        assertEquals(listOf("()", "hi", "「」", "12", "👍"), w.tokens().map { it.text })
        assertEquals(listOf(EnglishWord.TokenKind.PUNCTUATION, EnglishWord.TokenKind.LETTERS, EnglishWord.TokenKind.PUNCTUATION,
            EnglishWord.TokenKind.DIGITS, EnglishWord.TokenKind.OTHER), w.tokens().map { it.kind })
        assertTrue(w.caretAtEnd)
        assertEquals(w.shown.length, w.caretOffset)
        assertTrue(w.moveLeft()) // over the whole emoji
        assertEquals(w.shown.length - "👍".length, w.caretOffset)
        assertEquals(3, w.caretToken())
        assertTrue(w.moveToStart())
        assertFalse(w.moveLeft())
        assertFalse(w.backspace()) // nothing before the caret: nothing changes
        repeat(3) { w.moveRight() }
        assertEquals("hi", w.typed) // the word the caret is in
        w.type('X')
        assertEquals("()hXi「」12👍", w.shown)
        assertEquals("A→a", w.caseLabel()) // the letter before the caret
        assertTrue(w.toggleLastCase())
        assertEquals("()hxi「」12👍", w.shown)
        assertTrue(w.backspace())
        assertEquals("()hi「」12👍", w.shown)
        assertTrue(EnglishWord.isPunctuation("→") && EnglishWord.isPunctuation("-") && EnglishWord.isPunctuation("_"))
        assertFalse(EnglishWord.isPunctuation("a") || EnglishWord.isPunctuation("1") || EnglishWord.isPunctuation(" ") || EnglishWord.isPunctuation("😀"))
        assertTrue(EnglishWord.isDigits("12") && !EnglishWord.isDigits("1a") && !EnglishWord.isDigits("１"))
    }

    @Test fun bundledIndexIsTheOssDerivedFileWithProvenance() {
        val lex = EnglishLookup.fromAssets(RuntimeEnvironment.getApplication().assets)
        assertTrue(lex.header.any { it.contains("english-20261015.txt sha256=e520c970c49c34eddea017df5add37b39df8977ca54494cb8993d48c61f04734") })
        assertTrue(lex.header.any { it.contains("ut-extra.txt sha256=fe97d71efde491fcba541f0c457b1436f170e13f582c223bdbef283313914f59") })
        assertTrue(lex.size > 50_000)
        val examples = mapOf("openai" to "OpenAI", "github" to "GitHub", "iphone" to "iPhone", "javascript" to "JavaScript",
            "youtube" to "YouTube", "linkedin" to "LinkedIn", "ios" to "iOS")
        for ((typed, canonical) in examples) assertEquals(typed, canonical, lex.lookup(typed).exact.first())
        assertTrue(lex.lookup("qzxvk").exact.isEmpty())
        val started = System.nanoTime()
        repeat(1000) { lex.lookup("ja") }
        println("english index: keys=${lex.size}, 1000 two-letter lookups with completions in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    // --- Service ------------------------------------------------------------------------------------

    private var controller: ServiceController<TestIme>? = null
    private val ime: TestIme get() = controller!!.get()
    private var root: ViewGroup? = null

    @After fun tearDown() { controller?.destroy() }

    private fun start(type: Int, initial: String = "", imeOptions: Int = 0): FakeEditor {
        if (controller == null) {
            controller = Robolectric.buildService(TestIme::class.java).create()
            ime.ic = FakeEditor(View(ime), "", 0, 0)
            ime.info = EditorInfo().apply { inputType = T.TYPE_CLASS_TEXT }
            ime.onStartInput(ime.info!!, false)
            root = ime.onCreateInputView() as ViewGroup
        }
        val editor = FakeEditor(View(ime), initial, initial.length, initial.length)
        val info = EditorInfo().apply {
            inputType = type; initialSelStart = initial.length; initialSelEnd = initial.length; this.imeOptions = imeOptions
        }
        ime.ic = editor
        ime.info = info
        ime.onStartInput(info, false)
        return editor
    }

    private fun english(type: Int = T.TYPE_CLASS_TEXT, imeOptions: Int = 0): FakeEditor =
        start(type, imeOptions = imeOptions).also { ime.onAction(Keyboards.MODE_CYCLE) } // かな → English

    private fun pumpUntil(what: String = "condition", condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun chips(): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.tag == TAG_ENGLISH_CANDIDATE) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root!!)
        return out
    }

    private fun chipTexts() = chips().map { it.text.toString() }
    private fun type(word: String) = word.forEach { ime.onText(it.toString()) }
    private fun keys() = (0 until root!!.childCount).map { root!!.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
    private fun rows() = keys().keyRows
    private fun lookedUp(word: String) = pumpUntil("lookup of $word") { ime.englishResultKey == word.lowercase() }

    @Test fun modeKeyTogglesKanaEnglishAndAHoldOpensNumbersThatGoBackWhereOpened() {
        start(T.TYPE_CLASS_TEXT)
        fun left() = rows().map { it.first().label }
        fun modeKey() = rows().flatten().single { it.action == Keyboards.MODE_CYCLE }
        assertEquals(listOf("記号", "◀", "絵文字", "ABC"), left())
        assertEquals(Keyboards.TO_SYMBOLS, modeKey().longAction)
        assertEquals("123", modeKey().longLabel)
        val kanaRight = rows().map { it.last().label }
        assertEquals(listOf("⌫", "▶", "空白", "⏎"), kanaRight) // right column unchanged
        ime.onAction(Keyboards.MODE_CYCLE)
        assertEquals(listOf("記号", "◀", "絵文字", "あ"), left())
        assertTrue(rows().flatten().any { it.label == "abc" })
        assertEquals(Keyboards.TO_SYMBOLS, modeKey().longAction)
        ime.onAction(Keyboards.MODE_CYCLE) // numbers are no longer in the cycle
        assertEquals(listOf("記号", "◀", "絵文字", "ABC"), left())
        assertFalse(rows().flatten().any { it.label == "5" })
        // Hold on かな: numbers, whose mode key goes back to かな and has no hold of its own.
        ime.onAction(Keyboards.TO_SYMBOLS)
        assertTrue(rows().flatten().any { it.label == "5" })
        assertEquals(listOf("記号", "◀", "絵文字", "あ"), left())
        assertEquals(0, modeKey().longAction)
        assertEquals(null, modeKey().longLabel)
        ime.onAction(Keyboards.MODE_CYCLE)
        assertEquals(listOf("記号", "◀", "絵文字", "ABC"), left())
        assertFalse(rows().flatten().any { it.label == "abc" || it.label == "5" })
        // Hold on English: numbers go back to English.
        ime.onAction(Keyboards.MODE_CYCLE)
        ime.onAction(Keyboards.TO_SYMBOLS)
        assertEquals(listOf("記号", "◀", "絵文字", "ABC"), left())
        assertTrue(rows().flatten().any { it.label == "5" })
        ime.onAction(Keyboards.MODE_CYCLE)
        assertTrue(rows().flatten().any { it.label == "abc" })
        // Full rows: mode key bottom left, then 記号 and 絵文字.
        LayoutSettings.setEnglish12Key(ime, false)
        LayoutSettings.setNumeric12Key(ime, false)
        ime.onAction(Keyboards.MODE_CYCLE) // かな
        ime.onAction(Keyboards.MODE_CYCLE)
        assertEquals(listOf("あ", "記号", "絵文字"), rows().last().take(3).map { it.label })
        assertTrue(rows().flatten().any { it.label == "q" })
        assertEquals(Keyboards.TO_SYMBOLS, modeKey().longAction)
        ime.onAction(Keyboards.TO_SYMBOLS)
        assertEquals(listOf("ABC", "記号", "絵文字"), rows().last().take(3).map { it.label })
        assertEquals(0, modeKey().longAction)
        ime.onAction(Keyboards.MODE_CYCLE)
        assertTrue(rows().flatten().any { it.label == "q" })
        // No screen has an IME switch key any more (the navigation bar has one).
        for (layout in listOf(Keyboards.kana("⏎"), Keyboards.english12(Shift.OFF, "⏎"), Keyboards.numeric12("⏎"),
            Keyboards.letters(Shift.OFF, "⏎"), Keyboards.symbols("⏎"), Keyboards.numeric12("⏎", KeyboardMode.ASCII))) {
            assertTrue(layout.flatten().none { it.action == Keyboards.SWITCH_IME || it.label == "切替" })
            assertEquals(1, layout.flatten().count { it.action == Keyboards.TO_JSYMBOLS })
            assertEquals(1, layout.flatten().count { it.action == Keyboards.TO_EMOJI })
            assertEquals(1, layout.flatten().count { it.action == Keyboards.MODE_CYCLE })
        }
        // A palette returns to the remembered text screen; the mode key continues from there.
        ime.onAction(Keyboards.TO_EMOJI)
        assertEquals("abc", rows().single().first().label)
        ime.onAction(Keyboards.TO_TEXT)
        assertEquals("あ", rows().last().first().label) // English
        ime.onAction(Keyboards.TO_SYMBOLS)
        ime.onAction(Keyboards.TO_JSYMBOLS)
        assertEquals("123", rows().single().first().label)
        ime.onAction(Keyboards.MODE_CYCLE) // from numbers opened on English: back to English
        assertTrue(rows().flatten().any { it.label == "q" })
    }

    @Test fun openaiShowsOpenAiAndATapCommitsOnlyThatCandidate() {
        val e = english()
        type("openai")
        assertEquals("openai", e.text.toString())
        assertEquals(0, e.composingStart) // uncommitted
        pumpUntil("OpenAI chip") { "OpenAI" in chipTexts() }
        assertEquals(listOf("openai", "OpenAI", "Openai", "OPENAI"), chipTexts())
        chips().single { it.text == "OpenAI" }.performClick()
        assertEquals("OpenAI", e.text.toString()) // no space added
        assertEquals(-1, e.composingStart)
        assertTrue(chips().isEmpty())
        chips().forEach { it.performClick() }
        assertEquals("OpenAI", e.text.toString())
        assertEquals(0, e.reads)
    }

    @Test fun realSpellingsAndOovWordsSpaceAndEnterCommitTheShownWordOnce() {
        val e = english()
        for ((typed, canonical) in listOf("github" to "GitHub", "iphone" to "iPhone", "javascript" to "JavaScript")) {
            type(typed)
            pumpUntil(canonical) { canonical in chipTexts() }
            assertEquals(typed, chipTexts().first()) // the raw word stays first and is what Space commits
            ime.onAction(Keyboards.SPACE)
        }
        assertEquals("github iphone javascript ", e.text.toString())
        type("qzxvk")
        lookedUp("qzxvk")
        assertEquals(listOf("qzxvk", "Qzxvk", "QZXVK"), chipTexts()) // out of vocabulary: raw and case forms only
        fun enterLabel() = rows().flatten().single { it.action == Keyboards.ENTER }.label
        assertEquals(Keyboards.CONFIRM, enterLabel())
        ime.onAction(Keyboards.ENTER) // 確定: committed as shown, and nothing else
        assertEquals("github iphone javascript qzxvk", e.text.toString())
        assertTrue(e.actions.isEmpty())
        assertEquals(-1, e.composingStart)
        assertEquals("⏎", enterLabel())
        ime.onAction(Keyboards.ENTER) // now the editor's own Enter
        assertEquals(listOf(EditorInfo.IME_ACTION_UNSPECIFIED), e.actions) // sendKeyChar('\n') in this editor
    }

    /** 0.1.32: Enter with English composed only confirms, in search fields too; the next press searches. */
    @Test fun inASearchFieldEnterConfirmsTheWordThenSearches() {
        val search = english(imeOptions = EditorInfo.IME_ACTION_SEARCH)
        fun enterLabel() = rows().flatten().single { it.action == Keyboards.ENTER }.label
        val idle = enterLabel()
        type("openai")
        ime.onText("?")
        assertEquals(Keyboards.CONFIRM, enterLabel())
        ime.onAction(Keyboards.ENTER)
        assertEquals("openai?", search.text.toString())
        assertTrue(search.actions.isEmpty())
        assertEquals(-1, search.composingStart)
        assertEquals(idle, enterLabel())
        ime.onAction(Keyboards.ENTER)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), search.actions)
        // A candidate commits exactly what it shows, the whole composition (0.1.34); no search, no space.
        type("hi")
        ime.onText(",")
        type("openai")
        pumpUntil("hi,OpenAI chip") { "hi,OpenAI" in chipTexts() }
        assertFalse("OpenAI" in chipTexts()) // never a part alone
        chips().single { it.text == "hi,OpenAI" }.performClick()
        assertEquals("openai?hi,OpenAI", search.text.toString())
        assertEquals(1, search.actions.size)
        // From a palette or the number screen Enter confirms in the same way.
        type("ab")
        ime.onAction(Keyboards.TO_EMOJI)
        assertEquals(Keyboards.CONFIRM, enterLabel())
        ime.onAction(Keyboards.ENTER)
        assertEquals("openai?hi,OpenAIab", search.text.toString())
        assertEquals(1, search.actions.size)
    }

    @Test fun theCaseKeyFlipsTheLastLetterAndShiftAndCapsLockStayForTheNextWord() {
        val e = english()
        type("Akasak")
        type("a")
        fun has(label: String) = rows().flatten().any { it.label == label }
        assertTrue(has("a→A"))
        ime.onAction(Keyboards.SHIFT)
        assertEquals("AkasakA", e.text.toString())
        assertEquals(0, e.composingStart)
        assertTrue(has("A→a"))
        ime.onAction(Keyboards.SHIFT)
        assertEquals("Akasaka", e.text.toString())
        ime.onText(".")
        ime.onAction(Keyboards.SHIFT) // last character not a letter: nothing, not even an earlier letter
        assertEquals("Akasaka.", e.text.toString())
        ime.onText("1")
        ime.onAction(Keyboards.SHIFT)
        assertEquals("Akasaka.1", e.text.toString())
        ime.onAction(Keyboards.SPACE)
        assertEquals("Akasaka.1 ", e.text.toString())
        // Nothing composed: shift is the ordinary one-capital key again.
        assertTrue(has("a/A"))
        ime.onAction(Keyboards.SHIFT)
        assertTrue(rows().flatten().any { it.label == "A ⬆" && it.highlighted })
        type("Ab") // what the capital keys then type
        assertTrue(has("a→A"))
        assertEquals("Akasaka.1 Ab", e.text.toString())
        ime.onAction(Keyboards.SPACE)
        // Caps lock: letters come as capitals; the key, with a word composed, leaves caps lock and keeps the text.
        ime.onAction(Keyboards.SHIFT)
        ime.onAction(Keyboards.SHIFT)
        assertTrue(has("A ⇪"))
        type("OK")
        assertTrue(has("A ⇪"))
        ime.onAction(Keyboards.SHIFT)
        assertEquals("Akasaka.1 Ab OK", e.text.toString())
        assertTrue(has("A→a")) // caps lock off; the next tap flips K
        ime.onAction(Keyboards.SHIFT)
        assertEquals("Akasaka.1 Ab Ok", e.text.toString())
        assertEquals(0, e.reads)
    }

    @Test fun theCaseKeyOnQwertyFlipsOnlyTheLastLetterAndCandidatesStillOfferTitleCase() {
        LayoutSettings.setEnglish12Key(RuntimeEnvironment.getApplication(), false)
        val e = english()
        type("openai")
        pumpUntil("OpenAI chip") { "OpenAI" in chipTexts() }
        assertTrue(rows().flatten().any { it.label == "a→A" && it.action == Keyboards.SHIFT })
        ime.onAction(Keyboards.SHIFT)
        assertEquals("openaI", e.text.toString())
        assertEquals("openaI", chipTexts().first())
        assertTrue("Openai" in chipTexts()) // Title case remains a candidate choice, applied only when picked
        chips().single { it.text == "Openai" }.performClick()
        assertEquals("Openai", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    /** 0.1.32: ▼ opens every English candidate in the panel; a pick commits it and the keys come back. */
    @Test fun englishCandidatesExpandIntoThePanelAndAPickCommitsIt() {
        val e = english()
        fun expand() = (root!!.getChildAt(0) as ViewGroup).getChildAt(1) as TextView
        fun panel() = root!!.findViewWithTag<View>(TAG_PANEL)
        fun section(tag: String) = root!!.findViewWithTag<CandidateSectionView>(tag)
        type("jav")
        pumpUntil("completions") { chipTexts().size > 3 }
        val strip = chipTexts()
        assertEquals("▼", expand().text.toString())
        expand().performClick()
        assertEquals(View.VISIBLE, panel().visibility)
        assertEquals(View.GONE, keys().visibility)
        assertEquals("▲", expand().text.toString())
        val listed = section(TAG_PANEL_TOP).entries + section(TAG_PANEL_EXACT).entries
        assertEquals(strip.toSet(), listed.toSet()) // every candidate, each once
        assertEquals(strip.size, listed.size)
        assertEquals(listOf("jav"), chipTexts()) // the strip keeps what Enter would commit
        val pick = section(TAG_PANEL_TOP).entries.first { it.lowercase().startsWith("java") }
        section(TAG_PANEL_TOP).onPick!!(section(TAG_PANEL_TOP).entries.indexOf(pick))
        assertEquals(pick, e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals(View.GONE, panel().visibility)
        assertEquals(View.VISIBLE, keys().visibility)
        // A kana composition never shows the English lists, and a typed letter closes an open English panel.
        type("op")
        pumpUntil("chips") { chipTexts().size > 1 }
        expand().performClick()
        assertEquals(View.VISIBLE, panel().visibility)
        expand().performClick()
        type("e")
        assertEquals(View.GONE, panel().visibility)
        ime.onAction(Keyboards.MODE_CYCLE)
        ime.onFlick(FlickKey.A, FlickDirection.CENTER)
        assertTrue(chips().isEmpty())
        assertEquals(0, e.reads)
    }

    @Test fun backspaceEditsTheWordThenCommittedText() {
        val e = english()
        type("opena")
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals("open", e.text.toString())
        assertEquals(0, e.composingStart)
        repeat(4) { ime.onAction(Keyboards.BACKSPACE) }
        assertEquals("", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertTrue(chips().isEmpty())
        type("ab")
        ime.onText(".")
        ime.onAction(Keyboards.BACKSPACE) // no word: deletes the committed '.'
        assertEquals("ab", e.text.toString())
        assertEquals(0, e.reads)
    }

    @Test fun punctuationDelimitsUrlsAndEmailsWithoutChangingCase() {
        val url = english(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO)
        "https://openai.com/github".forEach { ime.onText(it.toString()) }
        // Candidates for the whole address, never applied by themselves; no width or number forms in an address field.
        pumpUntil("GitHub chip") { "https://openai.com/GitHub" in chipTexts() }
        assertTrue(chipTexts().none { it.any { c -> c.code in 0xFF01..0xFF5E } })
        assertEquals(0, url.composingStart) // one composition up to here: punctuation no longer commits
        ime.onAction(Keyboards.ENTER) // 確定
        assertEquals("https://openai.com/github", url.text.toString())
        assertTrue(url.actions.isEmpty())
        ime.onAction(Keyboards.ENTER)
        assertEquals(listOf(EditorInfo.IME_ACTION_GO), url.actions)
        val mail = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) // opens on the English screen
        "Foo.Bar@OpenAI.com".forEach { ime.onText(it.toString()) }
        ime.onAction(Keyboards.SPACE)
        assertEquals("Foo.Bar@OpenAI.com ", mail.text.toString())
        assertEquals(-1, mail.composingStart)
        // No storage: only the two layout booleans exist in the layout preferences.
        assertEquals(setOf("english_12key", "numeric_12key"), ime.getSharedPreferences("astra_layout", 0).all.keys)
        assertEquals(0, url.reads + mail.reads)
    }

    @Test fun passwordsAndIncognitoFields() {
        val pw = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)
        type("openai")
        assertEquals("openai", pw.text.toString())
        assertEquals(-1, pw.composingStart) // direct, no composition
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(chips().isEmpty())
        assertEquals("", ime.englishResultKey)
        // Incognito (no personalized learning): candidates still work, nothing is learned or stored.
        val before = Learning.store(ime).historySize
        val incognito = english(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        type("openai")
        pumpUntil("OpenAI chip") { "OpenAI" in chipTexts() }
        chips().single { it.text == "OpenAI" }.performClick()
        assertEquals("OpenAI", incognito.text.toString())
        assertEquals(before, Learning.store(ime).historySize)
    }

    @Test fun paletteEntriesAndNumbersJoinTheEnglishCompositionWithoutCommittingIt() {
        val e = english()
        type("open")
        ime.onAction(Keyboards.TO_EMOJI)
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.TO_TEXT)
        type("ai")
        assertEquals("openai", e.text.toString())
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.TO_JSYMBOLS)
        ime.onText("→")
        assertEquals("openai→", e.text.toString())
        assertEquals(0, e.composingStart) // still ours, still uncommitted
        ime.onAction(Keyboards.TO_EMOJI)
        ime.onText("👍🏽") // one entry, with its skin tone
        ime.onAction(Keyboards.TO_TEXT)
        assertEquals("あ", rows().last().first().label) // back on English
        assertTrue(rows().flatten().any { it.label == "abc" })
        ime.onAction(Keyboards.TO_SYMBOLS) // numbers: not a commit either
        ime.onText("2")
        ime.onAction(Keyboards.MODE_CYCLE)
        type("go")
        assertEquals("openai→👍🏽2go", e.text.toString())
        assertEquals(0, e.composingStart)
        // ⌫ takes the word's letters, then whole entries, never half an emoji.
        repeat(4) { ime.onAction(Keyboards.BACKSPACE) } // o, g, 2, then the whole 👍🏽
        assertEquals("openai→", e.text.toString())
        assertEquals(0, e.composingStart)
        pumpUntil("lookup") { true }
        ime.onAction(Keyboards.SPACE)
        assertEquals("openai→ ", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals(0, e.reads)
    }

    @Test fun passwordFieldsTypeSymbolsDirectlyAsBefore() {
        val pw = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)
        type("ab")
        ime.onAction(Keyboards.TO_JSYMBOLS)
        ime.onText("→")
        ime.onAction(Keyboards.TO_TEXT)
        type("c")
        assertEquals("ab→c", pw.text.toString())
        assertEquals(-1, pw.composingStart)
        assertEquals("⏎", rows().flatten().single { it.action == Keyboards.ENTER }.label)
    }

    // --- 0.1.33: a first symbol composes too ---------------------------------------------------------

    private fun enterLabel() = rows().flatten().single { it.action == Keyboards.ENTER }.label
    private fun converter() = pumpUntil("converter") { ime.converterReady }
    private fun paletteTap(entry: String) =
        (root!!.findViewWithTag<LinearLayout>(TAG_PALETTE).getChildAt(1) as PaletteView).onPick!!(entry)

    /** The user's case: ( and () typed first on the English keys, 12-key and QWERTY, compose with bracket alternatives. */
    @Test fun aFirstBracketComposesWithAlternativesOnBothEnglishLayouts() {
        for (twelve in listOf(true, false)) {
            LayoutSettings.setEnglish12Key(RuntimeEnvironment.getApplication(), twelve)
            val e = english()
            converter()
            ime.onText("(")
            assertEquals("(", e.text.toString())
            assertEquals(0, e.composingStart) // ours, not committed
            assertEquals("確定", enterLabel())
            val open = chipTexts()
            assertEquals("(", open.first()) // what Enter commits stays first
            assertTrue(open.containsAll(listOf("（", "「", "【", "［", "｛")))
            assertFalse(open.any { it.length > 1 && it != "(" }) // only opening brackets for an opening bracket
            assertEquals(open.size, open.toSet().size)
            ime.onText(")")
            assertEquals("()", e.text.toString())
            val pair = chipTexts()
            assertEquals("()", pair.first())
            assertTrue(pair.containsAll(listOf("（）", "「」", "【】", "『』", "［］", "〔〕")))
            assertEquals(pair.size, pair.toSet().size)
            assertFalse("（" in pair || "）" in pair) // a pair is offered as pairs only
            if (twelve) {
                ime.onAction(Keyboards.ENTER) // confirms exactly what is shown
                assertEquals("()", e.text.toString())
            } else {
                chips().single { it.text == "「」" }.performClick()
                assertEquals("「」", e.text.toString())
            }
            assertEquals(-1, e.composingStart)
            assertEquals("⏎", enterLabel())
        }
    }

    @Test fun aPairPickedInThePanelCommitsExactlyThatPair() {
        val e = english()
        converter()
        fun expand() = (root!!.getChildAt(0) as ViewGroup).getChildAt(1) as TextView
        fun section(tag: String) = root!!.findViewWithTag<CandidateSectionView>(tag)
        type("()")
        val strip = chipTexts()
        assertEquals("▼", expand().text.toString())
        expand().performClick()
        val listed = section(TAG_PANEL_TOP).entries + section(TAG_PANEL_EXACT).entries
        assertEquals(strip, listed) // all under 変換, each once, same order
        section(TAG_PANEL_EXACT).onPick!!(section(TAG_PANEL_EXACT).entries.indexOf("【】"))
        assertEquals("【】", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals(View.VISIBLE, keys().visibility)
    }

    /** 0.1.34 (was: candidates replace only the trailing part): every candidate is the whole composition. */
    @Test fun lettersAndDigitsAfterAFirstSymbolKeepOneCompositionAndCandidatesCoverAllOfIt() {
        val e = english()
        converter()
        ime.onText("(")
        type("open")
        lookedUp("open")
        assertEquals("(open", e.text.toString())
        assertEquals("(open", chipTexts().first())
        assertTrue(chipTexts().toString(), chipTexts().containsAll(listOf("(Open", "(OPEN", "（open")))
        assertTrue(chipTexts().all { it.length >= "(open".length }) // never the word alone
        ime.onAction(Keyboards.TO_SYMBOLS)
        ime.onText("1")
        ime.onText(")")
        assertEquals("(open1)", e.text.toString())
        assertEquals(0, e.composingStart)
        assertEquals("(open1)", chipTexts().first())
        assertFalse(chipTexts().any { it == ")" || it == "）" })
        chips().single { it.text == "【open1】" }.performClick() // both brackets of the pair in one style
        assertEquals("【open1】", e.text.toString())
        assertEquals(-1, e.composingStart)
        // Case key over a symbol-only composition: nothing changes, nothing commits.
        ime.onAction(Keyboards.MODE_CYCLE)
        ime.onText("!")
        ime.onAction(Keyboards.SHIFT)
        assertEquals("【open1】!", e.text.toString())
        assertEquals(7, e.composingStart)
    }

    @Test fun theEnglishOriginSurvivesNumbersAndPalettesAndEndsWithTheFieldOrKana() {
        val e = english()
        ime.onAction(Keyboards.TO_SYMBOLS) // numbers opened from English
        ime.onText("1")
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.MODE_CYCLE) // back to English: still composed
        ime.onAction(Keyboards.TO_EMOJI)
        ime.onText("👍🏽")
        ime.onText("👨‍👩‍👧")
        ime.onAction(Keyboards.TO_TEXT)
        assertEquals("1👍🏽👨‍👩‍👧", e.text.toString())
        assertEquals(0, e.composingStart)
        // 0.1.34: the digit's forms with the emoji kept (was: one chip with what is shown).
        assertEquals("1👍🏽👨‍👩‍👧", chipTexts().first())
        assertTrue(chipTexts().containsAll(listOf("１👍🏽👨‍👩‍👧", "①👍🏽👨‍👩‍👧")))
        ime.onAction(Keyboards.BACKSPACE) // the whole family emoji
        assertEquals("1👍🏽", e.text.toString())
        ime.onAction(Keyboards.ENTER)
        assertEquals("1👍🏽", e.text.toString())
        assertEquals(-1, e.composingStart)
        // From かな (and palettes opened from it) symbols still type at once; its number screen composes (0.1.33 numbers).
        ime.onAction(Keyboards.MODE_CYCLE)
        ime.onText("(")
        ime.onAction(Keyboards.TO_JSYMBOLS)
        ime.onText("→")
        ime.onAction(Keyboards.TO_TEXT)
        assertEquals("1👍🏽(→", e.text.toString())
        assertEquals(-1, e.composingStart)
        ime.onAction(Keyboards.TO_SYMBOLS)
        ime.onText("2")
        assertEquals(7, e.composingStart) // after 1, 👍🏽 (4 chars), ( and →
        ime.onAction(Keyboards.ENTER)
        assertEquals("1👍🏽(→2", e.text.toString())
        assertEquals(-1, e.composingStart)
        // A new field starts on its own screen: a text field in かな types symbols directly again.
        val next = start(T.TYPE_CLASS_TEXT)
        ime.onAction(Keyboards.MODE_CYCLE)
        ime.onAction(Keyboards.TO_JSYMBOLS)
        ime.onText("→")
        assertEquals(0, next.composingStart) // opened from English in this field
        val fresh = start(T.TYPE_CLASS_TEXT)
        ime.onAction(Keyboards.TO_JSYMBOLS)
        ime.onText("→")
        assertEquals("→", fresh.text.toString())
        assertEquals(-1, fresh.composingStart)
        // An e-mail field starts on English: its first symbol composes as its letters do.
        val mail = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        ime.onText("@")
        assertEquals(0, mail.composingStart)
        assertEquals(0, e.reads + next.reads + fresh.reads + mail.reads)
    }

    // --- 0.1.33 numbers: the number screens compose from either side ----------------------------------

    private fun has(label: String) = rows().flatten().any { it.label == label }
    private fun modeKey() = rows().flatten().single { it.action == Keyboards.MODE_CYCLE }

    /**
     * The user's addendum: on the number 12-key and on the number rows, opened from かな or from English, 1, 12 and ()
     * typed first stay composed with the supplement's forms; ⌫, screen switches and mixed text keep one composition;
     * only Enter or a candidate commits. The hold that opens numbers and the key that returns are unchanged.
     */
    @Test fun numberScreensComposeFromEitherSideOnBothLayouts() {
        for (twelve in listOf(true, false)) for (fromEnglish in listOf(false, true)) {
            val what = "twelve=$twelve english=$fromEnglish"
            LayoutSettings.setNumeric12Key(RuntimeEnvironment.getApplication(), twelve)
            val e = start(T.TYPE_CLASS_TEXT)
            if (fromEnglish) ime.onAction(Keyboards.MODE_CYCLE)
            converter()
            assertEquals(what, Keyboards.TO_SYMBOLS, modeKey().longAction) // the hold opens numbers, as before
            ime.onAction(Keyboards.TO_SYMBOLS)
            assertTrue(what, has("5"))
            assertEquals(what, !twelve, has("(")) // the rows have a ( key; the 12-key has it on 9's up flick
            assertEquals(what, 0, modeKey().longAction)
            ime.onText("1")
            assertEquals(what, "1", e.text.toString())
            assertEquals(what, 0, e.composingStart)
            assertEquals(what, "確定", enterLabel())
            val one = chipTexts()
            assertEquals(what, "1", one.first()) // what Enter commits stays first
            assertTrue(what, one.containsAll(listOf("１", "①", "一", "Ⅰ")))
            ime.onText("2")
            val twelveForms = chipTexts()
            assertEquals(what, "12", twelveForms.first())
            assertTrue(what, twelveForms.containsAll(listOf("１２", "⑫", "十二")))
            assertEquals(what, twelveForms.size, twelveForms.toSet().size)
            ime.onAction(Keyboards.BACKSPACE)
            assertEquals(what, "1", e.text.toString())
            assertEquals(what, "1", chipTexts().first())
            ime.onText("(")
            ime.onText(")")
            assertEquals(what, "1()", e.text.toString())
            val pair = chipTexts()
            assertEquals(what, "1()", pair.first()) // 0.1.34: the whole composition (was the trailing ())
            assertTrue(what, pair.containsAll(listOf("1（）", "1「」", "1【】", "①()", "１（）")))
            // Back where numbers were opened from: the same composition, which mixed text continues.
            ime.onAction(Keyboards.MODE_CYCLE)
            assertEquals(what, fromEnglish, has("abc") || has("q"))
            assertEquals(what, Keyboards.TO_SYMBOLS, modeKey().longAction)
            assertEquals(what, 0, e.composingStart)
            if (fromEnglish) {
                type("ab")
                assertEquals(what, "1()ab", e.text.toString())
                assertEquals(what, "1()ab", chipTexts().first())
            } else {
                ime.onFlick(FlickKey.A, FlickDirection.CENTER) // かな takes the composition over (it may convert it live)
                assertTrue(what, e.text.toString().length >= 4)
            }
            assertEquals(what, 0, e.composingStart)
            assertEquals(what, "確定", enterLabel())
            ime.onAction(Keyboards.ENTER) // confirms only
            assertEquals(what, -1, e.composingStart)
            assertTrue(what, e.actions.isEmpty())
            val confirmed = e.text.toString()
            // A number form picked from the strip commits exactly that form.
            ime.onAction(Keyboards.TO_SYMBOLS)
            ime.onText("1")
            ime.onText("2")
            chips().single { it.text == "⑫" }.performClick()
            assertEquals(what, "$confirmed⑫", e.text.toString())
            assertEquals(what, -1, e.composingStart)
            // ⌫ of the only digit leaves no span and the committed text alone.
            ime.onText("3")
            ime.onAction(Keyboards.BACKSPACE)
            assertEquals(what, "$confirmed⑫", e.text.toString())
            assertEquals(what, -1, e.composingStart)
            assertEquals(what, "⏎", enterLabel())
            assertEquals(what, 0, e.reads)
        }
    }

    @Test fun aNumberPickedInThePanelAndPalettesFromNumbersKeepTheComposition() {
        LayoutSettings.setNumeric12Key(RuntimeEnvironment.getApplication(), true)
        val e = start(T.TYPE_CLASS_TEXT) // かな
        converter()
        fun expand() = (root!!.getChildAt(0) as ViewGroup).getChildAt(1) as TextView
        fun section(tag: String) = root!!.findViewWithTag<CandidateSectionView>(tag)
        ime.onAction(Keyboards.TO_SYMBOLS)
        ime.onText("1")
        ime.onText("2")
        ime.onAction(Keyboards.TO_JSYMBOLS) // a palette opened from numbers joins the open composition
        ime.onText("→")
        ime.onAction(Keyboards.TO_TEXT)
        assertTrue(has("5")) // back to numbers
        assertEquals("12→", e.text.toString())
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.BACKSPACE)
        val strip = chipTexts()
        assertEquals("12", strip.first())
        expand().performClick()
        val listed = section(TAG_PANEL_TOP).entries + section(TAG_PANEL_EXACT).entries
        assertEquals(strip, listed) // all under 変換, each once, same order
        section(TAG_PANEL_EXACT).onPick!!(section(TAG_PANEL_EXACT).entries.indexOf("十二"))
        assertEquals("十二", e.text.toString())
        assertEquals(-1, e.composingStart)
        // With nothing composed a palette opened from numbers on かな still types at once (palettes follow the side).
        ime.onAction(Keyboards.TO_JSYMBOLS)
        ime.onText("→")
        assertEquals("十二→", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    /** Number, phone and date fields, passwords and raw editors keep typing exactly the key's text at once. */
    @Test fun constrainedFieldsTypeNumbersAtOnceWithoutCandidates() {
        for (twelve in listOf(true, false)) for (type in listOf(T.TYPE_CLASS_NUMBER, T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_FLAG_DECIMAL,
            T.TYPE_CLASS_PHONE, T.TYPE_CLASS_DATETIME, T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_VARIATION_PASSWORD,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            val what = "twelve=$twelve type=$type"
            LayoutSettings.setNumeric12Key(RuntimeEnvironment.getApplication(), twelve)
            val e = start(type)
            if (!has("5")) ime.onAction(Keyboards.TO_SYMBOLS)
            ime.onText("1")
            ime.onText("2")
            ime.onText("-")
            assertEquals(what, "12-", e.text.toString())
            assertEquals(what, -1, e.composingStart)
            assertTrue(what, chips().isEmpty())
            assertEquals(what, "⏎", enterLabel())
        }
        // A raw editor (TYPE_NULL) is never composed either.
        start(T.TYPE_NULL)
        ime.onAction(Keyboards.TO_SYMBOLS)
        ime.onText("1")
        assertTrue(chips().isEmpty())
        assertEquals("⏎", enterLabel())
        // URL and e-mail fields: a first digit composes (as their letters do) but gets no number forms.
        for (type in listOf(T.TYPE_TEXT_VARIATION_URI, T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) {
            val e = start(T.TYPE_CLASS_TEXT or type)
            converter()
            ime.onAction(Keyboards.TO_SYMBOLS)
            ime.onText("7")
            assertEquals(0, e.composingStart)
            assertEquals(listOf("7"), chipTexts())
            ime.onAction(Keyboards.ENTER)
            assertEquals("7", e.text.toString())
            assertEquals(-1, e.composingStart)
        }
    }

    @Test fun deletingTheOnlySymbolLeavesNoSpanAndTheHostTextAndCursorAlone() {
        val e = start(T.TYPE_CLASS_TEXT, initial = "abc")
        ime.onAction(Keyboards.MODE_CYCLE)
        ime.onText("(")
        assertEquals("abc(", e.text.toString())
        assertEquals(3, e.composingStart)
        ime.onText("「")
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals("abc(", e.text.toString())
        assertEquals(3, e.composingStart)
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals("abc", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertTrue(chips().isEmpty())
        assertEquals("⏎", enterLabel())
        ime.onText(".")
        ime.onAction(Keyboards.SPACE) // Space commits it, then a space
        assertEquals("abc. ", e.text.toString())
        assertEquals(0, e.reads)
    }

    @Test fun passwordsRawAndNumberFieldsTypeAFirstSymbolAtOnce() {
        for (type in listOf(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_NULL)) {
            val f = start(type)
            ime.onText("(")
            ime.onAction(Keyboards.TO_JSYMBOLS)
            ime.onText("「")
            assertEquals("(「", f.text.toString())
            assertEquals(-1, f.composingStart)
        }
        val number = start(T.TYPE_CLASS_NUMBER) // opens on numbers
        ime.onText("1")
        ime.onText("-")
        assertEquals("1-", number.text.toString())
        assertEquals(-1, number.composingStart)
    }

    @Test fun aSymbolFirstPaletteEntryCountsAsUsedOnlyWhenItsCompositionIsCommitted() {
        val context = RuntimeEnvironment.getApplication()
        Learning.setEnabled(context, true)
        try {
            val e = english()
            Learning.store(ime).clearHistory()
            ime.onAction(Keyboards.TO_JSYMBOLS)
            paletteTap("→")
            assertEquals(0, e.composingStart)
            assertFalse("→" in Learning.store(ime).recent(false)) // not before the commit
            ime.onAction(Keyboards.ENTER)
            assertEquals("→", e.text.toString())
            assertTrue("→" in Learning.store(ime).recent(false))
            // Deleted before 確定: not used.
            Learning.store(ime).clearHistory()
            paletteTap("→")
            ime.onAction(Keyboards.BACKSPACE)
            assertEquals("→", e.text.toString())
            assertTrue(Learning.store(ime).recent(false).isEmpty())
            // Private fields compose the same way but record nothing.
            val incognito = start(T.TYPE_CLASS_TEXT, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
            ime.onAction(Keyboards.MODE_CYCLE)
            ime.onAction(Keyboards.TO_JSYMBOLS)
            paletteTap("→")
            assertEquals(0, incognito.composingStart)
            ime.onAction(Keyboards.ENTER)
            assertEquals("→", incognito.text.toString())
            assertTrue(Learning.store(ime).recent(false).isEmpty())
        } finally {
            Learning.setEnabled(context, false)
        }
    }

    @Test fun mixedKanaEnglishNumbersKeepTheTypedText() {
        val e = start(T.TYPE_CLASS_TEXT)
        ime.onFlick(FlickKey.KA, FlickDirection.CENTER) // か composing
        ime.onAction(Keyboards.MODE_CYCLE)
        ime.onText("x") // joins the kana composition as before
        assertEquals("かx", e.text.toString())
        ime.onAction(Keyboards.ENTER)
        assertEquals("かx", e.text.toString())
        type("open")
        ime.onAction(Keyboards.TO_SYMBOLS) // hold: numbers; 0.1.32 keeps the word composed
        assertEquals("ABC", rows().last().first().label)
        assertEquals("かxopen", e.text.toString())
        assertEquals(2, e.composingStart)
        ime.onText("1") // the digit joins the composition
        assertEquals("かxopen1", e.text.toString())
        assertEquals(2, e.composingStart)
        ime.onAction(Keyboards.MODE_CYCLE) // back to English
        type("ai")
        ime.onAction(Keyboards.MODE_CYCLE) // かな with the composition still open
        ime.onFlick(FlickKey.A, FlickDirection.CENTER) // 0.1.25: it starts the kana composition, nothing committed
        assertEquals("かxopen1aiあ", e.text.toString())
        assertEquals(2, e.composingStart)
        pumpUntil("no English chips") { chips().isEmpty() }
    }

    @Test fun aKanaAfterAnEnglishWordComposesWithItInsteadOfCommittingIt() {
        val e = english()
        type("Android")
        ime.onAction(Keyboards.MODE_CYCLE) // かな
        ime.onFlick(FlickKey.TA, FlickDirection.DOWN) // と
        assertEquals("Androidと", e.text.toString())
        assertEquals(0, e.composingStart) // still one composition: nothing was committed
        pumpUntil("the English chips gone") { chips().isEmpty() } // the kana composition's strip now
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.BACKSPACE) // the kana goes; the word stays composed
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals("Andro", e.text.toString().take(5))
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.ENTER)
        assertEquals(-1, e.composingStart)
        assertEquals("Andro", e.text.toString().take(5))
    }

    @Test fun aKanaAfterDigitsFromTheEnglishKeysComposesWithThem() {
        val e = english()
        ime.onText("3") // English down flick: a composed digit
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.MODE_CYCLE) // かな
        ime.onFlick(FlickKey.KA, FlickDirection.LEFT) // き
        assertEquals("3き", e.text.toString())
        assertEquals(0, e.composingStart)
    }

    @Test fun staleLookupsNewEditorsAndCursorMovesNeverWrite() {
        val first = english()
        type("openai")
        // A new editor before the result arrives: nothing is written there, the old result is dropped.
        val second = english()
        repeat(20) { Thread.sleep(5); shadowOf(Looper.getMainLooper()).idle() }
        assertTrue(chips().isEmpty())
        assertEquals("", second.text.toString())
        assertEquals("openai", first.text.toString())
        // Results for an older spelling of the word are dropped; the current one wins.
        type("git")
        type("hub")
        pumpUntil("GitHub chip") { "GitHub" in chipTexts() }
        assertEquals("github", chipTexts().first())
        // The user taps elsewhere: the composition ends with the letters left exactly as shown.
        ime.onUpdateSelection(6, 6, 2, 2, 0, 6)
        assertEquals("github", second.text.toString())
        assertEquals(-1, second.composingStart)
        assertTrue(chips().isEmpty())
        ime.onAction(Keyboards.SHIFT) // no word any more: ordinary shift
        assertEquals("github", second.text.toString())
        // A late report of our own earlier commit does not end the next word.
        val third = english()
        type("a")
        ime.onAction(Keyboards.SPACE)
        type("jp")
        ime.onUpdateSelection(0, 0, 1, 1, -1, -1) // echo of committing "a"
        ime.onUpdateSelection(1, 1, 2, 2, -1, -1) // echo of committing " "
        assertEquals(2, third.composingStart)
        ime.onUpdateSelection(2, 2, 4, 4, 2, 4) // our own composing write
        assertEquals(2, third.composingStart)
        ime.onText(".")
        ime.onAction(Keyboards.SPACE)
        assertEquals("a jp. ", third.text.toString())
        // Hiding the keyboard while the editor stays: letters stay, composition ends.
        type("abc")
        ime.onFinishInputView(false)
        assertEquals("a jp. abc", third.text.toString())
        assertEquals(-1, third.composingStart)
        assertEquals(0, first.reads + second.reads + third.reads)
    }

    @Test fun qwertyTapsAndTwelveKeyFlicksComposeTheSameWay() {
        val e = english()
        val kb = keys()
        kb.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        kb.layout(0, 0, kb.measuredWidth, kb.measuredHeight)
        fun gesture(label: String, dx: Float = 0f, dy: Float = 0f) {
            val r = keys().keyRows.flatten().first { it.label == label }.rect
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            fun ev(t: Long, a: Int, x: Float, y: Float) = MotionEvent.obtain(0L, t, a, 1, props,
                arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0)
            listOf(ev(0, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY()), ev(10, MotionEvent.ACTION_MOVE, r.centerX() + dx, r.centerY() + dy),
                ev(20, MotionEvent.ACTION_UP, r.centerX() + dx, r.centerY() + dy)).forEach { keys().onTouchEvent(it); it.recycle() }
        }
        // 12-key (center, left, up, right, down): mno up = o, pqrs tap = p, def left = e, mno left = n.
        gesture("mno", dy = -40f)
        gesture("pqrs")
        gesture("def", dx = -40f)
        gesture("mno", dx = -40f)
        assertEquals("open", e.text.toString())
        assertEquals(0, e.composingStart)
        gesture("a→A") // the shift key names what it does to the last letter while composing
        assertEquals("opeN", e.text.toString())
        gesture("A→a")
        assertEquals("open", e.text.toString())
        gesture("空白")
        assertEquals("open ", e.text.toString())
        // QWERTY.
        LayoutSettings.setEnglish12Key(ime, false)
        val q = english()
        val qkb = keys()
        qkb.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        qkb.layout(0, 0, qkb.measuredWidth, qkb.measuredHeight)
        "openai".forEach { gesture(it.toString()) }
        pumpUntil("OpenAI chip") { "OpenAI" in chipTexts() }
        assertEquals("openai", q.text.toString())
        gesture("a→A")
        assertEquals("openaI", q.text.toString())
        gesture(".")
        assertEquals("openaI.", q.text.toString())
        assertFalse(rows().flatten().any { it.label == "切替" })
        // The period key and its flicks join the composition, like the period itself; letters after them start a word.
        "git".forEach { gesture(it.toString()) }
        gesture(".", dx = -40f)
        gesture("h")
        gesture(".", dx = 40f)
        assertEquals("openaI.git-h_", q.text.toString())
        assertEquals(0, q.composingStart)
        gesture("空白")
        assertEquals("openaI.git-h_ ", q.text.toString())
        assertEquals(-1, q.composingStart)
    }

    /** 0.1.18: real touches. Down flicks on the English 12-key type digits; a still hold on the mode key opens numbers. */
    @Test fun englishDownFlicksTypeDigitsAndAStillHoldOnTheModeKeyOpensNumbers() {
        LayoutSettings.setEnglish12Key(RuntimeEnvironment.getApplication(), true)
        LayoutSettings.setNumeric12Key(RuntimeEnvironment.getApplication(), true)
        val e = english()
        root!!.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        root!!.layout(0, 0, root!!.measuredWidth, root!!.measuredHeight)
        // Attached, as in the app: a detached view's postDelayed (the hold timer) would never run.
        Robolectric.buildActivity(android.app.Activity::class.java).setup().get().setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()
        val hold =ViewConfiguration.getLongPressTimeout().toLong() + 50
        fun gesture(label: String, dy: Float = 0f, holdMs: Long = 0, moveFirst: Boolean = false) {
            val r = keys().keyRows.flatten().first { it.label == label }.rect
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            fun ev(t: Long, a: Int, y: Float) = MotionEvent.obtain(0L, t, a, 1, props,
                arrayOf(MotionEvent.PointerCoords().apply { x = r.centerX(); this.y = y; pressure = 1f; size = 1f }), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0)
            fun send(event: MotionEvent) { keys().onTouchEvent(event); event.recycle() }
            send(ev(0, MotionEvent.ACTION_DOWN, r.centerY()))
            if (moveFirst) send(ev(10, MotionEvent.ACTION_MOVE, r.centerY() + dy))
            if (holdMs > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(holdMs))
            send(ev(holdMs + 20, MotionEvent.ACTION_MOVE, r.centerY() + dy))
            send(ev(holdMs + 30, MotionEvent.ACTION_UP, r.centerY() + dy))
        }
        for (label in listOf("@", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz", "'")) gesture(label, dy = 40f)
        assertEquals("1234567890", e.text.toString())
        gesture("abc") // the letters themselves are unchanged
        gesture("'", dy = -40f)
        assertEquals("1234567890a:", e.text.toString()) // up on ' is : (0.1.33 Gboard mapping)
        ime.onAction(Keyboards.SPACE)
        fun has(label: String) = keys().keyRows.flatten().any { it.label == label }
        assertFalse("0.1.19 keeps the two-way mode key: no 123 tap target", has("123"))
        // A quick tap on the English mode key still goes to かな.
        gesture("あ")
        assertFalse(has("abc"))
        assertFalse(has("5"))
        gesture("ABC")
        assertTrue(has("abc"))
        // A still hold opens numbers at once, and its release does not also switch screens.
        gesture("あ", holdMs = hold)
        assertTrue(has("5"))
        assertEquals("ABC", keys().keyRows.flatten().single { it.action == Keyboards.MODE_CYCLE }.label)
        gesture("5")
        assertEquals("1234567890a: 5", e.text.toString())
        gesture("ABC") // back to English, where numbers were opened from
        assertTrue(has("abc"))
        // A finger that slides off center before the timeout is a flick, not a hold: it never opens numbers.
        gesture("あ", dy = -40f, holdMs = hold, moveFirst = true)
        assertFalse(has("5"))
        assertFalse(has("abc"))
        assertEquals(0, e.reads)
    }
}
