package dev.astra.ime

import android.content.ClipData
import android.os.Build
import android.os.Looper
import android.text.InputType as T
import android.text.Selection
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import dev.astra.ime.core.FlickDirection
import dev.astra.ime.core.FlickKey
import dev.astra.ime.core.FlickLayout
import dev.astra.ime.oss.MappedDictionary
import dev.astra.ime.oss.MozcInputs
import dev.astra.ime.oss.OssConverter
import dev.astra.ime.oss.OssDictionaryBuilder
import dev.astra.ime.oss.zenz.ZenzRanking
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
import org.robolectric.util.ReflectionHelpers

/**
 * The real service, worker and [ZenzRanking] over a synthetic editor, a tiny dictionary and a spy in place of the model.
 * Every editor here fails the test if it is asked for text, so a context that reaches the scorer came from EditorInfo or
 * from our own commits. These tests show what text reaches the scorer and what never reaches the store. They say nothing
 * about model accuracy, and nothing about how long a host takes on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class CommittedContextTest {
    private class Seen(val context: String, val reading: String)

    /** Records every prompt; ranks by [prefer] (the candidate gets a large bonus), else keeps the dictionary order. */
    private inner class Spy : ZenzRanking.Scorer {
        val seen: MutableList<Seen> = Collections.synchronizedList(ArrayList())
        var prefer: (String, String) -> Boolean = { _, _ -> false }
        var scored: ((String) -> Float)? = null
        /** Log probabilities by context, for tests that replay what the real model answered. */
        var scoredIn: ((String, String) -> Float)? = null
        var gate: CountDownLatch? = null
        var entered = CountDownLatch(1)
        override fun score(context: String, reading: String, candidates: List<String>): FloatArray {
            seen += Seen(context, reading)
            entered.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            return FloatArray(candidates.size) { i ->
                scoredIn?.invoke(context, candidates[i]) ?: scored?.invoke(candidates[i]) ?: if (prefer(context, candidates[i])) 0f else -20f
            }
        }
        val contexts: List<String> get() = synchronized(seen) { seen.map { it.context } }
    }

    /** A snapshot answering whatever a test wants, as another process's EditorInfo could. */
    private class Snapshot(val answer: (Int) -> CharSequence?) : EditorInfo() {
        override fun getInitialTextBeforeCursor(length: Int, flags: Int): CharSequence? = answer(length)
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private var controller: ServiceController<TestIme>? = null
    private val ime: TestIme get() = controller!!.get()
    private val spy = Spy()
    private lateinit var converter: OssConverter
    private lateinit var root: ViewGroup

    @Before fun setUp() {
        ShadowLog.clear()
        DebugSettings.setOssEnabled(app, false)
        DebugSettings.setZenzEnabled(app, false)
        DebugSettings.setCorrectionEnabled(app, false)
        DebugSettings.setCommittedContext(app, true)
        Learning.setEnabled(app, true)
        Learning.store(app).clearHistory()
        converter = tinyConverter()
        controller = Robolectric.buildService(TestIme::class.java).create()
        pumpUntil { ime.converterReady }
        ime.conversionWorker.useForTests(converter, spy)
        root = ime.onCreateInputView() as ViewGroup
    }

    @After fun tearDown() { controller?.destroy() }

    private fun tinyConverter(): OssConverter {
        val base = Files.createTempDirectory("context-dictionary")
        val src = Files.createDirectory(base.resolve("mozc"))
        fun write(name: String, lines: List<String>) =
            Files.write(src.resolve(name), lines.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8))
        write("id.def", listOf("0 BOS/EOS", "1 名詞", "2 助詞"))
        write("connection_single_column.txt", listOf("3", "0", "100", "900", "0", "300", "-100", "0", "50", "900"))
        write(
            "dictionary00.txt",
            listOf(
                "あめ\t1\t1\t3500\t雨", "あめ\t1\t1\t3600\t飴", "かさ\t1\t1\t3500\t傘", "かさ\t1\t1\t3600\t笠", "そら\t1\t1\t3000\t空", "は\t2\t2\t1000\tは",
                "わたし\t1\t1\t3000\t私", "あなうんさー\t1\t1\t3000\tアナウンサー", "はいしゃ\t1\t1\t3000\t歯医者", "です\t2\t2\t1000\tです",
                "しかい\t1\t1\t3000\t司会", "しかい\t1\t1\t3100\t視界", "しかい\t1\t1\t3200\t歯科医", "を\t2\t2\t1000\tを", "つとめます\t1\t1\t3000\t務めます",
            ),
        )
        for (i in 1..9) write("dictionary%02d.txt".format(i), emptyList())
        val compiled = base.resolve("compiled")
        OssDictionaryBuilder.build(MozcInputs.fromDirectory(src, includeSuffix = false), compiled)
        MappedDictionary.pack(compiled)
        return OssConverter(MappedDictionary.open(compiled))
    }

    private fun start(
        initial: String = "",
        type: Int = T.TYPE_CLASS_TEXT,
        start: Int = initial.length,
        end: Int = start,
        imeOptions: Int = 0,
        restart: Boolean = false,
        info: EditorInfo = EditorInfo(),
    ): FakeEditor {
        val editor = FakeEditor(View(ime), initial, maxOf(start, 0), maxOf(end, 0))
        editor.forbidReads = true
        editor.reportCommits = true
        info.inputType = type
        info.initialSelStart = start
        info.initialSelEnd = end
        info.imeOptions = imeOptions
        if (Build.VERSION.SDK_INT >= 30) info.setInitialSurroundingText(initial)
        ime.ic = editor
        ime.info = info
        ime.onStartInput(info, restart)
        return editor
    }

    private fun type(kana: String) {
        for (c in kana) {
            val flick = FlickKey.values().flatMap { k -> FlickDirection.values().map { k to it } }.firstOrNull { (k, d) -> FlickLayout.kana(k, d) == c.toString() }
            if (flick != null) ime.onFlick(flick.first, flick.second) else ime.onText(c.toString()) // で is two keys: it joins the composition
        }
    }

    private fun pumpUntil(done: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    /** Waits for the conversion of what was just typed: the scorer ran for it and its result reached the editor. */
    private fun converted(e: FakeEditor, shown: String) {
        pumpUntil { e.text.toString().endsWith(shown) }
        assertTrue(e.text.toString(), e.text.toString().endsWith(shown))
    }

    /** Delivers what the editor owes us: every report so far, in order. */
    private fun settle(e: FakeEditor) {
        while (e.reports.isNotEmpty()) e.reports.removeAt(0).let { ime.onUpdateSelection(it[0], it[1], it[2], it[3], it[4], it[5]) }
    }

    /** Types [kana] after clearing the spy and answers the context of its last prompt. */
    private fun contextFor(kana: String): String {
        spy.seen.clear()
        type(kana)
        pumpUntil { spy.seen.isNotEmpty() }
        return spy.contexts.last()
    }

    /** Commits what is composed and lets the editor report all of it. */
    private fun confirm(e: FakeEditor) {
        ime.onAction(Keyboards.ENTER)
        settle(e)
    }

    private fun views(tag: String, under: ViewGroup = root): List<View> = (0 until under.childCount).flatMap { i ->
        val child = under.getChildAt(i)
        (if (child.tag == tag) listOf(child) else emptyList()) + ((child as? ViewGroup)?.let { views(tag, it) } ?: emptyList())
    }

    private fun occurrences(text: String, of: String) = text.windowed(of.length).count { it == of }

    private val prefix = "私は歯医者です。"

    /** Every test here relies on this: a read anywhere on the main thread or the worker's fails. */
    @Test fun theEditorsHereReallyRefuseToBeRead() {
        val e = start(prefix)
        val reads = listOf<() -> Any?>(
            { e.getTextBeforeCursor(1, 0) }, { e.getTextAfterCursor(1, 0) }, { e.getSelectedText(0) },
            { e.getExtractedText(null, 0) }, { e.getCursorCapsMode(0) },
        )
        for (read in reads) assertThrows(AssertionError::class.java) { read() }
        assertEquals(reads.size, e.reads)
    }

    // --- What reaches the scorer ---------------------------------------------------------------

    @Test fun theFieldsSnapshotIsLeftContextOnceAndTheFieldIsNeverAsked() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        assertEquals(prefix, spy.contexts.last())
        assertEquals("アメ", spy.seen.last().reading)
        type("は")
        converted(e, "雨は")
        assertEquals(prefix, spy.contexts.last())
        assertEquals(0, e.reads)
    }

    @Test fun theSnapshotIsAskedOnlyForWhatContextNeeds() {
        var asked = -1
        val info = Snapshot { asked = it; prefix }
        start(prefix, info = info)
        assertEquals(CommittedContext.SEED_CHARS, asked)
    }

    @Test fun rankingFollowsWhatTheScorerSaysWithThatContext() {
        spy.prefer = { context, candidate -> context.isNotEmpty() && candidate == "飴" }
        val withContext = start("甘いものが好きです。")
        type("あめ")
        converted(withContext, "飴")
        val without = start()
        type("あめ")
        converted(without, "雨")
    }

    /** The user's two examples, typed: only the wiring of the prefix is tested here, not what the model does with it. */
    @Test fun theFirstSentenceReachesTheSecondAcrossItsCommitWithoutAskingTheField() {
        for ((first, shown) in listOf("わたしはあなうんさーです" to "私はアナウンサーです", "わたしははいしゃです" to "私は歯医者です")) {
            val e = start()
            type(first)
            converted(e, shown)
            settle(e)
            confirm(e)
            ime.onText("。")
            settle(e)
            spy.seen.clear()
            type("しかいをつとめます")
            pumpUntil { spy.seen.any { it.reading == "シカイヲツトメマス" } }
            assertEquals("$shown。", spy.seen.last { it.reading == "シカイヲツトメマス" }.context)
            assertEquals(1, occurrences(spy.contexts.last(), shown))
            confirm(e)
            assertEquals(0, e.reads)
        }
    }

    @Test fun aLiveConversionOfAnotherLengthIsNeverContextOnlyWhatWasCommittedIs() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        settle(e)
        type("は")
        converted(e, "雨は")
        pumpUntil { spy.seen.any { it.reading.endsWith("は") } } // the kana shows first, and a job still running would be seen later
        settle(e)
        assertTrue(spy.contexts.all { it == prefix })
        confirm(e)
        assertEquals(prefix + "雨は", contextFor("かさ"))
        assertEquals(0, e.reads)
    }

    @Test fun whatWasCommittedCountsOnceWhateverTheReadingsLength() {
        val e = start()
        type("あなうんさー") // six kana become six units: same length
        converted(e, "アナウンサー")
        confirm(e)
        type("あめ") // two kana become one unit
        converted(e, "雨")
        confirm(e)
        type("わたし") // three kana become one unit, shown after a longer composition
        converted(e, "私")
        confirm(e)
        assertEquals("アナウンサー雨私", contextFor("かさ"))
        assertEquals(e.text.toString().removeSuffix("傘").removeSuffix("かさ"), spy.contexts.last())
    }

    @Test fun reportsThatArriveLateTogetherOrTwiceAreStillOurs() {
        val late = start(prefix)
        type("あめ")
        converted(late, "雨")
        ime.onAction(Keyboards.ENTER) // every report of this composition is still to come, the kana's among them
        settle(late)
        assertEquals(prefix + "雨", contextFor("かさ"))

        val coalesced = start(prefix)
        type("あめ")
        converted(coalesced, "雨")
        ime.onAction(Keyboards.ENTER)
        val last = coalesced.reports.last()
        coalesced.reports.clear()
        repeat(2) { ime.onUpdateSelection(last[0], last[1], last[2], last[3], last[4], last[5]) }
        assertEquals(prefix + "雨", contextFor("かさ"))
    }

    @Test fun aCaretTappedInsideTheCompositionChangesNothingBeforeIt() {
        val e = start(prefix)
        type("そらは")
        converted(e, "空は")
        settle(e)
        Selection.setSelection(e.text, prefix.length + 1)
        ime.onUpdateSelection(prefix.length + 2, prefix.length + 2, prefix.length + 1, prefix.length + 1, prefix.length, prefix.length + 2)
        pumpUntil { views(TAG_SEGMENT).isNotEmpty() }
        ime.onAction(Keyboards.ENTER)
        settle(e)
        assertTrue(contextFor("かさ").startsWith(prefix))
    }

    // --- Snapshots ---------------------------------------------------------------------------

    /** Below API 30 EditorInfo has no text. API 28 is the oldest SDK these tests run; 26 to 29 take the same branch. */
    @Test @Config(sdk = [28]) fun beforeApi30ThereIsNoSeedNoFallbackAndOwnCommitsStillBuild() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        assertEquals("", spy.contexts.last())
        confirm(e)
        assertEquals("雨", contextFor("かさ"))
        assertEquals(0, e.reads)
    }

    @Test fun api29IsTreatedAsBelowApi30EvenWithASnapshotPresent() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 29)
        try {
            val info = Snapshot { throw AssertionError("not available below API 30") }
            val e = FakeEditor(View(ime), prefix, prefix.length, prefix.length).apply { forbidReads = true }
            info.inputType = T.TYPE_CLASS_TEXT
            info.initialSelStart = prefix.length
            info.initialSelEnd = prefix.length
            ime.ic = e
            ime.info = info
            ime.onStartInput(info, false)
            assertEquals("", contextFor("あめ"))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 30)
        }
    }

    @Test fun aSnapshotThatDisagreesOrFailsGivesNothingAndConversionGoesOn() {
        val answers = listOf<(Int) -> CharSequence?>(
            { null },
            { throw IndexOutOfBoundsException("bad parcel") },
            { "カーソル位置より長い答えカーソル位置より長い答え" },
        )
        for (answer in answers) {
            val e = start("短い。", info = Snapshot(answer))
            spy.seen.clear()
            type("あめ")
            converted(e, "雨")
            assertEquals("", spy.contexts.last())
            confirm(e)
        }
    }

    @Test fun aTruncatedSnapshotIsTextBeforeTheCursorAndACutPairIsDropped() {
        start("x".repeat(500), info = Snapshot { "\uDE00後半だけ。" })
        assertEquals("後半だけ。", contextFor("あめ"))
        ime.onAction(Keyboards.ENTER)
        val styled = android.text.SpannableString("装飾つき。").apply { setSpan(android.text.style.BackgroundColorSpan(1), 0, 2, 0) }
        start("装飾つき。", info = Snapshot { styled })
        assertEquals("装飾つき。", contextFor("あめ"))
    }

    @Test fun anUnknownCursorGivesNoSeed() {
        assertEquals("", run { start("前の文。", start = -1); contextFor("あめ") })
        ime.onAction(Keyboards.ENTER)
        assertEquals("", run { start("前の文。", info = Snapshot { "前の文。" }, start = -1); contextFor("かさ") })
    }

    @Test fun aSelectionGivesNoSeedAndWhatReplacesItStillCounts() {
        val e = start("前の文。選択中", start = 4, end = 7)
        assertEquals("", contextFor("あめ"))
        converted(e, "雨")
        confirm(e)
        assertEquals("雨", contextFor("かさ"))
    }

    @Test fun textIsBoundedWithoutSplittingAPairAndCannotCarryPromptMarks() {
        start("x" + "😀".repeat(70)) // 141 units: the 128 asked start inside a pair
        assertEquals("😀".repeat(ZenzRanking.CONTEXT_CHARS), contextFor("あめ"))
        ime.onAction(Keyboards.ENTER)

        start("前文の\n改行​。</s>")
        assertEquals("前文の改行。</s>", contextFor("かさ"))
    }

    // --- Own commits -------------------------------------------------------------------------

    @Test fun punctuationSpacesAndEnglishCountInTheOrderTyped() {
        val e = start()
        ime.onText("、")
        ime.onAction(Keyboards.TO_ASCII)
        for (c in "ab") ime.onText(c.toString())
        ime.onAction(Keyboards.SPACE) // commits the word, then the space
        settle(e)
        ime.onAction(Keyboards.TO_KANA)
        assertEquals("、ab ", contextFor("あめ"))
        assertEquals("、ab あめ", e.text.toString().let { if (it.endsWith("雨")) it.replace("雨", "あめ") else it })
    }

    @Test fun anEmojiCommitKeepsOffsetsInUtf16Units() {
        val e = start("前。")
        ime.onText("😀")
        settle(e) // two units: a report one unit away would forget the text
        assertEquals("前。😀", contextFor("あめ"))
    }

    @Test fun ownCommitsStayBoundedAndSanitized() {
        val e = start()
        ime.onText("あ".repeat(50))
        settle(e)
        ime.onText("線\n​😀")
        settle(e)
        assertEquals("あ".repeat(ZenzRanking.CONTEXT_CHARS - 2) + "線😀", contextFor("あめ"))
    }

    @Test fun aLineBreakKeepsTheTextIfTheEditorReportsIt() {
        val e = start(prefix, type = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE)
        ime.onAction(Keyboards.ENTER)
        ime.onUpdateSelection(8, 8, 9, 9, -1, -1)
        assertEquals(prefix, contextFor("あめ"))
    }

    @Test fun enterThatDidNotBreakTheLineForgets() {
        // No action of its own, yet the host sent and cleared the field.
        val cleared = start(prefix, type = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE)
        ime.onAction(Keyboards.ENTER)
        ime.onUpdateSelection(8, 8, 0, 0, -1, -1)
        assertEquals("", contextFor("あめ"))
        ime.onAction(Keyboards.ENTER)
        val action = start(prefix, imeOptions = EditorInfo.IME_ACTION_SEND)
        ime.onAction(Keyboards.ENTER)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), action.actions)
        assertEquals("", contextFor("あめ"))
        assertEquals(0, cleared.reads + action.reads)
    }

    @Test fun aDeletedCompositionLeavesTheTextBeforeItAlone() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        settle(e)
        ime.onAction(Keyboards.BACKSPACE)
        settle(e)
        ime.onAction(Keyboards.BACKSPACE) // the composition is gone
        settle(e)
        assertEquals(prefix, e.text.toString())
        assertEquals(prefix, contextFor("かさ"))
    }

    @Test fun deletingCommittedTextKeepsWhatIsBeforeTheCursor() {
        val e = start("前の文。消える")
        ime.onAction(Keyboards.BACKSPACE)
        ime.onUpdateSelection(7, 7, 6, 6, -1, -1)
        assertEquals("前の文。消え", contextFor("あめ"))
        converted(e, "雨")
        settle(e)
        confirm(e)
        assertEquals("前の文。消え雨", contextFor("かさ"))
        assertEquals(0, e.reads)
    }

    @Test fun deletingBeforeTheReportCannotKeepOldText() {
        val e = start("前の文。消える")
        ime.onAction(Keyboards.BACKSPACE) // no report yet: the cursor is unknown
        assertEquals("", contextFor("あめ"))
    }

    @Test fun deletingASelectionForgets() {
        val e = start(prefix)
        Selection.setSelection(e.text, 2, 4)
        ime.onUpdateSelection(8, 8, 2, 4, -1, -1)
        ime.onAction(Keyboards.BACKSPACE)
        settle(e)
        assertEquals("", contextFor("あめ"))
    }

    @Test fun kanaAfterAnEnglishWordStartsFromTheWordsOwnOffset() {
        val e = start("前の文。")
        ime.onAction(Keyboards.TO_ASCII)
        ime.onText("a")
        settle(e)
        ime.onAction(Keyboards.TO_KANA)
        assertEquals("前の文。a", contextFor("あめ")) // head, as before
        settle(e)
        assertEquals("前の文。a", spy.contexts.last())
    }

    // --- Doubt -------------------------------------------------------------------------------

    @Test fun aCursorMovedOutOfWhatIsKnownInventsNothingAndLaterCommitsBuildAgain() {
        val e = start("前の文。".repeat(40), start = 160)
        Selection.setSelection(e.text, 8)
        ime.onUpdateSelection(160, 160, 8, 8, -1, -1) // the snapshot reaches back 128 units
        assertEquals("", contextFor("かさ"))
        pumpUntil { "傘" in e.text.toString() }
        settle(e)
        confirm(e)
        assertEquals("傘", contextFor("あめ"))
    }

    @Test fun theCursorLeavingACompositionForgetsIt() {
        val text = "前の文。後の文。"
        val e = start(text)
        type("あめ")
        converted(e, "雨")
        settle(e)
        Selection.setSelection(e.text, 4)
        ime.onUpdateSelection(9, 9, 4, 4, 8, 9)
        assertEquals(-1, e.composingStart)
        assertEquals("", contextFor("かさ"))
    }

    @Test fun anArrowKeyKnowsNothingUntilTheEditorReportsWhereItLanded() {
        start(prefix)
        ime.onAction(Keyboards.CURSOR_LEFT)
        assertEquals("", contextFor("あめ"))
    }

    @Test fun aSelectionByTheUserForgetsEvenAfterItCollapsesAgain() {
        start(prefix)
        ime.onUpdateSelection(8, 8, 2, 5, -1, -1)
        ime.onUpdateSelection(2, 5, 8, 8, -1, -1)
        assertEquals("", contextFor("あめ"))
    }

    @Test fun someoneElsesComposingSpanForgets() {
        start("前の文。下書き")
        ime.onUpdateSelection(7, 7, 7, 7, 4, 7)
        assertEquals("", contextFor("かさ"))
    }

    @Test fun anEditReportedByTheHostForgets() {
        val e = start(prefix)
        e.text.insert(0, "新しい")
        ime.onUpdateSelection(8, 8, 11, 11, -1, -1)
        assertEquals("", contextFor("あめ"))
    }

    @Test fun aReportFarFromOurCommitForgetsItToo() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        settle(e)
        ime.onAction(Keyboards.ENTER)
        e.reports.clear()
        ime.onUpdateSelection(9, 9, 8, 8, -1, -1) // a filter refused the commit though the connection took it
        assertEquals(prefix, contextFor("かさ")) // the offset is the one before the commit, and that text is right
    }

    @Test fun aSpanMovedByAnUnknownEditEndsTheCompositionAndItsContext() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        settle(e)
        assertEquals(prefix, spy.contexts.last())
        e.text.insert(0, "新しい")
        ime.onUpdateSelection(prefix.length + 1, prefix.length + 1, prefix.length + 4, prefix.length + 4, prefix.length + 3, prefix.length + 4)
        assertEquals(-1, e.composingStart)
        assertEquals("", contextFor("かさ"))
    }

    @Test fun refusedWritesForgetAndNothingOfThemIsAdded() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        settle(e)
        e.acceptsCommits = false
        ime.onAction(Keyboards.ENTER)
        e.acceptsCommits = true
        assertEquals("", contextFor("かさ"))

        val f = start(prefix)
        f.acceptsComposing = false
        assertEquals("", contextFor("あめ"))
    }

    @Test fun aHostThatNeverReportsLosesWhatItCannotConfirm() {
        start(prefix)
        repeat(CommittedContext.MAX_EXPECTED) { ime.onText("。") }
        assertEquals("", contextFor("あめ"))
    }

    /** The documented limit: offsets are all that is checked, so this is ranking with outdated text, once. */
    @Test fun aSilentSameLengthRewriteIsNotDetected() {
        val e = start(prefix)
        e.text.replace(0, 2, "僕は")
        ime.onUpdateSelection(8, 8, 8, 8, -1, -1)
        assertEquals(prefix, contextFor("あめ"))
    }

    @Test fun aPasteForgetsAndIsNeverContextOrLearnedAndTypingBuildsAgain() {
        val e = start(prefix)
        ime.onStartInputView(ime.info!!, false)
        ime.clip.copy(ClipData.newPlainText("", "貼り付けた文章"))
        pumpUntil { root.findViewWithTag<View>(TAG_CLIP_PREVIEW) != null }
        root.findViewWithTag<View>(TAG_CLIP_PREVIEW).performClick()
        settle(e)
        assertEquals(prefix + "貼り付けた文章", e.text.toString())
        assertEquals("", contextFor("あめ"))
        converted(e, "雨")
        ime.onAction(Keyboards.CONVERT)
        confirm(e)
        assertEquals("飴", contextFor("かさ"))
        pumpUntil { "飴" in learningFiles() }
        assertFalse("貼り付け" in learningFiles())
        assertEquals(0, e.reads)
    }

    // --- The text before the cursor, wherever it is -------------------------------------------

    private val announcer = "私はアナウンサーです。"
    private val dentist = "私は歯医者です。"
    private val reading = "しかいをつとめます"

    /** Types and commits one sentence with its full stop, as a user would, and lets the editor report all of it. */
    private fun sentence(e: FakeEditor, kana: String, shown: String) {
        type(kana)
        converted(e, shown)
        settle(e)
        confirm(e)
        ime.onText("。")
        settle(e)
    }

    private fun announcerThenDentist(e: FakeEditor) {
        sentence(e, "わたしはあなうんさーです", "私はアナウンサーです")
        sentence(e, "わたしははいしゃです", "私は歯医者です")
        assertEquals(announcer + dentist, e.text.toString())
    }

    /** The user tapped in the field: the editor moves its cursor and reports it. */
    private fun tapAt(e: FakeEditor, offset: Int) {
        val from = Selection.getSelectionStart(e.text)
        Selection.setSelection(e.text, offset)
        ime.onUpdateSelection(from, from, offset, offset, -1, -1)
    }

    /** What the scorer saw before the whole of [reading], the composition then left as it is. */
    private fun contextBefore(kana: String = reading): String {
        val prompt = ZenzRanking.katakana(kana)
        spy.seen.clear()
        type(kana)
        pumpUntil { spy.seen.any { it.reading == prompt } }
        return spy.seen.last { it.reading == prompt }.context
    }

    @Test fun backAfterTheFirstSentenceTheContextIsThatSentenceNotTheLatestTyped() {
        val e = start()
        announcerThenDentist(e)
        tapAt(e, announcer.length)
        assertEquals(announcer, contextBefore())
        assertEquals(0, e.reads)
    }

    @Test fun inTheReverseOrderTheContextIsTheOtherSentence() {
        val e = start()
        sentence(e, "わたしははいしゃです", "私は歯医者です")
        sentence(e, "わたしはあなうんさーです", "私はアナウンサーです")
        tapAt(e, dentist.length)
        assertEquals(dentist, contextBefore())
    }

    @Test fun afterTheLastSentenceBothAreContext() {
        val e = start()
        announcerThenDentist(e)
        assertEquals(announcer + dentist, contextBefore())
    }

    @Test fun backAndForthEndsWhereItStarted() {
        val e = start()
        announcerThenDentist(e)
        tapAt(e, announcer.length)
        tapAt(e, (announcer + dentist).length)
        assertEquals(announcer + dentist, contextBefore())
    }

    @Test fun aMoveInsideASentenceCutsTheContextThere() {
        val e = start()
        announcerThenDentist(e)
        tapAt(e, announcer.length + 2)
        assertEquals(announcer + "私は", contextBefore())
    }

    @Test fun textInsertedInTheMiddleCountsAndKeepsWhatFollowsInPlace() {
        val e = start()
        announcerThenDentist(e)
        tapAt(e, announcer.length)
        type("あめ")
        pumpUntil { "雨" in e.text.toString() }
        settle(e)
        confirm(e)
        ime.onText("。")
        settle(e)
        assertEquals(announcer + "雨。" + dentist, e.text.toString())
        assertEquals(announcer + "雨。", contextBefore("かさ"))
        confirm(e)
        val inserted = e.text.toString().substring(0, announcer.length + 3) // 雨。 and the committed かさ
        assertEquals(inserted, contextBefore("そら"))
        confirm(e)
        tapAt(e, e.text.length)
        assertEquals(e.text.toString().takeLast(ZenzRanking.CONTEXT_CHARS), contextBefore("あめ"))
    }

    @Test fun aLineBetweenTheSentencesKeepsTheOffsets() {
        val e = start(type = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE)
        sentence(e, "わたしはあなうんさーです", "私はアナウンサーです")
        ime.onAction(Keyboards.ENTER)
        val after = e.text.length + 1
        e.text.insert(e.text.length, "\n")
        ime.onUpdateSelection(after - 1, after - 1, after, after, -1, -1)
        sentence(e, "わたしははいしゃです", "私は歯医者です")
        tapAt(e, announcer.length)
        assertEquals(announcer, contextBefore())
        confirm(e)
        tapAt(e, e.text.length)
        assertTrue(spy.contexts.isNotEmpty())
        assertEquals(e.text.toString().replace("\n", ""), contextBefore("あめ"))
    }

    @Test fun anArrowKeyMovesTheContextWithTheCursorOnceTheEditorReportsIt() {
        val e = start(prefix)
        ime.onAction(Keyboards.CURSOR_LEFT)
        assertEquals(1, e.keys.size)
        ime.onUpdateSelection(8, 8, 7, 7, -1, -1)
        assertEquals("私は歯医者です", contextFor("あめ"))
    }

    @Test fun theFieldsOwnSnapshotReachesBackToo() {
        val e = start("前の文。後の文。")
        tapAt(e, 4)
        assertEquals("前の文。", contextFor("かさ"))
    }

    @Test fun deletingKeepsWhatIsBeforeTheCursorAndNothingAfterIt() {
        val e = start()
        announcerThenDentist(e)
        tapAt(e, announcer.length)
        ime.onAction(Keyboards.BACKSPACE)
        settle(e)
        ime.onUpdateSelection(announcer.length, announcer.length, announcer.length - 1, announcer.length - 1, -1, -1)
        assertEquals(announcer.dropLast(1), contextBefore())
        confirm(e)
        tapAt(e, e.text.length)
        assertEquals("", contextBefore("あめ")) // what followed the delete moved by an amount not worked out
    }

    @Test fun aSelectionLosesEverythingKnownEvenWhereItWasRightBefore() {
        val e = start()
        announcerThenDentist(e)
        ime.onUpdateSelection(19, 19, 2, 5, -1, -1)
        ime.onUpdateSelection(2, 5, 11, 11, -1, -1)
        assertEquals("", contextBefore())
    }

    @Test fun aMoveOutsideWhatIsKnownIsNothingAndABareMoveInventsNothing() {
        val e = start("後の文。".repeat(40), start = 160)
        tapAt(e, 4) // the snapshot reaches back 128 units only
        assertEquals("", contextFor("かさ"))
    }

    @Test fun aMoveBackToTheStartForgetsEverythingAfterIt() {
        val e = start(prefix, type = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE)
        ime.onUpdateSelection(8, 8, 0, 0, -1, -1) // cleared or sent as far as anyone can tell
        ime.onUpdateSelection(0, 0, 8, 8, -1, -1)
        assertEquals("", contextFor("あめ"))
    }

    @Test fun whatIsKnownStaysBounded() {
        val e = start()
        repeat(CommittedContext.MAX_KNOWN + 8) { ime.onText("。"); settle(e) }
        tapAt(e, 2) // scrolled out of what is kept
        assertEquals("", contextFor("かさ"))
    }

    @Test fun aPasteLosesWhatCameBeforeItToo() {
        val e = start(prefix)
        ime.onStartInputView(ime.info!!, false)
        ime.clip.copy(ClipData.newPlainText("", "貼り付けた文章"))
        pumpUntil { root.findViewWithTag<View>(TAG_CLIP_PREVIEW) != null }
        root.findViewWithTag<View>(TAG_CLIP_PREVIEW).performClick()
        settle(e)
        tapAt(e, 4)
        assertEquals("", contextFor("あめ"))
    }

    @Test fun protectedAndSwitchedOffFieldsKnowNothingAfterAMove() {
        val secret = start(prefix, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        tapAt(secret, 4)
        assertEquals("", contextFor("あめ"))
        DebugSettings.setCommittedContext(app, false)
        val off = start(prefix)
        tapAt(off, 4)
        assertEquals("", contextFor("かさ"))
    }

    @Test fun aRestartInTheMiddleTakesTheSnapshotOfThatCursor() {
        start(announcer + dentist, start = announcer.length)
        assertEquals(announcer, contextBefore())
    }

    @Test fun anotherFieldKnowsNothingOfTheFirst() {
        val first = start()
        announcerThenDentist(first)
        val other = start("別の欄の文。後ろ")
        tapAt(other, 6)
        assertEquals("別の欄の文。", contextBefore())
    }

    /** The documented limit: an edit the host makes in known text, landing the cursor on an offset that fits, reads as a move. */
    @Test fun anEditThatLandsLikeAMoveInsideWhatIsKnownIsNotDetected() {
        val e = start("前の文。後の文。")
        tapAt(e, 4)
        e.text.insert(4, "外の")
        Selection.setSelection(e.text, 6)
        ime.onUpdateSelection(4, 4, 6, 6, -1, -1)
        assertEquals("前の文。後の", contextFor("かさ"))
    }

    @Test fun aHostWhoseOffsetsDoNotContinueLosesTheContextOnAMoveOntoAnEarlierCommit() {
        val e = start(prefix)
        repeat(3) { ime.onText("。"); settle(e) }
        // Old offsets that do not continue, on the offset of a recent write: not told from a report out of order.
        Selection.setSelection(e.text, prefix.length + 1)
        ime.onUpdateSelection(1, 1, prefix.length + 1, prefix.length + 1, -1, -1)
        assertEquals("", contextFor("あめ"))
    }

    // --- Learned phrases against the text before the cursor ------------------------------------

    private val hostScript = "司会を務めます"
    private val dentalWork = "歯科医を務めます"

    /** What zenz-v3.2 answered on the real dictionary (reports/context-learning-0.1.29.md); anything else is unlikely. */
    private fun replayTheModel() {
        spy.scoredIn = { context, candidate ->
            val (host, dental) = when {
                context.endsWith(dentist) -> -1.89f to -0.40f
                context.endsWith(announcer) -> -0.07f to -6.35f
                context.isEmpty() -> -0.13f to -4.66f
                else -> -0.25f to -4.06f
            }
            when (candidate) {
                hostScript -> host
                dentalWork -> dental
                else -> -12f
            }
        }
    }

    private fun learned(surface: String) {
        val store = Learning.store(app)
        store.learn(reading, surface, listOf(dev.astra.ime.oss.Word("しかい", surface.substringBefore("を"))))
    }

    /** The second choice, which is one 変換 away, for tests that care what was not put first. */
    private fun second(e: FakeEditor): String {
        ime.onAction(Keyboards.CONVERT)
        return e.text.toString()
    }

    @Test fun theFailureAsReported() {
        replayTheModel()
        learned(dentalWork)
        val e = start()
        announcerThenDentist(e)
        tapAt(e, announcer.length)
        type(reading)
        converted(e, hostScript + dentist)
        assertEquals(announcer + hostScript + dentist, e.text.toString())
        assertEquals(announcer + dentalWork + dentist, second(e))
    }

    @Test fun aLearnedPhraseStaysFirstWithNothingBeforeIt() {
        replayTheModel()
        learned(dentalWork)
        val e = start()
        type(reading)
        converted(e, dentalWork)
    }

    @Test fun aLearnedPhraseStaysFirstWhereTheTextBeforeItSaysNothingAgainstIt() {
        replayTheModel()
        learned(dentalWork)
        val e = start("今日はいい天気です。")
        type(reading)
        converted(e, dentalWork)
    }

    @Test fun aLearnedPhraseTheContextAgreesWithStaysFirst() {
        replayTheModel()
        learned(dentalWork)
        val e = start(dentist)
        type(reading)
        converted(e, dentalWork)
    }

    @Test fun aLearnedPhraseStaysFirstWhenTheContextLeansAgainstItOnlyALittle() {
        replayTheModel()
        learned(hostScript)
        val e = start(dentist)
        type(reading)
        converted(e, hostScript) // 1.49 nats: the model prefers the other one, not by enough to overrule a choice
    }

    @Test fun withLearningOffThereIsNoPhraseAndTheContextDecidesNothingAtAll() {
        replayTheModel()
        learned(dentalWork)
        Learning.setEnabled(app, false)
        val e = start(announcer)
        type(reading)
        converted(e, hostScript)
    }

    @Test fun withContextOffALearnedPhraseIsFirstAndNoExtraPromptRuns() {
        replayTheModel()
        learned(dentalWork)
        DebugSettings.setCommittedContext(app, false)
        val e = start(announcer)
        type(reading)
        converted(e, dentalWork)
        assertTrue(spy.contexts.all { it.isEmpty() })
    }

    @Test fun anotherReadingIsNotAffectedByTheLearnedPhrase() {
        replayTheModel()
        learned(dentalWork)
        val e = start(announcer)
        type("あめ")
        converted(e, "雨")
    }

    @Test fun aPhraseUsingARegisteredWordIsNeverOverruled() {
        spy.scoredIn = { context, candidate -> if (candidate == "登録語を務めます") -9f - context.length else 0f }
        val store = Learning.store(app)
        store.register("しかい", "登録語")
        store.learn(reading, "登録語を務めます", listOf(dev.astra.ime.oss.Word("しかい", "登録語")))
        val e = start(announcer)
        type(reading)
        converted(e, "登録語を務めます")
        store.unregister("しかい", "登録語")
    }

    @Test fun aChoiceAgainstTheContextIsStillLearnedAndNothingOfTheContext() {
        replayTheModel()
        val e = start(announcer)
        type(reading)
        converted(e, hostScript)
        assertEquals(announcer + dentalWork, second(e))
        ime.onAction(Keyboards.ENTER)
        pumpUntil { dentalWork in learningFiles() }
        assertNoSurrounding(learningFiles().replace(reading, ""))
    }

    // --- Fields ------------------------------------------------------------------------------

    @Test fun aRestartAndAnotherFieldStartOverFromTheirOwnSnapshots() {
        val first = start("一つ目の欄。")
        type("あめ")
        converted(first, "雨")
        val restarted = start("再開した欄。", restart = true)
        assertEquals("再開した欄。", contextFor("かさ"))
        val other = start("別の欄。")
        assertEquals("別の欄。", contextFor("かさ"))
        assertTrue(spy.contexts.none { "一つ目" in it || "再開" in it })
        start()
        assertEquals("", contextFor("あめ"))
        assertEquals(0, restarted.reads + other.reads)
    }

    /** InputMethodService repeats the old EditorInfo after a configuration change; by then it describes an old field. */
    @Test fun aRepeatedEditorInfoIsNotReused() {
        val info = EditorInfo()
        val e = start(prefix, info = info)
        type("あめ")
        converted(e, "雨")
        confirm(e)
        ime.onStartInput(info, true)
        assertEquals("", contextFor("かさ"))
        ime.onAction(Keyboards.ENTER)
        assertEquals("傘", contextFor("そら"))
    }

    @Test fun anEditorInfoFirstSeenWithTheSwitchOffIsNotUsedLater() {
        DebugSettings.setCommittedContext(app, false)
        val info = EditorInfo()
        val e = start(prefix, info = info)
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.ENTER)
        DebugSettings.setCommittedContext(app, true)
        ime.onStartInput(info, true)
        assertEquals("", contextFor("かさ"))
    }

    @Test fun hidingForgetsAndShowingAgainDoesNotReseed() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        ime.onFinishInputView(false)
        assertEquals(-1, e.composingStart)
        ime.onStartInputView(ime.info!!, true)
        settle(e)
        assertEquals("", contextFor("かさ"))
    }

    @Test fun hidingWithNothingComposedForgetsToo() {
        start(prefix)
        ime.onFinishInputView(false)
        ime.onStartInputView(ime.info!!, true)
        assertEquals("", contextFor("あめ"))
    }

    @Test fun finishingForgets() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        ime.onFinishInput()
        val next = start()
        assertEquals("", contextFor("かさ"))
        assertEquals(0, e.reads + next.reads)
    }

    @Test fun protectedFieldsGetNoSeedAndBuildNothingAndStillConvert() {
        val fields = listOf(
            T.TYPE_CLASS_TEXT to EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_NO_SUGGESTIONS to 0,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI to 0,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS to 0,
        )
        for ((type, options) in fields) {
            val e = start(prefix, type = type, imeOptions = options)
            if (type and T.TYPE_MASK_VARIATION == T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) ime.onAction(Keyboards.TO_KANA)
            spy.seen.clear()
            type("あめ")
            converted(e, "雨")
            assertEquals("", spy.contexts.last())
            confirm(e)
            assertEquals("", contextFor("かさ"))
            assertEquals(0, e.reads)
        }
    }

    @Test fun passwordAndRawFieldsNeitherUseContextNorScore() {
        for (type in listOf(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_PASSWORD, T.TYPE_NULL)) {
            val e = start(prefix, type = type)
            spy.seen.clear()
            ime.onFlick(FlickKey.A, FlickDirection.CENTER)
            pumpUntil { true }
            assertEquals(prefix + "あ", e.text.toString())
            assertEquals(0, e.reads)
            assertTrue(spy.seen.isEmpty())
        }
    }

    // --- The switch --------------------------------------------------------------------------

    @Test fun switchingOffInTheSameFieldClearsAtOnceAndOnDoesNotBringItBack() {
        // The settings screen keeps its try box focused while a switch changes, so the field does not restart.
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        assertEquals(prefix, spy.contexts.last())
        DebugSettings.setCommittedContext(app, false)
        DebugSettings.setCommittedContext(app, true) // gone already, not merely hidden
        assertEquals("", contextFor("は"))
        confirm(e)
        DebugSettings.setCommittedContext(app, false)
        assertEquals("", contextFor("かさ"))
        confirm(e) // kept nothing of this
        DebugSettings.setCommittedContext(app, true)
        assertEquals("", contextFor("そら"))
        confirm(e)
        assertEquals("空", contextFor("あめ"))
        assertEquals(0, e.reads)
    }

    @Test fun aConversionInFlightWhenTheSwitchGoesOffIsRankedAgainWithoutTheText() {
        val gate = CountDownLatch(1)
        spy.gate = gate
        spy.prefer = { context, candidate -> context.isNotEmpty() && candidate == "飴" }
        val e = start(prefix)
        type("あめ")
        assertTrue(spy.entered.await(5, TimeUnit.SECONDS))
        DebugSettings.setCommittedContext(app, false)
        spy.gate = null
        gate.countDown()
        converted(e, "雨")
        assertEquals("", spy.contexts.last())
        assertEquals(prefix + "雨", e.text.toString()) // never 飴, what the forgotten text would have ranked first
    }

    @Test fun offFromTheStartAndNoJudgeUseNothing() {
        DebugSettings.setCommittedContext(app, false)
        val off = start(prefix)
        type("あめ")
        converted(off, "雨")
        assertEquals("", spy.contexts.last())
        confirm(off)
        assertEquals("", contextFor("かさ"))
        DebugSettings.setCommittedContext(app, true)
        controller!!.destroy()
        controller = Robolectric.buildService(TestIme::class.java).create()
        pumpUntil { ime.converterReady }
        val noJudge = start(prefix)
        type("あめ")
        pumpUntil { noJudge.text.toString() == prefix + "雨" }
        assertEquals(0, noJudge.reads)
    }

    @Test fun aClearPreferenceMovesToFirstOnlyWithCommittedTextNeverWithRepairedUnits() {
        val third = OssBackend.convertOrNull(converter, "あめ", 20)!!.candidates[2].surface
        // Two nats over 雨 and two ranks behind it: the rank prior alone keeps 雨 first, the shipped margin moves it.
        spy.scored = { if (it == third) -1f else -3f }
        val bare = start()
        type("あめ")
        converted(bare, "雨")
        ime.onAction(Keyboards.ENTER)
        val e = start(prefix)
        type("あめ")
        converted(e, third)
        ime.onAction(Keyboards.ENTER)

        var first: String? = null
        ime.conversionWorker.submitText("あめ", null, "打った", arrange = false) { result, _ -> first = result.first().surface }
        pumpUntil { first != null }
        assertEquals("雨", first) // text before it, but no committed context: the head and repaired units stay baseline
        first = null
        ime.conversionWorker.submitText("あめ", null, "打った", arrange = false, external = true) { result, _ -> first = result.first().surface }
        pumpUntil { first != null }
        assertEquals(third, first)
    }

    @Test fun anEmptyContextIsExactlyTheBaseline() {
        spy.prefer = { context, candidate -> context.isEmpty() && candidate == "飴" }
        val bare = start()
        type("あめ")
        converted(bare, "飴")
        val strip = bare.text.toString()
        DebugSettings.setCommittedContext(app, false)
        spy.seen.clear()
        val off = start(prefix)
        type("あめ")
        converted(off, strip)
        assertEquals(listOf(""), spy.contexts.distinct())
    }

    @Test fun committedTextIsNeverTouched() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.CONVERT)
        settle(e)
        assertEquals(prefix + "飴", e.text.toString())
        assertTrue(e.composingStart >= prefix.length)
        confirm(e)
        assertEquals(prefix + "飴", e.text.toString())
        // Backing out of a composition leaves the sentence as it was.
        type("かさ")
        converted(e, "傘")
        ime.onAction(Keyboards.BACKSPACE)
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals(prefix + "飴", e.text.toString())
        assertEquals(0, e.reads)
    }

    @Test fun aLateResultForAFinishedContextIsDropped() {
        val gate = CountDownLatch(1)
        spy.gate = gate
        spy.prefer = { _, candidate -> candidate == "飴" }
        val e = start(prefix)
        type("あめ")
        assertTrue(spy.entered.await(5, TimeUnit.SECONDS))
        val other = start("別の欄。")
        gate.countDown()
        spy.gate = null
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(prefix + "あめ", e.text.toString()) // the kana, not the late 飴
        assertEquals("別の欄。", other.text.toString())
    }

    @Test fun aLateResultAfterACursorMoveIsDropped() {
        val gate = CountDownLatch(1)
        spy.gate = gate
        spy.prefer = { _, candidate -> candidate == "飴" }
        val e = start("前の文。後の文。")
        type("あめ")
        assertTrue(spy.entered.await(5, TimeUnit.SECONDS))
        Selection.setSelection(e.text, 4)
        ime.onUpdateSelection(10, 10, 4, 4, 8, 10)
        gate.countDown()
        spy.gate = null
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals("前の文。後の文。あめ", e.text.toString())
    }

    @Test fun aLateResultAfterACommitChangesNothingAndTheCommitCountsOnce() {
        val gate = CountDownLatch(1)
        spy.gate = gate
        spy.prefer = { _, candidate -> candidate == "飴" }
        val e = start(prefix)
        type("あめ")
        assertTrue(spy.entered.await(5, TimeUnit.SECONDS))
        ime.onAction(Keyboards.ENTER) // the kana as shown: no conversion was there yet
        settle(e)
        gate.countDown()
        spy.gate = null
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(prefix + "あめ", e.text.toString())
        assertEquals(prefix + "あめ", contextFor("かさ"))
    }

    /** Opening the panel while a segment's conversion waits used to evict that conversion: its candidates never came. */
    @Test fun aPanelLookupDoesNotEvictAWaitingConversionNorTheOtherWayRound() {
        val worker = ime.conversionWorker
        val gate = CountDownLatch(1)
        spy.gate = gate
        worker.submitText("あめ", null, "", arrange = false) { _, _ -> } // holds the worker's thread in the scorer
        assertTrue(spy.entered.await(5, TimeUnit.SECONDS))
        var converted: List<String>? = null
        var looked = ""
        worker.submitText("かさ", null, "", arrange = false) { result, _ -> converted = result.map { it.surface } }
        worker.submitExact("そら", null) { looked = "first" }
        worker.submitExact("かさ", null) { looked = it.exact.joinToString(",") { c -> c.surface } }
        spy.gate = null
        gate.countDown()
        pumpUntil { converted != null && looked.isNotEmpty() }
        assertEquals("傘", converted?.first())
        assertTrue(looked, "笠" in looked) // the newest lookup, not the one it replaced
    }

    @Test fun segmentsGetCommittedTextPlusEarlierUnitsExactlyOnce() {
        val e = start(prefix)
        type("そらはかさあめ")
        converted(e, "空は傘雨")
        settle(e)
        val segments = views(TAG_SEGMENT)
        assertEquals(listOf("空は", "傘", "雨"), segments.map { (it as android.widget.TextView).text.toString() })
        spy.seen.clear()
        segments[1].performClick()
        pumpUntil { spy.seen.isNotEmpty() }
        assertEquals(prefix + "空は", spy.contexts.last())
        // かさ|あめ is resized to かさあ|め and back: the second job converts both parts, the second after the first's surface.
        ime.onAction(Keyboards.CURSOR_RIGHT)
        pumpUntil { e.text.toString().contains("かさあ") }
        repeat(10) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        spy.seen.clear()
        ime.onAction(Keyboards.CURSOR_LEFT)
        pumpUntil { spy.seen.size >= 2 }
        assertEquals(listOf(prefix + "空は", prefix + "空は傘"), spy.contexts)
        assertEquals(0, e.reads)
    }

    @Test fun aKeptSegmentIsContextOnceBehindTheCommittedText() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        settle(e)
        views(TAG_SEGMENT).first().performClick()
        pumpUntil { ime.conversionWorker.isReady && views(TAG_SEGMENT).isNotEmpty() }
        spy.seen.clear()
        type("かさ") // keeps 雨 as the head of the composition
        converted(e, "雨傘")
        assertEquals(prefix + "雨", spy.contexts.last())
        assertEquals(1, occurrences(spy.contexts.last(), "雨"))
        confirm(e) // the segment and what followed it, once, and no earlier unit again
        assertEquals(prefix + "雨傘", contextFor("そら"))
        assertEquals(0, e.reads)
    }

    @Test fun anEnglishWordBecomingTheCompositionCountsOnce() {
        val e = start("前の文。")
        ime.onAction(Keyboards.TO_ASCII)
        for (c in "ab") ime.onText(c.toString())
        assertEquals("前の文。ab", e.text.toString())
        ime.onAction(Keyboards.TO_KANA)
        type("あめ")
        pumpUntil { spy.seen.isNotEmpty() }
        // ab is the converter's own head of this composition, not committed text, so it is not in the shadow as well.
        assertEquals("前の文。ab", spy.contexts.last())
        assertEquals(0, e.reads)
    }

    @Test fun digitsComposingAfterACommittedWordFollowTheWord() {
        val e = start("前の文。")
        ime.onAction(Keyboards.TO_ASCII)
        ime.onText("a")
        ime.onText("1") // 0.1.32: joins the word's composition, nothing committed
        assertEquals(0, e.reads)
        assertEquals("前の文。a1", e.text.toString())
        assertEquals("前の文。".length, e.composingStart)
        settle(e)
        assertEquals("前の文。".length, e.composingStart)
        ime.onAction(Keyboards.ENTER)
        settle(e)
        val committed = e.text.toString()
        assertEquals("前の文。a1", committed)
        assertEquals(committed, contextFor("あめ"))
    }

    // --- Reports of our own writes while a newer composition is open ---------------------------------

    private fun deliver(report: IntArray) = ime.onUpdateSelection(report[0], report[1], report[2], report[3], report[4], report[5])

    /** A report for something that was not this keyboard: (old, new) offsets continuing from the last one delivered. */
    private fun moved(from: Int, to: Int) = ime.onUpdateSelection(from, from, to, to, -1, -1)

    /** 雨 committed and かさ composed behind it, with nothing reported yet. */
    private fun commitThenCompose(fieldStart: () -> FakeEditor = { start(prefix) }): FakeEditor {
        val e = fieldStart()
        type("あめ")
        converted(e, "雨")
        settle(e)
        ime.onAction(Keyboards.ENTER)
        type("かさ")
        converted(e, "傘")
        return e
    }

    private fun assertStillComposing(e: FakeEditor, expected: String) {
        val at = prefix.length + 1
        assertEquals(at, e.composingStart)
        ime.onAction(Keyboards.ENTER)
        settle(e)
        assertEquals(-1, e.composingStart)
        assertEquals(expected, e.text.toString())
    }

    @Test fun aLateReportOfACommitLeavesTheNextCompositionOpen() {
        val e = commitThenCompose()
        settle(e)
        assertStillComposing(e, prefix + "雨傘")
        assertEquals(prefix + "雨傘", contextFor("そら"))
    }

    @Test fun kanaTypedBeforeAnyReportOfTheCommitConvertsAsUsual() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.ENTER) // the composition's own reports are all still to come, as well as the commit's
        type("か")
        settle(e)
        assertEquals(prefix.length + 1, e.composingStart)
        spy.seen.clear()
        type("さ")
        converted(e, "傘")
        assertEquals(prefix + "雨", spy.contexts.last())
        settle(e)
        assertStillComposing(e, prefix + "雨傘")
    }

    @Test fun onlyTheLatestReportCoalescedOrRepeatedKeepsTheComposition() {
        val e = commitThenCompose()
        val last = e.reports.last()
        e.reports.clear()
        repeat(2) { deliver(last) }
        assertStillComposing(e, prefix + "雨傘")
        assertEquals(prefix + "雨傘", contextFor("そら"))
    }

    @Test fun reportsOvertakenByANewerOneChangeNothing() {
        val e = commitThenCompose()
        val inOrder = ArrayList(e.reports)
        e.reports.clear()
        inOrder.reversed().forEach { deliver(it) } // the composition's own report first, the commit's last
        assertStillComposing(e, prefix + "雨傘")
        assertEquals("傘", contextFor("そら")) // the composition's own head: the field's text went, as it could have been a move
    }

    @Test fun aLateReportBetweenTwoCommitsDoesNotMoveTheNextWrite() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        settle(e)
        ime.onAction(Keyboards.ENTER)
        ime.onText("、")
        val commit = e.reports.removeAt(0)
        deliver(commit) // the keyboard already wrote past this offset
        type("かさ")
        converted(e, "傘")
        settle(e)
        assertEquals(prefix.length + 2, e.composingStart)
        ime.onAction(Keyboards.ENTER)
        settle(e)
        assertEquals(prefix + "雨、傘", e.text.toString())
        assertEquals(prefix + "雨、傘", contextFor("そら"))
    }

    @Test fun lateReportsCountInFieldsThatKeepNoContext() {
        val privateField = commitThenCompose { start(prefix, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) }
        settle(privateField)
        assertStillComposing(privateField, prefix + "雨傘")
        DebugSettings.setCommittedContext(app, false)
        val switchedOff = commitThenCompose()
        settle(switchedOff)
        assertStillComposing(switchedOff, prefix + "雨傘")
    }

    @Test fun aCursorMovedAwayWhileOurReportsAreLateStillEndsTheComposition() {
        val e = commitThenCompose()
        val shown = e.text.toString()
        ime.onUpdateSelection(prefix.length + 1, prefix.length + 1, 2, 2, -1, -1) // no report of ours has this offset
        assertEquals(shown, e.text.toString())
        assertEquals(-1, e.composingStart)
        e.reports.clear()
        assertEquals("", contextFor("そら"))
    }

    @Test fun aCursorMovedBackOntoTheCommitAfterTheCompositionWasReportedEndsIt() {
        val e = commitThenCompose()
        settle(e)
        val shown = e.text.toString()
        moved(prefix.length + 2, prefix.length + 1) // the commit's offsets, but continuing from the last report
        assertEquals(shown, e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals("", contextFor("そら"))
    }

    @Test fun aCursorMovedBackOntoTheCommitWithBothReportsDeliveredSeparatelyEndsIt() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        settle(e)
        ime.onAction(Keyboards.ENTER)
        settle(e)
        type("か")
        settle(e)
        assertEquals(prefix.length + 1, e.composingStart)
        moved(prefix.length + 2, prefix.length + 1)
        assertEquals(-1, e.composingStart)
        assertEquals("", contextFor("そら"))
    }

    @Test fun aCursorMovedOntoACommitNotYetReportedEndsTheCompositionIfItDoesNotContinueTheReports() {
        val e = commitThenCompose()
        e.reports.clear()
        val end = prefix.length + 2
        ime.onUpdateSelection(end, end, prefix.length + 1, prefix.length + 1, -1, -1) // the editor was past the commit
        assertEquals(-1, e.composingStart)
        assertEquals("", contextFor("そら"))
    }

    @Test fun aCommitBeforeAnExternalMoveExcusesNothingAfterIt() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        confirm(e)
        val committed = prefix.length + 1
        Selection.setSelection(e.text, 2)
        moved(committed, 2)
        type("か")
        e.reports.clear()
        assertEquals(2, e.composingStart)
        moved(2, committed) // the composition's report is still to come, and this is where the commit was
        assertEquals(-1, e.composingStart)
    }

    /** Pins a limit, not a wish: offsets cannot tell these apart. The next report that is not ours still ends it. */
    @Test fun aMoveThatLooksExactlyLikeTheLateReportIsTakenForIt() {
        val e = commitThenCompose()
        val commit = e.reports.removeAt(0)
        e.reports.clear()
        deliver(commit)
        assertEquals(prefix.length + 1, e.composingStart)
        ime.onUpdateSelection(prefix.length + 1, prefix.length + 1, 2, 2, -1, -1)
        assertEquals(-1, e.composingStart)
    }

    @Test fun aSpanSomeoneElseMovedEndsTheCompositionWithLateReportsPending() {
        val e = commitThenCompose()
        val end = prefix.length + 3
        ime.onUpdateSelection(end, end, end, end, 0, 1) // text arrived before the span: no write of ours left this span
        assertEquals("", contextFor("そら"))
    }

    @Test fun anExternalEditAfterLateReportsForgetsTheContextAndCountsNoReportAsOurs() {
        val e = commitThenCompose()
        settle(e)
        val end = prefix.length + 2
        ime.onUpdateSelection(end, end, end + 5, end + 5, end + 4, end + 5)
        assertEquals("", contextFor("そら"))
    }

    @Test fun englishDigitsSurviveEveryLateReportOfTheWordInOrder() {
        val e = start(prefix)
        ime.onAction(Keyboards.TO_ASCII)
        for (c in "abc") ime.onText(c.toString())
        ime.onText("1")
        assertEquals(prefix.length, e.composingStart)
        assertTrue(e.reports.size >= 4)
        settle(e)
        assertEquals(prefix.length, e.composingStart)
        ime.onText("2") // joins the composition: nothing was dropped
        settle(e)
        assertEquals(prefix.length, e.composingStart)
        ime.onAction(Keyboards.ENTER)
        settle(e)
        assertEquals(prefix + "abc12", e.text.toString())
        assertEquals(prefix + "abc12", contextFor("あめ"))
    }

    @Test fun englishDigitsSurviveReportsCoalescedOrOvertaken() {
        val coalesced = start(prefix)
        ime.onAction(Keyboards.TO_ASCII)
        for (c in "ab") ime.onText(c.toString())
        ime.onText("1")
        val last = coalesced.reports.last()
        coalesced.reports.clear()
        repeat(2) { deliver(last) }
        assertEquals(prefix.length, coalesced.composingStart)

        val overtaken = start(prefix)
        ime.onAction(Keyboards.TO_ASCII)
        for (c in "ab") ime.onText(c.toString())
        ime.onText("1")
        val inOrder = ArrayList(overtaken.reports)
        overtaken.reports.clear()
        inOrder.reversed().forEach { deliver(it) }
        assertEquals(prefix.length, overtaken.composingStart)
        ime.onAction(Keyboards.ENTER)
        settle(overtaken)
        assertEquals(prefix + "ab1", overtaken.text.toString())
    }

    @Test fun aCursorMovedAwayEndsTheEnglishDigitsAsItEndsAnyComposition() {
        val e = start(prefix)
        ime.onAction(Keyboards.TO_ASCII)
        ime.onText("a")
        ime.onText("1")
        val end = prefix.length + 2
        ime.onUpdateSelection(end, end, 1, 1, -1, -1)
        assertEquals(-1, e.composingStart)
        assertEquals(prefix + "a1", e.text.toString())
    }

    @Test fun anEnglishWordSurvivesLateReportsOfTheCompositionBeforeIt() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.ENTER)
        ime.onAction(Keyboards.TO_ASCII)
        for (c in "jp") ime.onText(c.toString())
        settle(e) // あめ's reports have a longer span and start before the word
        assertEquals(prefix.length + 1, e.composingStart)
        ime.onAction(Keyboards.SPACE)
        settle(e)
        assertEquals(prefix + "雨jp ", e.text.toString())
        ime.onAction(Keyboards.TO_KANA)
        assertEquals(prefix + "雨jp ", contextFor("あめ"))
    }

    @Test fun anEnglishWordStillEndsWhenTheCursorReallyLeaves() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        settle(e)
        ime.onAction(Keyboards.ENTER)
        ime.onAction(Keyboards.TO_ASCII)
        ime.onText("j")
        settle(e)
        val end = prefix.length + 2
        moved(end, prefix.length + 1) // back onto the commit, though the word was already reported
        assertEquals(-1, e.composingStart)
    }

    @Test fun kanaTakingOverAnEnglishWordSurvivesTheWordsLateReports() {
        val e = start(prefix)
        ime.onAction(Keyboards.TO_ASCII)
        for (c in "ab") ime.onText(c.toString())
        ime.onAction(Keyboards.TO_KANA)
        type("あ")
        settle(e)
        assertEquals(prefix.length, e.composingStart)
        ime.onAction(Keyboards.ENTER)
        settle(e)
        assertTrue(e.text.toString(), e.text.toString().startsWith(prefix + "ab"))
    }

    // --- Configuration changes -------------------------------------------------------------------

    @Test fun aConfigurationChangeKeepsTheCursorAndNotTheFieldsFirstOffset() {
        val info = EditorInfo()
        val e = start(prefix, info = info)
        type("あめ")
        converted(e, "雨")
        confirm(e)
        ime.onStartInput(info, true)
        type("かさ")
        converted(e, "傘")
        settle(e)
        assertStillComposing(e, prefix + "雨傘")
    }

    @Test fun commitsAfterAConfigurationChangeFollowTheCursorNotTheSnapshot() {
        val info = EditorInfo()
        val e = start(prefix, info = info)
        type("あめ")
        converted(e, "雨")
        confirm(e)
        ime.onStartInput(info, true)
        ime.onText("、")
        settle(e)
        type("かさ")
        converted(e, "傘")
        settle(e)
        ime.onAction(Keyboards.ENTER)
        settle(e)
        assertEquals(prefix + "雨、傘", e.text.toString())
        assertEquals("、傘", contextFor("そら"))
    }

    @Test fun aConfigurationChangeWithReportsStillToComeKeepsThem() {
        val info = EditorInfo()
        val e = start(prefix, info = info)
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.ENTER)
        ime.onStartInput(info, true)
        type("か")
        settle(e)
        assertEquals(prefix.length + 1, e.composingStart)
    }

    @Test fun aConfigurationChangeWhileComposingKeepsTheTextAndTheNextComposition() {
        val info = EditorInfo()
        val e = start(prefix, info = info)
        type("あめ")
        converted(e, "雨")
        settle(e)
        val end = prefix.length + 1
        ime.onStartInput(info, true) // ends the span and writes nothing
        assertEquals(prefix + "雨", e.text.toString())
        assertEquals(-1, e.composingStart)
        type("か")
        ime.onUpdateSelection(end, end, end, end, -1, -1) // finishing the span: reported after か was written
        settle(e)
        assertEquals(end, e.composingStart)
    }

    @Test fun aConfigurationChangeKeepsTheSelectionTheEditorReported() {
        val info = EditorInfo()
        val e = start(prefix, info = info)
        Selection.setSelection(e.text, 1, 3)
        ime.onUpdateSelection(prefix.length, prefix.length, 1, 3, -1, -1)
        ime.onStartInput(info, true)
        ime.onAction(Keyboards.BACKSPACE)
        assertTrue(e.keys.isEmpty()) // a known selection: deleted by a commit, not by a key event
        assertEquals(prefix.removeRange(1, 3), e.text.toString())
    }

    @Test fun aCursorUnknownBeforeAConfigurationChangeStaysUnknownAndTheEchoIsAdopted() {
        val info = EditorInfo()
        val e = start(prefix, info = info)
        ime.onAction(Keyboards.BACKSPACE) // only the editor knows how much went; its report has not come
        ime.onStartInput(info, true)
        e.reports.clear()
        type("か")
        settle(e)
        assertEquals(prefix.length - 1, e.composingStart)
        assertEquals(prefix.dropLast(1) + "か", e.text.toString())
    }

    @Test fun aCursorMovedAfterAConfigurationChangeIsStillSomeoneElses() {
        val info = EditorInfo()
        val e = start(prefix, info = info)
        type("あめ")
        converted(e, "雨")
        confirm(e)
        ime.onStartInput(info, true)
        type("か")
        settle(e)
        moved(prefix.length + 2, 1)
        assertEquals(prefix + "雨か", e.text.toString())
        assertTrue(views(TAG_SEGMENT).isEmpty())
        assertEquals("", contextFor("そら"))
    }

    @Test fun aNewEditorInfoOnARestartTakesItsCursor() {
        val info = EditorInfo()
        val e = start(prefix, info = info)
        type("あめ")
        converted(e, "雨")
        confirm(e)
        val next = start(prefix, restart = true) // a new object: the app restarted the field, and it describes it now
        assertTrue(next !== e)
        assertEquals(prefix, contextFor("かさ"))
        converted(next, "傘")
        settle(next)
        assertEquals(prefix.length, next.composingStart)
    }

    /** The real InputMethodService path, so that the test above calling onStartInput by hand stands for what happens. */
    @Test fun theFrameworksConfigurationPathRepeatsTheEditorInfoAndKeepsTheCursor() {
        val info = EditorInfo()
        val e = start(prefix, info = info)
        (ime.onCreateInputMethodInterface() as android.view.inputmethod.InputMethod).startInput(e, info)
        type("あめ")
        converted(e, "雨")
        confirm(e)
        val rotated = android.content.res.Configuration(app.resources.configuration)
        rotated.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ime.onConfigurationChanged(rotated)
        type("かさ")
        converted(e, "傘")
        settle(e)
        assertStillComposing(e, prefix + "雨傘")
    }

    // --- Asking the field (0.1.30) ----------------------------------------------------------------

    /**
     * An editor that may be read, with a snapshot that knows nothing: context that reaches the scorer came from the read
     * unless a test says otherwise. Reads run on the reading thread against the synthetic text.
     */
    private fun asking(
        initial: String = "",
        type: Int = T.TYPE_CLASS_TEXT,
        start: Int = initial.length,
        end: Int = start,
        imeOptions: Int = 0,
        info: EditorInfo = Snapshot { null },
    ): FakeEditor {
        ime.reads = true
        return start(initial, type, start, end, imeOptions, info = info).also { it.forbidReads = false }
    }

    private val kouen = "子供と遊ぶ約束をした。"

    @Test fun theFieldIsAskedOnceOnTheFirstKeyBeforeAnythingOfOursIsComposing() {
        val e = asking(kouen)
        assertEquals(kouen, contextFor("あめ"))
        type("は")
        converted(e, "雨は")
        assertEquals(kouen, spy.contexts.last())
        assertEquals(1, e.reads)
        assertEquals(listOf<Pair<String?, Boolean>>(kouen to false), e.answered.toList())
    }

    @Test fun eachCompositionAsksAgainAndSeesOurCommits() {
        val e = asking(kouen)
        type("あめ")
        converted(e, "雨")
        confirm(e)
        assertEquals(kouen + "雨", contextFor("かさ"))
        assertEquals(2, e.reads)
        assertTrue(e.answered.none { it.second })
    }

    @Test fun theAnnouncerAndDentistCasesHoldWithReads() {
        val e = asking()
        announcerThenDentist(e)
        tapAt(e, announcer.length)
        assertEquals(announcer, contextBefore())
        confirm(e)
        tapAt(e, e.text.length)
        assertEquals(ZenzRanking.promptContext(e.text.toString()), contextBefore("あめ"))
    }

    @Test fun theAnswerIsWhatIsBeforeTheCursorNowWhateverTheHostDid() {
        val e = asking(announcer)
        // Pasted from the host's own menu: the keyboard only sees the cursor move, and the shadow forgets.
        e.text.insert(announcer.length, dentist)
        Selection.setSelection(e.text, e.text.length)
        ime.onUpdateSelection(announcer.length, announcer.length, e.text.length, e.text.length, -1, -1)
        assertEquals(announcer + dentist, contextFor("あめ"))
    }

    @Test fun overASelectionTheAnswerEndsWhereTheSelectionStarts() {
        val e = asking(announcer + dentist, start = announcer.length, end = announcer.length + 2)
        assertEquals(announcer, contextFor("あめ"))
        assertTrue(e.answered.none { it.second })
    }

    @Test fun aSlowFieldFallsBackToTheShadowAndTheLateAnswerIsNeverUsed() {
        val release = CountDownLatch(1)
        val e = asking(kouen, info = EditorInfo()) // a snapshot the shadow trusts
        e.beforeRead = { release.await(5, TimeUnit.SECONDS) }
        val begun = System.nanoTime()
        type("あ")
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun)
        assertTrue("waited $waited ms", waited in 90..1000)
        assertEquals(kouen.length, e.composingStart) // written after the cutoff
        release.countDown()
        pumpUntil { e.answered.isNotEmpty() }
        assertTrue(e.answered.single().second) // the late read saw our span: exactly what must not be used
        spy.seen.clear()
        type("め")
        pumpUntil { spy.seen.any { it.reading == "アメ" } }
        assertEquals(kouen, spy.seen.last { it.reading == "アメ" }.context)
        assertTrue(spy.contexts.none { "あ" in it })
    }

    @Test fun aSlowFieldWithoutAnAlignedShadowHasNoContext() {
        val release = CountDownLatch(1)
        val e = asking(kouen)
        e.beforeRead = { release.await(5, TimeUnit.SECONDS) }
        assertEquals("", contextFor("あめ"))
        release.countDown()
    }

    @Test fun afterASlowAnswerNeitherThisFieldNorAnotherIsAskedForThirtySeconds() {
        var now = 0L
        ime.hostContext = HostContext(clock = { now })
        val release = CountDownLatch(1)
        val e = asking(kouen)
        e.beforeRead = { release.await(5, TimeUnit.SECONDS) }
        type("あめ")
        release.countDown()
        pumpUntil { e.answered.isNotEmpty() }
        confirm(e)
        type("かさ")
        confirm(e)
        assertEquals(1, e.reads)
        // Another field within the quiet period: its snapshot, not a read. The first field's late answer is not in it.
        val other = asking("別の欄。", info = EditorInfo())
        other.forbidReads = true
        assertEquals("別の欄。", contextFor("あめ"))
        assertEquals(0, other.reads)
        confirm(other)
        now += TimeUnit.SECONDS.toNanos(HostContext.QUIET_SECONDS)
        val third = asking(kouen)
        assertEquals(kouen, contextFor("あめ"))
        assertEquals(1, third.reads)
    }

    @Test fun aFieldThatFailsOrAnswersNothingIsNotSlowAndUsesTheShadow() {
        val e = asking(kouen, info = EditorInfo())
        e.beforeRead = { throw IllegalStateException("connection gone") }
        assertEquals(kouen, contextFor("あめ"))
        confirm(e)
        e.beforeRead = null
        assertEquals(kouen + "雨", contextFor("かさ"))
        assertEquals(2, e.reads)
    }

    @Test fun theSwitchOffDropsTheAnswerAtOnceAndAsksNothing() {
        val e = asking(kouen)
        assertEquals(kouen, contextFor("あめ"))
        DebugSettings.setCommittedContext(app, false)
        spy.seen.clear()
        type("は")
        pumpUntil { spy.seen.isNotEmpty() }
        assertEquals("", spy.contexts.last())
        confirm(e)
        e.forbidReads = true
        type("かさ")
        assertEquals(1, e.reads)
    }

    @Test fun fieldsThatAllowNoContextAreNeverRead() {
        val fields = listOf(
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD to 0,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_PASSWORD to 0,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_NO_SUGGESTIONS to 0,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI to 0,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS to 0,
            T.TYPE_CLASS_NUMBER to 0,
            T.TYPE_CLASS_PHONE to 0,
            T.TYPE_CLASS_TEXT to EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        for ((type, options) in fields) {
            val e = asking(kouen, type = type, imeOptions = options)
            e.forbidReads = true
            type("あめ")
            ime.onText("1")
            repeat(3) { shadowOf(Looper.getMainLooper()).idle() }
            confirm(e)
            assertEquals("type=$type options=$options", 0, e.reads)
        }
    }

    @Test fun anEnglishWordOfOursIsNeverAskedAbout() {
        val e = asking("前の文。")
        e.forbidReads = true
        ime.onAction(Keyboards.TO_ASCII)
        ime.onText("a")
        settle(e)
        ime.onAction(Keyboards.TO_KANA)
        type("あめ")
        assertEquals(0, e.reads)
    }

    @Test fun anEditorOfThisProcessIsNotAsked() {
        val info = Snapshot { null }.apply { packageName = app.packageName }
        val e = asking(kouen, info = info)
        e.forbidReads = true
        type("あめ")
        assertEquals(0, e.reads)
    }

    // --- Context and learning stay apart ---------------------------------------------------------

    /** Every stored line. The writer runs on its own thread: "" while a file is being replaced, so callers wait. */
    private fun learningFiles(): String = try {
        listOf("words.tsv", "phrases.tsv", "registered.tsv").map { File(File(app.noBackupFilesDir, "learning"), it) }
            .filter { it.isFile }.joinToString("\n") { it.readText() }
    } catch (e: java.io.IOException) {
        ""
    }

    private fun assertNoSurrounding(text: String) {
        for (fragment in listOf("歯医者", "私は", "です", prefix)) assertFalse(text, fragment in text)
    }

    @Test fun learningIsOnByDefaultAndAStoredOffIsRespected() {
        app.getSharedPreferences("astra_learning", 0).edit().clear().commit()
        assertTrue(Learning.enabled(app))
        Learning.setEnabled(app, false)
        start(prefix)
        assertFalse(Learning.enabled(app)) // opening an editor changes no stored choice
        Learning.setEnabled(app, true)
        assertTrue(Learning.enabled(app))
    }

    @Test fun anExplicitChoiceLearnsOnlyItsReadingAndSurfaceNeverTheContext() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.CONVERT) // 飴: an explicit choice
        ime.onAction(Keyboards.ENTER)
        pumpUntil { "飴" in learningFiles() }
        assertTrue(spy.contexts.all { it == prefix }) // the scorer did see the sentence
        val files = learningFiles()
        assertTrue(files, files.contains("あめ\t飴"))
        assertNoSurrounding(files)
        assertTrue(ShadowLog.getLogs().none { "歯医者" in it.msg.orEmpty() })
    }

    @Test fun onlyExplicitCommitsLearn() {
        val store = Learning.store(app)
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.CONVERT) // shown, not chosen
        ime.onAction(Keyboards.BACKSPACE)
        ime.onAction(Keyboards.BACKSPACE)
        assertEquals(0, store.historySize) // typed and deleted
        type("あめ")
        converted(e, "雨")
        settle(e)
        Selection.setSelection(e.text, 0)
        ime.onUpdateSelection(e.text.length, e.text.length, 0, 0, prefix.length, e.text.length)
        assertEquals(0, store.historySize) // the cursor left: dropped, not committed
        ime.onText("、")
        assertEquals(0, store.historySize) // direct text
    }

    @Test fun offNeitherRecordsNorUsesHistoryWhileContextStaysInMemoryRanking() {
        val store = Learning.store(app)
        val on = start()
        type("あめ")
        converted(on, "雨")
        ime.onAction(Keyboards.CONVERT)
        ime.onAction(Keyboards.ENTER)
        var files = ""
        var unchanged = 0
        pumpUntil { // the writer replaces several files, each read "" for a moment: wait for what it ends with
            val now = learningFiles()
            unchanged = if (now == files && "飴" in now) unchanged + 1 else 0
            files = now
            unchanged >= 10
        }
        val before = store.historySize
        assertTrue(before > 0)

        Learning.setEnabled(app, false)
        val off = start(prefix)
        spy.seen.clear()
        type("あめ")
        converted(off, "雨") // the dictionary's order, not the learned 飴
        assertEquals(prefix, spy.contexts.last()) // learning off does not end context ranking: nothing of it is kept
        ime.onAction(Keyboards.CONVERT)
        ime.onAction(Keyboards.ENTER)
        assertEquals(prefix + "飴", off.text.toString())
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(before, store.historySize)
        assertEquals(files, learningFiles())
    }

    @Test fun learningSwitchedOffInTheSameFieldStopsAtOnce() {
        // The settings screen's try box stays focused while the switch changes: no editor restart announces it.
        val store = Learning.store(app)
        val e = start()
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.CONVERT)
        ime.onAction(Keyboards.ENTER)
        pumpUntil { "飴" in learningFiles() }
        val before = store.historySize
        val files = learningFiles()
        settle(e)

        Learning.setEnabled(app, false)
        type("かさ")
        converted(e, "傘")
        ime.onAction(Keyboards.CONVERT)
        ime.onAction(Keyboards.ENTER)
        assertTrue(e.text.toString(), e.text.toString().endsWith("笠"))
        settle(e)
        type("あめ")
        converted(e, "雨") // the dictionary's order at once, not the learned 飴
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(before, store.historySize)
        assertEquals(files, learningFiles())
    }

    @Test fun privateFieldsDoNotLearnEvenWithLearningOn() {
        val e = start(prefix, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.CONVERT)
        ime.onAction(Keyboards.ENTER)
        assertEquals(0, Learning.store(app).historySize)
        assertEquals(0, e.reads)
    }

    @Test fun whatTheShadowHoldsNeverReachesLearningEvenAsItGrows() {
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.CONVERT)
        confirm(e)
        type("かさ")
        converted(e, "傘")
        ime.onAction(Keyboards.CONVERT)
        assertEquals(prefix + "飴", spy.contexts.last())
        confirm(e)
        pumpUntil { "笠" in learningFiles() }
        val files = learningFiles()
        assertNoSurrounding(files)
        assertFalse(files, "飴笠" in files) // two commits are two choices: the shadow does not join them
    }

    @Test fun ownCommitsBuildContextWithLearningOffAndNothingIsRecorded() {
        Learning.setEnabled(app, false)
        val e = start()
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.CONVERT)
        confirm(e)
        assertEquals("飴", contextFor("かさ"))
        confirm(e)
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(0, Learning.store(app).historySize)
        assertEquals("", learningFiles().trim())
    }

    @Test fun clearingHistoryLeavesNoLearnedTextBehind() {
        val store = Learning.store(app)
        val e = start(prefix)
        type("あめ")
        converted(e, "雨")
        ime.onAction(Keyboards.CONVERT)
        ime.onAction(Keyboards.ENTER)
        pumpUntil { "飴" in learningFiles() }
        store.clearHistory()
        var files = "飴"
        pumpUntil { files = learningFiles(); "飴" !in files && File(File(app.noBackupFilesDir, "learning"), "phrases.tsv").length() == 0L }
        assertEquals(0, store.historySize)
        assertFalse(files, "飴" in files)
    }
}
