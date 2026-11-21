package dev.astra.ime

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType as T
import android.text.Selection
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_UP
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import dev.astra.ime.core.FlickDirection
import dev.astra.ime.core.FlickKey
import java.time.Duration
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
import org.robolectric.shadows.ShadowLog

/** Synthetic clipboard: every clip is made up by a test and every access is recorded. No device clipboard is read. */
internal class FakeClipSource : ClipSource {
    var current: ClipData? = null
    /** Metadata that differs from [current]: the clip changed between the two reads. */
    var staleDescription: ClipDescription? = null
    var failure: RuntimeException? = null
    val reads = ArrayList<String>()
    val listeners = ArrayList<ClipboardManager.OnPrimaryClipChangedListener>()

    override fun description(): ClipDescription? {
        reads += "description"
        failure?.let { throw it }
        return staleDescription ?: current?.description
    }

    override fun clip(): ClipData? {
        reads += "clip"
        failure?.let { throw it }
        return current
    }

    override fun listen(listener: ClipboardManager.OnPrimaryClipChangedListener) { listeners += listener }
    override fun unlisten(listener: ClipboardManager.OnPrimaryClipChangedListener) { listeners -= listener }

    /** A new clip that the system reports. */
    fun copy(clip: ClipData?) {
        current = clip
        listeners.toList().forEach { it.onPrimaryClipChanged() }
    }
}

/** Fails the test if anything beyond the plain text getter is used. */
private class ProbeItem(text: CharSequence?, uri: Uri? = null) : ClipData.Item(text, null, null, uri) {
    var textReads = 0
    override fun getText(): CharSequence? { textReads++; return super.getText() }
    override fun coerceToText(context: android.content.Context?): CharSequence = throw AssertionError("coerceToText")
    override fun coerceToStyledText(context: android.content.Context?): CharSequence = throw AssertionError("coerceToStyledText")
    override fun coerceToHtmlText(context: android.content.Context?): String = throw AssertionError("coerceToHtmlText")
    override fun getHtmlText(): String = throw AssertionError("getHtmlText")
}

internal fun plain(text: CharSequence) = ClipData.newPlainText("label", text)

private fun sensitive(text: CharSequence, set: PersistableBundle.() -> Unit = { putBoolean(ClipPolicy.EXTRA_IS_SENSITIVE, true) }) =
    plain(text).apply { description.extras = PersistableBundle().apply(set) }

