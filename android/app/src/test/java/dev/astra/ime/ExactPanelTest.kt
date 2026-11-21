package dev.astra.ime

import android.os.Looper
import android.text.InputType as T
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import dev.astra.ime.core.Candidate
import dev.astra.ime.core.CandidateClass
import dev.astra.ime.core.CandidateGroups
import dev.astra.ime.core.FlickDirection
import dev.astra.ime.core.FlickKey
import dev.astra.ime.core.FlickLayout
import dev.astra.ime.core.Segment
import dev.astra.ime.core.SegmentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** A homophone list of any length through the real service, worker and session; only the lookup is scripted. */
class PanelIme : AstraImeService() {
    var ic: InputConnection? = null
    var info: EditorInfo? = null
    internal val clip = FakeClipSource()
    internal override fun clipSource(): ClipSource = clip
    override fun getCurrentInputConnection(): InputConnection? = ic
    override fun getCurrentInputEditorInfo(): EditorInfo? = info

    /** Non-null: lookups answer from this; [held] keeps the callback until a test releases it. */
    internal var script: ((String) -> ExactPanel)? = null
    var hold = false
    private val held = ArrayList<() -> Unit>()
    var lookups = 0

    internal override fun lookupPanel(text: String, onResult: (ExactPanel) -> Unit) {
        lookups++
        val answer = script ?: return super.lookupPanel(text, onResult)
        if (hold) held += { onResult(answer(text)) } else onResult(answer(text))
    }

    fun release() {
        held.forEach { it() }
        held.clear()
    }

    fun releaseOldest() = held.removeAt(0)()
}

