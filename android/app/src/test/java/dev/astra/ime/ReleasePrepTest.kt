package dev.astra.ime

import android.content.ClipData
import android.content.ClipDescription
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType as T
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import dev.astra.ime.core.FlickDirection
import dev.astra.ime.core.FlickKey
import dev.astra.ime.core.FlickLayout
import java.io.File
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** A synthetic clip with the copy time the system would give it (the setter is hidden API). */
private fun copied(text: CharSequence, at: Long): ClipData = plain(text).apply {
    ReflectionHelpers.callInstanceMethod<Unit>(description, "setTimestamp", ClassParameter.from(Long::class.javaPrimitiveType, at))
}

/** 0.1.17: paste dismissal, four-way arrows, the single-line guard and the release audit's fixes. mdpi: 1dp = 1px. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class ReleasePrepTest {
    private var controller: ServiceController<TestIme>? = null
    private val ime: TestIme get() = controller!!.get()
    private val clip: FakeClipSource get() = ime.clip
    private var root: ViewGroup? = null

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

    @After fun tearDown() { controller?.destroy() }

    private fun launch() {
        controller = Robolectric.buildService(TestIme::class.java).create()
        ime.ic = FakeEditor(View(ime), "", 0, 0)
        ime.info = EditorInfo().apply { inputType = T.TYPE_CLASS_TEXT }
        ime.onStartInput(ime.info!!, false)
        root = ime.onCreateInputView() as ViewGroup
    }

    private fun start(type: Int = T.TYPE_CLASS_TEXT, initial: String = ""): FakeEditor {
        if (controller == null) launch()
        val editor = FakeEditor(View(ime), initial, initial.length, initial.length)
        val info = EditorInfo().apply { inputType = type; initialSelStart = initial.length; initialSelEnd = initial.length }
        ime.ic = editor
        ime.info = info
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
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
    private fun preview(): TextView? = root!!.findViewWithTag(TAG_CLIP_PREVIEW)
    private fun stripBar() = root!!.findViewWithTag<View>(TAG_STRIP_ROW)
    private fun keys() = (0 until root!!.childCount).map { root!!.getChildAt(it) }.filterIsInstance<KeyboardView>().single()

    private fun typeAme(e: FakeEditor) {
        pumpUntil("converter") { ime.converterReady }
        ime.onFlick(FlickKey.A, FlickDirection.CENTER)
        ime.onFlick(FlickKey.MA, FlickDirection.entries.first { FlickLayout.kana(FlickKey.MA, it) == "め" })
        pumpUntil("conversion") { e.text.toString().endsWith("雨") }
    }

    // --- 1. A paste hides the preview and nothing else -----------------------------------------------

    @Test fun aPasteHidesThePreviewAndLeavesTheClipboardAlone() {
        val e = start()
        val copy = copied("copied text", 1000)
        clip.copy(copy)
        preview()!!.performClick()
        assertEquals("copied text", e.text.toString())
        assertNull(preview())
        assertEquals(View.GONE, stripBar().visibility)
        assertTrue(clip.current === copy)
    }

    @Test fun nothingInTheAppCanWriteTheSystemClipboard() {
        val sources = File("src/main/java/dev/astra/ime").listFiles { f -> f.extension == "kt" }!!
        assertTrue(sources.size > 10)
        for (source in sources) {
            val code = source.readText()
            for (write in listOf("setPrimaryClip", "clearPrimaryClip", "ClipData.newPlainText")) {
                assertFalse("${source.name}: $write", write in code)
            }
        }
    }

    @Test fun theUsedClipStaysHiddenAcrossHideShowAndEditorChangesWithoutBeingReadAgain() {
        val e = start()
        clip.copy(copied("once", 1000))
        preview()!!.performClick()
        clip.reads.clear()

        ime.onFinishInputView(false)
        ime.onStartInputView(ime.info!!, false)
        assertNull(preview())
        ime.onFinishInput()
        val next = start()
        assertNull(preview())
        ime.onStartInput(ime.info!!, true) // restart of the same editor
        ime.onStartInputView(ime.info!!, true)
        assertNull(preview())
        assertEquals(setOf("description"), clip.reads.toSet()) // recognized from metadata; the text is not fetched
        assertEquals("once", e.text.toString())
        assertEquals("", next.text.toString())
    }

    @Test fun theUsedClipStaysHiddenWhenTheServiceIsRecreatedInTheSameProcess() {
        start()
        clip.copy(copied("once", 1000))
        preview()!!.performClick()
        controller!!.destroy()
        controller = null
        start()
        clip.current = copied("once", 1000)
        ime.onStartInputView(ime.info!!, false)
        assertNull(preview())
    }

    @Test fun aNewCopyOfIdenticalTextShowsAgain() {
        val e = start()
        clip.copy(copied("same", 1000))
        preview()!!.performClick()
        assertNull(preview())

        clip.copy(copied("same", 2000))
        assertEquals("same", preview()!!.text.toString())
        preview()!!.performClick()
        assertEquals("samesame", e.text.toString())
        assertNull(preview())

        // Copied in another app while the keyboard was away: no report reaches us, the copy time still differs.
        ime.onFinishInputView(false)
        clip.current = copied("same", 3000)
        ime.onStartInputView(ime.info!!, false)
        assertEquals("same", preview()!!.text.toString())
    }

    @Test fun lateAndRepeatedReportsOfTheUsedClipDoNotBringItBack() {
        start()
        clip.copy(copied("once", 1000))
        preview()!!.performClick()
        val report = clip.listeners.single()
        clip.reads.clear()
        repeat(3) { report.onPrimaryClipChanged() }
        assertNull(preview())
        assertEquals(setOf("description"), clip.reads.toSet())
    }

    @Test fun aCopyAfterTheTapButBeforeItsReportIsWhatWasPastedSoItIsWhatGetsHidden() {
        val e = start()
        clip.copy(copied("shown", 1000))
        val chip = preview()!!
        clip.current = copied("newer", 2000) // copied, report still on its way
        chip.performClick()
        assertEquals("newer", e.text.toString())
        assertNull(preview())
        clip.listeners.single().onPrimaryClipChanged() // its report arrives
        assertNull(preview())
        clip.copy(copied("next", 3000))
        assertEquals("next", preview()!!.text.toString())
    }

    @Test fun aCopyDuringTheCommitIsNotTakenForTheUsedClip() {
        val e = start()
        clip.copy(copied("pasted", 1000))
        e.duringCommit = { e.duringCommit = null; clip.current = copied("arrived meanwhile", 2000) }
        preview()!!.performClick()
        assertEquals("pasted", e.text.toString())
        assertEquals("arrived meanwhile", preview()!!.text.toString())
    }

    @Test fun aRefusedCommitChangesNothingAndCanBeRetried() {
        val e = start()
        clip.copy(copied("text", 1000))
        e.acceptsCommits = false
        preview()!!.performClick()
        assertEquals("", e.text.toString())
        assertEquals("text", preview()!!.text.toString())
        ime.onFinishInputView(false)
        ime.onStartInputView(ime.info!!, false)
        assertEquals("text", preview()!!.text.toString())

        e.acceptsCommits = true
        preview()!!.performClick()
        assertEquals("text", e.text.toString())
        assertNull(preview())
    }

    @Test fun noActiveInputConnectionKeepsThePreview() {
        val e = start()
        clip.copy(copied("text", 1000))
        ime.ic = null
        preview()!!.performClick()
        assertEquals("text", preview()!!.text.toString())
        ime.ic = e
        preview()!!.performClick()
        assertEquals("text", e.text.toString())
    }

    @Test fun aDuplicateTapOnTheOldChipPastesOnce() {
        val e = start()
        clip.copy(copied("once", 1000))
        val chip = preview()!!
        chip.performClick()
        chip.performClick()
        assertEquals("once", e.text.toString())
    }

    @Test fun aClipTurnedSensitiveAfterItWasShownIsNeitherPastedNorMarked() {
        val e = start()
        clip.copy(copied("shown", 1000))
        val chip = preview()!!
        clip.current = copied("secret", 2000).apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipPolicy.EXTRA_IS_SENSITIVE, true) }
        }
        chip.performClick()
        assertEquals("", e.text.toString())
        assertNull(preview())
        clip.copy(copied("shown", 1000)) // the earlier clip was never used, so it may show
        assertEquals("shown", preview()!!.text.toString())
    }

    @Test fun aClipWithoutACopyTimeStaysHiddenOnlyUntilTheNextReportOrHide() {
        val e = start()
        clip.copy(plain("no time"))
        val chip = preview()!!
        chip.performClick()
        chip.performClick()
        assertEquals("no time", e.text.toString())
        assertNull(preview())
        clip.listeners.single().onPrimaryClipChanged() // cannot be told from a new copy: shown rather than lost
        assertNotNull(preview())

        preview()!!.performClick()
        assertNull(preview())
        ime.onFinishInputView(false)
        ime.onStartInputView(ime.info!!, false)
        assertNotNull(preview()) // a copy made meanwhile would not have been reported
    }

    @Test fun aPasteConfirmsTheCompositionThenHides() {
        val e = start()
        clip.copy(copied("[clip]", 1000))
        val chip = preview()!!
        typeAme(e)
        chip.performClick()
        assertEquals("雨[clip]", e.text.toString())
        assertNull(preview())
        assertEquals(0, e.reads)
    }

    // --- 2. Four-way arrows ------------------------------------------------------------------------

    private val log = ArrayList<Int>()

    private fun arrows() = KeyboardView(RuntimeEnvironment.getApplication()).apply {
        setRows(Keyboards.kana("⏎"))
        listener = object : KeyboardView.Listener {
            override fun onFlick(key: FlickKey, direction: FlickDirection) = Unit
            override fun onText(text: String) = Unit
            override fun onAction(action: Int) { log += action }
        }
        measure(MeasureSpec.makeMeasureSpec(575, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(224, MeasureSpec.EXACTLY))
        layout(0, 0, 575, 224)
    }

    private fun attached(): KeyboardView = arrows().also {
        Robolectric.buildActivity(android.app.Activity::class.java).setup().get().setContentView(it)
        idle(100)
    }

    private fun press(view: View, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun KeyboardView.key(label: String) = keyRows.flatten().single { it.label == label }.rect

    @Test fun bothArrowsMoveInAllFourDirectionsExactlyOnceAndATapKeepsTheirOwn() {
        val flicks = listOf(
            Triple(0f, -30f, Keyboards.CURSOR_UP), Triple(0f, 30f, Keyboards.CURSOR_DOWN),
            Triple(-30f, 0f, Keyboards.CURSOR_LEFT), Triple(30f, 0f, Keyboards.CURSOR_RIGHT),
        )
        val kb = arrows()
        for ((label, own) in listOf("◀" to Keyboards.CURSOR_LEFT, "▶" to Keyboards.CURSOR_RIGHT)) {
            val r = kb.key(label)
            for ((dx, dy, expected) in flicks) {
                log.clear()
                press(kb, 0, ACTION_DOWN, r.centerX(), r.centerY())
                press(kb, 10, ACTION_MOVE, r.centerX() + dx, r.centerY() + dy)
                assertTrue(log.isEmpty()) // no initial step before it resolves
                press(kb, 20, ACTION_UP, r.centerX() + dx, r.centerY() + dy)
                assertEquals(listOf(expected), log)
            }
            log.clear()
            press(kb, 30, ACTION_DOWN, r.centerX(), r.centerY())
            press(kb, 40, ACTION_UP, r.centerX(), r.centerY())
            assertEquals(listOf(own), log)
        }
    }

    @Test fun theThresholdAndTheDominantAxisAreThoseOfEveryOtherFlick() {
        val kb = arrows()
        val r = kb.key("◀")
        fun flick(dx: Float, dy: Float): Int {
            log.clear()
            press(kb, 0, ACTION_DOWN, r.centerX(), r.centerY())
            press(kb, 10, ACTION_UP, r.centerX() + dx, r.centerY() + dy)
            return log.single()
        }
        assertEquals(Keyboards.CURSOR_LEFT, flick(17f, 0f)) // under 18dp: a tap, even toward the other side
        assertEquals(Keyboards.CURSOR_RIGHT, flick(18f, 0f))
        assertEquals(Keyboards.CURSOR_DOWN, flick(20f, 25f))
        assertEquals(Keyboards.CURSOR_RIGHT, flick(25f, -20f))
        assertEquals(Keyboards.CURSOR_RIGHT, flick(25f, 25f)) // an exact diagonal is horizontal, as on the kana keys
    }

    @Test fun holdingRepeatsTheKeysOwnDirectionFrom400MsAndTheReleaseAddsNothing() {
        val kb = attached()
        val r = kb.key("◀")
        press(kb, 0, ACTION_DOWN, r.centerX(), r.centerY())
        idle(390)
        assertTrue(log.isEmpty())
        idle(10 + 60)
        assertEquals(List(2) { Keyboards.CURSOR_LEFT }, log)
        press(kb, 500, ACTION_MOVE, r.centerX() + 40f, r.centerY()) // locked: moving the other way changes nothing
        idle(60)
        assertEquals(List(3) { Keyboards.CURSOR_LEFT }, log)
        press(kb, 600, ACTION_UP, r.centerX() + 40f, r.centerY())
        idle(500)
        assertEquals(List(3) { Keyboards.CURSOR_LEFT }, log)
    }

    @Test fun aSlowFlickAgainstTheKeysDirectionSendsOnlyThatDirection() {
        val kb = attached()
        val r = kb.key("◀")
        press(kb, 0, ACTION_DOWN, r.centerX(), r.centerY())
        press(kb, 100, ACTION_MOVE, r.centerX() + 12f, r.centerY()) // under the threshold, but moving
        idle(800)
        assertTrue(log.isEmpty())
        press(kb, 900, ACTION_MOVE, r.centerX() + 30f, r.centerY())
        idle(800)
        assertTrue(log.isEmpty())
        press(kb, 1800, ACTION_UP, r.centerX() + 30f, r.centerY())
        assertEquals(listOf(Keyboards.CURSOR_RIGHT), log)
    }

    @Test fun aHoldThatDriftsAlongTheKeysOwnDirectionStillRepeats() {
        val kb = attached()
        val r = kb.key("▶")
        press(kb, 0, ACTION_DOWN, r.centerX(), r.centerY())
        press(kb, 100, ACTION_MOVE, r.centerX() + 30f, r.centerY())
        idle(460)
        assertEquals(List(2) { Keyboards.CURSOR_RIGHT }, log)
        press(kb, 600, ACTION_UP, r.centerX() + 30f, r.centerY())
        assertEquals(2, log.size)
    }

    @Test fun theBubbleShowsHorizontalFlicksToo() {
        val kb = arrows()
        val r = kb.key("▶")
        val texts = ArrayList<String>()
        val canvas = object : android.graphics.Canvas() {
            override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
                if (paint.color == kb.colors.onAccent) texts += text
            }
        }
        press(kb, 0, ACTION_DOWN, r.centerX(), r.centerY())
        press(kb, 10, ACTION_MOVE, r.centerX() - 30f, r.centerY())
        kb.draw(canvas)
        assertEquals(listOf("←"), texts)
    }

    @Test fun withASegmentSelectedAHorizontalFlickResizesItAndAVerticalOneDoesNothing() {
        val e = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE)
        typeAme(e)
        fun segments(): List<TextView> {
            val row = (root!!.findViewWithTag<ViewGroup>(TAG_STRIP_ROW).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
            return (0 until row.childCount).map { row.getChildAt(it) as TextView }.filter { it.tag == TAG_SEGMENT }
        }
        val expand = (root!!.findViewWithTag<ViewGroup>(TAG_STRIP_ROW)).getChildAt(1) as TextView
        expand.performClick() // ▼ shows the segment buttons
        segments().single().performClick()
        expand.performClick() // the panel kept open for the segment is closed again
        root!!.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        root!!.layout(0, 0, root!!.measuredWidth, root!!.measuredHeight)
        val kb = keys()
        val r = kb.key("▶")
        fun flick(dx: Float, dy: Float) {
            press(kb, 0, ACTION_DOWN, r.centerX(), r.centerY())
            press(kb, 10, ACTION_UP, r.centerX() + dx, r.centerY() + dy)
        }
        assertEquals(1, segments().size)
        flick(-30f, 0f) // ◀ by flick: the segment gives its last kana to a new one after it
        assertEquals(2, segments().size)
        assertTrue(segments()[0].isSelected)
        flick(0f, -30f)
        flick(0f, 30f)
        assertEquals(2, segments().size)
        assertEquals(0, e.composingStart) // never committed
        assertTrue(e.keys.isEmpty())
    }

    // --- Single-line guard ---------------------------------------------------------------------------

    @Test fun verticalKeysGoOnlyToEditorsThatCanUseThem() {
        fun allowed(type: Int) = EditorPolicy.allowsVerticalKeys(EditorInfo().apply { inputType = type })
        assertTrue(allowed(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(allowed(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE or T.TYPE_TEXT_VARIATION_LONG_MESSAGE))
        assertFalse(allowed(T.TYPE_CLASS_TEXT))
        assertFalse(allowed(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_IME_MULTI_LINE)) // only a fullscreen IME is multi-line
        assertFalse(allowed(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI))
        assertFalse(allowed(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(allowed(T.TYPE_CLASS_NUMBER))
        assertFalse(allowed(T.TYPE_CLASS_PHONE))
        assertFalse(allowed(T.TYPE_CLASS_DATETIME))
        assertTrue(allowed(T.TYPE_NULL)) // terminals and other raw editors want the key
        assertTrue(allowed(0xF)) // an unknown class is left to the host
        assertTrue(EditorPolicy.allowsVerticalKeys(null))
    }

    @Test fun inASingleLineFieldVerticalFlicksDoNothingAtAllAndHorizontalOnesStillMove() {
        val e = start()
        typeAme(e)
        ime.onAction(Keyboards.CURSOR_UP)
        ime.onAction(Keyboards.CURSOR_DOWN)
        assertEquals("雨", e.text.toString())
        assertEquals(0, e.composingStart) // no move, so no reason to commit
        assertTrue(e.keys.isEmpty())
        ime.onAction(Keyboards.CURSOR_RIGHT) // 0.1.34: ▶ at the end of a composition does nothing (it used to confirm)
        assertEquals(0, e.composingStart)
        assertTrue(e.keys.isEmpty())
        ime.onAction(Keyboards.ENTER)
        ime.onAction(Keyboards.CURSOR_RIGHT) // nothing composed: the editor's own cursor key
        assertEquals(-1, e.composingStart)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), e.keys)

        ime.onAction(Keyboards.MODE_CYCLE)
        ime.onText("w")
        ime.onAction(Keyboards.CURSOR_DOWN)
        assertTrue(e.composingStart >= 0) // the English word stays composed too
        assertEquals(1, e.keys.size)
        assertEquals(0, e.reads)
    }

    @Test fun otherEditorsGetOneKeyEvent() {
        for (type in listOf(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE, T.TYPE_NULL)) {
            val e = start(type)
            ime.onAction(Keyboards.CURSOR_UP)
            ime.onAction(Keyboards.CURSOR_DOWN)
            assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN), e.keys)
        }
        val number = start(T.TYPE_CLASS_NUMBER)
        ime.onAction(Keyboards.CURSOR_UP)
        assertTrue(number.keys.isEmpty())
    }

    // --- Audit fixes -----------------------------------------------------------------------------------

    @Test fun aHeldBackspaceKeepsRepeatingThroughAnEnglishWord() {
        val e = start()
        ime.onAction(Keyboards.MODE_CYCLE)
        "abcdef".forEach { ime.onText(it.toString()) }
        root!!.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        root!!.layout(0, 0, root!!.measuredWidth, root!!.measuredHeight)
        val kb = keys()
        Robolectric.buildActivity(android.app.Activity::class.java).setup().get().setContentView(root)
        idle(10)
        val r = kb.keyRows.flatten().single { it.action == Keyboards.BACKSPACE }.rect
        press(kb, 0, ACTION_DOWN, r.centerX(), r.centerY())
        assertEquals("abcde", e.text.toString())
        idle(400)
        assertEquals("abcd", e.text.toString())
        idle(60)
        assertEquals("abc", e.text.toString())
        press(kb, 500, ACTION_UP, r.centerX(), r.centerY())
    }

    @Test fun backspaceLeavesWholeCharactersToTheEditorAndKeepsSelectionsLocal() {
        val e = start(T.TYPE_CLASS_TEXT, "🇯🇵")
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), e.keys) // a code point count would leave half a flag behind
        assertEquals(0, e.reads)

        val f = start(T.TYPE_CLASS_TEXT, "abc")
        ime.onUpdateSelection(3, 3, 1, 3, -1, -1)
        android.text.Selection.setSelection(f.text, 1, 3)
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals("a", f.text.toString())
        assertTrue(f.keys.isEmpty())
    }

    @Test fun aNewCompositionAfterBackspaceTakesItsStartFromTheEditorNotAnEstimate() {
        val e = start(T.TYPE_CLASS_TEXT, "😀")
        ime.onAction(Keyboards.BACKSPACE)
        ime.onUpdateSelection(2, 2, 0, 0, -1, -1) // two UTF-16 units went away: only the editor's report says so
        typeAme(e)
        assertEquals("雨", e.text.toString())
        assertEquals(0, e.composingStart)
    }

    @Test fun aScorerLoadedAfterShutdownIsStillClosedOnAnotherThread() {
        val worker = ConversionWorker(RuntimeEnvironment.getApplication().assets, Handler(Looper.getMainLooper()))
        worker.shutdown()
        val closed = java.util.concurrent.CountDownLatch(1)
        var thread: Thread? = null
        worker.closeOffMain {
            thread = Thread.currentThread()
            closed.countDown()
        }
        assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(thread === Looper.getMainLooper().thread)
    }

    @Test fun aFailedEnglishIndexLoadLeavesWordsWithoutCandidates() {
        val lookup = EnglishLookup(Handler(Looper.getMainLooper())) { throw OutOfMemoryError("synthetic") }
        var answered = false
        lookup.lookup(listOf("word"), 1) { _, results ->
            answered = true
            val result = results.getValue("word")
            assertTrue(result.exact.isEmpty() && result.completions.isEmpty())
        }
        pumpUntil("lookup") { answered }
        assertTrue(lookup.failed)
        lookup.shutdown()
    }

    @Test fun onlyTheSetupScreenMentionsAFailedDictionaryLoad() {
        val context = RuntimeEnvironment.getApplication()
        fun lineShown(): Boolean {
            val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
            val message = activity.getString(R.string.setup_dictionary_failed)
            fun find(v: View): TextView? = when (v) {
                is TextView -> v.takeIf { it.text.toString() == message }
                is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                else -> null
            }
            return find(activity.window.decorView)!!.visibility == View.VISIBLE
        }
        DebugSettings.recordDictionaryLoad(context, ConversionWorker.OssState.READY)
        assertFalse(lineShown())
        DebugSettings.recordDictionaryLoad(context, ConversionWorker.OssState.FAILED)
        assertTrue(lineShown())
        DebugSettings.recordDictionaryLoad(context, ConversionWorker.OssState.LOADING) // not an outcome
        assertTrue(lineShown())
        DebugSettings.recordDictionaryLoad(context, ConversionWorker.OssState.MISSING)
        assertTrue(lineShown())
        DebugSettings.recordDictionaryLoad(context, ConversionWorker.OssState.READY)
        assertFalse(lineShown())
    }

    @Test fun hintsAreReadableWhereverTheyAreDrawn() {
        for (theme in KeyboardTheme.entries) {
            val c = theme.colors
            for ((what, surface) in listOf("key" to c.key, "arrow key" to c.functionKey, "strip" to c.background)) {
                val ratio = contrast(c.hint, surface)
                assertTrue("$theme hint on $what: $ratio", ratio >= 4.5)
            }
        }
    }
}