private fun luminance(color: Int): Double {
    fun channel(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
    return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
}

internal fun contrast(a: Int, b: Int): Double {
    val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

/** What a view really paints: fills and text with their colors. */
private class RecordingCanvas : Canvas() {
    val fills = ArrayList<Int>()
    val texts = ArrayList<Pair<String, Int>>()
    override fun drawColor(color: Int) { fills += color }
    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) { fills += paint.color }
    override fun drawText(text: String, x: Float, y: Float, paint: Paint) { texts += text to paint.color }
}

/** 0.1.16: spaces, text tabs, the idle strip, the clipboard preview, themes and vertical arrow flicks. mdpi: 1dp = 1px. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class UxPolishTest {
    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        DebugSettings.setOssEnabled(context, false)
        DebugSettings.setZenzEnabled(context, false)
        DebugSettings.setLiveConversion(context, true)
        LayoutSettings.setEnglish12Key(context, true)
        LayoutSettings.setNumeric12Key(context, true)
        Learning.setEnabled(context, true)
        Learning.store(context).clearHistory()
        ConsumedClip.process.forget()
    }

    private var controller: ServiceController<TestIme>? = null
    private val ime: TestIme get() = controller!!.get()
    private val clip: FakeClipSource get() = ime.clip
    private var root: ViewGroup? = null

    @After fun tearDown() { controller?.destroy() }

    /** Starts [type] in a new editor; [visible] also brings the keyboard up, as the framework does. */
    private fun start(type: Int = T.TYPE_CLASS_TEXT, initial: String = "", selection: IntRange? = null, imeOptions: Int = 0, visible: Boolean = true): FakeEditor {
        if (controller == null) {
            controller = Robolectric.buildService(TestIme::class.java).create()
            ime.ic = FakeEditor(View(ime), "", 0, 0)
            ime.info = EditorInfo().apply { inputType = T.TYPE_CLASS_TEXT }
            ime.onStartInput(ime.info!!, false)
            root = ime.onCreateInputView() as ViewGroup
        }
        val from = selection?.first ?: initial.length
        val to = selection?.let { it.last + 1 } ?: initial.length
        val editor = FakeEditor(View(ime), initial, from, to)
        val info = EditorInfo().apply {
            inputType = type; initialSelStart = from; initialSelEnd = to; this.imeOptions = imeOptions
        }
        ime.ic = editor
        ime.info = info
        ime.onStartInput(info, false)
        if (visible) ime.onStartInputView(info, false)
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

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun stripBar() = root!!.findViewWithTag<View>(TAG_STRIP_ROW)
    private fun preview(): TextView? = root!!.findViewWithTag(TAG_CLIP_PREVIEW)
    private fun toggle() = root!!.findViewWithTag<TextView>(TAG_LAYOUT_TOGGLE)

    private fun keys() = (0 until root!!.childCount).map { root!!.getChildAt(it) }.filterIsInstance<KeyboardView>().single()

    /** Chip row of the strip: strip bar -> HorizontalScrollView -> chips. */
    private fun chipRow() = ((stripBar() as ViewGroup).getChildAt(0) as HorizontalScrollView).getChildAt(0) as LinearLayout
    private fun stripViews() = chipRow().let { r -> (0 until r.childCount).map { r.getChildAt(it) as TextView } }
    /** Candidate chips (segment buttons and their divider left out). */
    private fun chips() = stripViews().filter { it.tag != TAG_SEGMENT && it.tag != TAG_SEGMENT_DIVIDER }.map { it.text.toString() }
    private fun segments() = stripViews().filter { it.tag == TAG_SEGMENT }
    private fun selecting() = segments().any { it.isSelected }

    /** あめ typed; with the handmade lexicon, live conversion shows 雨 once the result is applied. */
    private fun typeAme(e: FakeEditor) {
        pumpUntil("converter") { ime.converterReady }
        ime.onFlick(FlickKey.A, FlickDirection.CENTER)
        ime.onFlick(FlickKey.MA, FlickDirection.entries.first { dev.astra.ime.core.FlickLayout.kana(FlickKey.MA, it) == "め" })
        pumpUntil("conversion") { e.text.toString().endsWith("雨") }
    }

    private fun type(word: String) = word.forEach { ime.onText(it.toString()) }

    // --- 2. Spaces ---------------------------------------------------------------------------------

    @Test fun spaceWidthFollowsTheScreenNotThePaletteOrigin() {
        assertEquals("　", Keyboards.spaceFor(KeyboardMode.KANA, directInput = false))
        for (mode in KeyboardMode.entries.filter { it != KeyboardMode.KANA }) assertEquals(" ", Keyboards.spaceFor(mode, directInput = false))
        for (mode in KeyboardMode.entries) assertEquals(" ", Keyboards.spaceFor(mode, directInput = true))
    }

    @Test fun everyIdleScreenTypesExactlyOneSpaceOfItsWidth() {
        val e = start()
        ime.onAction(Keyboards.SPACE)
        assertEquals("　", e.text.toString())
        ime.onAction(Keyboards.MODE_CYCLE) // English
        ime.onAction(Keyboards.SPACE)
        ime.onAction(Keyboards.TO_SYMBOLS) // numbers (a hold on the mode key)
        ime.onAction(Keyboards.SPACE)
        assertEquals("　  ", e.text.toString())
        ime.onAction(Keyboards.MODE_CYCLE) // back to English
        ime.onAction(Keyboards.MODE_CYCLE) // かな
        ime.onAction(Keyboards.TO_JSYMBOLS) // opened from かな: still half-width
        ime.onAction(Keyboards.SPACE)
        ime.onAction(Keyboards.TO_EMOJI)
        ime.onAction(Keyboards.SPACE)
        assertEquals("　    ", e.text.toString())
        ime.onAction(Keyboards.TO_TEXT)
        ime.onAction(Keyboards.SPACE)
        assertEquals("　    　", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertTrue(e.keys.isEmpty())
        assertEquals(0, e.reads)
    }

    @Test fun aComposedKanaConvertsOnKanaAndIsConfirmedOnceBeforeTheSpaceElsewhere() {
        Learning.setEnabled(RuntimeEnvironment.getApplication(), false) // 飴 below must not become the first candidate
        val e = start()
        typeAme(e)
        ime.onAction(Keyboards.SPACE) // reads 変換: conversion unchanged, no space
        assertEquals("飴", e.text.toString())
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.TO_JSYMBOLS) // palette opened from かな, composition kept
        ime.onAction(Keyboards.SPACE) // reads 空白: shown candidate confirmed, then the half-width space
        assertEquals("飴 ", e.text.toString())
        assertEquals(-1, e.composingStart)
        ime.onAction(Keyboards.TO_TEXT)
        typeAme(e)
        ime.onAction(Keyboards.MODE_CYCLE) // English, kana still composed
        ime.onAction(Keyboards.SPACE)
        assertEquals("飴 雨 ", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals(0, e.reads)
    }

    @Test fun aComposedEnglishWordIsCommittedOnceBeforeItsHalfWidthSpace() {
        val e = start()
        ime.onAction(Keyboards.MODE_CYCLE)
        type("openai")
        ime.onAction(Keyboards.SPACE)
        assertEquals("openai ", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun passwordFieldsTypeAHalfWidthSpaceOnEveryScreenAndLearnNothing() {
        val context = RuntimeEnvironment.getApplication()
        for (type in listOf(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_NULL)) {
            val e = start(type)
            ime.onAction(Keyboards.TO_KANA)
            ime.onAction(Keyboards.SPACE)
            assertEquals(" ", e.text.toString())
            assertEquals(-1, e.composingStart)
            assertEquals(0, e.reads)
        }
        assertEquals(0, Learning.store(context).historySize)
    }

    // --- 3. Text tabs ------------------------------------------------------------------------------

    @Test fun categoryTabsAreWordsAndTheCatalogIsUntouched() {
        val all = Palettes.symbols + Palettes.emoji
        for (c in all) {
            assertTrue(c.label, c.label.isNotEmpty())
            c.label.codePoints().forEach { cp ->
                val script = Character.UnicodeScript.of(cp)
                assertTrue(c.label, cp == '・'.code || script == Character.UnicodeScript.HAN ||
                    script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA)
            }
        }
        assertEquals(listOf("句読点", "括弧", "矢印", "数学", "通貨・単位", "図形", "囲み・番号", "ギリシャ・キリル"), Palettes.symbols.map { it.label })
        assertEquals(listOf("顔", "人・手", "動物", "自然", "食べ物", "旅行・場所", "活動", "物", "記号", "旗"), Palettes.emoji.map { it.label })
        assertEquals(454, Palettes.symbols.sumOf { it.items.size })
        assertEquals(1190, Palettes.emoji.sumOf { it.items.size })
        assertEquals("😀", Palettes.emoji.first().items.first())
        assertEquals("、", Palettes.symbols.first().items.first())

        start()
        for ((action, categories) in listOf(Keyboards.TO_JSYMBOLS to Palettes.symbols, Keyboards.TO_EMOJI to Palettes.emoji)) {
            ime.onAction(action)
            val tabs = root!!.findViewWithTag<LinearLayout>(TAG_PALETTE_TABS)
            assertEquals(listOf(Palettes.HISTORY_LABEL) + categories.map { it.label }, (0 until tabs.childCount).map { (tabs.getChildAt(it) as TextView).text.toString() })
            for (i in 0 until tabs.childCount) assertTrue((tabs.getChildAt(i) as TextView).compoundDrawablesRelative.all { it == null })
        }
    }

    // --- 1 and 4. The idle strip ---------------------------------------------------------------------

    @Test fun noStatusOrDiagnosticTextIsLeftInTheKeyboard() {
        val names = R.string::class.java.fields.map { it.name }
        assertEquals(listOf("status_converting"), names.filter { it.startsWith("status_") })
        for (type in listOf(T.TYPE_CLASS_TEXT, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI)) {
            start(type, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
            assertTrue(chips().isEmpty())
            pumpUntil("converter") { ime.converterReady }
            assertTrue(chips().isEmpty())
        }
    }

    @Test fun theIdleRowGoesAwayButEveryActiveControlKeepsIt() {
        val e = start()
        pumpUntil("converter") { ime.converterReady }
        assertEquals(View.GONE, stripBar().visibility) // かな, nothing composed, no clip

        // Japanese candidates and ▼ with an empty clipboard.
        typeAme(e)
        assertEquals(View.VISIBLE, stripBar().visibility)
        assertTrue(chips().containsAll(listOf("あめ", "飴")))
        // ▼ shows the segment buttons (what is shown stays 雨, so nothing new is learned); one is selected.
        ((stripBar() as ViewGroup).getChildAt(1) as TextView).performClick()
        segments().single().performClick()
        assertEquals(View.VISIBLE, stripBar().visibility)
        assertTrue(selecting())
        ime.onAction(Keyboards.ENTER)
        assertTrue(segments().isEmpty())
        assertEquals(View.GONE, stripBar().visibility) // back to idle after the commit

        // The layout toggle alone keeps the row on the English and number screens.
        ime.onAction(Keyboards.MODE_CYCLE)
        assertEquals(View.VISIBLE, stripBar().visibility)
        assertEquals(View.VISIBLE, toggle().visibility)
        assertTrue(chips().isEmpty())
        ime.onAction(Keyboards.TO_SYMBOLS)
        assertEquals(View.VISIBLE, toggle().visibility)
        ime.onAction(Keyboards.TO_EMOJI)
        assertEquals(View.GONE, stripBar().visibility)
        ime.onAction(Keyboards.TO_TEXT)
        ime.onAction(Keyboards.MODE_CYCLE) // numbers -> English
        ime.onAction(Keyboards.MODE_CYCLE) // かな
        assertEquals(View.GONE, stripBar().visibility)

        // English candidates.
        ime.onAction(Keyboards.MODE_CYCLE)
        type("openai")
        assertEquals(View.VISIBLE, stripBar().visibility)
        pumpUntil("english candidates") { "OpenAI" in chips() }
        ime.onAction(Keyboards.SPACE)
        assertEquals(View.VISIBLE, toggle().visibility)

        // The full candidate panel: its closing ▲ stays reachable.
        ime.onAction(Keyboards.TO_KANA)
        typeAme(e)
        ((stripBar() as ViewGroup).getChildAt(1) as TextView).performClick()
        assertEquals(View.VISIBLE, stripBar().visibility)
    }

    @Test fun keysStayBelowTheRowAndAboveTheSafeArea() {
        start()
        val measure = {
            root!!.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            root!!.layout(0, 0, root!!.measuredWidth, root!!.measuredHeight)
        }
        measure()
        val keysHeight = keys().height
        assertEquals(0, keys().top)
        assertTrue(keys().bottom <= root!!.height - root!!.paddingBottom)
        clip.copy(plain("copied"))
        measure()
        assertEquals(keysHeight, keys().height)
        assertEquals(44, stripBar().height) // the preview's touch height
        assertEquals(44, preview()!!.height)
        assertTrue(preview()!!.width >= 48)
        assertEquals(stripBar().bottom, keys().top)
        assertTrue(keys().bottom <= root!!.height - root!!.paddingBottom)
    }

    // --- 4. Clipboard preview ------------------------------------------------------------------------

    @Test fun shortClipReplacesTheIdleAreaAndNothingIsPastedUntilATap() {
        val e = start()
        assertNull(preview())
        clip.copy(plain("短い"))
        assertEquals("短い", preview()!!.text.toString())
        assertEquals(View.VISIBLE, stripBar().visibility)
        assertEquals("", e.text.toString()) // never pasted automatically
        preview()!!.performClick()
        assertEquals("短い", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals(0, e.reads)
        assertTrue(e.keys.isEmpty())
        assertNull(preview()) // used; the clip itself is still there
        assertEquals("短い", clip.current!!.getItemAt(0).text.toString())
    }

    @Test fun longClipIsPreviewedBoundedButPastedWhole() {
        val full = "あいう😀".repeat(500)
        val e = start()
        clip.copy(plain(full))
        val shown = preview()!!
        val text = shown.text.toString()
        assertEquals(ClipPolicy.PREVIEW_CODE_POINTS + 1, text.codePointCount(0, text.length))
        assertTrue(text.endsWith("…"))
        assertTrue(shown.contentDescription.length < 120)
        assertTrue(shown.contentDescription.toString().contains(text))
        assertEquals(android.text.TextUtils.TruncateAt.END, shown.ellipsize)
        assertEquals(1, shown.maxLines)
        fun walk(v: View): List<CharSequence> = when (v) {
            is ViewGroup -> (0 until v.childCount).flatMap { walk(v.getChildAt(it)) }
            is TextView -> listOfNotNull(v.text, v.contentDescription)
            else -> emptyList()
        }
        assertTrue(walk(root!!).none { it.length > 200 })
        shown.performClick()
        assertEquals(full, e.text.toString())
    }

    @Test fun exactlyTheLimitGetsNoEllipsisAndBlanksAreNotUsable() {
        assertEquals("a".repeat(80), ClipPolicy.preview("a".repeat(80)))
        assertEquals("a".repeat(80) + "…", ClipPolicy.preview("a".repeat(81)))
        assertEquals("a b", ClipPolicy.preview("  a \n\n b  \n"))
        assertNull(ClipPolicy.preview(" \n\t　 "))
        assertNull(ClipPolicy.preview(" ".repeat(100_000) + "late")) // the scan is bounded
        assertEquals("x…", ClipPolicy.preview("x" + " ".repeat(100_000) + "late"))
        val e = start()
        clip.copy(plain(" \n "))
        assertNull(preview())
        assertEquals(View.GONE, stripBar().visibility)
        assertEquals("", e.text.toString())
    }

    @Test fun newlinesAreOneLineInThePreviewAndExactInThePaste() {
        val full = "一行目\r\n\n二行目\t三 四\u0007五"
        val e = start()
        clip.copy(plain(full))
        assertEquals("一行目 二行目 三 四 五", preview()!!.text.toString())
        preview()!!.performClick()
        assertEquals(full, e.text.toString())
    }

    @Test fun bidiControlsNeverReachThePreview() {
        assertEquals("abcdef", ClipPolicy.preview("abc‮def⁦"))
    }

    @Test fun emojiSequencesAreNeverSplit() {
        val family = "👨‍👩‍👧‍👦" // 7 code points
        val preview = ClipPolicy.preview(family.repeat(30))!!
        assertTrue(preview.endsWith("…"))
        assertEquals(11, preview.dropLast(1).length / family.length)
        assertEquals(family.repeat(11), preview.dropLast(1))
        val flags = "🇯🇵".repeat(60)
        val cut = ClipPolicy.preview(flags)!!.dropLast(1)
        assertEquals(0, cut.length % "🇯🇵".length)
        assertEquals("😀🇯🇵", ClipPolicy.preview("😀🇯🇵"))
        val e = start()
        clip.copy(plain(family))
        assertEquals(family, preview()!!.text.toString())
        preview()!!.performClick()
        assertEquals(family, e.text.toString())
    }

    @Test fun spansNeverReachThePreviewOrTheEditor() {
        val styled = SpannableString("リンク").apply { setSpan(URLSpan("https://example.invalid"), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val e = start()
        clip.copy(plain(styled))
        assertFalse(preview()!!.text is Spanned)
        preview()!!.performClick()
        assertEquals("リンク", e.text.toString())
        assertEquals(0, e.text.getSpans(0, e.text.length, URLSpan::class.java).size)
    }

    @Test fun aSensitiveClipIsNotEvenFetched() {
        start()
        val item = ProbeItem("hunter2")
        clip.reads.clear()
        clip.copy(ClipData(ClipDescription("l", arrayOf("text/plain")).apply {
            extras = PersistableBundle().apply { putBoolean(ClipPolicy.EXTRA_IS_SENSITIVE, true) }
        }, item))
        assertEquals(listOf("description"), clip.reads)
        assertEquals(0, item.textReads)
        assertNull(preview())
        assertEquals(View.GONE, stripBar().visibility)
    }

    @Test fun aSensitiveMarkOfAnotherTypeCountsAndAnExplicitFalseDoesNot() {
        start()
        clip.copy(sensitive("x") { putString(ClipPolicy.EXTRA_IS_SENSITIVE, "true") })
        assertNull(preview())
        clip.copy(sensitive("x") { putBoolean(ClipPolicy.EXTRA_IS_SENSITIVE, false) })
        assertEquals("x", preview()!!.text.toString())
        clip.copy(sensitive("x") { putString("unrelated", "y") })
        assertEquals("x", preview()!!.text.toString())
    }

    @Test fun aClipThatTurnsSensitiveBetweenTheTwoReadsIsRefused() {
        start()
        val item = ProbeItem("hunter2")
        val marked = ClipData(ClipDescription("l", arrayOf("text/plain")).apply {
            extras = PersistableBundle().apply { putBoolean(ClipPolicy.EXTRA_IS_SENSITIVE, true) }
        }, item)
        clip.staleDescription = ClipDescription("l", arrayOf("text/plain"))
        clip.copy(marked)
        assertEquals(0, item.textReads)
        assertNull(preview())
    }

    @Test fun passwordAndRawFieldsNeverTouchTheClipboard() {
        start()
        clip.current = plain("copied")
        for (type in listOf(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_PASSWORD, T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_VARIATION_PASSWORD, T.TYPE_NULL)) {
            clip.reads.clear()
            val e = start(type)
            assertTrue(clip.reads.isEmpty())
            assertTrue(clip.listeners.isEmpty())
            assertNull(preview())
            clip.copy(plain("still copied"))
            assertTrue(clip.reads.isEmpty())
            assertEquals("", e.text.toString())
        }
        // Back in an ordinary field the clip is offered again.
        start()
        assertEquals("still copied", preview()!!.text.toString())
    }

    @Test fun onlyPlainTextOfTheFirstItemIsUsed() {
        val e = start()
        val uri = Uri.parse("content://example.invalid/secret")
        val html = ClipData.newHtmlText("l", "plain part", "<img src=x onerror=y>html part")
        clip.copy(html)
        assertEquals("plain part", preview()!!.text.toString())
        clip.copy(ClipData.newIntent("l", Intent("example.invalid.ACTION")))
        assertNull(preview())
        clip.copy(ClipData.newRawUri("l", uri))
        assertNull(preview())
        // Text with a URI is not a text-only clip; a provider URI is never opened or coerced.
        val mixed = ProbeItem("text", uri)
        clip.copy(ClipData(ClipDescription("l", arrayOf("text/plain")), mixed))
        assertNull(preview())
        val probe = ProbeItem("only this")
        clip.copy(ClipData(ClipDescription("l", arrayOf("text/plain")), probe).apply { addItem(ClipData.Item("not this")) })
        assertEquals("only this", preview()!!.text.toString())
        preview()!!.performClick()
        assertEquals("only this", e.text.toString())
        clip.reads.clear()
        clip.copy(ClipData(ClipDescription("l", arrayOf("image/png")), ProbeItem("x")))
        assertEquals(listOf("description"), clip.reads) // not text: not fetched
        assertNull(preview())
        clip.copy(ClipData(ClipDescription("l", arrayOf("text/plain")), ProbeItem(null)))
        assertNull(preview())
    }

    @Test fun deniedOrFailingReadsAndNullClipsMeanNoPreview() {
        val e = start()
        clip.copy(plain("copied"))
        val chip = preview()!!
        clip.failure = SecurityException("denied")
        clip.copy(plain("other"))
        assertNull(preview())
        assertEquals(View.GONE, stripBar().visibility)
        chip.performClick()
        assertEquals("", e.text.toString())
        clip.failure = IllegalStateException("service died")
        clip.copy(null)
        assertNull(preview())
        clip.failure = null
        clip.copy(null)
        assertNull(preview())
        clip.copy(plain("back"))
        assertEquals("back", preview()!!.text.toString())
    }

    @Test fun aNewClipUpdatesAndAClearedOneHides() {
        start()
        clip.copy(plain("one"))
        assertEquals("one", preview()!!.text.toString())
        clip.copy(plain("two"))
        assertEquals("two", preview()!!.text.toString())
        clip.copy(sensitive("three"))
        assertNull(preview())
        clip.copy(plain("four"))
        clip.copy(null)
        assertNull(preview())
        assertEquals(View.GONE, stripBar().visibility)
    }

    @Test fun hidingUnregistersForgetsThePreviewAndALateCallbackReadsNothing() {
        val e = start()
        clip.copy(plain("private"))
        val late = clip.listeners.single()
        ime.onFinishInputView(false)
        assertTrue(clip.listeners.isEmpty())
        assertNull(preview())
        assertEquals(View.GONE, stripBar().visibility)
        clip.reads.clear()
        late.onPrimaryClipChanged() // already queued when we unregistered
        assertTrue(clip.reads.isEmpty())
        assertNull(preview())

        // Back to the keyboard: a clip taken meanwhile is picked up without any listener having fired.
        clip.current = plain("new")
        ime.onStartInputView(ime.info!!, false)
        assertEquals("new", preview()!!.text.toString())
        assertEquals(1, clip.listeners.size)
        ime.onStartInputView(ime.info!!, true) // a restart does not register twice
        assertEquals(1, clip.listeners.size)
        assertEquals("", e.text.toString())
    }

    @Test fun anEditorChangeClearsThePreviewUntilTheKeyboardStartsOnTheNewEditor() {
        start()
        clip.copy(plain("from the old app"))
        assertNotNull(preview())
        val next = start(visible = false)
        assertNull(preview())
        assertTrue(clip.listeners.isEmpty())
        ime.onStartInputView(ime.info!!, false)
        assertEquals("from the old app", preview()!!.text.toString())
        ime.onFinishInput()
        assertNull(preview())
        assertTrue(clip.listeners.isEmpty())
        assertEquals("", next.text.toString())
    }

    @Test fun destroyingTheServiceUnregisters() {
        start()
        val source = clip
        assertEquals(1, source.listeners.size)
        controller!!.destroy()
        controller = null
        assertTrue(source.listeners.isEmpty())
    }

    @Test fun typingNeverReadsTheClipboard() {
        val e = start()
        clip.copy(plain("copied"))
        clip.reads.clear()
        typeAme(e)
        ime.onAction(Keyboards.BACKSPACE)
        ime.onAction(Keyboards.ENTER)
        ime.onAction(Keyboards.MODE_CYCLE)
        type("hello")
        ime.onAction(Keyboards.SPACE)
        ime.onUpdateSelection(0, 0, 1, 1, -1, -1)
        assertTrue(clip.reads.isEmpty())
    }

    @Test fun thePreviewGivesWayToCandidatesAndComesBack() {
        val e = start()
        clip.copy(plain("copied"))
        typeAme(e)
        assertNull(preview())
        assertTrue("雨" in chips())
        clip.copy(plain("copied later")) // while composing: candidates stay
        assertNull(preview())
        assertTrue("雨" in chips())
        ime.onAction(Keyboards.ENTER)
        assertEquals("copied later", preview()!!.text.toString())
        ime.onAction(Keyboards.MODE_CYCLE)
        assertEquals("copied later", preview()!!.text.toString())
        assertEquals(View.VISIBLE, toggle().visibility) // both on the English screen
        type("ab")
        assertNull(preview())
        assertEquals(View.GONE, toggle().visibility)
    }

    @Test fun aStaleChipPastesTheCurrentClipNeverTheOneItShowed() {
        val e = start()
        clip.copy(plain("old"))
        val chip = preview()!!
        clip.current = plain("new whole text") // switched, report not delivered yet
        chip.performClick()
        assertEquals("new whole text", e.text.toString())

        clip.current = sensitive("hunter2")
        chip.performClick()
        assertEquals("new whole text", e.text.toString())
        assertNull(preview()) // the refusal also corrects the preview

        clip.current = null
        chip.performClick()
        assertEquals("new whole text", e.text.toString())

        clip.copy(plain("visible"))
        val shown = preview()!!
        ime.onFinishInputView(false)
        clip.current = plain("after hide")
        shown.performClick() // a queued tap after the keyboard went away
        assertEquals("new whole text", e.text.toString())
    }

    @Test fun aPasteConfirmsOurOwnCompositionOnceFirst() {
        val e = start()
        clip.copy(plain("[clip]"))
        val chip = preview()!!
        typeAme(e)
        chip.performClick()
        assertEquals("雨[clip]", e.text.toString())
        assertEquals(-1, e.composingStart)
        repeat(30) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) } // no late result lands
        assertEquals("雨[clip]", e.text.toString())

        val f = start()
        val second = preview()!!
        ime.onAction(Keyboards.MODE_CYCLE)
        type("word")
        second.performClick()
        assertEquals("word[clip]", f.text.toString())
        assertEquals(-1, f.composingStart)

        // No usable clip: the composition is left alone.
        val g = start()
        val third = preview()!!
        typeAme(g)
        clip.current = null
        third.performClick()
        assertEquals(0, g.composingStart)
        assertEquals("雨", g.text.toString())
    }

    /**
     * Every file in app storage by path, with its text, once the writes queued so far have landed: setUp's learning
     * files ([Learning]'s own thread) and preferences (`apply()`, written by QueuedWork, flushed here as onPause does).
     */
    private fun storedFiles(): Map<String, String> {
        Learning.awaitWritesForTests()
        Class.forName("android.app.QueuedWork").getMethod("waitToFinish").invoke(null)
        val context = RuntimeEnvironment.getApplication()
        return listOf(context.filesDir, context.noBackupFilesDir, context.dataDir)
            .flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }
            .associate { it.path to String(it.readBytes(), Charsets.UTF_8) }
    }

    @Test fun aPasteReplacesTheSelectionAndLeavesNoTrace() {
        val context = RuntimeEnvironment.getApplication()
        val marker = "clip-marker-7f3a"
        ShadowLog.clear()
        start()
        // Neither the dictionary nor zenz loads here (setUp turns both off), so nothing but the paste could write now.
        assertEquals(ConversionWorker.OssState.OFF, ime.conversionWorker.ossState)
        assertEquals(ConversionWorker.OssState.OFF, ime.conversionWorker.zenzState)
        val before = storedFiles()
        val history = Learning.store(context).historySize

        val e = start(initial = "abcdef", selection = 2..4)
        clip.copy(plain(marker))
        preview()!!.performClick()
        assertEquals("ab" + marker + "f", e.text.toString())
        assertEquals(0, e.reads)

        for (options in listOf(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, 0)) {
            val f = start(imeOptions = options)
            preview()!!.performClick()
            assertEquals(marker, f.text.toString())
        }
        assertEquals(history, Learning.store(context).historySize)
        assertEquals(0, Learning.store(context).snapshot().user.bonus(marker, marker))
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        val after = storedFiles()
        for ((path, text) in after) assertFalse(path, marker in text)
        assertTrue(ShadowLog.getLogs().none { marker in it.msg.orEmpty() })
        // Nothing was written at all: no file added, removed or changed.
        assertEquals(before.keys, after.keys)
        for ((path, text) in after) assertEquals(path, before[path], text)
    }

    @Test fun thePreviewUsesTheCurrentTheme() {
        start()
        clip.copy(plain("copied"))
        assertEquals(KeyboardTheme.WHITE.colors.text, preview()!!.currentTextColor)
        ThemeSettings.setTheme(RuntimeEnvironment.getApplication(), KeyboardTheme.DARK)
        assertEquals(KeyboardTheme.DARK.colors.text, preview()!!.currentTextColor)
    }

    // --- 5. Themes -----------------------------------------------------------------------------------

    @Test fun whiteIsTheDefaultWithItsColorsAndUnknownIdsFallBack() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(KeyboardTheme.WHITE, ThemeSettings.theme(context))
        assertEquals(KeyboardTheme.WHITE, KeyboardTheme.fromId(null))
        assertEquals(KeyboardTheme.WHITE, KeyboardTheme.fromId("sepia"))
        val c = KeyboardTheme.WHITE.colors
        assertEquals(listOf(0xFFE8EAED.toInt(), 0xFFFFFFFF.toInt(), 0xFFD3D7DC.toInt(), 0xFF202124.toInt(), 0xFF555C61.toInt(), 0xFF0D47A1.toInt()),
            listOf(c.background, c.key, c.functionKey, c.text, c.hint, c.primary))
        assertEquals(listOf("white", "gray", "dark", "azuki", "anmitsu"), KeyboardTheme.entries.map { it.id })
    }

    @Test fun onlyTheChoiceIsStored() {
        val context = RuntimeEnvironment.getApplication()
        ThemeSettings.setTheme(context, KeyboardTheme.GRAY)
        assertEquals(KeyboardTheme.GRAY, ThemeSettings.theme(context))
        assertEquals(mapOf("theme" to "gray"), context.getSharedPreferences("astra_theme", android.content.Context.MODE_PRIVATE).all)
    }

    @Test fun everyThemeIsReadable() {
        for (theme in KeyboardTheme.entries) {
            val c = theme.colors
            fun readable(what: String, foreground: Int, background: Int, minimum: Double = 4.5) {
                val ratio = contrast(foreground, background)
                assertTrue("$theme $what: $ratio", ratio >= minimum)
            }
            for ((name, surface) in listOf("key" to c.key, "function" to c.functionKey, "pressed" to c.pressed, "highlight" to c.highlight, "background" to c.background)) {
                readable("text on $name", c.text, surface)
            }
            readable("hint on key", c.hint, c.key)
            readable("preview bubble", c.onAccent, c.accent)
            readable("primary on strip", c.primary, c.background)
            readable("primary on repair row", c.primary, c.repairRow)
            readable("text on repair row", c.text, c.repairRow)
            readable("shown chip", c.onShown, c.shown)
            readable("tab", c.tabText, c.background)
            // Non-text: a key must stand out from what is around it (WCAG 1.4.11 asks 3:1 only of meaningful edges).
            assertTrue("$theme keys", c.key != c.background && c.functionKey != c.background && c.pressed != c.key)
            assertEquals("$theme navigation", contrast(c.background, Color.BLACK) > contrast(c.background, Color.WHITE), c.lightNavigation)
            assertTrue("$theme scrollbar", Color.alpha(c.scrollbar) in 1..254)
        }
    }

    /** Every color a [view] paints with, from a real draw pass. */
    private fun painted(view: View): RecordingCanvas = RecordingCanvas().also { view.draw(it) }

    private fun layoutRoot() {
        root!!.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        root!!.layout(0, 0, root!!.measuredWidth, root!!.measuredHeight)
    }

    @Test fun everySurfaceTakesItsColorsFromTheChosenTheme() {
        val context = RuntimeEnvironment.getApplication()
        for (theme in KeyboardTheme.entries) {
            ThemeSettings.setTheme(context, theme)
            val c = theme.colors
            val e = start()
            clip.copy(plain("copied"))
            layoutRoot()
            assertEquals(c.background, (root!!.background as ColorDrawable).color) // also behind the navigation bar
            assertEquals(c.background, (ime.window.window!!.navigationBarColor))
            assertEquals(c.text, preview()!!.currentTextColor)

            val keyPass = painted(keys())
            assertEquals(c.background, keyPass.fills.first())
            assertTrue(keyPass.fills.drop(1).toSet().minus(setOf(c.key, c.functionKey, c.shadow)).isEmpty())
            assertTrue(keyPass.texts.map { it.second }.toSet().minus(setOf(c.text, c.hint)).isEmpty())
            assertTrue(c.hint in keyPass.texts.map { it.second })

            typeAme(e)
            val row = chipRow()
            val first = row.getChildAt(0) as TextView
            assertEquals(c.primary, first.currentTextColor)
            val shown = (0 until row.childCount).map { row.getChildAt(it) as TextView }.single { it.text == "雨" && it.tag != TAG_SEGMENT }
            assertEquals(c.onShown, shown.currentTextColor)
            assertEquals(c.shown, (shown.background as ColorDrawable).color)
            // Live conversion shows the segment buttons at once, blue until one is selected.
            assertEquals(c.primary, segments().single().currentTextColor)
            segments().single().performClick()
            val selected = segments().single()
            assertEquals(c.onShown, selected.currentTextColor)
            assertEquals(c.shown, (selected.background as ColorDrawable).color)
            ime.onAction(Keyboards.ENTER)

            ime.onAction(Keyboards.TO_EMOJI)
            layoutRoot()
            val box = root!!.findViewWithTag<LinearLayout>(TAG_PALETTE)
            assertEquals(c.background, (box.background as ColorDrawable).color)
            val grid = box.getChildAt(1) as PaletteView
            val gridPass = painted(grid)
            assertEquals(c.background, gridPass.fills.first())
            assertTrue(gridPass.texts.isNotEmpty() && gridPass.texts.all { it.second == c.text })
            assertTrue(c.scrollbar in gridPass.fills)
            val tabs = root!!.findViewWithTag<LinearLayout>(TAG_PALETTE_TABS)
            // 履歴 (empty) leftmost, the first category selected.
            assertEquals(c.onShown, (tabs.getChildAt(1) as TextView).currentTextColor)
            assertEquals(c.shown, ((tabs.getChildAt(1) as TextView).background as ColorDrawable).color)
            assertEquals(c.tabText, (tabs.getChildAt(0) as TextView).currentTextColor)
            assertEquals(c.tabText, (tabs.getChildAt(2) as TextView).currentTextColor)
            ime.onAction(Keyboards.TO_TEXT)
            ime.onFinishInputView(false)
        }
    }

    @Test fun pressAndPreviewBubbleUseTheTheme() {
        for (theme in KeyboardTheme.entries) {
            val kb = arrowKeyboard()
            kb.colors = theme.colors
            val ka = kb.keyRows[0][1]
            press(kb, 0, ACTION_DOWN, ka.rect.centerX() to ka.rect.centerY())
            press(kb, 10, ACTION_MOVE, ka.rect.centerX() to ka.rect.centerY() - 40f)
            val pass = painted(kb)
            assertTrue(theme.colors.pressed in pass.fills)
            assertTrue(theme.colors.accent in pass.fills)
            assertTrue(theme.colors.onAccent in pass.texts.map { it.second })
        }
    }

    @Test fun aThemeChangeRepaintsAnOpenKeyboardAndAppliesAtTheNextShowOtherwise() {
        val context = RuntimeEnvironment.getApplication()
        start()
        ThemeSettings.setTheme(context, KeyboardTheme.DARK) // the setup screen over the open keyboard
        assertEquals(KeyboardTheme.DARK.colors, keys().colors)
        assertEquals(KeyboardTheme.DARK.colors.background, (root!!.background as ColorDrawable).color)
        ime.onFinishInputView(false)
        ThemeSettings.setTheme(context, KeyboardTheme.GRAY)
        ime.onStartInputView(ime.info!!, false)
        assertEquals(KeyboardTheme.GRAY.colors, keys().colors)
    }

    @Test fun aFreshInputViewStartsInTheStoredThemeAndKeepsItsGeometry() {
        val context = RuntimeEnvironment.getApplication()
        start()
        layoutRoot()
        val white = keys().keyRows.flatten().map { RectF(it.rect) }
        ThemeSettings.setTheme(context, KeyboardTheme.DARK)
        root = ime.onCreateInputView() as ViewGroup
        start()
        layoutRoot()
        assertEquals(KeyboardTheme.DARK.colors, keys().colors)
        assertEquals(white, keys().keyRows.flatten().map { RectF(it.rect) })
    }

    @Test @Config(sdk = [28]) fun navigationButtonsFollowTheThemeBeforeApi30() {
        val context = RuntimeEnvironment.getApplication()
        start()
        val flag = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val decor = ime.window.window!!.decorView
        assertEquals(flag, decor.systemUiVisibility and flag)
        ThemeSettings.setTheme(context, KeyboardTheme.DARK)
        assertEquals(0, decor.systemUiVisibility and flag)
        ThemeSettings.setTheme(context, KeyboardTheme.GRAY)
        assertEquals(flag, decor.systemUiVisibility and flag)
    }

    @Test fun setupOffersAllThemesWithSwatchesAndStoresTheChoice() {
        val context = RuntimeEnvironment.getApplication()
        ThemeSettings.setTheme(context, KeyboardTheme.GRAY)
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        fun walk(v: View): List<RadioButton> = when (v) {
            is RadioButton -> listOf(v)
            is ViewGroup -> (0 until v.childCount).flatMap { walk(v.getChildAt(it)) }
            else -> emptyList()
        }
        val buttons = walk(activity.window.decorView)
        assertEquals(listOf("ホワイト（標準）", "グレー", "ダーク", "AZUKI（小豆）", "anmitsu（あんみつ）"), buttons.map { it.text.toString() })
        assertEquals(listOf(false, true, false, false, false), buttons.map { it.isChecked })
        assertTrue(buttons.all { it.compoundDrawablesRelative[2] != null })
        buttons[2].performClick()
        assertEquals(KeyboardTheme.DARK, ThemeSettings.theme(context))
        assertEquals(listOf(false, false, true, false, false), buttons.map { it.isChecked })
    }

    // --- 6. Vertical flicks on the arrows ------------------------------------------------------------

    private val log = ArrayList<String>()

    private fun arrowKeyboard() = KeyboardView(RuntimeEnvironment.getApplication()).apply {
        setRows(Keyboards.kana("⏎"))
        listener = object : KeyboardView.Listener {
            override fun onFlick(key: FlickKey, direction: FlickDirection) { log += "$key:$direction" }
            override fun onText(text: String) { log += "text:$text" }
            override fun onAction(action: Int) { log += "action:$action" }
        }
        measure(MeasureSpec.makeMeasureSpec(575, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(224, MeasureSpec.EXACTLY))
        layout(0, 0, 575, 224)
    }

    private fun press(view: View, time: Long, action: Int, vararg p: Pair<Float, Float>) {
        val props = Array(p.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(p.size) { i -> MotionEvent.PointerCoords().apply { x = p[i].first; y = p[i].second; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(0L, time, action, p.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun KeyboardView.arrow(label: String) = keyRows.flatten().single { it.label == label }.rect

    private fun flick(kb: KeyboardView, x: Float, y: Float, dx: Float, dy: Float) {
        press(kb, 0, ACTION_DOWN, x to y)
        assertTrue(log.toString(), log.none { it.startsWith("action:") && it.split(':')[1].toInt() in Keyboards.CURSOR_LEFT..Keyboards.CURSOR_RIGHT })
        press(kb, 10, ACTION_MOVE, x + dx to y + dy)
        press(kb, 20, ACTION_UP, x + dx to y + dy)
    }

    /** Attached to a window: only then do delayed View callbacks (the repeat) run. */
    private fun attached(): KeyboardView = arrowKeyboard().also {
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(it)
        idle(100)
    }

    @Test fun everyTwelveKeyScreenHasBothArrowsWithVerticalFlicksAndQwertyHasNone() {
        val screens = listOf(Keyboards.kana("⏎"), Keyboards.kana("⏎", composing = true), Keyboards.english12(Shift.OFF, "⏎"), Keyboards.numeric12("⏎"))
        for (rows in screens) {
            val arrows = rows.flatten().filter { it.action == Keyboards.CURSOR_LEFT || it.action == Keyboards.CURSOR_RIGHT }
            assertEquals(listOf("◀", "▶"), arrows.map { it.label })
            for (key in arrows) {
                assertEquals(Keyboards.CURSOR_UP, key.upAction)
                assertEquals(Keyboards.CURSOR_DOWN, key.downAction)
                assertTrue(key.isCursorPad)
            }
            assertTrue(rows.flatten().single { it.action == Keyboards.BACKSPACE }.let { it.repeat && !it.isCursorPad })
        }
        assertTrue(Keyboards.letters(Shift.OFF, "⏎").flatten().none { it.isCursorPad })
    }

    @Test fun aTapMovesOneStepHorizontallyOnRelease() {
        val kb = arrowKeyboard()
        for ((label, action) in listOf("◀" to Keyboards.CURSOR_LEFT, "▶" to Keyboards.CURSOR_RIGHT)) {
            log.clear()
            val r = kb.arrow(label)
            press(kb, 0, ACTION_DOWN, r.centerX() to r.centerY())
            assertTrue(log.isEmpty()) // nothing before the gesture resolves
            press(kb, 10, ACTION_MOVE, r.centerX() + 5f to r.centerY() + 5f)
            assertTrue(log.isEmpty())
            press(kb, 20, ACTION_UP, r.centerX() + 5f to r.centerY() + 5f)
            assertEquals(listOf("action:$action"), log)
        }
    }


    @Test fun aVerticalFlickOnEitherArrowSendsOnlyTheVerticalAction() {
        val kb = arrowKeyboard()
        for (label in listOf("◀", "▶")) {
            log.clear()
            val r = kb.arrow(label)
            flick(kb, r.centerX(), r.centerY(), 0f, -30f)
            flick(kb, r.centerX(), r.centerY(), 3f, 30f)
            assertEquals(listOf("action:${Keyboards.CURSOR_UP}", "action:${Keyboards.CURSOR_DOWN}"), log)
        }
    }

    @Test fun aFlickReleasedPastTheKeyEdgeStillCounts() {
        val kb = arrowKeyboard()
        val r = kb.arrow("▶")
        flick(kb, r.centerX(), r.centerY(), 0f, -r.height())
        assertEquals(listOf("action:${Keyboards.CURSOR_UP}"), log)
    }

    @Test fun cancelAndASecondFingerSendNothing() {
        val kb = arrowKeyboard()
        val r = kb.arrow("◀")
        val ka = kb.keyRows[0][1].rect
        press(kb, 0, ACTION_DOWN, r.centerX() to r.centerY())
        press(kb, 10, ACTION_MOVE, r.centerX() to r.centerY() - 30f)
        press(kb, 20, ACTION_CANCEL, r.centerX() to r.centerY() - 30f)
        assertTrue(log.isEmpty())

        press(kb, 30, ACTION_DOWN, r.centerX() to r.centerY())
        press(kb, 40, ACTION_MOVE, r.centerX() to r.centerY() - 30f)
        press(kb, 50, ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), r.centerX() to r.centerY() - 30f, ka.centerX() to ka.centerY())
        assertTrue(log.isEmpty()) // the unresolved arrow is dropped, not completed
        press(kb, 60, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), r.centerX() to r.centerY() - 30f, ka.centerX() to ka.centerY())
        press(kb, 70, ACTION_UP, r.centerX() to r.centerY() - 30f) // the arrow's finger lifts last: already handed off
        assertEquals(1, log.size) // only the second finger's own key
        assertTrue(log.single(), log.single().startsWith("A:") || log.single().startsWith("KA:"))
    }

    @Test fun holdingStillRepeatsHorizontallyAndTheReleaseAddsNothing() {
        val kb = attached()
        val r = kb.arrow("▶")
        press(kb, 0, ACTION_DOWN, r.centerX() to r.centerY())
        idle(390)
        assertTrue(log.isEmpty()) // the delay passes without a move
        idle(10 + 60 * 2)
        assertEquals(List(3) { "action:${Keyboards.CURSOR_RIGHT}" }, log)
        press(kb, 500, ACTION_MOVE, r.centerX() to r.centerY() - 40f) // too late: stays horizontal
        press(kb, 510, ACTION_UP, r.centerX() to r.centerY() - 40f)
        val held = log.size
        assertTrue(log.all { it == "action:${Keyboards.CURSOR_RIGHT}" })
        idle(1000)
        assertEquals(held, log.size)
    }

    @Test fun aSlowVerticalFlickNeverStartsTheRepeat() {
        val kb = attached()
        val r = kb.arrow("◀")
        press(kb, 0, ACTION_DOWN, r.centerX() to r.centerY())
        press(kb, 100, ACTION_MOVE, r.centerX() to r.centerY() - 12f) // moving, under the flick threshold
        idle(800)
        assertTrue(log.isEmpty())
        press(kb, 900, ACTION_MOVE, r.centerX() to r.centerY() - 30f)
        idle(800)
        assertTrue(log.isEmpty())
        press(kb, 1800, ACTION_UP, r.centerX() to r.centerY() - 30f)
        assertEquals(listOf("action:${Keyboards.CURSOR_UP}"), log)
    }

    @Test fun oncePastTheThresholdNothingRepeatsEvenBackAtTheCenter() {
        val kb = attached()
        val r = kb.arrow("◀")
        press(kb, 0, ACTION_DOWN, r.centerX() to r.centerY())
        press(kb, 50, ACTION_MOVE, r.centerX() to r.centerY() + 30f)
        press(kb, 100, ACTION_MOVE, r.centerX() to r.centerY())
        idle(1000)
        assertTrue(log.isEmpty())
        press(kb, 1200, ACTION_UP, r.centerX() to r.centerY())
        assertEquals(listOf("action:${Keyboards.CURSOR_LEFT}"), log) // the release position decides, as on the kana keys
    }

    @Test fun repeatStopsOnCancelASecondFingerAndDetach() {
        val kb = attached()
        val r = kb.arrow("▶")
        val ka = kb.keyRows[0][1].rect
        press(kb, 0, ACTION_DOWN, r.centerX() to r.centerY())
        idle(460)
        press(kb, 500, ACTION_CANCEL, r.centerX() to r.centerY())
        val afterCancel = log.size
        idle(1000)
        assertEquals(afterCancel, log.size)

        press(kb, 1600, ACTION_DOWN, r.centerX() to r.centerY())
        idle(460)
        press(kb, 2100, ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), r.centerX() to r.centerY(), ka.centerX() to ka.centerY())
        val afterFinger = log.size
        idle(1000)
        assertEquals(afterFinger, log.size)
        press(kb, 3200, ACTION_CANCEL, r.centerX() to r.centerY(), ka.centerX() to ka.centerY())

        press(kb, 3300, ACTION_DOWN, r.centerX() to r.centerY())
        (kb.parent as ViewGroup).removeView(kb)
        val beforeDetachRepeat = log.size
        idle(1000)
        assertEquals(beforeDetachRepeat, log.size)
    }

    @Test fun backspaceStillActsOnPressAndKanaFlicksAreUnchanged() {
        val kb = attached()
        val del = kb.keyRows.flatten().single { it.action == Keyboards.BACKSPACE }.rect
        press(kb, 0, ACTION_DOWN, del.centerX() to del.centerY())
        assertEquals(listOf("action:${Keyboards.BACKSPACE}"), log) // immediately
        idle(400 + 60)
        assertEquals(3, log.size)
        press(kb, 500, ACTION_MOVE, del.centerX() to del.centerY() - 40f) // a vertical move does not stop it
        idle(60)
        assertEquals(4, log.size)
        press(kb, 600, ACTION_UP, del.centerX() to del.centerY() - 40f)
        log.clear()
        val ka = kb.keyRows[0][2].rect
        press(kb, 700, ACTION_DOWN, ka.centerX() to ka.centerY())
        press(kb, 710, ACTION_MOVE, ka.centerX() to ka.centerY() - 40f)
        press(kb, 720, ACTION_UP, ka.centerX() to ka.centerY() - 40f)
        assertEquals(1, log.size)
        assertTrue(log.single(), log.single().endsWith(":UP"))
    }

    @Test fun theArrowsShowTheirFlickHintsAndABubbleWhileFlicked() {
        val kb = arrowKeyboard()
        val idle = painted(kb)
        assertEquals(2, idle.texts.count { it.first == "↑" })
        assertEquals(2, idle.texts.count { it.first == "↓" })
        assertEquals(2, idle.texts.count { it.first == "←" })
        assertEquals(2, idle.texts.count { it.first == "→" })
        assertTrue(idle.texts.filter { it.first == "↑" }.all { it.second == kb.colors.hint })
        val r = kb.arrow("▶")
        press(kb, 0, ACTION_DOWN, r.centerX() to r.centerY())
        press(kb, 10, ACTION_MOVE, r.centerX() to r.centerY() - 30f)
        assertTrue(painted(kb).texts.any { it.first == "↑" && it.second == kb.colors.onAccent })
    }

    /** 0.1.34 (was: confirm, then one key event): while composed ▲ ▼ go to the composition's start and end. */
    @Test fun verticalActionsMoveTheComposingCaretAndSendAKeyEventOnlyWithNothingComposed() {
        val e = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE)
        typeAme(e)
        ime.onAction(Keyboards.CURSOR_UP)
        assertEquals("雨", e.text.toString())
        assertEquals(0, e.composingStart) // nothing committed
        assertEquals(0, Selection.getSelectionStart(e.text)) // the caret before 雨
        assertTrue(e.keys.isEmpty())
        ime.onAction(Keyboards.CURSOR_DOWN)
        assertEquals(1, Selection.getSelectionStart(e.text))
        repeat(30) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals("雨", e.text.toString())
        ime.onAction(Keyboards.ENTER)

        ime.onAction(Keyboards.MODE_CYCLE)
        type("word")
        ime.onAction(Keyboards.CURSOR_UP)
        assertEquals("雨word", e.text.toString())
        assertEquals(1, e.composingStart)
        assertEquals(1, Selection.getSelectionStart(e.text))
        ime.onAction(Keyboards.CURSOR_DOWN)
        assertEquals(5, Selection.getSelectionStart(e.text))
        assertTrue(e.keys.isEmpty())
        ime.onAction(Keyboards.ENTER)
        ime.onAction(Keyboards.CURSOR_DOWN) // nothing composed: only the key event
        assertEquals("雨word", e.text.toString())
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN), e.keys)
        assertEquals(0, e.reads)
    }

    @Test fun verticalActionsAreNotCandidateMovesAndChangeNothingElseWithASegmentSelected() {
        val e = start()
        typeAme(e)
        ime.onAction(Keyboards.CONVERT)
        assertEquals("飴", e.text.toString())
        segments().single().performClick()
        val shown = e.text.toString()
        ime.onAction(Keyboards.CURSOR_UP)
        ime.onAction(Keyboards.CURSOR_DOWN)
        assertEquals(shown, e.text.toString())
        assertEquals(0, e.composingStart) // nothing committed
        assertTrue(selecting())
        assertTrue(e.keys.isEmpty())
        ime.onAction(Keyboards.CURSOR_LEFT) // the horizontal keys resize the segment
        assertTrue(selecting())
        assertEquals(2, segments().size)
        assertTrue(e.keys.isEmpty())
    }

    @Test fun rawEditorsGetPlainKeyEventsAndNoActiveEditorNothing() {
        val e = start(T.TYPE_NULL)
        ime.onAction(Keyboards.CURSOR_UP)
        ime.onAction(Keyboards.CURSOR_DOWN)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN), e.keys)
        ime.onFinishInput()
        ime.onAction(Keyboards.CURSOR_UP)
        assertEquals(2, e.keys.size)
        assertEquals(0, e.reads)
    }

    @Test fun aRealGestureOnTheServiceKeyboardGoesThroughToTheEditor() {
        val e = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE)
        layoutRoot()
        val kb = keys()
        val r = kb.arrow("▶")
        flick(kb, r.centerX(), r.centerY(), 0f, 30f)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN), e.keys)
        press(kb, 0, ACTION_DOWN, r.centerX() to r.centerY())
        press(kb, 10, ACTION_UP, r.centerX() to r.centerY())
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT), e.keys)
    }
}