/** 0.1.18/0.1.19: the expanded panel's two groups and the enlarged single group. mdpi: 1dp = 1px. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w400dp-h892dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ExactPanelTest {
    private var controller: ServiceController<PanelIme>? = null
    private val ime: PanelIme get() = controller!!.get()
    private var root: ViewGroup? = null

    private fun word(text: String, surface: String) = Candidate(text, surface, 0.0, listOf(Segment(0, text.length, text, surface, SegmentKind.WORD)))
    private fun many(text: String, n: Int) = (0 until n).map { word(text, "語%03d".format(it)) }

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        DebugSettings.setOssEnabled(context, false)
        DebugSettings.setZenzEnabled(context, false)
        DebugSettings.setLiveConversion(context, true)
        Learning.setEnabled(context, true)
        Learning.store(context).clearHistory()
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

    private fun measure() {
        val r = root!!
        r.measure(MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        r.layout(0, 0, 400, r.measuredHeight)
    }

    private fun typeAme(e: FakeEditor) {
        pumpUntil("converter") { ime.converterReady }
        ime.onFlick(FlickKey.A, FlickDirection.CENTER)
        ime.onFlick(FlickKey.MA, FlickDirection.entries.first { FlickLayout.kana(FlickKey.MA, it) == "め" })
        pumpUntil("conversion") { e.text.toString().endsWith("雨") }
        pumpUntil("expand button") { expand().text == "▼" }
    }

    private fun expand(): TextView = (root!!.findViewWithTag<ViewGroup>(TAG_STRIP_ROW)).getChildAt(1) as TextView
    private fun panel(): LinearLayout = root!!.findViewWithTag(TAG_PANEL)
    private fun top(): CandidateSectionView = root!!.findViewWithTag(TAG_PANEL_TOP)
    private fun exact(): CandidateSectionView = root!!.findViewWithTag(TAG_PANEL_EXACT)
    private fun keys() = (0 until root!!.childCount).map { root!!.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
    private fun headerView(tag: String): ViewGroup = root!!.findViewWithTag(tag)
    private fun header(tag: String) = (headerView(tag).getChildAt(0) as TextView).text.toString()
    private fun headerAction(tag: String) = (headerView(tag).getChildAt(1) as TextView).text.toString()

    /** Taps a group header, as the system delivers it, and lays the result out. */
    private fun tapHeader(tag: String) {
        headerView(tag).performClick()
        measure()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun open(data: (String) -> ExactPanel = { ExactPanel(many(it, 150), 150, emptyList()) }) {
        ime.script = data
        expand().performClick()
        measure()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tap(view: CandidateSectionView, x: Float, y: Float) {
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        touch(view, MotionEvent.ACTION_UP, x, y)
    }

    private fun touch(view: CandidateSectionView, action: Int, x: Float, y: Float, time: Long = 0) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(0L, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun scrollTo(view: CandidateSectionView, index: Int) {
        repeat(200) {
            if (index in view.visibleItems()) return
            if (view.twoRows) drag(view, -(view.width * 0.8f), 0f) else drag(view, 0f, -(view.height * 0.8f))
        }
        error("candidate $index was never reached")
    }

    /** Drags by ([dx], [dy]) from the middle of the view. */
    private fun drag(view: CandidateSectionView, dx: Float, dy: Float) {
        val x = view.width / 2f
        val y = view.height / 2f
        touch(view, MotionEvent.ACTION_DOWN, x, y, 0)
        touch(view, MotionEvent.ACTION_MOVE, x + dx / 2, y + dy / 2, 10)
        touch(view, MotionEvent.ACTION_MOVE, x + dx, y + dy, 2000)
        touch(view, MotionEvent.ACTION_UP, x + dx, y + dy, 4000)
    }

    // --- Layout -----------------------------------------------------------------------------------

    @Test fun theDownTriangleOpensOnePanelWithBothSectionsVisibleTogether() {
        val e = start()
        typeAme(e)
        open()
        assertEquals(View.VISIBLE, panel().visibility)
        assertEquals(View.VISIBLE, top().visibility)
        assertEquals(View.VISIBLE, exact().visibility)
        assertEquals(View.GONE, keys().visibility)
        assertEquals("▲", expand().text.toString())
        assertTrue("prediction above exact", top().bottom <= exact().top)
        assertTrue(top().height > 0 && exact().height > 0)
    }

    @Test fun thereAreNoTabsOnlySectionHeaders() {
        val e = start()
        typeAme(e)
        open()
        assertEquals("予測・補正", header(TAG_PANEL_TOP_HEADER))
        assertEquals("変換", header(TAG_PANEL_EXACT_HEADER))
        assertEquals(4, panel().childCount) // header, two rows, header, two rows: nothing to switch between
        assertEquals(listOf(TAG_PANEL_TOP_HEADER, TAG_PANEL_TOP, TAG_PANEL_EXACT_HEADER, TAG_PANEL_EXACT), (0 until 4).map { panel().getChildAt(it).tag })
    }

    @Test fun theTopSectionIsTwoRowsHighAndScrollsSideways() {
        val e = start()
        typeAme(e)
        open { ExactPanel(emptyList(), 0, (0 until 30).map { n -> Candidate("あめ$n", "予測語$n", 0.0) }) }
        assertTrue(top().twoRows)
        assertEquals(88, top().height)
        val rows = (0 until top().entries.size).map { top().centerOf(it).second }.toSet()
        assertEquals("exactly two rows", 2, rows.size)
        assertTrue(top().maxScroll > 0)
        drag(top(), -200f, 0f)
        assertTrue(top().scrollOffset > 0)
    }

    @Test fun theSectionsScrollIndependently() {
        val e = start()
        typeAme(e)
        open { text -> ExactPanel(many(text, 150), 150, (0 until 30).map { n -> Candidate("あめ$n", "予測語$n", 0.0) }) }
        drag(exact(), -60f, 0f)
        assertTrue(exact().scrollOffset > 0)
        assertEquals(0, top().scrollOffset)
        val along = exact().scrollOffset
        drag(top(), -120f, 0f)
        assertTrue(top().scrollOffset > 0)
        assertEquals(along, exact().scrollOffset)
    }

    @Test fun inTheNormalPanelBothGroupsAreTwoRowsAndScrollSideways() {
        val e = start()
        typeAme(e)
        open { text -> ExactPanel(many(text, 150), 150, (0 until 30).map { n -> Candidate("あめ$n", "予測語$n", 0.0) }) }
        for (group in listOf(top(), exact())) {
            assertTrue(group.twoRows)
            assertEquals(88, group.height)
            assertEquals(2, group.entries.indices.map { group.centerOf(it).second }.toSet().size)
            assertTrue(group.maxScroll > 0)
            drag(group, -100f, 0f)
            assertTrue(group.scrollOffset > 0)
            val along = group.scrollOffset
            drag(group, 0f, -60f) // across the axis: nothing
            assertEquals(along, group.scrollOffset)
        }
    }

    @Test fun thePanelKeepsTheKeysHeightOrEnoughForTwoExactRows() {
        val e = start()
        measure()
        typeAme(e)
        measure()
        val keysHeight = keys().height
        open()
        assertTrue(panel().height >= keysHeight)
        assertEquals("two full rows in each group", 2 * CandidateSectionView.ROW_DP.toInt(), exact().height)
    }

    // --- One group enlarged -----------------------------------------------------------------------

    private fun full(text: String) = ExactPanel(many(text, 150), 150, (0 until 150).map { n -> Candidate("あめ$n", "予測語$n", 0.0) })

    @Test fun aHeaderTapEnlargesThatGroupAloneAsAVerticalGrid() {
        val e = start()
        typeAme(e)
        for ((tag, tagOther) in listOf(TAG_PANEL_EXACT_HEADER to TAG_PANEL_TOP_HEADER, TAG_PANEL_TOP_HEADER to TAG_PANEL_EXACT_HEADER)) {
            open { full(it) }
            val group = if (tag == TAG_PANEL_EXACT_HEADER) exact() else top()
            val other = if (tag == TAG_PANEL_EXACT_HEADER) top() else exact()
            val normal = group.height
            tapHeader(tag)
            assertEquals(View.VISIBLE, group.visibility)
            assertEquals("the other group and its header are hidden", View.GONE, other.visibility)
            assertEquals(View.GONE, headerView(tagOther).visibility)
            assertFalse(group.twoRows)
            assertTrue("about half of the 820dp usable height, was $normal", group.height in 380..440)
            assertTrue("a grid, not two rows", group.entries.indices.map { group.centerOf(it).second }.toSet().size > 2)
            if (tag == TAG_PANEL_EXACT_HEADER) { // the top group is capped at 32 and may fit without scrolling
                assertTrue(group.maxScroll > 0)
                drag(group, 0f, -100f)
                assertTrue("vertical now", group.scrollOffset > 0)
                val down = group.scrollOffset
                drag(group, -80f, 0f) // across the axis: nothing
                assertEquals(down, group.scrollOffset)
            }
            assertEquals(View.GONE, keys().visibility)
            expand().performClick()
        }
    }

    @Test fun theReadingBarStaysAndTheCompositionIsUntouchedWhileEnlarged() {
        val e = start()
        typeAme(e)
        open { full(it) }
        val strip = (root!!.findViewWithTag<ViewGroup>(TAG_STRIP_ROW).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        tapHeader(TAG_PANEL_EXACT_HEADER)
        assertEquals(View.VISIBLE, root!!.findViewWithTag<View>(TAG_STRIP_ROW).visibility)
        val first = strip.getChildAt(0) as TextView // the segment buttons replace the reading while open
        assertEquals(TAG_SEGMENT, first.tag)
        assertEquals("雨", first.text.toString())
        assertEquals("▲", expand().text.toString())
        assertEquals("雨", e.text.toString())
        assertEquals(0, e.composingStart)
    }

    @Test fun shrinkReturnsToBothGroupsWithoutClosingAndNeitherPositionJumps() {
        val e = start()
        typeAme(e)
        open { full(it) }
        val normalHeight = panel().height
        drag(top(), -150f, 0f)
        drag(exact(), -90f, 0f)
        val topAlong = top().scrollOffset
        val exactAlong = exact().scrollOffset
        tapHeader(TAG_PANEL_EXACT_HEADER)
        assertEquals("縮小", headerAction(TAG_PANEL_EXACT_HEADER))
        assertEquals("the vertical grid starts at the top", 0, exact().scrollOffset)
        drag(exact(), 0f, -120f)
        val exactDown = exact().scrollOffset
        tapHeader(TAG_PANEL_EXACT_HEADER)
        assertEquals(View.VISIBLE, panel().visibility)
        assertEquals("the keys stay away", View.GONE, keys().visibility)
        assertEquals(View.VISIBLE, top().visibility)
        assertEquals(View.VISIBLE, exact().visibility)
        assertTrue(exact().twoRows)
        assertEquals(normalHeight, panel().height)
        assertEquals(topAlong, top().scrollOffset)
        assertEquals(exactAlong, exact().scrollOffset)
        tapHeader(TAG_PANEL_EXACT_HEADER)
        assertEquals("the grid is where it was left", exactDown, exact().scrollOffset)
    }

    @Test fun theOtherGroupCanBeChosenAfterShrinking() {
        val e = start()
        typeAme(e)
        open { full(it) }
        tapHeader(TAG_PANEL_TOP_HEADER)
        assertFalse(top().twoRows)
        tapHeader(TAG_PANEL_TOP_HEADER)
        tapHeader(TAG_PANEL_EXACT_HEADER)
        assertFalse(exact().twoRows)
        assertTrue(top().twoRows)
        assertEquals(View.GONE, top().visibility)
    }

    @Test fun headersAreRealTouchTargetsWithAClearSign() {
        val e = start()
        typeAme(e)
        open { full(it) }
        for ((tag, name) in listOf(TAG_PANEL_TOP_HEADER to "予測・補正", TAG_PANEL_EXACT_HEADER to "変換")) {
            val h = headerView(tag)
            assertTrue(h.isClickable)
            assertEquals(44, h.height)
            assertEquals("the whole row", 400, h.width)
            assertEquals(PanelSizing.expandIcon { false }, headerAction(tag))
            assertEquals("${name}を広げる", h.contentDescription.toString())
        }
        assertEquals("⤢", PanelSizing.expandIcon { true })
        tapHeader(TAG_PANEL_TOP_HEADER)
        assertEquals("予測・補正を縮小して戻す", headerView(TAG_PANEL_TOP_HEADER).contentDescription.toString())
    }

    @Test fun enlargingLooksNothingUpAgainAndLeavesTheListAsItIs() {
        val e = start()
        typeAme(e)
        open { full(it) }
        val all = exact().entries
        repeat(3) {
            tapHeader(TAG_PANEL_EXACT_HEADER)
            tapHeader(TAG_PANEL_EXACT_HEADER)
        }
        tapHeader(TAG_PANEL_TOP_HEADER)
        assertEquals(1, ime.lookups)
        assertEquals(all, exact().entries)
    }

    @Test fun aLookupAnsweringWhileEnlargedFillsTheGridAndKeepsItsPosition() {
        val e = start()
        typeAme(e)
        ime.script = { full(it) }
        ime.hold = true
        expand().performClick()
        measure()
        tapHeader(TAG_PANEL_TOP_HEADER)
        assertFalse(top().twoRows)
        val before = top().entries.size
        ime.release()
        measure()
        assertTrue(top().entries.size > before)
        drag(top(), 0f, -100f)
        assertFalse(top().twoRows)
        assertEquals(View.GONE, exact().visibility)
        assertEquals(View.VISIBLE, panel().visibility)
    }

    @Test fun aLookupCompletingTheListDoesNotThrowTheGroupBackToTheStart() {
        val e = start()
        typeAme(e)
        val section = CandidateSectionView(RuntimeEnvironment.getApplication(), twoRows = true)
        section.setItems((0 until 50).map { "候補$it" })
        section.measure(MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(88, MeasureSpec.EXACTLY))
        section.layout(0, 0, 400, 88)
        drag(section, -200f, 0f)
        val along = section.scrollOffset
        assertTrue(along > 0)
        section.setItems((0 until 80).map { "候補$it" }, keepScroll = true)
        assertEquals(along, section.scrollOffset)
        section.setItems((0 until 10).map { "別$it" })
        assertEquals("another list starts over", 0, section.scrollOffset)
    }

    @Test fun thePanelKeepsAGroupsPositionWhenTheLookupAnswers() {
        val e = start()
        typeAme(e)
        ime.script = { full(it) }
        ime.hold = true
        expand().performClick()
        // Narrow enough for the short ranked list to scroll before the answer comes.
        root!!.measure(MeasureSpec.makeMeasureSpec(60, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        root!!.layout(0, 0, 60, root!!.measuredHeight)
        drag(exact(), -40f, 0f)
        val along = exact().scrollOffset
        assertTrue(along > 0)
        ime.release()
        assertEquals(along, exact().scrollOffset)
    }

    @Test fun aPressInProgressDiesWithTheLayoutChange() {
        val e = start()
        typeAme(e)
        open { full(it) }
        val (x, y) = exact().centerOf(3)
        touch(exact(), MotionEvent.ACTION_DOWN, x, y)
        tapHeader(TAG_PANEL_EXACT_HEADER)
        touch(exact(), MotionEvent.ACTION_UP, x, y)
        assertEquals("雨", e.text.toString())
        assertEquals(0, e.composingStart)
        assertEquals(View.VISIBLE, panel().visibility)
    }

    @Test fun aPickInTheGridCommitsThatWordAndReturnsToTheKeysAndTheNextPanelIsNormal() {
        val e = start()
        typeAme(e)
        open { full(it) }
        tapHeader(TAG_PANEL_EXACT_HEADER)
        val late = exact().entries.indexOf("語140")
        scrollTo(exact(), late)
        val (x, y) = exact().centerOf(late)
        tap(exact(), x, y)
        assertEquals("語140", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals(View.GONE, panel().visibility)
        assertEquals(View.VISIBLE, keys().visibility)
        Learning.store(RuntimeEnvironment.getApplication()).clearHistory() // 語140 would lead the next conversion
        typeAme(e)
        open { full(it) }
        assertTrue(exact().twoRows)
        assertEquals(View.VISIBLE, top().visibility)
        assertEquals(View.VISIBLE, exact().visibility)
        assertEquals(View.VISIBLE, headerView(TAG_PANEL_TOP_HEADER).visibility)
        assertEquals(264, panel().height)
    }

    @Test fun aPredictionPickedFromTheEnlargedGroupCommitsAndIsLearnedTheSameWay() {
        val e = start()
        typeAme(e)
        open { ExactPanel(emptyList(), 0, listOf(Candidate("あめだま", "飴玉", 0.0))) }
        tapHeader(TAG_PANEL_TOP_HEADER)
        val index = top().entries.indexOf("飴玉")
        val (x, y) = top().centerOf(index)
        tap(top(), x, y)
        assertEquals("飴玉", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun anEditWhileEnlargedClosesEverythingAndTheNextPanelIsNormal() {
        val e = start()
        typeAme(e)
        open { full(it) }
        tapHeader(TAG_PANEL_EXACT_HEADER)
        ime.onFlick(FlickKey.NA, FlickDirection.entries.first { FlickLayout.kana(FlickKey.NA, it) == "の" })
        assertEquals(View.GONE, panel().visibility)
        assertEquals(View.VISIBLE, keys().visibility)
        assertTrue(exact().twoRows)
        assertEquals(View.VISIBLE, top().visibility)
    }

    @Test fun aLateHeaderClickAfterClosingOrAnotherEditorDoesNothing() {
        val e = start()
        typeAme(e)
        open { full(it) }
        val h = headerView(TAG_PANEL_EXACT_HEADER)
        expand().performClick() // closed by ▲
        h.performClick()
        assertTrue(exact().twoRows)
        assertEquals(View.GONE, panel().visibility)
        open { full(it) }
        val second = start() // another editor
        h.performClick()
        assertTrue(exact().twoRows)
        assertEquals(View.GONE, panel().visibility)
        assertEquals(View.VISIBLE, keys().visibility)
        assertEquals("", second.text.toString())
    }

    @Test fun aRestartOfTheSameEditorEndsTheEnlargedGroupAndCommitsNothingNew() {
        val e = start()
        typeAme(e)
        open { full(it) }
        tapHeader(TAG_PANEL_EXACT_HEADER)
        val stale = exact().centerOf(5)
        val info = ime.info!!
        ime.onStartInput(info, true)
        ime.onStartInputView(info, true)
        assertEquals(View.GONE, panel().visibility)
        assertEquals(View.VISIBLE, keys().visibility)
        tap(exact(), stale.first, stale.second)
        assertEquals("雨", e.text.toString())
    }

    @Test fun aLookupForAPanelClosedWhileEnlargedIsDropped() {
        val e = start()
        typeAme(e)
        ime.script = { full(it) }
        ime.hold = true
        expand().performClick()
        measure()
        tapHeader(TAG_PANEL_EXACT_HEADER)
        val before = exact().entries
        expand().performClick()
        ime.release()
        assertEquals(before, exact().entries)
    }

    @Test fun inRepairTheEnlargedGridChangesOnlyTheUnitAndCommitsNothing() {
        val e = start()
        typeAme(e)
        enterRepair()
        open { full(it) }
        tapHeader(TAG_PANEL_EXACT_HEADER)
        assertTrue(segmentSelected())
        val late = exact().entries.indexOf("語140")
        assertTrue(late > 20)
        scrollTo(exact(), late)
        val (x, y) = exact().centerOf(late)
        tap(exact(), x, y)
        assertEquals("語140", e.text.toString())
        assertEquals("still composing", 0, e.composingStart)
        assertEquals(View.GONE, panel().visibility)
        assertEquals(View.VISIBLE, keys().visibility)
        assertTrue(segmentSelected())
        ime.onAction(Keyboards.ENTER)
        assertEquals("語140", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun typingWhileEnlargedKeepsTheSegmentAndRestoresThePanelState() {
        val e = start()
        typeAme(e)
        enterRepair()
        open { full(it) }
        tapHeader(TAG_PANEL_TOP_HEADER)
        ime.onFlick(FlickKey.NA, FlickDirection.entries.first { FlickLayout.kana(FlickKey.NA, it) == "の" })
        assertEquals(View.GONE, panel().visibility)
        assertTrue(top().twoRows)
        assertFalse(segmentSelected())
        assertTrue(e.text.toString().startsWith("雨"))
        assertEquals(0, e.composingStart)
    }

    @Test fun enlargedGestureRulesAreTheSameDragsAndCancelsCommitNothing() {
        val e = start()
        typeAme(e)
        open { full(it) }
        tapHeader(TAG_PANEL_EXACT_HEADER)
        drag(exact(), 0f, -200f)
        drag(exact(), -100f, 3f)
        val (x, y) = exact().centerOf(exact().visibleItems().first + 1)
        touch(exact(), MotionEvent.ACTION_DOWN, x, y)
        touch(exact(), MotionEvent.ACTION_CANCEL, x, y)
        assertEquals("雨", e.text.toString())
        assertEquals(0, e.composingStart)
        assertEquals(View.VISIBLE, panel().visibility)
    }

    @Test fun everyWordInBothGroupsIsReachableInBothLayouts() {
        val e = start()
        typeAme(e)
        open { full(it) }
        fun sweep(group: CandidateSectionView): Set<Int> {
            val seen = HashSet<Int>()
            repeat(600) {
                seen += group.visibleItems().toList()
                if (group.scrollOffset >= group.maxScroll) return@repeat
                if (group.twoRows) drag(group, -(group.width * 0.5f), 0f) else drag(group, 0f, -(group.height * 0.5f))
            }
            return seen
        }
        assertEquals("sideways", exact().entries.indices.toSet(), sweep(exact()))
        assertEquals("sideways", top().entries.indices.toSet(), sweep(top()))
        tapHeader(TAG_PANEL_TOP_HEADER)
        assertEquals("grid", top().entries.indices.toSet(), sweep(top()))
        tapHeader(TAG_PANEL_TOP_HEADER)
        tapHeader(TAG_PANEL_EXACT_HEADER)
        assertEquals("grid", exact().entries.indices.toSet(), sweep(exact()))
        assertTrue("no 20 cap", exact().entries.size > 150)
    }

    @Config(qualifiers = "w892dp-h411dp-land")
    @Test fun inLandscapeThePanelLeavesTheHostItsLineAndStillEnlarges() {
        val e = start()
        typeAme(e)
        open { full(it) }
        assertTrue("two rows that still hold text", exact().height >= 2 * 32)
        val stripAndBottom = 44 + root!!.paddingBottom
        val normal = panel().height
        assertTrue(411 - normal - stripAndBottom >= 96)
        tapHeader(TAG_PANEL_EXACT_HEADER)
        assertTrue(411 - panel().height - stripAndBottom >= 96)
        assertTrue("the grid has more rows than the normal group", exact().height >= 2 * (normal - 88) / 2)
    }

    // --- Lists ------------------------------------------------------------------------------------

    @Test fun everyExactWordIsReachableOnceByScrollingAndNothingIsSkipped() {
        val e = start()
        typeAme(e)
        open()
        tapHeader(TAG_PANEL_EXACT_HEADER)
        val total = exact().entries.size
        assertEquals(150, exact().entries.count { it.startsWith("語") })
        assertTrue(total > 152) // raw, katakana and the test lexicon's own conversions as well
        val seen = LinkedHashSet<Int>()
        repeat(400) {
            seen += exact().visibleItems().toList()
            if (exact().scrollOffset >= exact().maxScroll) return@repeat
            drag(exact(), 0f, -(exact().height * 0.5f))
        }
        assertEquals("no gap and no repeat in the list", total, exact().entries.toSet().size)
        assertEquals((0 until total).toSet(), seen)
    }

    @Test fun onlyWhatIsOnScreenIsDrawn() {
        val e = start()
        typeAme(e)
        open()
        assertTrue(exact().visibleItems().count() < 40)
        assertEquals("a single view holds them all", 0, (exact() as View).let { if (it is ViewGroup) it.childCount else 0 })
    }

    @Test fun noHeaderEverShowsACount() {
        val e = start()
        typeAme(e)
        ime.script = { ExactPanel(many(it, 150), 150, emptyList()) }
        ime.hold = true
        expand().performClick()
        measure()
        fun assertNoCount() {
            for (tag in listOf(TAG_PANEL_TOP_HEADER, TAG_PANEL_EXACT_HEADER)) {
                val all = header(tag) + headerAction(tag) + headerView(tag).contentDescription
                assertFalse(all, all.any { it.isDigit() } || "件" in all)
            }
        }
        assertNoCount()
        ime.release()
        assertNoCount()
        tapHeader(TAG_PANEL_EXACT_HEADER)
        assertNoCount()
        tapHeader(TAG_PANEL_EXACT_HEADER)
        expand().performClick()
        ime.hold = false
        open { text -> ExactPanel(many(text, 150), 0, emptyList()) } // no entry for the whole reading
        assertEquals("変換", header(TAG_PANEL_EXACT_HEADER)) // no sub-label for one-word replacements (0.1.20)
        assertNoCount()
    }

    @Test fun rawAndKatakanaLeadTheExactList() {
        val e = start()
        typeAme(e)
        open()
        assertEquals(listOf("あめ", "アメ"), exact().entries.take(2))
    }

    @Test fun beforeTheLookupAnswersTheRankedExactWordsAreAlreadyThere() {
        val e = start()
        typeAme(e)
        ime.script = { ExactPanel(many(it, 150), 150, emptyList()) }
        ime.hold = true
        expand().performClick()
        measure()
        assertEquals("変換 …", header(TAG_PANEL_EXACT_HEADER))
        assertTrue("雨" in exact().entries)
        ime.release()
        assertEquals(150, exact().entries.count { it.startsWith("語") })
    }

    @Test fun oneLookupPerOpeningAndNonePerKeyPress() {
        val e = start()
        typeAme(e)
        assertEquals(0, ime.lookups)
        open()
        assertEquals(1, ime.lookups)
    }

    @Test fun theRealLookupAnswersThroughTheWorkerWithTheFixedLexicon() {
        val e = start()
        typeAme(e)
        expand().performClick()
        measure()
        pumpUntil("lookup") { !header(TAG_PANEL_EXACT_HEADER).endsWith("…") }
        assertEquals(listOf("あめ", "アメ"), exact().entries.take(2))
        assertTrue(exact().entries.containsAll(listOf("雨", "飴")))
        assertEquals("the raw kana and katakana are not predictions", emptyList<String>(), top().entries.filter { it == "あめ" || it == "アメ" })
    }

    // --- Taps and gestures ------------------------------------------------------------------------

    @Test fun aLateExactWordCommitsExactlyThatWord() {
        val e = start()
        typeAme(e)
        open()
        val late = exact().entries.indexOf("語140")
        assertTrue("past every list the worker caps", late > 20)
        scrollTo(exact(), late)
        val (x, y) = exact().centerOf(late)
        tap(exact(), x, y)
        assertEquals("語140", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals(View.GONE, panel().visibility)
        assertEquals(View.VISIBLE, keys().visibility)
    }

    @Test fun aLateWordIsLearnedAsTheDictionaryWordItIs() {
        val e = start()
        typeAme(e)
        open()
        val late = exact().entries.indexOf("語140")
        scrollTo(exact(), late)
        val (x, y) = exact().centerOf(late)
        tap(exact(), x, y)
        val snapshot = Learning.store(RuntimeEnvironment.getApplication()).snapshot()
        assertTrue(snapshot.user.hasBonus(dev.astra.ime.oss.Word("あめ", "語140")))
    }

    @Test fun theRawEntryCommitsTheKana() {
        val e = start()
        typeAme(e)
        open()
        val (x, y) = exact().centerOf(0)
        tap(exact(), x, y)
        assertEquals("あめ", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun aDragCommitsNothing() {
        val e = start()
        typeAme(e)
        open()
        val shown = e.text.toString()
        drag(exact(), 0f, -60f)
        assertEquals(shown, e.text.toString())
        assertEquals(0, e.composingStart)
        assertEquals(View.VISIBLE, panel().visibility)
    }

    @Test fun aSidewaysDragOnTheVerticalListNeitherScrollsNorCommits() {
        val e = start()
        typeAme(e)
        open()
        tapHeader(TAG_PANEL_EXACT_HEADER)
        drag(exact(), -80f, 2f)
        assertEquals(0, exact().scrollOffset)
        assertEquals(0, e.composingStart)
    }

    @Test fun anUpwardDragOnTheTwoRowsNeitherScrollsNorCommits() {
        val e = start()
        typeAme(e)
        open { ExactPanel(emptyList(), 0, (0 until 30).map { n -> Candidate("あめ$n", "予測語$n", 0.0) }) }
        drag(top(), 2f, -40f)
        assertEquals(0, top().scrollOffset)
        assertEquals(0, e.composingStart)
    }

    @Test fun theLockHoldsEvenIfTheFingerLaterTurnsBack() {
        val e = start()
        typeAme(e)
        open()
        tapHeader(TAG_PANEL_EXACT_HEADER)
        val x = exact().width / 2f
        val y = exact().height / 2f
        touch(exact(), MotionEvent.ACTION_DOWN, x, y)
        touch(exact(), MotionEvent.ACTION_MOVE, x - 60f, y)
        touch(exact(), MotionEvent.ACTION_MOVE, x - 60f, y - 60f)
        touch(exact(), MotionEvent.ACTION_UP, x, y)
        assertEquals(0, exact().scrollOffset)
        assertEquals(0, e.composingStart)
    }

    @Test fun cancelledTouchesPickNothing() {
        val e = start()
        typeAme(e)
        open()
        val (x, y) = exact().centerOf(2)
        touch(exact(), MotionEvent.ACTION_DOWN, x, y)
        touch(exact(), MotionEvent.ACTION_CANCEL, x, y)
        assertEquals(0, e.composingStart)
    }

    @Test fun aReleaseOnAnotherCandidatePicksNothing() {
        val e = start()
        typeAme(e)
        open()
        val (x, y) = exact().centerOf(2)
        touch(exact(), MotionEvent.ACTION_DOWN, x, y)
        touch(exact(), MotionEvent.ACTION_UP, -5f, -5f)
        assertEquals(0, e.composingStart)
    }

    @Test fun aPredictionCommitsAndIsLearnedForItsLongerReading() {
        val e = start()
        typeAme(e)
        open { ExactPanel(emptyList(), 0, listOf(Candidate("あめだま", "飴玉", 0.0))) }
        val index = top().entries.indexOf("飴玉")
        assertTrue(index >= 0)
        val (x, y) = top().centerOf(index)
        tap(top(), x, y)
        assertEquals("飴玉", e.text.toString())
        val phrases = Learning.store(RuntimeEnvironment.getApplication()).snapshot()
        val arranged = phrases.arrange("あめだま", listOf(Candidate("あめだま", "あめだま", 0.0)), listOf(emptyList()))
        assertEquals("飴玉", arranged.first.first().surface)
    }

    // --- Staleness --------------------------------------------------------------------------------

    @Test fun aLookupForAClosedPanelIsDropped() {
        val e = start()
        typeAme(e)
        ime.script = { ExactPanel(many(it, 150), 150, emptyList()) }
        ime.hold = true
        expand().performClick()
        measure()
        val before = exact().entries
        expand().performClick() // closed again
        ime.release()
        assertEquals(before, exact().entries)
    }

    @Test fun anOlderLookupCannotFillAReopenedPanel() {
        val e = start()
        typeAme(e)
        var opening = 0
        ime.script = { text -> opening++; ExactPanel(listOf(word(text, if (opening == 1) "古い語" else "新しい語")), 1, emptyList()) }
        ime.hold = true
        expand().performClick() // first lookup, still running
        expand().performClick()
        expand().performClick() // second lookup for the reopened panel
        measure()
        ime.releaseOldest()
        assertFalse("古い語" in exact().entries)
        ime.release()
        assertTrue("新しい語" in exact().entries)
    }

    @Test fun aLookupFromTheLastEditorIsDropped() {
        val e = start()
        typeAme(e)
        ime.script = { ExactPanel(many(it, 150), 150, emptyList()) }
        ime.hold = true
        expand().performClick()
        val before = exact().entries
        start()
        ime.release()
        assertEquals(before, exact().entries)
        assertEquals(View.GONE, panel().visibility)
    }

    @Test fun aNewEditorClosesThePanelAndAnOldTapDoesNothing() {
        val e = start()
        typeAme(e)
        open()
        val second = start()
        assertEquals(View.GONE, panel().visibility)
        val (x, y) = exact().centerOf(5)
        tap(exact(), x, y)
        assertEquals("", second.text.toString())
        assertEquals("雨", e.text.toString().takeLast(1))
    }

    // --- Repair -----------------------------------------------------------------------------------

    private fun segmentChips(): List<TextView> {
        val row = (root!!.findViewWithTag<ViewGroup>(TAG_STRIP_ROW).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        return (0 until row.childCount).map { row.getChildAt(it) as TextView }.filter { it.tag == TAG_SEGMENT }
    }

    private fun segmentSelected() = segmentChips().any { it.isSelected }

    /** ▼ reveals the segment buttons; the last one is selected and the panel, kept open for it, closed again. */
    private fun enterRepair() {
        expand().performClick()
        segmentChips().last().performClick()
        assertTrue(segmentSelected())
        expand().performClick()
        pumpUntil("segment candidates") { expand().text == "▼" }
    }

    @Test fun repairOffersTheSamePanelForTheFocusedUnit() {
        val e = start()
        typeAme(e)
        enterRepair()
        open()
        assertEquals(View.VISIBLE, panel().visibility)
        assertTrue("語140" in exact().entries)
        assertEquals("雨", e.text.toString())
    }

    @Test fun aLateRepairChoiceChangesOnlyThatUnitAndCommitsNothing() {
        val e = start()
        typeAme(e)
        enterRepair()
        open()
        val late = exact().entries.indexOf("語140")
        scrollTo(exact(), late)
        val (x, y) = exact().centerOf(late)
        tap(exact(), x, y)
        assertEquals("語140", e.text.toString())
        assertEquals(0, e.composingStart)
        assertEquals(View.GONE, panel().visibility)
        ime.onAction(Keyboards.ENTER)
        assertEquals("語140", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun theRepairPanelClosesWhenTheRepairEnds() {
        val e = start()
        typeAme(e)
        enterRepair()
        open()
        ime.onAction(Keyboards.ENTER) // 確定 ends the selection and commits
        assertEquals(View.GONE, panel().visibility)
        assertEquals("雨", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun aLookupForTheRepairThatEndedIsDropped() {
        val e = start()
        typeAme(e)
        enterRepair()
        ime.script = { ExactPanel(many(it, 150), 150, emptyList()) }
        ime.hold = true
        expand().performClick()
        val before = exact().entries
        ime.onAction(Keyboards.ENTER)
        ime.release()
        assertEquals(before, exact().entries)
    }

    // --- Collapsed strip --------------------------------------------------------------------------

    @Test fun collapsedLiveConversionStillShowsTheRankedFirstCandidate() {
        val e = start()
        typeAme(e)
        assertEquals("雨", e.text.toString())
        assertEquals(0, e.composingStart)
    }

    @Test fun whileOpenTheStripKeepsOnlyTheSegmentButtonsAndTheCloseButton() {
        val e = start()
        typeAme(e)
        val strip = (root!!.findViewWithTag<ViewGroup>(TAG_STRIP_ROW).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        assertTrue(strip.childCount > 1)
        open()
        val views = (0 until strip.childCount).map { strip.getChildAt(it) as TextView }
        assertEquals(listOf(TAG_SEGMENT, TAG_SEGMENT_DIVIDER), views.map { it.tag })
        assertEquals("雨", views[0].text.toString())
        assertEquals("▲", expand().text.toString())
        expand().performClick()
        assertTrue(strip.childCount > 1)
    }

    @Test fun typedTextClosesThePanelSoNoListOutlivesItsReading() {
        val e = start()
        typeAme(e)
        open()
        // Keys are hidden while open; a hardware or accessibility key path still goes through the same edit.
        ime.onFlick(FlickKey.NA, FlickDirection.entries.first { FlickLayout.kana(FlickKey.NA, it) == "の" })
        assertEquals(View.GONE, panel().visibility)
    }

    @Test fun exactAndCorrectionNeverShareASection() {
        val e = start()
        typeAme(e)
        val corrected = Candidate("あま", "尼", 0.0, listOf(Segment(0, 2, "あま", "尼", SegmentKind.WORD)))
        open { ExactPanel(many(it, 30), 30, listOf(corrected, Candidate("あめだま", "飴玉", 0.0))) }
        assertTrue("尼" in top().entries)
        assertTrue("飴玉" in top().entries)
        assertFalse("尼" in exact().entries)
        assertFalse("飴玉" in exact().entries)
        assertEquals(CandidateClass.CORRECTION, CandidateGroups.classify("あめ", corrected))
    }

    @Test fun theShownCandidateIsMarkedInBothSections() {
        val e = start()
        typeAme(e)
        open()
        assertNotNull(e)
        assertEquals("雨", top().entries[top().markedIndex])
        assertEquals("雨", exact().entries[exact().markedIndex])
    }
}
