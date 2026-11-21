package dev.astra.ime

import android.app.Activity
import android.os.Looper
import android.text.Editable
import android.text.InputType as T
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.InputDevice
import android.view.KeyEvent
import dev.astra.ime.oss.Word
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.TextView
import dev.astra.ime.core.FlickDirection
import dev.astra.ime.core.FlickKey
import dev.astra.ime.core.FlickLayout
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
 * Substitutes only the editor connection and the clipboard; all input handling is the real service. The field is not
 * asked for context unless a test sets [reads]: tests written for the shadow keep editors that refuse every read.
 */
class TestIme : AstraImeService() {
    var ic: InputConnection? = null
    var info: EditorInfo? = null
    var reads = false
    internal val clip = FakeClipSource()
    internal override fun clipSource(): ClipSource = clip
    internal override fun hostReads(): Boolean = reads
    override fun getCurrentInputConnection(): InputConnection? = ic
    override fun getCurrentInputEditorInfo(): EditorInfo? = info
}

/** In-memory editor over synthetic text. Counts every field read; getSelectedText "times out". */
class FakeEditor(view: View, initial: String, start: Int, end: Int) : BaseInputConnection(view, true) {
    val text = SpannableStringBuilder(initial).also { Selection.setSelection(it, start, end) }
    var reads = 0
    /** Any read fails the test at once, wherever in the service it came from. */
    var forbidReads = false
    private fun read() {
        reads++
        if (forbidReads) throw AssertionError("the field was asked for text")
    }
    val keys = ArrayList<Int>()
    /** Editor actions (GO, SEARCH, ...) the IME performed, in order. */
    val actions = ArrayList<Int>()
    override fun getEditable(): Editable = text
    override fun performEditorAction(actionCode: Int): Boolean { actions += actionCode; return true }
    override fun getSelectedText(flags: Int): CharSequence? { read(); return null }
    /** Runs first in every getTextBeforeCursor, on whatever thread asks: a slow, stuck or failing host. */
    @Volatile var beforeRead: (() -> Unit)? = null
    /** What each getTextBeforeCursor answered and whether a composing span was in the field then. */
    val answered: MutableList<Pair<String?, Boolean>> = java.util.Collections.synchronizedList(ArrayList())
    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
        read()
        beforeRead?.invoke()
        val answer = super.getTextBeforeCursor(length, flags)
        answered += answer?.toString() to (composingStart >= 0)
        return answer
    }
    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? { read(); return super.getTextAfterCursor(length, flags) }
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? { read(); return null }
    override fun getCursorCapsMode(reqModes: Int): Int { read(); return 0 }
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            keys += event.keyCode
            if (event.keyCode == KeyEvent.KEYCODE_DEL) deleteBeforeCursor()
        }
        return true
    }
    /** What an editor does with DEL: the selection, or the character before the cursor (one code point here). */
    private fun deleteBeforeCursor() {
        val start = Selection.getSelectionStart(text)
        val end = Selection.getSelectionEnd(text)
        if (start < 0) return
        val from = if (start != end) start else if (start > 0) text.toString().offsetByCodePoints(start, -1) else 0
        text.delete(from, end)
    }
    /** What commitText answers: false as from a connection that is no longer valid, and nothing changes. */
    var acceptsCommits = true
    /** Runs inside commitText: something that happens while the editor is being written. */
    var duringCommit: (() -> Unit)? = null
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!acceptsCommits) return false
        duringCommit?.invoke()
        return if (reportCommits) reported { super.commitText(text, newCursorPosition) } else super.commitText(text, newCursorPosition)
    }
    val composingStart: Int get() = BaseInputConnection.getComposingSpanStart(text)
    val composingEnd: Int get() = BaseInputConnection.getComposingSpanEnd(text)

    /**
     * The onUpdateSelection report (old start, old end, new start, new end, composing start, composing end) a real
     * editor would send after each composing write. Never delivered automatically: tests deliver, delay or repeat them.
     */
    val reports = ArrayList<IntArray>()
    /** Commits are reported too; most tests only look at composing writes. */
    var reportCommits = false
    /** What setComposingText answers: false as from a connection that is no longer valid, and nothing changes. */
    var acceptsComposing = true
    private fun reported(write: () -> Boolean): Boolean {
        val oldStart = Selection.getSelectionStart(this.text)
        val oldEnd = Selection.getSelectionEnd(this.text)
        val done = write()
        reports += intArrayOf(oldStart, oldEnd, Selection.getSelectionStart(this.text), Selection.getSelectionEnd(this.text), composingStart, composingEnd)
        return done
    }
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!acceptsComposing) return false
        return reported { super.setComposingText(text, newCursorPosition) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class InputUiTest {
    @Before fun useDeterministicUiFixture() {
        val context = RuntimeEnvironment.getApplication()
        // Real async worker + fixed small lexicon. Full dictionary loading has its own tests.
        DebugSettings.setOssEnabled(context, false)
        DebugSettings.setZenzEnabled(context, false)
        // Learning is on by default; each test starts without history so candidate order is the lexicon's.
        Learning.setEnabled(context, true)
        Learning.store(context).clearHistory()
    }

    // --- KeyboardView: real multi-pointer MotionEvents through the listener ----------------------

    private val log = ArrayList<String>()
    private val ka = Key("か", flick = FlickKey.KA)
    private val na = Key("な", flick = FlickKey.NA)
    private val del = Key("⌫", action = Keyboards.BACKSPACE, repeat = true)
    private val textKey = Key("x", text = "x")

    private fun keyboard() = KeyboardView(RuntimeEnvironment.getApplication()).apply {
        setRows(listOf(listOf(ka, na, del, textKey)))
        listener = object : KeyboardView.Listener {
            override fun onFlick(key: FlickKey, direction: FlickDirection) { log += "$key:$direction" }
            override fun onText(text: String) { log += "text:$text" }
            override fun onAction(action: Int) { log += "action:$action" }
        }
        measure(MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY))
        layout(0, 0, 400, 100)
    }

    private class P(val id: Int, val x: Float, val y: Float)

    /** Pointer [id] at [key]'s current center plus an offset in px (mdpi: flick threshold 18px). */
    private fun at(id: Int, key: Key, dx: Float = 0f, dy: Float = 0f) = P(id, key.rect.centerX() + dx, key.rect.centerY() + dy)

    private fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun touch(view: KeyboardView, time: Long, action: Int, vararg p: P) {
        val props = Array(p.size) { i -> MotionEvent.PointerProperties().apply { id = p[i].id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(p.size) { i -> MotionEvent.PointerCoords().apply { x = p[i].x; y = p[i].y; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(0L, time, action, p.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun singlePointerFlickAndTapAreUnchanged() {
        val kb = keyboard()
        touch(kb, 0, ACTION_DOWN, at(0, ka))
        touch(kb, 10, ACTION_MOVE, at(0, ka, dy = -40f))
        touch(kb, 20, ACTION_UP, at(0, ka, dy = -40f))
        touch(kb, 30, ACTION_DOWN, at(0, na))
        touch(kb, 40, ACTION_MOVE, at(0, na, dx = 5f)) // below threshold; direction was reset
        touch(kb, 50, ACTION_UP, at(0, na, dx = 5f))
        assertEquals(listOf("KA:UP", "NA:CENTER"), log)
    }

    @Test fun rollingHandOffWhenEarlierFingerLiftsFirst() {
        val kb = keyboard()
        touch(kb, 0, ACTION_DOWN, at(0, ka))
        touch(kb, 10, ACTION_MOVE, at(0, ka, dx = 40f))
        touch(kb, 20, pointer(ACTION_POINTER_DOWN, 1), at(0, ka, dx = 40f), at(1, na))
        assertEquals(listOf("KA:RIGHT"), log) // completed at the old pointer's coordinates
        touch(kb, 30, pointer(ACTION_POINTER_UP, 0), at(0, ka, dx = 40f), at(1, na))
        touch(kb, 40, ACTION_MOVE, at(1, na, dy = 40f))
        touch(kb, 50, ACTION_UP, at(1, na, dy = 40f))
        assertEquals(listOf("KA:RIGHT", "NA:DOWN"), log)
    }

    @Test fun rollingHandOffWhenNewFingerLiftsFirst() {
        val kb = keyboard()
        touch(kb, 0, ACTION_DOWN, at(0, ka))
        touch(kb, 10, pointer(ACTION_POINTER_DOWN, 1), at(0, ka), at(1, na))
        touch(kb, 20, pointer(ACTION_POINTER_UP, 1), at(0, ka, dx = 40f), at(1, na))
        touch(kb, 30, ACTION_MOVE, at(0, ka, dx = 40f))
        touch(kb, 40, ACTION_UP, at(0, ka, dx = 40f))
        assertEquals(listOf("KA:CENTER", "NA:CENTER"), log) // the old finger never emits again
    }

    @Test fun cancelEmitsNoPendingCharacter() {
        val kb = keyboard()
        touch(kb, 0, ACTION_DOWN, at(0, ka))
        touch(kb, 10, ACTION_MOVE, at(0, ka, dx = 40f))
        touch(kb, 20, ACTION_CANCEL, at(0, ka, dx = 40f))
        assertTrue(log.isEmpty())
        touch(kb, 30, ACTION_DOWN, at(0, ka))
        touch(kb, 40, pointer(ACTION_POINTER_DOWN, 1), at(0, ka), at(1, na))
        touch(kb, 50, ACTION_CANCEL, at(0, ka), at(1, na))
        assertEquals(listOf("KA:CENTER"), log) // emitted by the hand-off, not by the cancel
    }

    @Test fun repeatStopsOnHandOffAndNothingRunsAfterDetach() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val kb = keyboard()
        activity.setContentView(kb)
        idle(100)
        touch(kb, 0, ACTION_DOWN, at(0, del))
        idle(400 + 60)
        val held = log.size
        assertTrue(held >= 2)
        touch(kb, 500, pointer(ACTION_POINTER_DOWN, 1), at(0, del), at(1, textKey))
        idle(1000)
        assertEquals(held, log.size)
        touch(kb, 600, pointer(ACTION_POINTER_UP, 1), at(0, del), at(1, textKey))
        touch(kb, 610, ACTION_UP, at(0, del))
        assertEquals(listOf("text:x"), log.drop(held))
        touch(kb, 700, ACTION_DOWN, at(0, del))
        (kb.parent as ViewGroup).removeView(kb)
        idle(2000)
        assertEquals(listOf("text:x", "action:${Keyboards.BACKSPACE}"), log.drop(held))
    }

    // --- AstraImeService with a synthetic editor --------------------------------------------------

    private var controller: ServiceController<TestIme>? = null
    private val ime: TestIme get() = controller!!.get()

    @After fun tearDown() { controller?.destroy() }

    private fun start(type: Int, initial: String = "", start: Int = initial.length, end: Int = start, imeOptions: Int = 0): FakeEditor {
        if (controller == null) controller = Robolectric.buildService(TestIme::class.java).create()
        val editor = FakeEditor(View(ime), initial, maxOf(start, 0), maxOf(end, 0))
        val info = EditorInfo().apply { inputType = type; initialSelStart = start; initialSelEnd = end; this.imeOptions = imeOptions }
        ime.ic = editor
        ime.info = info
        ime.onStartInput(info, false)
        return editor
    }

    @Test fun selectionDeletionThenPrecedingCharWithoutCallbacksOrReads() {
        val e = start(T.TYPE_CLASS_TEXT, "abcdef", 2, 5)
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals("abf", e.text.toString())
        ime.onAction(Keyboards.BACKSPACE) // no onUpdateSelection in between
        assertEquals("af", e.text.toString())
        Selection.setSelection(e.text, 0, 1)
        ime.onUpdateSelection(1, 1, 0, 1, -1, -1)
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals("f", e.text.toString())
        assertEquals(0, e.reads)
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), e.keys) // only for the character: the editor knows where it starts
    }

    @Test fun unknownSelectionFallsBackToDeleteKey() {
        val e = start(T.TYPE_CLASS_TEXT, "abc", -1, -1)
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), e.keys)
        assertEquals(0, e.reads)
    }

    @Test fun passwordFieldTypesDirectlyAndNeverReadsText() {
        val e = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)
        ime.onText("a")
        ime.onFlick(FlickKey.KA, FlickDirection.CENTER)
        ime.onFlick(FlickKey.MODIFIER, FlickDirection.CENTER) // no owned composition: no-op
        assertEquals("aか", e.text.toString())
        assertEquals(-1, e.composingStart)
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals("a", e.text.toString())
        assertEquals(0, e.reads)
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), e.keys)
    }

    @Test fun restartPreservesRawComposition() {
        val e = start(T.TYPE_CLASS_TEXT)
        ime.onFlick(FlickKey.KA, FlickDirection.CENTER)
        assertEquals(0, e.composingStart)
        ime.onStartInput(ime.info!!, true)
        assertEquals("か", e.text.toString())
        assertEquals(-1, e.composingStart)
        ime.onFlick(FlickKey.NA, FlickDirection.CENTER)
        assertEquals("かな", e.text.toString())
        assertEquals(1, e.composingStart)
    }

    @Test fun selectionMoveNeverRelocatesAndStaleChipNeverCommits() {
        start(T.TYPE_CLASS_TEXT)
        val strip = stripOf(ime.onCreateInputView())
        val e = start(T.TYPE_CLASS_TEXT)
        ime.onFlick(FlickKey.KA, FlickDirection.CENTER)
        val staleChip = strip.getChildAt(0) // raw "か" chip, bound to that revision
        ime.onFlick(FlickKey.NA, FlickDirection.CENTER)
        staleChip.performClick()
        assertEquals("かな", e.text.toString())
        assertEquals(0, e.composingStart) // still composing: nothing was committed
        ime.onUpdateSelection(0, 0, 2, 2, 0, 2) // own echo keeps the composition
        assertEquals(0, e.composingStart)
        Selection.setSelection(e.text, 0)
        ime.onUpdateSelection(2, 2, 0, 0, 0, 2) // user moved the cursor
        assertEquals(-1, e.composingStart)
        ime.onFlick(FlickKey.TA, FlickDirection.CENTER)
        staleChip.performClick()
        assertEquals("たかな", e.text.toString())
        assertEquals(0, e.composingStart)
        assertEquals(0, e.reads)
    }

    // --- Live conversion and the candidate panel --------------------------------------------------

    /** root -> strip row -> HorizontalScrollView -> chip row. */
    private fun stripOf(root: View): ViewGroup =
        (((root as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup

    private fun direction(key: FlickKey, kana: String) = FlickDirection.entries.first { FlickLayout.kana(key, it) == kana }

    private fun typeAme() {
        ime.onFlick(FlickKey.A, FlickDirection.CENTER)
        ime.onFlick(FlickKey.MA, direction(FlickKey.MA, "め"))
    }

    /** The conversion worker runs on real threads; pump the paused main looper until [done]. */
    private fun pumpUntil(done: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    private var liveStrip: ViewGroup? = null
    private var liveRoot: ViewGroup? = null

    /** root -> strip row -> ▼/▲ button. */
    private fun expandOf(root: ViewGroup) = (root.getChildAt(0) as ViewGroup).getChildAt(1) as TextView

    /** Types あめ and waits until real (not identity) candidates were applied. */
    private fun startTyped(live: Boolean): FakeEditor {
        DebugSettings.setLiveConversion(RuntimeEnvironment.getApplication(), live)
        start(T.TYPE_CLASS_TEXT)
        val root = ime.onCreateInputView() as ViewGroup
        liveRoot = root
        liveStrip = stripOf(root)
        val e = start(T.TYPE_CLASS_TEXT)
        // The handmade lexicon loads asynchronously after create; wait for its candidates.
        typeAme()
        pumpUntil { expandOf(root).text == "▼" }
        return e
    }

    private fun startLive(): FakeEditor = startTyped(live = true)

    @Test fun liveConversionShowsTopCandidateWithoutCommitting() {
        val e = startLive()
        assertEquals("雨", e.text.toString())
        assertEquals(0, e.composingStart) // still composing: nothing committed
        ime.onAction(Keyboards.CONVERT) // next candidate replaces the shown one
        assertEquals("飴", e.text.toString())
        assertEquals(0, e.composingStart)
        ime.onFinishInput() // leaving without a choice restores the typed kana
        assertEquals("あめ", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun enterCommitsTheCandidateChosenWithConvert() {
        val e = startLive()
        ime.onAction(Keyboards.CONVERT)
        ime.onAction(Keyboards.ENTER)
        assertEquals("飴", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertTrue(e.keys.isEmpty()) // the first Enter only confirms
    }

    @Test fun convertWithLiveOffShowsCandidatesAndEnterKeepsTheManualChoice() {
        val e = startTyped(live = false)
        assertEquals("あめ", e.text.toString()) // live off: the kana stays until 変換
        ime.onAction(Keyboards.CONVERT)
        val first = e.text.toString()
        assertNotEquals("あめ", first)
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.CONVERT)
        val second = e.text.toString()
        assertNotEquals(first, second)
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.ENTER)
        assertEquals(second, e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun convertWhileResultIsPendingChangesNothing() {
        val e = startLive()
        ime.onFlick(FlickKey.NA, direction(FlickKey.NA, "の")) // new request; result not applied yet
        ime.onAction(Keyboards.CONVERT)
        assertEquals("雨の", e.text.toString())
        assertEquals(0, e.composingStart)
    }

    @Test fun explicitConfirmationKeepsVisiblePrefixWhileNextResultIsPending() {
        val e = startLive()
        // 0.1.34: ▶ is no confirmation either (at the end of a composition it does nothing); Enter is.
        ime.onFlick(FlickKey.NA, direction(FlickKey.NA, "の"))
        assertEquals("雨の", e.text.toString())
        ime.onAction(Keyboards.CURSOR_RIGHT)
        assertEquals(0, e.composingStart)
        assertTrue(e.keys.isEmpty())
        ime.onAction(Keyboards.ENTER) // the visible prefix, while the result for あめの is still pending
        assertEquals("雨の", e.text.toString())
        assertEquals(-1, e.composingStart)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("雨の", e.text.toString())
        assertEquals(0, e.reads)
    }

    @Test fun spaceIsConvertWhileComposingKanaAndASpaceOtherwise() {
        val e = startLive()
        ime.onAction(Keyboards.SPACE) // reads 変換 while composing: next candidate, nothing committed
        assertEquals("飴", e.text.toString())
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.ENTER) // reads 確定
        assertEquals("飴", e.text.toString())
        assertEquals(-1, e.composingStart)
        ime.onAction(Keyboards.SPACE)
        assertEquals("飴　", e.text.toString()) // the かな screen types the full-width space
        assertTrue(e.keys.isEmpty()) // no Enter/editor action
        assertEquals(0, e.reads)
    }

    @Test fun spaceWhileResultIsPendingChangesNothing() {
        val e = startLive()
        ime.onFlick(FlickKey.NA, direction(FlickKey.NA, "の"))
        ime.onAction(Keyboards.SPACE)
        assertEquals("雨の", e.text.toString())
        assertEquals(0, e.composingStart)
    }

    // --- Learning ------------------------------------------------------------------------------

    @Test fun aChosenCandidateComesFirstNextTimeAndIncognitoFieldsDoNotRecord() {
        val e = startLive()
        ime.onAction(Keyboards.SPACE) // 飴
        ime.onAction(Keyboards.ENTER)
        assertEquals("飴", e.text.toString())
        typeAme()
        pumpUntil { e.text.toString() == "飴飴" } // learned: 飴 is now shown first
        assertEquals(1, e.composingStart)
        ime.onAction(Keyboards.ENTER)

        val context = RuntimeEnvironment.getApplication()
        val learned = Learning.store(context).historySize
        assertTrue(learned > 0)
        // Incognito (no-personalized-learning) fields convert live, but neither use nor record history.
        val incognito = start(T.TYPE_CLASS_TEXT, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        typeAme()
        pumpUntil { incognito.text.toString() == "雨" } // the lexicon's order, not the learned 飴
        assertEquals(0, incognito.composingStart)
        ime.onAction(Keyboards.SPACE)
        ime.onAction(Keyboards.ENTER)
        assertEquals("飴", incognito.text.toString())
        assertEquals(learned, Learning.store(context).historySize)

        // Learning switched off: nothing recorded and history not used.
        Learning.setEnabled(context, false)
        val off = start(T.TYPE_CLASS_TEXT)
        typeAme()
        pumpUntil { off.text.toString() == "雨" }
        ime.onAction(Keyboards.SPACE)
        ime.onAction(Keyboards.ENTER)
        assertEquals(learned, Learning.store(context).historySize)
    }

    // --- Private and address fields (Chrome omnibox-like EditorInfo; real flags not measured) ----

    /** Candidate chips of the strip (segment buttons and their divider left out). */
    private fun chips(): List<String> = liveStrip!!.let { s -> (0 until s.childCount).map { s.getChildAt(it) as TextView } }
        .filter { it.tag != TAG_SEGMENT && it.tag != TAG_SEGMENT_DIVIDER }.map { it.text.toString() }


    private fun keysOf(root: ViewGroup): KeyboardView =
        (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<KeyboardView>().single()

    private fun samplesOn(): Boolean = keysOf(liveRoot!!).collectSamples

    /** Learns 飴 for あめ in an ordinary field; returns the unlearned strip order and the history size. */
    private fun learnCandy(): Pair<List<String>, Int> {
        val e = startLive()
        assertEquals("雨", e.text.toString())
        val unlearned = chips()
        assertTrue(samplesOn())
        ime.onAction(Keyboards.SPACE)
        ime.onAction(Keyboards.ENTER)
        assertEquals("飴", e.text.toString())
        val size = Learning.store(RuntimeEnvironment.getApplication()).historySize
        assertTrue(size > 0)
        return unlearned to size
    }

    private val addressBar = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI or T.TYPE_TEXT_FLAG_NO_SUGGESTIONS

    @Test fun addressBarStartsInKanaAndLiveConvertsWithoutHistoryThenGoes() {
        val context = RuntimeEnvironment.getApplication()
        val (unlearned, learned) = learnCandy() // live conversion stays on globally
        for (options in listOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)) {
            // Kana by default; あめ shows the unpersonalized 雨 before any 変換, still composing.
            val query = start(addressBar, imeOptions = options)
            assertTrue(chips().isEmpty()) // no guidance text in the keyboard
            assertEquals(false, samplesOn())
            typeAme()
            pumpUntil { query.text.toString() == "雨" }
            assertEquals("雨", query.text.toString()) // not the learned 飴
            assertEquals(0, query.composingStart)
            assertEquals(unlearned, chips()) // no history-derived order or predictions
            ime.onAction(Keyboards.ENTER) // 確定 only
            assertEquals("雨", query.text.toString())
            assertEquals(-1, query.composingStart)
            assertTrue(query.actions.isEmpty())
            ime.onAction(Keyboards.ENTER)
            assertEquals(listOf(EditorInfo.IME_ACTION_GO), query.actions)
            assertEquals("雨", query.text.toString())
            assertEquals(learned, Learning.store(context).historySize)

            // ABC: a URL is typed exactly and the first Enter goes.
            val url = start(addressBar, imeOptions = options)
            ime.onAction(Keyboards.TO_ASCII)
            assertTrue(chips().isEmpty())
            for (c in listOf("a", ".", "j", "p")) ime.onText(c)
            ime.onAction(Keyboards.SPACE)
            assertEquals("a.jp ", url.text.toString())
            assertEquals(-1, url.composingStart)
            ime.onAction(Keyboards.ENTER)
            assertEquals(listOf(EditorInfo.IME_ACTION_GO), url.actions)
            assertEquals("a.jp ", url.text.toString())
            assertEquals(0, url.reads + query.reads)
        }

        // Positive control: the learned choice is still used and recorded in an ordinary field.
        val plain = start(T.TYPE_CLASS_TEXT)
        assertTrue(samplesOn())
        typeAme()
        pumpUntil { plain.text.toString() == "飴" }
        ime.onAction(Keyboards.ENTER)
        assertEquals("飴", plain.text.toString())
    }

    @Test fun addressBarWithLiveOffKeepsKanaUntilExplicitConversion() {
        val context = RuntimeEnvironment.getApplication()
        val (unlearned, learned) = learnCandy()
        DebugSettings.setLiveConversion(context, false)
        val query = start(addressBar, imeOptions = EditorInfo.IME_ACTION_GO)
        typeAme()
        pumpUntil { expandOf(liveRoot!!).text == "▼" }
        assertEquals("あめ", query.text.toString()) // no live replacement with the global switch off
        assertEquals(0, query.composingStart)
        // The kana is shown, so its chip leads (with live on it is among the candidates behind the segments).
        assertEquals(listOf("あめ") + unlearned.filter { it != "あめ" }, chips())
        ime.onAction(Keyboards.SPACE) // 変換
        assertEquals("雨", query.text.toString())
        assertEquals(0, query.composingStart)
        ime.onAction(Keyboards.ENTER)
        assertEquals("雨", query.text.toString())
        assertTrue(query.actions.isEmpty())
        ime.onAction(Keyboards.ENTER)
        assertEquals(listOf(EditorInfo.IME_ACTION_GO), query.actions)
        assertEquals(learned, Learning.store(context).historySize)
    }

    @Test fun noSuggestionTextFieldConvertsLiveWithoutHistory() {
        val context = RuntimeEnvironment.getApplication()
        val (unlearned, learned) = learnCandy()
        val e = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_NO_SUGGESTIONS, imeOptions = EditorInfo.IME_ACTION_SEARCH)
        typeAme() // kana by default
        pumpUntil { e.text.toString() == "雨" }
        assertEquals("雨", e.text.toString())
        assertEquals(0, e.composingStart)
        assertEquals(unlearned, chips())
        ime.onAction(Keyboards.CONVERT)
        assertEquals("飴", e.text.toString())
        ime.onAction(Keyboards.ENTER)
        ime.onAction(Keyboards.ENTER)
        assertEquals("飴", e.text.toString())
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), e.actions)
        assertEquals(learned, Learning.store(context).historySize)
    }

    @Test fun aResultRequestedBeforeARestartIntoAPrivateFieldNeverShowsThere() {
        val context = RuntimeEnvironment.getApplication()
        val (_, learned) = learnCandy()
        val e = start(T.TYPE_CLASS_TEXT)
        typeAme() // request in flight with the learning snapshot
        val incognito = EditorInfo().apply {
            inputType = T.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            initialSelStart = 2
            initialSelEnd = 2
        }
        ime.info = incognito
        ime.onStartInput(incognito, true)
        assertEquals("あめ", e.text.toString()) // finished as typed
        assertEquals(-1, e.composingStart)
        typeAme()
        pumpUntil { e.text.toString() == "あめ雨" }
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("あめ雨", e.text.toString()) // not the learned 飴
        assertEquals(2, e.composingStart)
        ime.onAction(Keyboards.ENTER)
        assertEquals("あめ雨", e.text.toString())
        assertEquals(learned, Learning.store(context).historySize)
    }

    @Test fun passwordAndRawFieldsStayProtectedAndDirect() {
        val context = RuntimeEnvironment.getApplication()
        start(T.TYPE_CLASS_TEXT)
        val root = ime.onCreateInputView() as ViewGroup
        liveRoot = root
        liveStrip = stripOf(root)
        for (type in listOf(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_PASSWORD, T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_VARIATION_PASSWORD, T.TYPE_NULL)) {
            val e = start(type)
            assertTrue(chips().isEmpty())
            assertEquals(false, samplesOn())
            typeAme()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("あめ", e.text.toString())
            assertEquals(-1, e.composingStart)
            assertEquals(0, Learning.store(context).historySize)
        }
    }

    @Test fun withoutCompositionOrInSensitiveFieldsKeysDoNotMutateText() {
        val plain = start(T.TYPE_CLASS_TEXT, "abc", 0, 3)
        ime.onAction(Keyboards.CONVERT)
        ime.onAction(Keyboards.CURSOR_LEFT)
        assertEquals("abc", plain.text.toString()) // the selection is not replaced
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), plain.keys)

        val e = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, "ab", 1, 1)
        ime.onAction(Keyboards.CONVERT)
        assertEquals("ab", e.text.toString())
        ime.onAction(Keyboards.SPACE)
        assertEquals("a b", e.text.toString())
        ime.onAction(Keyboards.CURSOR_RIGHT)
        assertEquals("a b", e.text.toString())
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), e.keys)
        assertEquals(-1, e.composingStart)
        ime.onFinishInput()
        ime.onAction(Keyboards.CURSOR_LEFT) // no active input: nothing is sent
        ime.onAction(Keyboards.SPACE)
        assertEquals("a b", e.text.toString())
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), e.keys)
        assertEquals(0, plain.reads + e.reads)
    }

    /** 0.1.34 (was: arrows confirm the shown composition, then move): arrows never commit while something is composed. */
    @Test fun arrowsNeverConfirmTheShownCompositionAndAMoveBackLeavesItAsItWas() {
        val e = startTyped(live = false)
        ime.onAction(Keyboards.CONVERT)
        val shown = e.text.toString()
        ime.onAction(Keyboards.CURSOR_RIGHT) // at the end: nothing at all
        assertEquals(shown, e.text.toString())
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.CURSOR_LEFT) // the caret goes into the reading
        assertEquals(0, e.composingStart)
        assertTrue(Selection.getSelectionStart(e.text) < e.text.length)
        ime.onAction(Keyboards.CURSOR_RIGHT) // back at the end, nothing edited: the manual choice is shown again
        assertEquals(shown, e.text.toString())
        assertEquals(e.text.length, Selection.getSelectionStart(e.text))
        assertEquals(0, e.composingStart)
        assertTrue(e.keys.isEmpty())
        ime.onAction(Keyboards.ENTER)
        assertEquals(shown, e.text.toString())
        assertEquals(-1, e.composingStart)

        ime.onFlick(FlickKey.KA, FlickDirection.CENTER) // conversion requested, not applied yet
        ime.onAction(Keyboards.CURSOR_LEFT) // the caret goes before か; the pending result is dropped
        assertEquals(shown + "か", e.text.toString())
        assertEquals(shown.length, Selection.getSelectionStart(e.text))
        repeat(30) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertEquals(shown + "か", e.text.toString()) // the stale result never lands or moves the caret
        assertEquals(shown.length, Selection.getSelectionStart(e.text))
        assertEquals(shown.length, e.composingStart)
        assertTrue(e.keys.isEmpty())
        assertEquals(0, e.reads)
    }

    @Test fun confirmCommitsTheShownCandidateAndRawChipCommitsKana() {
        val e = startLive()
        ime.onAction(Keyboards.ENTER)
        assertEquals("雨", e.text.toString())
        assertEquals(-1, e.composingStart)

        typeAme()
        pumpUntil { e.text.toString() == "雨雨" }
        assertEquals(1, e.composingStart)
        // Live conversion shows segment buttons first; the typed kana is still one tap away among the candidates.
        stripChip("あめ").performClick()
        assertEquals("雨あめ", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun typingKeepsShownConversionAndCursorMoveRestoresKana() {
        val e = startLive()
        ime.onFlick(FlickKey.NA, direction(FlickKey.NA, "の"))
        assertEquals("雨の", e.text.toString()) // shown conversion + new kana until the next result
        Selection.setSelection(e.text, 0)
        ime.onUpdateSelection(2, 2, 0, 0, 0, 2) // user moved the cursor
        assertEquals("あめの", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun symbolPagesSwitchAndTypeFullWidthMarks() {
        val e = start(T.TYPE_CLASS_TEXT)
        ime.onAction(Keyboards.TO_JSYMBOLS)
        ime.onText("「")
        ime.onAction(Keyboards.TO_EMOJI)
        ime.onText("👍")
        assertEquals("「👍", e.text.toString())
        assertTrue(Palettes.symbols.flatMap { it.items }.contains("、"))
        // Gboard/iOS layout: ◀ ▶ in the side columns, 空白/⏎ that read 変換/確定 while composing, no ▼ key.
        val idle = Keyboards.kana("⏎")
        val composing = Keyboards.kana("⏎", composing = true)
        assertEquals(idle.map { it.size }, composing.map { it.size })
        assertEquals(listOf("◀", "▶"), idle[1].let { listOf(it.first().label, it.last().label) })
        assertTrue(idle.flatten().any { it.label == "空白" && it.action == Keyboards.SPACE })
        assertTrue(composing.flatten().any { it.label == "変換" && it.action == Keyboards.SPACE })
        assertTrue(composing.flatten().any { it.label == "確定" && it.action == Keyboards.ENTER })
        assertTrue(idle.flatten().none { it.action == Keyboards.HIDE || it.action == Keyboards.CONVERT })
    }

    // --- Cursor keys and brackets -------------------------------------------------------------

    @Test fun bracketFlicksOnYaAndCursorKeysSendKeyEvents() {
        val e = start(T.TYPE_CLASS_TEXT)
        assertEquals("「", FlickLayout.kana(FlickKey.YA, FlickDirection.LEFT))
        assertEquals("」", FlickLayout.kana(FlickKey.YA, FlickDirection.RIGHT))
        ime.onFlick(FlickKey.YA, FlickDirection.LEFT)
        ime.onFlick(FlickKey.YA, FlickDirection.RIGHT)
        assertEquals("「」", e.text.toString()) // composed like kana (brackets pass through conversion)
        ime.onAction(Keyboards.CURSOR_RIGHT) // 0.1.34: at the end of the composition nothing happens
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.ENTER) // confirms as shown
        assertEquals("「」", e.text.toString())
        assertEquals(-1, e.composingStart)
        ime.onAction(Keyboards.CURSOR_LEFT) // nothing composed: an ordinary key event
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), e.keys)
        assertEquals(0, e.reads)
    }

    @Test fun noUtilityRowBetweenStripAndKeys() {
        start(T.TYPE_CLASS_TEXT)
        val root = ime.onCreateInputView() as ViewGroup
        start(T.TYPE_CLASS_TEXT)
        root.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val kb = keysOf(root)
        assertEquals(4, root.childCount) // strip, candidate panel, palette (both hidden), keys: segments live in the strip
        assertEquals(root.getChildAt(0).bottom, kb.top)
        assertTrue(kb.bottom <= root.height - root.paddingBottom) // keys stay above the safe area
    }

    // --- Segments (文節): select one part of the composition and fix it without committing ------------

    /** Types [text] with the flick keys that produce each kana (any key/direction pair). */
    private fun typeKana(text: String) {
        for (c in text) {
            val (key, dir) = FlickKey.entries.flatMap { k -> FlickDirection.entries.map { k to it } }
                .first { FlickLayout.kana(it.first, it.second) == c.toString() }
            ime.onFlick(key, dir)
        }
    }

    /** Live conversion on, input view created, lexicon loaded; then [type] is started. */
    private fun startWithView(type: Int = T.TYPE_CLASS_TEXT, imeOptions: Int = 0, live: Boolean = true): FakeEditor {
        DebugSettings.setLiveConversion(RuntimeEnvironment.getApplication(), live)
        start(T.TYPE_CLASS_TEXT)
        val root = ime.onCreateInputView() as ViewGroup
        liveRoot = root
        liveStrip = stripOf(root)
        val e = start(type, imeOptions = imeOptions)
        pumpUntil { ime.converterReady }
        return e
    }

    /** Types あしたはあめ and waits for the live 明日は雨 (segments 明日は | 雨 in the handmade lexicon). */
    private fun typeTomorrowRain(e: FakeEditor) {
        typeKana("あしたはあめ")
        pumpUntil { e.text.toString() == "明日は雨" }
        assertEquals("明日は雨", e.text.toString())
        assertEquals(0, e.composingStart)
    }

    private fun stripViews(): List<TextView> = liveStrip!!.let { s -> (0 until s.childCount).map { s.getChildAt(it) as TextView } }

    private fun segmentChips(): List<TextView> = stripViews().filter { it.tag == TAG_SEGMENT }

    /** Segment buttons in order; the selected one ends with |. */
    private fun unitLabels(): List<String> = segmentChips().map { it.text.toString() + if (it.isSelected) "|" else "" }

    private fun selecting(): Boolean = segmentChips().any { it.isSelected }

    private fun stripChip(label: String): TextView =
        stripViews().first { it.tag != TAG_SEGMENT && it.text.toString() == label }

    /**
     * ▼ shows the segment buttons without changing what is shown; segment [index] (default: the last) is tapped and
     * the panel, which stays open for it, is closed again.
     */
    private fun selectSegment(index: Int = -1) {
        val expand = expandOf(liveRoot!!)
        pumpUntil { expand.text == "▼" }
        expand.performClick()
        val chips = segmentChips()
        assertTrue(chips.isNotEmpty())
        chips[if (index < 0) chips.lastIndex else index].performClick()
        assertTrue(selecting())
        assertEquals("▲", expand.text.toString())
        expand.performClick()
    }

    @Test fun liveConversionShowsSegmentButtonsInPlaceOfTheReadingWithoutPressingAnything() {
        val e = startWithView()
        typeTomorrowRain(e)
        // 0.1.24: no ▼ or 変換 needed; the segments of what live conversion shows are there at once.
        assertEquals(e.text.toString(), segmentChips().joinToString("") { it.text })
        assertTrue(segmentChips().size > 1)
        assertFalse(selecting())
        assertTrue("あしたはあめ" in chips()) // the raw kana stays among the candidates
        assertEquals(0, e.composingStart)
        // A tap selects that segment straight away.
        segmentChips()[0].performClick()
        assertTrue(segmentChips()[0].isSelected)
        assertEquals("明日は雨", e.text.toString())
        assertEquals(0, e.composingStart) // nothing committed
    }

    @Test fun typingHidesTheSegmentsUntilTheNextConversionIsShown() {
        val e = startWithView()
        typeTomorrowRain(e)
        assertTrue(segmentChips().isNotEmpty())
        typeKana("の")
        assertTrue(segmentChips().isEmpty()) // the old segments do not match the longer reading
        pumpUntil { segmentChips().isNotEmpty() }
        assertEquals(e.text.toString(), segmentChips().joinToString("") { it.text })
        assertEquals(0, e.composingStart)
    }

    @Test fun withLiveConversionOffConvertRevealsTheSegments() {
        val e = startWithView(live = false)
        typeKana("あしたはあめ")
        pumpUntil { "明日は雨" in chips() }
        assertTrue(segmentChips().isEmpty())
        assertEquals("あしたはあめ", stripViews().first().text.toString()) // the raw chip: the kana is shown
        ime.onAction(Keyboards.SPACE) // 変換: the first candidate, and the segments of what it shows
        assertEquals("明日は雨", e.text.toString())
        assertEquals(e.text.toString(), segmentChips().joinToString("") { it.text })
        assertTrue("あしたはあめ" in chips())
        assertEquals(0, e.composingStart)
    }

    @Test fun aSegmentIsChosenWithoutCommittingAndTypingKeepsItThenEnterLearnsIt() {
        val context = RuntimeEnvironment.getApplication()
        val e = startWithView()
        typeTomorrowRain(e)
        selectSegment()
        assertEquals(listOf("明日は", "雨|"), unitLabels())
        pumpUntil { "飴" in chips() }
        stripChip("飴").performClick()
        assertEquals("明日は飴", e.text.toString())
        assertEquals(0, e.composingStart) // still composing: nothing committed
        assertEquals(listOf("明日は", "飴|"), unitLabels())
        assertEquals(0, Learning.store(context).historySize) // a choice alone is not learned

        // Another segment: its candidates come without touching the chosen one.
        segmentChips()[0].performClick()
        assertEquals(listOf("明日は|", "飴"), unitLabels())
        pumpUntil { "あしたは" in chips() }
        assertEquals("明日は飴", e.text.toString())
        // 変換 cycles the selected segment only.
        segmentChips()[1].performClick()
        pumpUntil { chips().size > 1 }
        ime.onAction(Keyboards.SPACE)
        assertTrue(e.text.toString().startsWith("明日は"))
        assertTrue(e.text.toString() != "明日は飴")
        stripChip("飴").performClick()

        // Typing keeps every segment as shown and continues after them.
        typeKana("の")
        assertFalse(selecting())
        assertEquals("明日は飴の", e.text.toString())
        pumpUntil { chips() == listOf("の", "ノ") } // the tail's result was applied (raw chip, then its katakana)
        assertEquals("明日は飴の", e.text.toString())
        ime.onAction(Keyboards.ENTER)
        assertEquals("明日は飴の", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertTrue(e.keys.isEmpty())
        assertEquals(0, e.reads)
        // Only the explicit final commit is learned, with the chosen word.
        assertTrue(Learning.store(context).snapshot().user.bonus("あめ", "飴") > 0)
    }

    @Test fun arrowsShrinkAndExtendTheSelectedSegmentAndConvertBothSides() {
        val e = startWithView()
        typeTomorrowRain(e)
        selectSegment(0)
        assertEquals(listOf("明日は|", "雨"), unitLabels())
        ime.onAction(Keyboards.CURSOR_LEFT) // あしたは -> あした | はあめ
        pumpUntil { unitLabels() == listOf("明日|", "は雨") }
        assertEquals("明日は雨", e.text.toString())
        ime.onAction(Keyboards.CURSOR_RIGHT) // back: あしたは | あめ
        pumpUntil { unitLabels() == listOf("明日は|", "雨") }
        ime.onAction(Keyboards.CURSOR_RIGHT) // あしたはあ | め: the next segment gives its first kana
        pumpUntil { segmentChips().size == 2 && unitLabels()[0].endsWith("|") && chips().isNotEmpty() }
        ime.onAction(Keyboards.CURSOR_RIGHT) // the whole reading in one segment
        pumpUntil { segmentChips().size == 1 && chips().isNotEmpty() }
        assertFalse(segmentChips().isEmpty())
        ime.onAction(Keyboards.CURSOR_RIGHT) // nothing left to take
        assertEquals(1, segmentChips().size)
        ime.onAction(Keyboards.CURSOR_UP)
        ime.onAction(Keyboards.CURSOR_DOWN)
        assertEquals(0, e.composingStart)
        assertTrue(e.keys.isEmpty()) // arrows never reached the editor while a segment is selected
        ime.onAction(Keyboards.ENTER) // 確定 commits everything as shown
        assertEquals(-1, e.composingStart)
        assertFalse(selecting())
        assertEquals(0, e.reads)
    }

    @Test fun acceptingTheTopIsWeakAndChoosingAnotherCandidateIsStrong() {
        val store = Learning.store(RuntimeEnvironment.getApplication())
        val e = startWithView()
        typeKana("あめ")
        pumpUntil { e.text.toString() == "雨" }
        ime.onAction(Keyboards.ENTER) // accepts the ranking
        assertEquals("雨", e.text.toString())
        assertEquals(0, store.snapshot().user.bonus("あめ", "雨"))
        assertFalse(store.snapshot().isStrong(Word("あめ", "雨")))
        typeKana("あめ")
        pumpUntil { e.text.toString() == "雨雨" && expandOf(liveRoot!!).text == "▼" }
        ime.onAction(Keyboards.CONVERT) // cycling alone is no choice
        assertEquals("雨飴", e.text.toString())
        assertFalse(store.snapshot().isStrong(Word("あめ", "飴")))
        ime.onAction(Keyboards.ENTER) // confirming what 変換 showed is
        assertEquals("雨飴", e.text.toString())
        // The fixture engine gives no word list, so the phrase alone records it: 飴 now leads あめ, explicitly chosen.
        typeKana("あめ")
        pumpUntil { e.text.toString() == "雨飴飴" }
        assertEquals("雨飴飴", e.text.toString())
        assertEquals(0, store.snapshot().user.bonus("あめ", "雨")) // the accepted 雨 never gained anything
    }

    @Test fun backspaceDeletesInTheSelectedSegmentAndKeepsTheOthers() {
        val e = startWithView()
        typeTomorrowRain(e)
        selectSegment(0)
        pumpUntil { "あしたは" in chips() }
        stripChip("あしたは").performClick()
        assertEquals("あしたは雨", e.text.toString())
        ime.onAction(Keyboards.BACKSPACE) // は of the selected あしたは, not め of 雨
        assertTrue(selecting())
        pumpUntil { e.text.toString() == "明日雨" } // あした, previewed as live conversion does
        assertEquals(listOf("明日|", "雨"), unitLabels())
        assertEquals(0, e.composingStart)
        ime.onAction(Keyboards.ENTER)
        assertEquals("明日雨", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals(0, e.reads)
    }

    /** The reported case, ここで | 区切られて | いた場合 with ここで selected, on the fixture 明日は | 、 | 雨. */
    @Test fun backspaceOnTheFirstSegmentDeletesItsLastKanaThenContinuesBackwards() {
        val e = startWithView()
        typeTomorrowCommaRain(e)
        selectSegment(0)
        pumpUntil { chips().isNotEmpty() }
        ime.onAction(Keyboards.BACKSPACE)
        pumpUntil { e.text.toString() == "明日、雨" }
        assertEquals(listOf("明日|", "、", "雨"), unitLabels())
        ime.onAction(Keyboards.BACKSPACE) // あし: no word, shown as kana
        pumpUntil { e.text.toString() == "あし、雨" && chips().isNotEmpty() }
        assertEquals(listOf("あし|", "、", "雨"), unitLabels())
        repeat(2) { ime.onAction(Keyboards.BACKSPACE) } // the segment is gone; the next one is selected, caret at its start
        pumpUntil { e.text.toString() == "、雨" }
        assertEquals(listOf("、|", "雨"), unitLabels())
        ime.onAction(Keyboards.BACKSPACE) // nothing before the caret in the composition: nothing changes
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("、雨", e.text.toString())
        assertEquals(listOf("、|", "雨"), unitLabels())
        assertEquals(0, e.composingStart)
        assertTrue(e.keys.isEmpty()) // never reached the editor
        ime.onAction(Keyboards.ENTER)
        assertEquals("、雨", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun backspaceOnAMiddleSegmentRemovesItAndSelectsThePreviousOneWithoutDeletingIt() {
        val e = startWithView()
        typeTomorrowCommaRain(e)
        selectSegment(1)
        ime.onAction(Keyboards.BACKSPACE) // 、 goes; 明日は is selected, still whole
        pumpUntil { e.text.toString() == "明日は雨" && unitLabels() == listOf("明日は|", "雨") }
        ime.onAction(Keyboards.BACKSPACE) // then its own last kana
        pumpUntil { e.text.toString() == "明日雨" }
        assertEquals(listOf("明日|", "雨"), unitLabels())
        assertEquals(0, e.composingStart)
        assertTrue(e.keys.isEmpty())
    }

    @Test fun backspaceOnTheLastSegmentEmptiesTheCompositionWithoutCommittingOrTouchingTheField() {
        val e = startWithView()
        typeTomorrowCommaRain(e)
        selectSegment(2)
        ime.onAction(Keyboards.BACKSPACE)
        pumpUntil { e.text.toString() == "明日は、あ" && chips().isNotEmpty() }
        assertEquals(listOf("明日は", "、", "あ|"), unitLabels())
        ime.onAction(Keyboards.BACKSPACE)
        pumpUntil { unitLabels() == listOf("明日は", "、|") }
        assertEquals("明日は、", e.text.toString())
        repeat(5) { ime.onAction(Keyboards.BACKSPACE) } // 、 and あしたは, one kana a press, no stale result shown
        pumpUntil { e.text.isEmpty() }
        assertEquals("", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertFalse(selecting())
        assertTrue(e.keys.isEmpty())
        ime.onAction(Keyboards.BACKSPACE) // empty composition: the editor's own delete, legacy behaviour
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), e.keys)
        assertEquals(0, Learning.store(RuntimeEnvironment.getApplication()).historySize)
    }

    @Test fun backspaceAfterAResizeDeletesInTheResizedSegmentAndLateResultsAreDropped() {
        val e = startWithView()
        typeTomorrowRain(e)
        selectSegment(0)
        ime.onAction(Keyboards.CURSOR_LEFT) // あした | はあめ
        ime.onAction(Keyboards.BACKSPACE) // before its result: あし | はあめ; the result for あした is stale
        // あし previews its top candidate (the fixture corrects it to 朝); the candidates are for あし, は雨 is converted too.
        pumpUntil { unitLabels().size == 2 && unitLabels()[1] == "は雨" && "あし" in chips() }
        assertTrue(unitLabels()[0].endsWith("|"))
        assertTrue(e.text.toString().endsWith("は雨"))
        ime.onAction(Keyboards.BACKSPACE)
        ime.onAction(Keyboards.BACKSPACE) // あし is gone: は雨 is selected, whole
        pumpUntil { e.text.toString() == "は雨" && chips().isNotEmpty() }
        assertEquals(listOf("は雨|"), unitLabels())
        ime.onAction(Keyboards.ENTER)
        assertEquals("は雨", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun backspaceHonoursACaretTappedInsideTheSelectedSegment() {
        val e = startWithView(live = false) // kana segments: あしたは | あめ
        typeKana("あしたはあめ")
        pumpUntil { expandOf(liveRoot!!).text == "▼" }
        settle(e)
        tap(e, 2) // あし|たは
        assertEquals(listOf("あしたは|", "あめ"), unitLabels())
        ime.onAction(Keyboards.BACKSPACE) // し, before the caret
        pumpUntil { e.text.toString() == "あたはあめ" }
        assertEquals(listOf("あたは|", "あめ"), unitLabels())
        ime.onAction(Keyboards.BACKSPACE)
        pumpUntil { e.text.toString() == "たはあめ" }
        assertEquals(0, e.composingStart)

        // In a kanji word the caret has no kana of its own: it goes after the word (明|日は deletes た of あした).
        val f = startWithView()
        typeTomorrowRain(f)
        settle(f)
        tap(f, 1)
        assertTrue(selecting())
        ime.onAction(Keyboards.BACKSPACE)
        pumpUntil { f.text.toString() == "あしは雨" }
        assertEquals(listOf("あしは|", "雨"), unitLabels())
    }

    // --- Typing in the selected segment (0.1.32) ---------------------------------------------------

    /** The reported case: after ⌫ in ここで of ここで | 区切られて | いた場合, typing goes into ここ, not after 合. */
    @Test fun typingAfterBackspaceInTheFirstSegmentGoesInThatSegmentNotAtTheEnd() {
        val e = startWithView()
        typeTomorrowCommaRain(e)
        selectSegment(0)
        ime.onAction(Keyboards.BACKSPACE)
        pumpUntil { e.text.toString() == "明日、雨" }
        typeKana("の")
        assertTrue(segmentChips()[0].isSelected)
        pumpUntil { "あしたの" in chips() } // the selected segment's reading, converted again
        assertEquals(listOf("、", "雨"), unitLabels().drop(1))
        assertTrue(unitLabels()[0].endsWith("|"))
        assertTrue(e.text.toString().endsWith("、雨"))
        assertEquals(0, e.composingStart)
        assertTrue(e.keys.isEmpty())
        val shown = e.text.toString()
        ime.onAction(Keyboards.ENTER) // 確定 commits everything as shown, nothing more
        assertEquals(shown, e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun typingInAMiddleSegmentKeepsTheOthersAndTheSelection() {
        val e = startWithView(live = false) // kana segments: あしたは | 、 | あめ
        typeKana("あしたは、あめ")
        selectSegment(1)
        assertEquals(listOf("あしたは", "、|", "あめ"), unitLabels())
        typeKana("の")
        assertEquals("あしたは、のあめ", e.text.toString())
        assertEquals(listOf("あしたは", "、の|", "あめ"), unitLabels())
        typeKana("を")
        assertEquals("あしたは、のをあめ", e.text.toString()) // the caret advanced past の
        pumpUntil { "、のを" in chips() }
        assertEquals(listOf("あしたは", "、のを|", "あめ"), unitLabels())
        assertEquals(0, e.composingStart)
    }

    @Test fun aCaretTappedInsideTheLastSegmentTakesTheTypedKanaThere() {
        val e = startWithView(live = false)
        typeKana("あしたはあめ")
        pumpUntil { expandOf(liveRoot!!).text == "▼" }
        settle(e)
        tap(e, 5) // あ|め
        assertEquals(listOf("あしたは", "あめ|"), unitLabels())
        typeKana("ま")
        assertEquals("あしたはあまめ", e.text.toString())
        assertEquals(listOf("あしたは", "あまめ|"), unitLabels())
        ime.onAction(Keyboards.ENTER)
        assertEquals("あしたはあまめ", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun atTheEndOfTheLastSegmentTypingStartsANewTailAfterTheKeptSegments() {
        val e = startWithView(live = false)
        typeKana("あしたはあめ")
        selectSegment()
        ime.onAction(Keyboards.BACKSPACE) // あ, caret at the end of the composition
        assertEquals("あしたはあ", e.text.toString())
        typeKana("き")
        assertFalse(selecting())
        assertEquals("あしたはあき", e.text.toString())
        assertEquals(0, e.composingStart)
    }

    @Test fun repeatedDeleteAndTypeInTheFirstSegmentStayInIt() {
        val e = startWithView(live = false)
        typeKana("あしたは、あめ")
        selectSegment(0)
        repeat(2) { ime.onAction(Keyboards.BACKSPACE) }
        typeKana("きの")
        ime.onAction(Keyboards.BACKSPACE)
        typeKana("う")
        assertEquals("あしきう、あめ", e.text.toString())
        assertEquals(listOf("あしきう|", "、", "あめ"), unitLabels())
        pumpUntil { "あしきう" in chips() } // only the latest reading's result is shown
        assertEquals("あしきう、あめ", e.text.toString())
        assertTrue(e.keys.isEmpty())
    }

    @Test fun anEmptiedFirstSegmentLeavesTheNextSelectedAndTypingGoesBeforeIt() {
        val e = startWithView(live = false)
        typeKana("あしたは、あめ")
        selectSegment(0)
        repeat(4) { ime.onAction(Keyboards.BACKSPACE) } // あしたは is gone: 、 is selected, caret at its start
        assertEquals(listOf("、|", "あめ"), unitLabels())
        typeKana("の")
        assertEquals("の、あめ", e.text.toString())
        assertEquals(listOf("の、|", "あめ"), unitLabels())
        assertEquals(0, e.composingStart)
    }

    @Test fun typingInAResizedSegmentGoesAtItsNewEnd() {
        val e = startWithView(live = false)
        typeKana("あしたはあめ")
        selectSegment(0)
        ime.onAction(Keyboards.CURSOR_LEFT) // あした | はあめ
        assertEquals(listOf("あした|", "はあめ"), unitLabels())
        typeKana("の")
        assertEquals("あしたのはあめ", e.text.toString())
        assertEquals(listOf("あしたの|", "はあめ"), unitLabels())
    }

    @Test fun theSmallKanaKeyAndTextKeysActInTheSelectedSegment() {
        val e = startWithView(live = false)
        typeKana("あしたは、あめ")
        selectSegment(0)
        ime.onFlick(FlickKey.MODIFIER, FlickDirection.CENTER) // は of the selected segment, not め at the end
        assertEquals("あしたば、あめ", e.text.toString())
        assertEquals(listOf("あしたば|", "、", "あめ"), unitLabels())
        ime.onText("!")
        assertEquals("あしたば!、あめ", e.text.toString())
        assertTrue(segmentChips()[0].isSelected)
        // Hidden before 確定: the edited reading stays as kana, nothing converted is committed.
        ime.onFinishInputView(false)
        assertEquals("あしたば!、あめ", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun inTheAddressBarEnterKeepsGoAndNothingIsLearnedAndPasswordsHaveNoSegments() {
        val context = RuntimeEnvironment.getApplication()
        val query = startWithView(addressBar, imeOptions = EditorInfo.IME_ACTION_GO)
        typeTomorrowRain(query)
        selectSegment()
        pumpUntil { "飴" in chips() }
        stripChip("飴").performClick()
        ime.onAction(Keyboards.ENTER) // 確定 only
        assertEquals("明日は飴", query.text.toString())
        assertEquals(-1, query.composingStart)
        assertTrue(query.actions.isEmpty())
        ime.onAction(Keyboards.ENTER)
        assertEquals(listOf(EditorInfo.IME_ACTION_GO), query.actions)
        assertEquals(0, Learning.store(context).historySize)

        val secret = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)
        typeKana("あめ")
        ime.onAction(Keyboards.CONVERT)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("あめ", secret.text.toString())
        assertTrue(segmentChips().isEmpty())
        assertTrue(chips().isEmpty())
        assertEquals(0, secret.reads + query.reads)
    }

    @Test fun leavingTheEditorWhileASegmentIsSelectedRestoresTheTypedKana() {
        val e = startWithView()
        typeTomorrowRain(e)
        selectSegment()
        pumpUntil { "飴" in chips() }
        stripChip("飴").performClick()
        ime.onFinishInput() // nothing the user did not confirm is committed
        assertEquals("あしたはあめ", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertFalse(selecting())

        // Resized, then the keyboard is hidden: the typed kana, whatever the segments showed.
        val f = start(T.TYPE_CLASS_TEXT)
        typeTomorrowRain(f)
        selectSegment(0)
        ime.onAction(Keyboards.CURSOR_LEFT)
        ime.onFinishInputView(false)
        assertEquals("あしたはあめ", f.text.toString())
        assertEquals(-1, f.composingStart)
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) } // late results are dropped
        assertEquals("あしたはあめ", f.text.toString())
        assertEquals(0, e.reads + f.reads)
    }

    @Test fun aCursorMoveWhileASegmentIsSelectedRestoresTheKanaOnlyIntoOurSpan() {
        val e = startWithView()
        typeTomorrowRain(e)
        selectSegment()
        ime.onAction(Keyboards.CURSOR_LEFT) // あめ -> あ | め, shown as kana until the result
        Selection.setSelection(e.text, 0)
        ime.onUpdateSelection(4, 4, 0, 0, 0, e.text.length) // the user moved the cursor; our span is still there
        assertEquals("あしたはあめ", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertFalse(selecting())

        // The editor dropped the span: nothing is written again at the new cursor.
        val f = start(T.TYPE_CLASS_TEXT)
        typeTomorrowRain(f)
        selectSegment()
        ime.onAction(Keyboards.CURSOR_LEFT)
        val shown = f.text.toString()
        BaseInputConnection.removeComposingSpans(f.text)
        Selection.setSelection(f.text, 0)
        ime.onUpdateSelection(4, 4, 0, 0, -1, -1)
        assertEquals(shown, f.text.toString())
        assertEquals(-1, f.composingStart)
        assertFalse(selecting())
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertEquals(shown, f.text.toString())
        assertEquals(0, e.reads + f.reads)
    }

    @Test fun aRestartKeepsTheVisibleTextAndANewEditorIsNeverWritten() {
        val e = startWithView()
        typeTomorrowRain(e)
        selectSegment()
        pumpUntil { "飴" in chips() }
        stripChip("飴").performClick()
        // Restart with the cursor at the end of our composition: the visible text stays, nothing is written.
        val restart = EditorInfo().apply { inputType = T.TYPE_CLASS_TEXT; initialSelStart = 4; initialSelEnd = 4 }
        ime.info = restart
        ime.onStartInput(restart, true)
        assertEquals("明日は飴", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertFalse(selecting())
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) } // late results are dropped
        assertEquals("明日は飴", e.text.toString())

        // The app replaced the text, then restarted: nothing is inserted into it.
        val f = start(T.TYPE_CLASS_TEXT)
        typeTomorrowRain(f)
        selectSegment()
        ime.onAction(Keyboards.CURSOR_LEFT)
        f.text.replace(0, f.text.length, "x")
        BaseInputConnection.removeComposingSpans(f.text)
        val replaced = EditorInfo().apply { inputType = T.TYPE_CLASS_TEXT; initialSelStart = 1; initialSelEnd = 1 }
        ime.info = replaced
        ime.onStartInput(replaced, true)
        assertEquals("x", f.text.toString())
        assertEquals(-1, f.composingStart)

        // Another editor starts while a segment is selected: the new field is never written with the old composition.
        val g = start(T.TYPE_CLASS_TEXT)
        typeTomorrowRain(g)
        selectSegment()
        ime.onAction(Keyboards.CURSOR_LEFT)
        val h = start(T.TYPE_CLASS_TEXT)
        assertEquals("", h.text.toString())
        assertEquals(-1, h.composingStart)
        assertFalse(selecting())
        typeKana("あめ")
        pumpUntil { h.text.toString() == "雨" } // a fresh composition there
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) } // late results are dropped
        assertEquals("雨", h.text.toString())
        assertEquals(0, h.composingStart)
        assertEquals("x", f.text.toString())
        assertEquals(0, e.reads + f.reads + g.reads + h.reads)
    }

    @Test fun anUnrelatedComposingRangeIsNeverRewrittenOnACursorMove() {
        val e = startWithView()
        typeTomorrowRain(e)
        selectSegment()
        ime.onAction(Keyboards.CURSOR_LEFT)
        val shown = e.text.toString()
        // The editor reports a composing range that is not ours (another start); the cursor moved.
        Selection.setSelection(e.text, 0)
        ime.onUpdateSelection(4, 4, 0, 0, 1, 3)
        assertEquals(shown, e.text.toString()) // composing ended, nothing written
        assertEquals(-1, e.composingStart)
        assertFalse(selecting())
        assertEquals(0, e.reads)
    }

    // --- Direct tap on the composition (simulated editor; real Chrome/WebView not measured) ------

    private fun deliver(report: IntArray) = ime.onUpdateSelection(report[0], report[1], report[2], report[3], report[4], report[5])

    /** Delivers every report [e] owes for our composing writes, in order: an editor that has caught up. */
    private fun settle(e: FakeEditor) {
        while (e.reports.isNotEmpty()) deliver(e.reports.removeAt(0))
    }

    /** The user taps at surface [offset] of the composing span: the editor moves its caret there and reports it. */
    private fun tap(e: FakeEditor, offset: Int): IntArray {
        val old = Selection.getSelectionStart(e.text)
        val at = e.composingStart + offset
        Selection.setSelection(e.text, at)
        return intArrayOf(old, old, at, at, e.composingStart, e.composingEnd).also { deliver(it) }
    }

    /** Types あしたは、あめ and waits for the live 明日は、雨: surface 明日は 0..3 | 、 3..4 | 雨 4..5. */
    private fun typeTomorrowCommaRain(e: FakeEditor) {
        typeKana("あしたは、あめ")
        pumpUntil { e.text.toString() == "明日は、雨" }
        assertEquals("明日は、雨", e.text.toString())
        assertEquals(0, e.composingStart)
    }

    @Test fun aTapInsideTheCompositionSelectsTheSegmentUnderItByItsSurfaceOffset() {
        val context = RuntimeEnvironment.getApplication()
        val e = startWithView()
        typeTomorrowCommaRain(e)
        settle(e)
        tap(e, 1) // inside 明日は (typed あしたは: reading and surface lengths differ)
        assertEquals(listOf("明日は|", "、", "雨"), unitLabels())
        assertEquals("明日は、雨", e.text.toString()) // only the highlight moved
        assertEquals(0, e.composingStart)
        assertEquals(5, Selection.getSelectionEnd(e.text)) // our write put the caret back at the end
        settle(e) // its report is our own: no further write, no loop
        assertTrue(e.reports.isEmpty())

        tap(e, 3) // the boundary 明日は|、 selects the following segment: the punctuation
        assertEquals(listOf("明日は", "、|", "雨"), unitLabels())
        settle(e)
        tap(e, 4) // 、|雨: the last segment by surface offset (typed offset 4 would be 、)
        assertEquals(listOf("明日は", "、", "雨|"), unitLabels())
        pumpUntil { "飴" in chips() } // candidates for the tapped segment; none is chosen for it
        assertEquals("明日は、雨", e.text.toString())
        assertEquals(0, e.composingStart)
        settle(e)
        tap(e, 4) // the selected segment again: nothing changes, nothing is written
        assertTrue(e.reports.isEmpty())
        assertEquals(0, Learning.store(context).historySize)

        stripChip("飴").performClick()
        assertEquals("明日は、飴", e.text.toString())
        assertEquals(0, e.composingStart)
        assertTrue(e.keys.isEmpty())
        assertEquals(0, e.reads)
    }

    @Test fun aTapNeverSplitsASurrogatePairAndCoversKeptSegmentsWithALiveTail() {
        val e = startWithView()
        typeAme()
        ime.onText("😀") // joins the composition as passthrough
        pumpUntil { e.text.toString() == "雨😀" }
        settle(e)
        tap(e, 2) // between the halves of 😀: not a character boundary
        assertFalse(selecting())
        assertEquals("雨😀", e.text.toString())
        assertEquals(0, e.composingStart) // still composing; nothing written
        assertTrue(e.reports.isEmpty())
        tap(e, 1) // before 😀: its own segment
        assertEquals(listOf("雨", "😀|"), unitLabels())
        assertEquals("雨😀", e.text.toString())

        // Kept segments are a fixed head; a tap there selects over head and live tail.
        val f = start(T.TYPE_CLASS_TEXT)
        typeTomorrowRain(f)
        selectSegment()
        typeKana("の")
        pumpUntil { chips() == listOf("の", "ノ") }
        settle(f)
        tap(f, 1)
        assertEquals(listOf("明日は|", "雨", "の"), unitLabels())
        assertEquals("明日は雨の", f.text.toString())
        settle(f)
        tap(f, 3)
        assertEquals(listOf("明日は", "雨|", "の"), unitLabels())
        assertEquals("明日は雨の", f.text.toString())
        assertEquals(0, f.composingStart)
        assertEquals(0, e.reads + f.reads)
    }

    @Test fun aTapIsIgnoredWhileAResultIsPendingAndOtherReportsKeepTheirSafeEnd() {
        val e = startWithView()
        typeTomorrowRain(e)
        typeKana("の") // request in flight: 明日は雨の is shown, no result for it applied yet
        settle(e)
        tap(e, 1)
        assertFalse(selecting()) // nothing reliable to align: ignored, not dropped
        assertEquals("明日は雨の", e.text.toString())
        assertEquals(0, e.composingStart)
        assertTrue(e.reports.isEmpty())
        pumpUntil { expandOf(liveRoot!!).text == "▼" } // the result arrived
        assertEquals("明日は雨の", e.text.toString())
        settle(e)
        tap(e, 2)
        assertTrue(selecting())
        assertEquals("明日は雨の", e.text.toString())

        // The editor finished the composition itself: no span is reported, the composition is not recreated.
        val f = start(T.TYPE_CLASS_TEXT)
        typeTomorrowRain(f)
        settle(f)
        BaseInputConnection.removeComposingSpans(f.text)
        Selection.setSelection(f.text, 1)
        ime.onUpdateSelection(4, 4, 1, 1, -1, -1)
        Selection.setSelection(f.text, 2)
        ime.onUpdateSelection(1, 1, 2, 2, -1, -1) // a later tap in the committed text
        assertEquals("明日は雨", f.text.toString())
        assertEquals(-1, f.composingStart)
        assertFalse(selecting())
        assertTrue(f.reports.isEmpty()) // never written again

        // A span that is not ours (another start): composition ends without writing.
        val g = start(T.TYPE_CLASS_TEXT)
        typeTomorrowRain(g)
        settle(g)
        Selection.setSelection(g.text, 2)
        ime.onUpdateSelection(4, 4, 2, 2, 1, 3)
        assertEquals("明日は雨", g.text.toString())
        assertEquals(-1, g.composingStart)
        assertFalse(selecting())
        assertTrue(g.reports.isEmpty())

        // A range selection inside our span is not a tap: the existing end (kana restored into our span).
        val h = start(T.TYPE_CLASS_TEXT)
        typeTomorrowRain(h)
        settle(h)
        Selection.setSelection(h.text, 1, 3)
        ime.onUpdateSelection(4, 4, 1, 3, 0, 4)
        assertEquals("あしたはあめ", h.text.toString())
        assertEquals(-1, h.composingStart)
        assertFalse(selecting())

        // After a restart a report for the old span finds no composition: nothing is written.
        val k = start(T.TYPE_CLASS_TEXT)
        typeTomorrowRain(k)
        settle(k)
        val restart = EditorInfo().apply { inputType = T.TYPE_CLASS_TEXT; initialSelStart = 4; initialSelEnd = 4 }
        ime.info = restart
        ime.onStartInput(restart, true)
        Selection.setSelection(k.text, 1)
        ime.onUpdateSelection(4, 4, 1, 1, 0, 4)
        assertEquals("明日は雨", k.text.toString())
        assertEquals(-1, k.composingStart)
        assertFalse(selecting())
        assertTrue(k.reports.isEmpty())

        // Password fields have no composition to tap.
        val secret = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)
        typeKana("あめ")
        Selection.setSelection(secret.text, 1)
        ime.onUpdateSelection(2, 2, 1, 1, -1, -1)
        assertEquals("あめ", secret.text.toString())
        assertFalse(selecting())
        assertEquals(0, e.reads + f.reads + g.reads + h.reads + k.reads + secret.reads)
    }

    @Test fun lateDuplicateAndLaggingReportsNeverReselectAndLateResultsFollowTheTappedSegment() {
        val e = startWithView()
        typeTomorrowCommaRain(e)
        tap(e, 2) // before the editor reported the live result: cannot be attributed to it
        assertFalse(selecting())
        assertEquals("明日は、雨", e.text.toString())
        assertEquals(0, e.composingStart)
        settle(e)
        val first = tap(e, 1) // the caret was left at 2: a real move
        assertEquals(listOf("明日は|", "、", "雨"), unitLabels())
        segmentChips()[2].performClick() // the user selects another segment with its button
        assertEquals(listOf("明日は", "、", "雨|"), unitLabels())
        val writes = e.reports.size
        deliver(first) // the tap's report again, before our writes were reported
        deliver(intArrayOf(4, 4, 4, 4, 0, 4)) // a lagging own report of an earlier, shorter span
        assertEquals(listOf("明日は", "、", "雨|"), unitLabels())
        assertEquals(writes, e.reports.size) // nothing written
        assertEquals("明日は、雨", e.text.toString())
        assertEquals(0, e.composingStart)

        // A resize, then another segment before that result arrives: the result belongs to the old selection.
        segmentChips()[0].performClick()
        ime.onAction(Keyboards.CURSOR_LEFT) // あした | は、: the next segment (、) takes the は
        segmentChips()[segmentChips().lastIndex].performClick()
        pumpUntil { "飴" in chips() } // the candidates belong to the selected segment
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) } // the earlier result is dropped
        assertEquals(0, e.composingStart)
        assertEquals(0, e.reads)
    }
}
