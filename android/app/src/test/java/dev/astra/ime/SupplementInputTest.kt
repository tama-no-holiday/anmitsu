package dev.astra.ime

import android.os.Looper
import android.text.InputType as T
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.astra.ime.core.FlickDirection
import dev.astra.ime.core.FlickKey
import dev.astra.ime.core.FlickLayout
import dev.astra.ime.core.Supplement
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
 * 0.1.20: symbols, numbers, emoticons, emoji and single kanji for a whole reading, from the bundled Mozc-derived table
 * (assets/supplement/mozc-supplement.tsv), through the real service, worker, session and panel. Only the editor is fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w400dp-h892dp")
@LooperMode(LooperMode.Mode.PAUSED)
class SupplementInputTest {
    private var controller: ServiceController<PanelIme>? = null
    private val ime: PanelIme get() = controller!!.get()
    private var root: ViewGroup? = null

    private val table: Supplement by lazy {
        RuntimeEnvironment.getApplication().assets.open(ConversionWorker.SUPPLEMENT).bufferedReader().use(Supplement::parse)
    }

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        DebugSettings.setOssEnabled(context, false)
        DebugSettings.setZenzEnabled(context, false)
        DebugSettings.setLiveConversion(context, true)
        LayoutSettings.setNumeric12Key(context, true)
        Learning.setEnabled(context, false)
    }

    @After fun tearDown() { controller?.destroy() }

    private fun start(type: Int = T.TYPE_CLASS_TEXT): FakeEditor {
        if (controller == null) {
            controller = Robolectric.buildService(PanelIme::class.java).create()
            ime.ic = FakeEditor(View(ime), "", 0, 0)
            ime.info = EditorInfo().apply { inputType = T.TYPE_CLASS_TEXT }
            ime.onStartInput(ime.info!!, false)
            root = ime.onCreateInputView() as ViewGroup
        }
        val editor = FakeEditor(View(ime), "", 0, 0)
        val info = EditorInfo().apply { inputType = type; initialSelStart = 0; initialSelEnd = 0 }
        ime.ic = editor
        ime.info = info
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        pumpUntil("converter") { ime.converterReady }
        return editor
    }

    private fun pumpUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Types [kana] with flicks; small and voiced kana through the 小゛゜ key, as a user would. */
    private fun type(kana: String) {
        for (ch in kana.map { it.toString() }) {
            val direct = FlickKey.entries.flatMap { k -> FlickDirection.entries.map { k to it } }
                .firstOrNull { (k, d) -> FlickLayout.kana(k, d) == ch }
            if (direct != null) {
                ime.onFlick(direct.first, direct.second)
                continue
            }
            val (key, dir) = FlickKey.entries.flatMap { k -> FlickDirection.entries.map { k to it } }.first { (k, d) ->
                val base = FlickLayout.kana(k, d) ?: return@first false
                generateSequence(FlickLayout.toggle(base)) { FlickLayout.toggle(it).takeIf { n -> n != base } }.take(3).any { it == ch }
            }
            ime.onFlick(key, dir)
            var shown = FlickLayout.kana(key, dir)!!
            while (shown != ch) {
                ime.onFlick(FlickKey.MODIFIER, FlickDirection.CENTER)
                shown = FlickLayout.toggle(shown)!!
            }
        }
    }

    private fun stripBar() = root!!.findViewWithTag<View>(TAG_STRIP_ROW)
    private fun chips(): List<String> {
        val row = ((stripBar() as ViewGroup).getChildAt(0) as HorizontalScrollView).getChildAt(0) as LinearLayout
        return (0 until row.childCount).map { (row.getChildAt(it) as TextView).text.toString() }
    }
    private fun expand(): TextView = (root!!.findViewWithTag<ViewGroup>(TAG_STRIP_ROW)).getChildAt(1) as TextView
    private fun exact(): CandidateSectionView = root!!.findViewWithTag(TAG_PANEL_EXACT)

    private fun openPanel() {
        pumpUntil("expand button") { expand().text == "▼" }
        expand().performClick()
        root!!.measure(MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        root!!.layout(0, 0, 400, root!!.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun theBundledTableHoldsMozcSymbolsKanjiEmoticonsAndEmoji() {
        assertTrue(table.keyCount > 9000)
        fun surfaces(key: String, kind: Supplement.Kind) = table.lookup(key).filter { it.kind == kind }.map { it.surface }
        assertTrue(surfaces("かっこ", Supplement.Kind.SYMBOL).containsAll(listOf("「」", "（）", "【】", "『』", "()", "｢｣")))
        assertTrue(surfaces("やじるし", Supplement.Kind.SYMBOL).containsAll(listOf("→", "←", "↑", "↓")))
        assertTrue(surfaces("「」", Supplement.Kind.SYMBOL).containsAll(listOf("｢｣", "『』", "【】", "（）", "()")))
        assertTrue(surfaces("まる", Supplement.Kind.SYMBOL).containsAll(listOf("○", "●", "◎")))
        assertTrue(surfaces("かおもじ", Supplement.Kind.EMOTICON).size > 100)
        assertTrue(surfaces("ねこ", Supplement.Kind.EMOJI).isNotEmpty())
        val shi = surfaces("し", Supplement.Kind.KANJI)
        assertTrue(shi.size > 200)
        assertTrue(shi.containsAll(listOf("士", "子", "詩", "梓", "篩")))
        assertEquals(shi.size, shi.toSet().size)
        assertTrue(surfaces("1", Supplement.Kind.NUMBER).containsAll(listOf("１", "①", "一", "Ⅰ", "ⅰ", "❶", "⑴", "⒈")))
    }

    @Test fun symbolsJoinTheStripAfterTheFirstEntriesAndThePanelListsEveryOne() {
        val e = start()
        type("かっこ")
        pumpUntil("bracket chips") { "「」" in chips() }
        val strip = chips()
        assertFalse(strip.first() in table.lookup("かっこ").map { it.surface }) // the first candidate is still a word or the kana
        assertTrue(strip.containsAll(listOf("（）", "「」")))
        assertEquals(0, e.composingStart)
        openPanel()
        pumpUntil("panel lookup") { exact().entries.contains("【】") }
        val entries = exact().entries
        assertTrue(entries.containsAll(table.lookup("かっこ").map { it.surface })) // every one, past the strip's cap
        assertEquals(entries.size, entries.toSet().size)
    }

    @Test fun everySingleKanjiOfAReadingIsInThePanel() {
        start()
        type("し")
        openPanel()
        val kanji = table.lookup("し").filter { it.kind == Supplement.Kind.KANJI }.map { it.surface }
        pumpUntil("single kanji") { exact().entries.containsAll(kanji) }
        assertEquals(exact().entries.size, exact().entries.toSet().size)
    }

    @Test fun typedBracketsOfferOtherBracketsInBothWidths() {
        start()
        type("「」")
        pumpUntil("bracket variants") { chips().containsAll(listOf("｢｣", "『』")) }
    }

    @Test fun englishDownFlickDigitsComposeAndConvert() {
        val e = start()
        ime.onAction(Keyboards.MODE_CYCLE) // English
        ime.onText("1") // a down flick on @-_/
        pumpUntil("number forms") { chips().containsAll(listOf("①", "一", "Ⅰ", "１")) }
        assertEquals("1", e.text.toString()) // live conversion keeps the digits first
        ime.onText("2")
        pumpUntil("12 forms") { chips().containsAll(listOf("⑫", "十二", "Ⅻ")) }
        ime.onAction(Keyboards.ENTER)
        assertEquals("12", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun aDigitAfterAWordJoinsItsEnglishComposition() {
        val e = start()
        ime.onAction(Keyboards.MODE_CYCLE)
        ime.onText("a")
        ime.onText("b")
        ime.onText("3")
        assertEquals("ab3", e.text.toString())
        assertEquals(0, e.composingStart) // 0.1.32: nothing committed, the digit joins the word's composition
    }

    @Test fun theNumberScreenAndNonTextFieldsTypeDigitsAtOnce() {
        // The number screen (a hold on either mode key) never runs the converter. In a text field, opened from either
        // side (0.1.33 numbers), the digit is an English composition with the supplement's number forms; Enter commits
        // it as typed.
        for (from in listOf(Keyboards.TO_KANA, Keyboards.TO_ASCII)) {
            val e = start()
            ime.onAction(from)
            ime.onAction(Keyboards.TO_SYMBOLS)
            ime.onText("1")
            assertEquals("1", e.text.toString())
            assertEquals(0, e.composingStart)
            ime.onAction(Keyboards.ENTER)
            assertEquals("1", e.text.toString())
            assertEquals(-1, e.composingStart)
        }
        // English down flicks in number, phone and password fields type at once too.
        for (type in listOf(T.TYPE_CLASS_NUMBER, T.TYPE_CLASS_PHONE, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)) {
            val e = start(type)
            ime.onAction(Keyboards.TO_ASCII)
            ime.onText("7")
            assertEquals("$type", "7", e.text.toString())
            assertEquals("$type", -1, e.composingStart)
        }
        // A URL field never converts digits; since 0.1.33 a first one composes as English, as its letters do.
        val url = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI)
        ime.onAction(Keyboards.TO_ASCII)
        ime.onText("7")
        assertEquals(0, url.composingStart)
        assertFalse(chips().any { it == "７" || it == "⑦" })
        ime.onAction(Keyboards.ENTER)
        assertEquals("7", url.text.toString())
        assertEquals(-1, url.composingStart)
    }
}
