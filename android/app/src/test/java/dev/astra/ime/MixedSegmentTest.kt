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
import dev.astra.ime.core.Supplement
import dev.astra.ime.oss.EnglishLexicon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * 0.1.35: an English part of a kana composition (m-1 taken over by ぐらんぷり, openai-1 between あめ and です) gets the
 * whole forms an English composition gets ([WholeCandidates]) as candidates of its segment, in the strip, the panel and at
 * the composing caret; a pick replaces that segment only and commits nothing. The real service with the OSS converter
 * when the build bundles it (Mozc keeps m-1 one segment, as in the user's screenshot), otherwise the fixture converter
 * (one segment per ASCII character; the scope is then made by resizing, as a user can).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class MixedSegmentTest {
    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        DebugSettings.setOssEnabled(context, true)
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

    private fun start(type: Int = T.TYPE_CLASS_TEXT, initial: String = ""): FakeEditor {
        if (controller == null) {
            controller = Robolectric.buildService(TestIme::class.java).create()
            ime.ic = FakeEditor(View(ime), "", 0, 0)
            ime.info = EditorInfo().apply { inputType = T.TYPE_CLASS_TEXT }
            ime.onStartInput(ime.info!!, false)
            root = ime.onCreateInputView() as ViewGroup
        }
        val editor = FakeEditor(View(ime), initial, initial.length, initial.length)
        val info = EditorInfo().apply { inputType = type; initialSelStart = initial.length; initialSelEnd = initial.length }
        ime.ic = editor
        ime.info = info
        ime.onStartInput(info, false)
        pumpUntil("converter") { ime.converterReady && ime.conversionWorker.ossState != ConversionWorker.OssState.LOADING }
        return editor
    }

    private fun pumpUntil(what: String = "condition", condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 60_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun idleABit() = repeat(40) { Thread.sleep(5); shadowOf(Looper.getMainLooper()).idle() }

    private fun views(): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root!!)
        return out
    }

    private fun stripRow(): ViewGroup = root!!.findViewWithTag(TAG_STRIP_ROW)

    /** The strip's candidate chips: what follows the segment buttons and their divider in the scrolled row. */
    private fun stripChips(): List<TextView> {
        val row = (stripRow().getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        val all = (0 until row.childCount).map { row.getChildAt(it) }
        val divider = all.indexOfFirst { it.tag == TAG_SEGMENT_DIVIDER }
        return all.drop(divider + 1).filterIsInstance<TextView>()
    }
    private fun strip() = stripChips().map { it.text.toString() }
    private fun segmentButtons() = views().filter { it.tag == TAG_SEGMENT }
    private fun repair() = ime.repairForTests!!
    private fun expand(): TextView = stripRow().getChildAt(1) as TextView
    private fun exact(): CandidateSectionView = root!!.findViewWithTag(TAG_PANEL_EXACT)
    private fun caret(e: FakeEditor) = Selection.getSelectionStart(e.text)

    private fun commitsCounted(e: FakeEditor): () -> Int {
        var n = 0
        e.duringCommit = { n++ }
        return { n }
    }

    /** Flicks [text]; a voiced or semi-voiced kana is its plain kana and 小゛゜ as often as needed. */
    private fun typeKana(text: String) {
        val all = FlickKey.entries.flatMap { k -> FlickDirection.entries.map { k to it } }
        for (c in text) {
            val s = c.toString()
            val direct = all.firstOrNull { FlickLayout.kana(it.first, it.second) == s }
            if (direct != null) {
                ime.onFlick(direct.first, direct.second)
                continue
            }
            val (base, steps) = all.mapNotNull { (k, d) ->
                val plain = FlickLayout.kana(k, d) ?: return@mapNotNull null
                var x: String? = plain
                var n = 0
                while (x != null && x != s && n < 3) { x = FlickLayout.toggle(x); n++ }
                if (x == s) (k to d) to n else null
            }.first()
            ime.onFlick(base.first, base.second)
            repeat(steps) { ime.onFlick(FlickKey.MODIFIER, FlickDirection.CENTER) }
        }
    }

    /** The user's screenshot: m-1 on the English screen (12 keys, numbers screen), then かな and ぐらんぷり. */
    private fun screenshotComposition(): FakeEditor {
        val e = start()
        ime.onAction(Keyboards.MODE_CYCLE) // かな → English
        ime.onText("m")
        ime.onText("-")
        ime.onAction(Keyboards.TO_SYMBOLS)
        ime.onText("1")
        ime.onAction(Keyboards.TO_KANA)
        typeKana("ぐらんぷり")
        assertEquals(0, e.composingStart)
        pumpUntil("segment buttons") { segmentButtons().isNotEmpty() }
        return e
    }

    /** [prefix] in kana, [run] typed from the English screen into the composition, then [suffix] in kana. */
    private fun kanaAround(prefix: String, run: String, suffix: String, type: Int = T.TYPE_CLASS_TEXT): FakeEditor {
        val e = start(type)
        typeKana(prefix)
        ime.onAction(Keyboards.TO_ASCII)
        for (c in run) ime.onText(c.toString())
        ime.onAction(Keyboards.TO_KANA)
        typeKana(suffix)
        pumpUntil("segment buttons") { segmentButtons().isNotEmpty() }
        return e
    }

    /**
     * Selects the segment starting at typed offset [from] with a segment button and, where the converter cut the English
     * part in pieces (the fixture), resizes it to [run] with ◀ ▶ as a user would. The scope's candidates are then shown.
     */
    private fun selectScope(from: Int, run: String) {
        segmentButtons()[0].performClick()
        var at = 0
        val index = repair().units.indexOfFirst { u -> (at == from).also { at += u.typed.length } }
        assertTrue("a segment starts at $from", index >= 0)
        if (index != 0) segmentButtons()[index].performClick()
        var guard = 0
        while (repair().scopeTyped != run && guard++ < run.length) {
            ime.onAction(if (repair().scopeTyped.length < run.length) Keyboards.CURSOR_RIGHT else Keyboards.CURSOR_LEFT)
        }
        assertEquals(run, repair().scopeTyped)
        pumpUntil("candidates of $run") { repair().candidates.isNotEmpty() && strip().isNotEmpty() }
    }

    private fun oss() = ime.conversionWorker.ossState == ConversionWorker.OssState.READY

    // --- The screenshot (reproduced first, on 0.1.34) ------------------------------------------------

    @Test fun theSegmentM1AfterAKanaTakeoverListsItsWholeFormsAndAPickReplacesOnlyIt() {
        val e = screenshotComposition()
        val commits = commitsCounted(e)
        val shownBefore = e.text.toString()
        assertTrue(shownBefore, shownBefore.startsWith("m-1"))
        val suffix = shownBefore.substring(3) // グランプリ with the OSS converter
        if (oss()) assertEquals("m-1", segmentButtons()[0].text.toString()) // one segment, as in the screenshot
        selectScope(0, "m-1")
        pumpUntil("M-1 in the strip") { "M-1" in strip() }
        val s = strip()
        for (f in listOf("M-1", "ｍ－１", "Ｍ－１", "M-①", "m-①", "Ｍ－①")) assertTrue("$f in $s", f in s)
        assertEquals(s.toString(), s.size, s.toSet().size)
        assertTrue(s.toString(), s.none { it == "1" || it == "M" || it.contains("グ") || it.contains("ぐ") }) // the scope only
        // The panel lists the same forms (not cut to a top-K).
        expand().performClick()
        pumpUntil("panel") { exact().entries.isNotEmpty() }
        val panel = exact().entries
        for (f in s) assertTrue("$f in panel $panel", f in panel)
        expand().performClick() // ▲
        // A pick: only the segment changes, the rest stays, nothing is committed.
        pumpUntil("M-1 chip") { stripChips().any { it.text == "M-1" } }
        stripChips().first { it.text == "M-1" }.performClick()
        assertEquals("M-1$suffix", e.text.toString())
        assertEquals(0, e.composingStart)
        assertEquals(e.text.length, e.composingEnd)
        assertEquals(0, commits())
        assertNotNull(ime.repairForTests)
        assertEquals("M-1", repair().units[repair().focus].surface)
        assertEquals("m-1", repair().units[repair().focus].typed) // the reading is what was typed
        assertTrue(repair().units[repair().focus].locked)
        idleABit()
        assertEquals("M-1$suffix", e.text.toString()) // no late result undoes it
        ime.onAction(Keyboards.ENTER)
        assertEquals("M-1$suffix", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals(1, commits())
        assertEquals(0, e.reads)
    }

    @Test fun aFormPickedInThePanelReplacesOnlyTheSegment() {
        val e = screenshotComposition()
        val suffix = e.text.toString().substring(3)
        selectScope(0, "m-1")
        pumpUntil("forms") { "Ｍ－①" in strip() }
        expand().performClick()
        pumpUntil("panel") { "Ｍ－①" in exact().entries }
        exact().onPick!!.invoke(exact().entries.indexOf("Ｍ－①"))
        assertEquals("Ｍ－①$suffix", e.text.toString())
        assertEquals(0, e.composingStart)
        assertEquals("Ｍ－①", repair().units[repair().focus].surface)
    }

    // --- Every kind of English part, with Japanese before and after ---------------------------------

    @Test fun lettersDigitsAndSymbolsBetweenKanaGetTheirFormsAndAPickKeepsPrefixAndSuffix() {
        // run, forms that must be offered, the one picked
        val cases = listOf(
            Triple("openai-1", listOf("OpenAI-1", "OpenAI-①", "OPENAI-1", "ｏｐｅｎａｉ－１"), "OpenAI-①"), // English index, all three
            Triple("m1", listOf("M1", "M①", "ｍ１", "Ｍ１"), "M①"), // letters + digits
            Triple("1-2", listOf("１－２", "①-②", "①－2"), "①-②"), // digits + symbols
            Triple("abc()", listOf("ABC()", "abc「」", "ABC「」", "ａｂｃ（）"), "ABC「」"), // letters + a bracket pair
            Triple("abc", listOf("ABC", "Abc", "ａｂｃ"), "ABC"), // letters only
        )
        for ((run, forms, pick) in cases) {
            val e = kanaAround("あめ", run, "です")
            val commits = commitsCounted(e)
            val before = e.text.toString()
            val at = before.indexOf(run)
            assertTrue("$run in $before", at >= 0)
            val prefix = before.substring(0, at)
            val suffix = before.substring(at + run.length)
            selectScope(2, run)
            pumpUntil("forms of $run") { forms.all { it in strip() } }
            val s = strip()
            assertEquals(s.toString(), s.size, s.toSet().size)
            assertTrue(s.toString(), s.none { it.contains("です") || it.contains("雨") })
            stripChips().first { it.text == pick }.performClick()
            assertEquals("$prefix$pick$suffix", e.text.toString())
            assertEquals(0, e.composingStart)
            assertEquals(0, commits())
            ime.onAction(Keyboards.ENTER)
            assertEquals("$prefix$pick$suffix", e.text.toString())
            assertEquals(1, commits())
        }
    }

    /** The keyboard showing English or numbers when the segment is selected suppresses nothing. */
    @Test fun theScreenInUseDoesNotChangeTheForms() {
        for (screen in listOf(Keyboards.TO_KANA, Keyboards.TO_ASCII, Keyboards.TO_SYMBOLS)) {
            screenshotComposition()
            ime.onAction(screen)
            selectScope(0, "m-1")
            pumpUntil("forms on $screen") { listOf("M-1", "Ｍ－１", "M-①").all { it in strip() } }
        }
    }

    // --- The composing caret --------------------------------------------------------------------------

    @Test fun scopesMadeByTheCaretGetTheFormsOfThePartBeforeIt() {
        val e = screenshotComposition()
        val commits = commitsCounted(e)
        val suffix = e.text.toString().substring(3)
        repeat(5) { ime.onAction(Keyboards.CURSOR_LEFT) } // after m-1: the caret on the boundary before the kana
        assertTrue(repair().caretMode)
        val scope = repair().scopeTyped
        assertTrue(scope, scope.isNotEmpty() && "m-1".endsWith(scope)) // m-1 (OSS) or 1 (fixture: one per character)
        pumpUntil("full width of $scope") { Supplement.Widths.full(scope) in strip() }
        if (oss()) {
            assertEquals("m-1", scope)
            assertTrue(strip().toString(), listOf("M-1", "Ｍ－１", "M-①").all { it in strip() })
        }
        // Inside it: the part before the caret.
        ime.onAction(Keyboards.CURSOR_LEFT)
        val inner = repair().scopeTyped
        assertTrue(inner, inner.isNotEmpty() && "m-".endsWith(inner))
        pumpUntil("full width of $inner") { Supplement.Widths.full(inner) in strip() }
        val strip = strip()
        assertTrue(strip.toString(), strip.none { it.contains("1") || it.contains("１") || it.contains("①") })
        // The panel is consistent with the strip.
        expand().performClick()
        pumpUntil("panel") { Supplement.Widths.full(inner) in exact().entries }
        expand().performClick()
        val full = Supplement.Widths.full(inner)
        stripChips().first { it.text == full }.performClick()
        val expected = "m-".dropLast(inner.length) + full + "1" + suffix
        assertEquals(expected, e.text.toString()) // only the part before the caret
        assertEquals(0, e.composingStart)
        assertEquals(0, commits())
        assertEquals(expected.length - 1 - suffix.length, caret(e)) // the caret after the choice
    }

    // --- Stale results ---------------------------------------------------------------------------------

    @Test fun formsAndTapsOfAnEarlierScopeAreRejected() {
        val e = screenshotComposition()
        selectScope(0, "m-1")
        pumpUntil("forms") { "Ｍ－１" in strip() }
        val stale = stripChips().first { it.text == "Ｍ－１" }
        ime.onAction(Keyboards.CURSOR_LEFT) // the segment is now m-
        assertEquals("m-", repair().scopeTyped)
        stale.performClick()
        assertFalse(e.text.toString(), e.text.toString().startsWith("Ｍ－１"))
        pumpUntil("forms of m-") { "Ｍ－" in strip() }
        assertTrue(strip().toString(), strip().none { it.contains("1") || it.contains("１") || it.contains("①") })
        assertTrue(repair().candidates.none { it.surface.contains('①') || it.surface.contains('１') })
        assertEquals(0, e.composingStart)

        // An English index answer for an earlier scope never lists its forms in a later one.
        val f = kanaAround("あめ", "openai", "です")
        selectScope(2, "openai")
        ime.onAction(Keyboards.CURSOR_LEFT) // opena, before the lookup of openai is shown
        assertEquals("opena", repair().scopeTyped)
        pumpUntil("forms of opena") { "OPENA" in strip() }
        idleABit()
        assertTrue(strip().toString(), strip().none { it.equals("openai", ignoreCase = true) })
        assertEquals(0, f.composingStart)
    }

    @Test fun aNewFieldDropsTheSegmentAndItsForms() {
        screenshotComposition()
        selectScope(0, "m-1")
        val next = start()
        idleABit()
        assertNull(ime.repairForTests)
        assertEquals("", next.text.toString())
    }

    // --- Unchanged: kana segments, English compositions, fields --------------------------------------

    @Test fun kanaSegmentsGetNoEnglishForms() {
        val e = kanaAround("あめ", "", "です")
        segmentButtons()[0].performClick()
        pumpUntil("candidates") { repair().candidates.isNotEmpty() }
        idleABit()
        assertTrue(repair().candidates.toString(), repair().candidates.none { c -> c.surface.any { it.code in 0xFF01..0xFF5E } })
        assertEquals(0, e.composingStart)
    }

    @Test fun anEnglishCompositionKeepsItsOwnWholeCandidates() {
        val e = start()
        ime.onAction(Keyboards.MODE_CYCLE)
        ime.onText("m")
        ime.onText("-")
        ime.onAction(Keyboards.TO_SYMBOLS)
        ime.onText("1")
        val chips = { views().filter { it.tag == TAG_ENGLISH_CANDIDATE }.map { it.text.toString() } }
        pumpUntil("whole") { "M-①" in chips() }
        assertEquals("m-1", chips().first())
        assertTrue(chips().containsAll(listOf("M-1", "ｍ－１", "Ｍ－１", "m-①", "Ｍ－①")))
        assertTrue(chips().size <= WholeCandidates.MAX)
        assertEquals(0, e.composingStart)
        assertNull(ime.repairForTests)
    }

    @Test fun addressFieldsGetCaseFormsOnlyAndNothingReadsTheField() {
        for (variation in listOf(T.TYPE_TEXT_VARIATION_URI, T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) {
            val e = start(T.TYPE_CLASS_TEXT or variation)
            e.forbidReads = true
            ime.onAction(Keyboards.TO_ASCII)
            for (c in "m-1") ime.onText(c.toString())
            ime.onAction(Keyboards.TO_KANA)
            typeKana("あめ")
            pumpUntil("segment buttons") { segmentButtons().isNotEmpty() }
            selectScope(0, "m-1")
            pumpUntil("case forms") { "M-1" in strip() }
            idleABit()
            // No width or number forms of ours (the converter's own list, ｍ－１ with OSS, is as before).
            assertTrue(strip().toString(), strip().none { it == "Ｍ－１" || it.contains('①') })
            assertEquals(0, e.reads)
        }
    }

    // --- The forms themselves (pure) ---------------------------------------------------------------

    @Test fun mixedSegmentFormsCoverEveryKindAndOnlyEnglishParts() {
        val openai = EnglishLexicon.Result(listOf("OpenAI"), emptyList())
        val lookup = { w: String -> if (w == "openai") openai else EnglishLexicon.Result(emptyList(), listOf("${w}xyz")) }
        val alternatives = { s: String ->
            when (s) {
                "1" -> listOf("①", "一")
                "12" -> listOf("⑫", "十二")
                "-" -> listOf("ー", "—")
                else -> emptyList()
            }
        }
        fun forms(t: String, widths: Boolean = true) = MixedSegment.candidates(t, lookup, alternatives, widths)
        for (kana in listOf("", "あめ", "m-ー", "ｍ", "雨1", "a b", "👍1", "x".repeat(MixedSegment.MAX_TYPED + 1))) {
            assertTrue(kana, forms(kana).isEmpty())
        }
        assertTrue(forms("-").isEmpty()) // a mark alone is the converter's
        val m1 = forms("m-1")
        assertEquals("m-1", m1.first().surface)
        assertTrue(m1.all { it.reading == "m-1" && it.segments == null })
        assertTrue(m1.map { it.surface }.containsAll(listOf("M-1", "ｍ－１", "Ｍ－１", "M-①", "m－1")))
        assertEquals(m1.size, m1.map { it.surface }.toSet().size)
        assertTrue(m1.size <= WholeCandidates.MAX)
        assertTrue(forms("openai-1").map { it.surface }.containsAll(listOf("OpenAI-1", "OpenAI-①")))
        assertTrue(forms("12").map { it.surface }.containsAll(listOf("12", "⑫", "十二")))
        // No completions: a segment's forms spell what was typed.
        assertTrue(forms("ab").none { it.surface.contains("xyz") })
        // Letters alone: their case forms and full widths.
        assertTrue(forms("abc").map { it.surface }.containsAll(listOf("abc", "Abc", "ABC", "ａｂｃ", "ＡＢＣ")))
        assertTrue(forms("abc", widths = false).none { c -> c.surface.any { it.code in 0xFF01..0xFF5E } })
        // Address fields: no widths or number forms.
        assertTrue(forms("m-1", widths = false).none { c -> c.surface.any { it.code in 0xFF01..0xFF5E || it == '①' } })
    }
}
