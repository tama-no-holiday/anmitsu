package dev.astra.ime

import android.os.Looper
import android.text.InputType as T
import android.text.Selection
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import dev.astra.ime.core.FlickDirection
import dev.astra.ime.core.FlickKey
import dev.astra.ime.core.FlickLayout
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
 * 0.1.34: candidates for the whole mixed English composition (m-1, abc123, (12) …) and a composing caret that the
 * arrow keys move without committing, in English and kana compositions. The real service, the real English index and
 * the fixture lexicon; only the editor is fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class MixedCaretTest {
    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        DebugSettings.setOssEnabled(context, false)
        DebugSettings.setZenzEnabled(context, false)
        DebugSettings.setLiveConversion(context, true)
        LayoutSettings.setEnglish12Key(context, true)
        LayoutSettings.setNumeric12Key(context, true)
        Learning.setEnabled(context, false)
    }

    private var controller: ServiceController<TestIme>? = null
    private val ime: TestIme get() = controller!!.get()
    private var root: ViewGroup? = null

    @After fun tearDown() { controller?.destroy() }

    private fun start(type: Int = T.TYPE_CLASS_TEXT, initial: String = "", imeOptions: Int = 0): FakeEditor {
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

    private fun english(type: Int = T.TYPE_CLASS_TEXT, initial: String = ""): FakeEditor =
        start(type, initial).also { ime.onAction(Keyboards.MODE_CYCLE) } // かな → English

    private fun pumpUntil(what: String = "condition", condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun idleABit() = repeat(20) { Thread.sleep(5); shadowOf(Looper.getMainLooper()).idle() }

    private fun views(): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root!!)
        return out
    }

    private fun chips(): List<TextView> = views().filter { it.tag == TAG_ENGLISH_CANDIDATE }
    private fun chipTexts() = chips().map { it.text.toString() }
    private fun type(text: String) = text.forEach { ime.onText(it.toString()) }
    private fun converter() = pumpUntil("converter") { ime.converterReady }
    private fun caret(e: FakeEditor) = Selection.getSelectionStart(e.text)

    private fun typeKana(text: String) {
        for (c in text) {
            val (key, dir) = FlickKey.entries.flatMap { k -> FlickDirection.entries.map { k to it } }
                .first { FlickLayout.kana(it.first, it.second) == c.toString() }
            ime.onFlick(key, dir)
        }
    }

    // --- Reproductions (written first, against 0.1.33) ----------------------------------------------

    /** The user's screenshot: m-1 composed, the strip showed only 1 and its number forms. */
    @Test fun mMinusOneHasCandidatesForTheWholeComposition() {
        val e = english()
        converter()
        type("m-")
        ime.onAction(Keyboards.TO_SYMBOLS)
        ime.onText("1")
        assertEquals("m-1", e.text.toString())
        assertEquals(0, e.composingStart)
        pumpUntil("whole candidates") { "M-1" in chipTexts() }
        val strip = chipTexts()
        assertEquals("m-1", strip.first())
        assertTrue(strip.toString(), strip.containsAll(listOf("M-1", "ｍ－１", "Ｍ－１")))
        assertTrue(strip.toString(), strip.none { it == "1" || it == "①" }) // never a part alone
    }

    @Test fun anArrowInAnEnglishCompositionMovesItsCaretWithoutCommitting() {
        val e = english()
        type("abc")
        ime.onAction(Keyboards.CURSOR_LEFT)
        assertEquals("abc", e.text.toString())
        assertEquals(0, e.composingStart) // still ours
        assertEquals(3, e.composingEnd)
        assertTrue(e.keys.isEmpty()) // no DPAD to the editor
        assertEquals(2, caret(e))
    }

    @Test fun anArrowInAKanaCompositionMovesItsCaretWithoutCommitting() {
        val e = start()
        converter()
        typeKana("あしたはあめ")
        pumpUntil("live 明日は雨") { e.text.toString() == "明日は雨" }
        ime.onAction(Keyboards.CURSOR_LEFT)
        assertEquals(0, e.composingStart)
        assertTrue(e.keys.isEmpty())
        assertTrue(e.text.toString(), e.text.toString() == "明日はあめ" || e.text.toString() == "明日は雨")
        assertTrue(caret(e) < e.text.length)
    }

    // --- A. Whole-composition candidates ----------------------------------------------------------

    private fun commitsCounted(e: FakeEditor): () -> Int {
        var n = 0
        e.duringCommit = { n++ }
        return { n }
    }

    @Test fun mixedCompositionsGetWholeFormsOnBothEnglishLayoutsAndAPickCommitsExactlyIt() {
        for (twelve in listOf(true, false)) {
            LayoutSettings.setEnglish12Key(RuntimeEnvironment.getApplication(), twelve)
            val e = english()
            converter()
            val commits = commitsCounted(e)
            type("m-")
            ime.onAction(Keyboards.TO_SYMBOLS) // the number screen joins the composition
            ime.onText("1")
            assertEquals(0, commits()) // nothing committed by a symbol or a digit
            pumpUntil("whole") { "M-1" in chipTexts() }
            val strip = chipTexts()
            assertEquals("twelve=$twelve", "m-1", strip.first())
            // Whole case and width forms, and the forms of one part with the rest as typed (mixed widths).
            for (c in listOf("M-1", "ｍ－１", "Ｍ－１", "m-①", "m-１", "m－1", "ｍ-1")) assertTrue("$c in $strip", c in strip)
            assertEquals(strip.size, strip.toSet().size) // each once
            assertTrue(strip.size <= WholeCandidates.MAX)
            chips().single { it.text == "Ｍ－１" }.performClick()
            assertEquals("Ｍ－１", e.text.toString())
            assertEquals(-1, e.composingStart)
            assertEquals(1, commits())
        }
    }

    @Test fun lettersWithDigitsUseTheDictionaryAndTheBracketsOfAPairChangeTogether() {
        val e = english()
        converter()
        type("openai")
        ime.onAction(Keyboards.TO_SYMBOLS)
        type("123")
        pumpUntil("OpenAI123") { "OpenAI123" in chipTexts() }
        assertEquals("openai123", chipTexts().first())
        assertTrue(chipTexts().containsAll(listOf("OPENAI123", "Openai123", "ｏｐｅｎａｉ１２３", "openai１２３")))
        ime.onAction(Keyboards.ENTER)
        assertEquals("openai123", e.text.toString())

        // abc() and (12): a pair is never split into two styles; an unmatched bracket still has its own forms.
        type("abc()")
        val abc = chipTexts()
        assertTrue(abc.toString(), abc.containsAll(listOf("abc()", "abc（）", "abc「」", "abc【】", "ABC()", "ａｂｃ（）")))
        assertFalse(abc.toString(), abc.any { it == "abc（)" || it == "abc(）" || it == "abc「)" })
        ime.onAction(Keyboards.ENTER)
        type("(12)")
        val twelve = chipTexts()
        assertEquals("(12)", twelve.first())
        assertTrue(twelve.toString(), twelve.containsAll(listOf("（12）", "「12」", "【12】", "(⑫)", "(十二)", "（１２）")))
        assertFalse(twelve.toString(), twelve.any { it.startsWith("「") && it.endsWith(")") || it.startsWith("(") && it.endsWith("」") })
        ime.onAction(Keyboards.ENTER)
        type("(12")
        assertTrue(chipTexts().toString(), "「12" in chipTexts() && "（12" in chipTexts())
        ime.onAction(Keyboards.ENTER)
        // () alone keeps 0.1.33's pair list.
        type("()")
        assertTrue(chipTexts().containsAll(listOf("()", "（）", "「」")))
        ime.onAction(Keyboards.ENTER)
        assertEquals("openai123abc()(12)(12()", e.text.toString())
        assertEquals(0, e.reads)
    }

    /** Every bracket of [s] that has a partner in [STYLES] is closed by its own partner, in order (no mixed styles). */
    private fun bracketsMatched(s: String): Boolean {
        val open = ArrayList<Char>()
        for (c in s) {
            val i = STYLES.indexOf(c)
            if (i < 0) continue
            if (i % 2 == 0) open += c else if (open.removeLastOrNull() != STYLES[i - 1]) return false
        }
        return open.isEmpty()
    }

    /** Review follow-up: forms of different parts combined (letters, digits, symbols), not only one part or the whole. */
    @Test fun combinedFormsOfLettersDigitsAndSymbolsAreOfferedAndAPickKeepsTheTextAround() {
        fun compose(text: String): List<String> {
            type(text)
            val strip = chipTexts()
            assertEquals(text, strip.first())
            assertEquals(strip.toString(), strip.size, strip.toSet().size)
            assertTrue(strip.size <= WholeCandidates.MAX)
            return strip
        }
        val e = english(initial = "x ")
        converter()
        val commits = commitsCounted(e)
        // Letters + digit.
        val m1 = compose("m1")
        assertTrue(m1.toString(), m1.containsAll(listOf("M①", "ｍ①", "Ｍ①", "M１", "M1", "ｍ１")))
        ime.onAction(Keyboards.ENTER)
        // Letters + symbol + digit: all three.
        val m = compose("m-1")
        assertTrue(m.toString(), m.containsAll(listOf("M-①", "ｍ-①", "Ｍ－①", "M－1", "m－①", "M-1", "Ｍ－１")))
        assertTrue(m.toString(), m.none { it == "1" || it == "-1" || it == "M" })
        ime.onAction(Keyboards.ENTER)
        // Digits + symbol, on the number screen (a digit first on the English screen is typed at once, as before).
        ime.onAction(Keyboards.TO_SYMBOLS)
        val d = compose("1-2")
        ime.onAction(Keyboards.TO_ASCII)
        assertTrue(d.toString(), d.containsAll(listOf("①－2", "①-②", "１－２", "1－②")))
        ime.onAction(Keyboards.ENTER)
        // Letters + brackets: a pair keeps one style, combined with the letters' forms.
        val abc = compose("abc()")
        assertTrue(abc.toString(), abc.containsAll(listOf("ABC「」", "Abc【】", "ａｂｃ「」", "ABC（）", "abc『』")))
        assertTrue(abc.toString(), abc.all(::bracketsMatched))
        ime.onAction(Keyboards.ENTER)
        // A pair across tokens with a digit inside and a letter before: all three combined, the pair together.
        val a1 = compose("a(1)")
        assertTrue(a1.toString(), a1.containsAll(listOf("A「①」", "a「①」", "A(①)", "Ａ（１）")))
        assertTrue(a1.toString(), a1.all(::bracketsMatched))
        // The English index's spelling combined with a digit form.
        ime.onAction(Keyboards.ENTER)
        type("openai-1")
        pumpUntil("OpenAI-①") { "OpenAI-①" in chipTexts() }
        assertEquals(5, commits()) // only the five Enters
        chips().single { it.text == "OpenAI-①" }.performClick()
        assertEquals("x m1m-11-2abc()a(1)OpenAI-①", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals(e.text.length, caret(e))
        assertEquals(6, commits())
        assertEquals(0, e.reads)
    }

    @Test fun aCombinedPickFromAnEarlierCompositionOrAfterMovingTheCaretIsSafe() {
        val e = english(initial = "<")
        converter()
        val commits = commitsCounted(e)
        type("m-")
        ime.onAction(Keyboards.TO_SYMBOLS) // the number screen
        ime.onText("1")
        ime.onAction(Keyboards.TO_ASCII)
        val stale = chips().single { it.text == "Ｍ－①" }
        type("x") // the composition changes; the old chip is not current any more
        stale.performClick()
        assertEquals("<m-1x", e.text.toString())
        assertEquals(0, commits())
        ime.onAction(Keyboards.CURSOR_LEFT)
        ime.onAction(Keyboards.CURSOR_LEFT) // m-|1x: the caret's token comes first, the candidates are still whole
        val strip = chipTexts()
        assertEquals("m-1x", strip.first())
        assertTrue(strip.toString(), strip.containsAll(listOf("M-1X", "m-①x", "M-①X", "Ｍ－①Ｘ")))
        chips().single { it.text == "M-①X" }.performClick()
        assertEquals("<M-①X", e.text.toString())
        assertEquals(1, commits())
        assertEquals(e.text.length, caret(e))
    }

    @Test fun addressFieldsKeepCaseAndDictionaryFormsOnly() {
        for (variation in listOf(T.TYPE_TEXT_VARIATION_URI, T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) {
            val e = start(T.TYPE_CLASS_TEXT or variation) // URL starts on かな, e-mail on English
            ime.onAction(Keyboards.TO_ASCII)
            converter()
            type("m-1")
            pumpUntil("case forms") { "M-1" in chipTexts() }
            val strip = chipTexts()
            assertEquals("m-1", strip.first())
            assertTrue(strip.toString(), strip.none { it.any { c -> c.code in 0xFF01..0xFF5E || c == '①' } })
            ime.onAction(Keyboards.ENTER)
            assertEquals("m-1", e.text.toString())
        }
    }

    @Test fun protectedAndNumberFieldsStillTypeAtOnceAndArrowsGoToTheEditor() {
        for (type in listOf(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_CLASS_NUMBER, T.TYPE_CLASS_PHONE)) {
            val e = start(type)
            ime.onText("m")
            ime.onText("1")
            assertEquals(-1, e.composingStart)
            assertTrue(chips().isEmpty())
            ime.onAction(Keyboards.CURSOR_LEFT)
            assertEquals(listOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT), e.keys) // nothing of ours composed
            assertEquals(0, e.reads)
        }
    }

    // --- B. English composing caret --------------------------------------------------------------

    /** Delivers what an editor would report for the setComposingText writes so far, then for our caret placement. */
    private fun settleWithCaret(e: FakeEditor) {
        var last = intArrayOf(-1, -1)
        while (e.reports.isNotEmpty()) {
            val r = e.reports.removeAt(0)
            ime.onUpdateSelection(r[0], r[1], r[2], r[3], r[4], r[5])
            last = intArrayOf(r[2], r[3])
        }
        val at = caret(e)
        if (at != last[1]) ime.onUpdateSelection(last[0], last[1], at, at, e.composingStart, e.composingEnd)
    }

    @Test fun englishTypingDeletingAndTheCaseKeyActAtTheCaretAndTheEditorsReportsKeepIt() {
        val e = english(initial = "x ")
        val commits = commitsCounted(e)
        type("abc")
        ime.onAction(Keyboards.CURSOR_LEFT)
        ime.onAction(Keyboards.CURSOR_LEFT) // a|bc
        assertEquals(3, caret(e))
        settleWithCaret(e) // our own reports: the composition stays, nothing is written again
        assertEquals(2, e.composingStart)
        assertTrue(e.reports.isEmpty())
        type("Z")
        assertEquals("x aZbc", e.text.toString())
        assertEquals(4, caret(e))
        ime.onAction(Keyboards.SHIFT) // the letter before the caret
        assertEquals("x azbc", e.text.toString())
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals("x abc", e.text.toString())
        assertEquals(3, caret(e))
        ime.onAction(Keyboards.TO_SYMBOLS) // the number screen types at the caret too
        ime.onText("7")
        ime.onAction(Keyboards.MODE_CYCLE)
        assertEquals("x a7bc", e.text.toString())
        assertEquals(4, caret(e))
        ime.onAction(Keyboards.CURSOR_UP)
        assertEquals(2, caret(e)) // ▲: the start of the composition, never into committed text
        ime.onAction(Keyboards.CURSOR_LEFT)
        ime.onAction(Keyboards.BACKSPACE) // nothing of ours before the caret: nothing happens
        assertEquals("x a7bc", e.text.toString())
        ime.onAction(Keyboards.CURSOR_DOWN)
        assertEquals(6, caret(e))
        ime.onAction(Keyboards.CURSOR_RIGHT)
        assertEquals(6, caret(e))
        assertEquals(0, commits())
        assertTrue(e.keys.isEmpty())
        assertEquals(2, e.composingStart)
        // A cursor moved by the user (not our report) still ends the composition, text left as shown.
        settleWithCaret(e)
        Selection.setSelection(e.text, 0)
        ime.onUpdateSelection(6, 6, 0, 0, 2, 6)
        assertEquals(-1, e.composingStart)
        assertEquals("x a7bc", e.text.toString())
        assertEquals(0, e.reads)
    }

    @Test fun anEnglishPickWithTheCaretInsideReplacesTheWholeCompositionOnce() {
        val e = english(initial = "<")
        val commits = commitsCounted(e)
        type("abc()")
        ime.onAction(Keyboards.CURSOR_LEFT) // abc(|)
        type("x")
        assertEquals("<abc(x)", e.text.toString())
        assertEquals(6, caret(e))
        pumpUntil("whole") { "abc「x」" in chipTexts() }
        assertEquals("abc(x)", chipTexts().first())
        chips().single { it.text == "abc「x」" }.performClick()
        assertEquals("<abc「x」", e.text.toString())
        assertEquals(7, caret(e)) // after the committed text
        assertEquals(-1, e.composingStart)
        assertEquals(1, commits())
    }

    @Test fun englishCaretStepsOverEmojiAndKanaGoesInAtIt() {
        val e = english()
        type("ab")
        ime.onAction(Keyboards.TO_EMOJI)
        ime.onText("👨‍👩‍👧")
        ime.onAction(Keyboards.TO_TEXT)
        type("c")
        ime.onAction(Keyboards.CURSOR_LEFT)
        ime.onAction(Keyboards.CURSOR_LEFT) // before the whole family emoji
        assertEquals(2, caret(e))
        ime.onAction(Keyboards.MODE_CYCLE) // かな
        ime.onFlick(FlickKey.A, FlickDirection.CENTER)
        assertEquals("abあ👨‍👩‍👧c", e.text.toString())
        assertEquals(3, caret(e))
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.ENTER)
        assertEquals("abあ👨‍👩‍👧c", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun staleEnglishLookupsNeverChangeTheCompositionOrTheCaret() {
        val e = english()
        type("github")
        ime.onAction(Keyboards.CURSOR_LEFT)
        ime.onAction(Keyboards.CURSOR_LEFT)
        type("x") // before the lookup for github answers
        val shown = e.text.toString()
        val at = caret(e)
        pumpUntil("lookup of githxub") { ime.englishResultKey == "githxub" }
        idleABit()
        assertEquals(shown, e.text.toString())
        assertEquals(at, caret(e))
        assertFalse(chipTexts().any { it == "GitHub" })
        // A new field before an answer: nothing is written there.
        type("y")
        val next = english()
        idleABit()
        assertEquals("", next.text.toString())
        assertTrue(chips().isEmpty())
    }

    // --- B. Kana composing caret ----------------------------------------------------------------------

    private fun liveTomorrowRain(): FakeEditor {
        val e = start()
        converter()
        typeKana("あしたはあめ")
        pumpUntil("live 明日は雨") { e.text.toString() == "明日は雨" }
        return e
    }

    /** Text with | at the editor's caret. */
    private fun shown(e: FakeEditor) = e.text.toString().let { it.substring(0, caret(e)) + "|" + it.substring(caret(e)) }

    @Test fun theKanaCaretMovesByReadingNotBySurfaceAndAWordHoldingItShowsItsKana() {
        val e = liveTomorrowRain()
        val commits = commitsCounted(e)
        ime.onAction(Keyboards.CURSOR_LEFT)
        assertEquals("明日はあ|め", shown(e)) // あ|め: 雨 has no place for the caret, so its kana
        ime.onAction(Keyboards.CURSOR_LEFT)
        assertEquals("明日は|雨", shown(e))
        ime.onAction(Keyboards.CURSOR_LEFT)
        assertEquals("明日|は雨", shown(e)) // reading offset 3, surface offset 2
        ime.onAction(Keyboards.CURSOR_LEFT)
        assertEquals("あし|たは雨", shown(e))
        settleWithCaret(e) // our reports are not taken for taps or moves
        assertEquals("あし|たは雨", shown(e))
        assertTrue(e.reports.isEmpty())
        ime.onAction(Keyboards.CURSOR_UP)
        assertEquals("|明日は雨", shown(e))
        ime.onAction(Keyboards.CURSOR_LEFT) // the start: nothing
        assertEquals("|明日は雨", shown(e))
        ime.onAction(Keyboards.CURSOR_DOWN) // back at the end, nothing edited: as before the first arrow
        assertEquals("明日は雨|", shown(e))
        assertEquals(0, commits())
        assertTrue(e.keys.isEmpty())
        assertEquals(0, e.composingStart)
        typeKana("の") // typing continues the composition as it did before
        assertTrue(e.text.toString().startsWith("明日は"))
        assertEquals(e.text.length, caret(e))
    }

    private fun repair() = ime.repairForTests!!
    private fun segments() = views().filter { it.tag == TAG_SEGMENT }.map { it.text.toString() }
    private fun candidateChip(text: String) = views().firstOrNull { it.tag != TAG_SEGMENT && it.tag != TAG_ENGLISH_CANDIDATE && it.text.toString() == text }

    @Test fun insertionAndDeletionInTheMiddleChangeOnlyThePortionAtTheCaret() {
        val e = liveTomorrowRain()
        val commits = commitsCounted(e)
        repeat(2) { ime.onAction(Keyboards.CURSOR_LEFT) } // 明日は|雨: a segment boundary
        assertEquals(listOf("明日は", "雨"), segments())
        assertEquals("あしたは", repair().scopeTyped)
        typeKana("の")
        assertEquals("明日はの|雨", shown(e)) // a new portion at the caret; 明日は and 雨 are not converted again
        assertEquals(listOf("あしたは", "の", "あめ"), repair().units.map { it.typed })
        assertEquals("の", repair().scopeTyped)
        idleABit()
        assertTrue(shown(e), shown(e).startsWith("明日は") && shown(e).endsWith("|雨"))
        ime.onAction(Keyboards.BACKSPACE) // の
        assertEquals("明日は|雨", shown(e))
        assertEquals(listOf("明日は", "雨"), repair().units.map { it.surface })
        ime.onAction(Keyboards.BACKSPACE) // は, after 明日's boundary: 明日 stays, nothing else changes
        assertEquals("明日|雨", shown(e))
        assertEquals(listOf("明日", "雨"), repair().units.map { it.surface })
        ime.onAction(Keyboards.CURSOR_LEFT) // あし|た: inside 明日, shown as its two kana parts
        assertEquals("あし|た雨", shown(e))
        assertEquals(listOf("あし", "た", "雨"), segments())
        ime.onFlick(FlickKey.MODIFIER, FlickDirection.CENTER) // し → じ, before the caret, never め at the end
        assertEquals(listOf("あじ", "た", "あめ"), repair().units.map { it.typed })
        assertTrue(shown(e), shown(e).endsWith("雨"))
        assertEquals(0, commits())
        ime.onAction(Keyboards.ENTER)
        assertTrue(e.text.toString(), e.text.toString().endsWith("雨"))
        assertEquals(-1, e.composingStart)
        assertEquals(1, commits())
        assertTrue(e.keys.isEmpty())
    }

    /** Review follow-up: Enter after only moving inside 明日 or 雨 commits the conversion, not the kana shown at the caret. */
    @Test fun enterAfterOnlyMovingInsideAWordCommitsTheConversion() {
        for (presses in listOf(1, 4)) {
            val e = liveTomorrowRain()
            val commits = commitsCounted(e)
            repeat(presses) { ime.onAction(Keyboards.CURSOR_LEFT) }
            assertEquals(if (presses == 1) "明日はあ|め" else "あし|たは雨", shown(e))
            assertEquals(if (presses == 1) listOf("明日は", "あ", "め") else listOf("あし", "たは", "雨"), segments())
            assertEquals(if (presses == 1) "あ" else "あし", repair().scopeTyped)
            settleWithCaret(e)
            ime.onAction(Keyboards.ENTER)
            assertEquals("明日は雨", e.text.toString())
            assertEquals(-1, e.composingStart)
            assertEquals(1, commits())
            assertEquals(e.text.length, caret(e))
        }
    }

    @Test fun aKanaPickAtTheCaretReplacesOnlyThePortionBeforeIt() {
        val e = liveTomorrowRain()
        repeat(3) { ime.onAction(Keyboards.CURSOR_LEFT) } // 明日|は雨
        assertEquals("明日|は雨", shown(e))
        assertEquals(listOf("明日", "は", "雨"), segments()) // the boundary at the caret
        assertEquals("あした", repair().scopeTyped)
        assertEquals(0 until 2, repair().shownFocusRange)
        assertEquals(listOf("明日は", "雨"), repair().units.map { it.surface }) // only shown, not made
        pumpUntil("アシタ chip") { candidateChip("アシタ") != null }
        candidateChip("アシタ")!!.performClick()
        assertEquals("アシタ|は雨", shown(e)) // は and 雨 untouched, the caret after the choice
        assertEquals(listOf("アシタ", "は", "雨"), repair().units.map { it.surface })
        assertEquals(0, e.composingStart) // a choice inside commits nothing
        ime.onAction(Keyboards.ENTER)
        assertEquals("アシタは雨", e.text.toString())

        // Inside 雨 (あ|め) the scope is あ; め, the rest of the cut word, converts again; 明日は stays.
        val f = liveTomorrowRain()
        ime.onAction(Keyboards.CURSOR_LEFT)
        assertEquals("あ", repair().scopeTyped)
        pumpUntil("ア chip") { candidateChip("ア") != null }
        candidateChip("ア")!!.performClick()
        assertTrue(shown(f), shown(f).startsWith("明日はア|"))
        assertEquals(listOf("あしたは", "あ", "め"), repair().units.map { it.typed })
        ime.onAction(Keyboards.ENTER)
        assertTrue(f.text.toString(), f.text.toString().startsWith("明日はア"))
    }

    @Test fun englishInTheMiddleOfAKanaCompositionGetsABoundaryAtTheCaret() {
        val e = english()
        converter()
        type("abc")
        ime.onAction(Keyboards.MODE_CYCLE) // かな: the kana take the English letters over
        typeKana("あめ")
        pumpUntil("abc雨") { e.text.toString() == "abc雨" }
        repeat(3) { ime.onAction(Keyboards.CURSOR_LEFT) } // ab|c雨
        assertEquals("ab|c雨", shown(e))
        // The converter gives each letter its own passthrough segment: the caret is on the boundary after b.
        assertEquals(listOf("a", "b", "c", "あめ"), repair().shownUnits.map { it.typed })
        assertEquals("b", repair().scopeTyped)
        typeKana("の")
        assertTrue(shown(e), shown(e).endsWith("|c雨")) // the suffix and its conversion are untouched
        assertEquals(listOf("a", "bの", "c", "あめ"), repair().units.map { it.typed })
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.ENTER)
        assertTrue(e.text.toString(), e.text.toString().startsWith("ab") && e.text.toString().endsWith("c雨"))
    }

    @Test fun aSegmentButtonStillSelectsAndItsArrowsResizeAsBefore() {
        val e = liveTomorrowRain()
        ime.onAction(Keyboards.CURSOR_LEFT)
        views().filter { it.tag == TAG_SEGMENT }[0].performClick()
        assertEquals("明日は雨|", shown(e)) // a selected segment: the words again, the caret at the end
        ime.onAction(Keyboards.CURSOR_LEFT) // resizes the selected segment
        pumpUntil("resized") { views().filter { it.tag == TAG_SEGMENT }.map { it.text.toString() } == listOf("明日", "は雨") }
        assertEquals(0, e.composingStart)
        assertTrue(e.keys.isEmpty())
    }

    @Test fun aStaleKanaResultNeverMovesTheCaretOrUndoesAnEdit() {
        val e = liveTomorrowRain()
        repeat(2) { ime.onAction(Keyboards.CURSOR_LEFT) } // 明日は|雨
        typeKana("の") // a result for の is requested
        typeKana("に") // and superseded at once
        assertEquals("明日はのに|雨", shown(e))
        repeat(60) { Thread.sleep(5); shadowOf(Looper.getMainLooper()).idle() } // whatever answers, answers now
        assertTrue(shown(e), shown(e).startsWith("明日は") && shown(e).endsWith("|雨")) // prefix, suffix and caret kept
        assertEquals(listOf("あしたは", "のに", "あめ"), repair().units.map { it.typed }) // never the older reading
        assertEquals(0, e.composingStart)
    }

    @Test fun anEmojiInAKanaCompositionIsOneCaretStep() {
        val e = start()
        converter()
        typeKana("あめ")
        ime.onAction(Keyboards.TO_EMOJI)
        ime.onText("👍🏽")
        pumpUntil("雨👍🏽") { e.text.toString() == "雨👍🏽" }
        ime.onAction(Keyboards.CURSOR_LEFT)
        assertEquals("雨|👍🏽", shown(e)) // never between the thumb and its skin tone
        ime.onAction(Keyboards.BACKSPACE) // め, before the caret; the emoji stays
        pumpUntil("あ") { e.text.toString().endsWith("👍🏽") && !e.text.toString().startsWith("雨") }
        assertTrue(shown(e), shown(e).endsWith("|👍🏽"))
        assertEquals(0, e.composingStart)
    }

    @Test fun aNewFieldOrAHiddenKeyboardWithTheCaretInsideNeverWritesTheOldComposition() {
        val e = liveTomorrowRain()
        repeat(2) { ime.onAction(Keyboards.CURSOR_LEFT) }
        typeKana("の")
        val next = start()
        idleABit()
        assertEquals("", next.text.toString())
        assertEquals(-1, next.composingStart)
        assertTrue(e.text.toString().endsWith("雨")) // the old field is not written by late results either
        // Hidden with the caret inside: the typed kana stays, nothing converted is committed.
        val f = liveTomorrowRain()
        ime.onAction(Keyboards.CURSOR_LEFT)
        ime.onFinishInputView(false)
        assertEquals("あしたはあめ", f.text.toString())
        assertEquals(-1, f.composingStart)
        // A tap elsewhere ends the composition without writing at the new cursor.
        val g = liveTomorrowRain()
        ime.onAction(Keyboards.CURSOR_LEFT)
        settleWithCaret(g)
        val shownBefore = g.text.toString()
        BaseInputConnectionShim.removeComposing(g)
        Selection.setSelection(g.text, 0)
        ime.onUpdateSelection(4, 4, 0, 0, -1, -1)
        assertEquals(shownBefore, g.text.toString())
        assertEquals(-1, g.composingStart)
        assertEquals(0, e.reads + f.reads + g.reads)
    }

    private companion object {
        /** Opening and closing brackets, pairwise. */
        const val STYLES = "()（）「」『』【】〔〕［］〘〙〚〛｛｝〈〉《》«»‹›‘’“”〝〟[]{}"
    }

    private object BaseInputConnectionShim {
        fun removeComposing(e: FakeEditor) = android.view.inputmethod.BaseInputConnection.removeComposingSpans(e.text)
    }
}
