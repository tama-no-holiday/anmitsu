package dev.astra.ime

import android.graphics.Insets
import android.graphics.Rect
import android.text.InputType as T
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_UP
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import dev.astra.ime.core.FlickDirection
import dev.astra.ime.core.FlickKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Symbol/emoji palettes, the English and number 12-key layouts and the stored layout choice. SDK 30 so that
 * android.icu segments graphemes with Unicode 13 rules. mdpi: 1dp = 1px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class PaletteLayoutTest {
    @Before fun useSmallFixture() {
        val context = RuntimeEnvironment.getApplication()
        DebugSettings.setOssEnabled(context, false)
        DebugSettings.setZenzEnabled(context, false)
        DebugSettings.setLiveConversion(context, false)
        Learning.setEnabled(context, true)
        Learning.store(context).clearHistory() // palettes open on their catalog until something is used
    }

    // --- Catalog -------------------------------------------------------------------------------------

    private val allSymbols get() = Palettes.symbols.flatMap { it.items }
    private val allEmoji get() = Palettes.emoji.flatMap { it.items }

    @Test fun catalogsAreLargeAndUniqueAcrossBothPalettes() {
        println("palette counts: symbols=${allSymbols.size} (${Palettes.symbols.size} categories), emoji=${allEmoji.size} (${Palettes.emoji.size} categories)")
        assertTrue(allSymbols.size >= 150)
        assertTrue(allEmoji.size >= 500)
        // Distinct even ignoring the emoji variation selector (☀ and ☀️ would count once).
        val all = (allSymbols + allEmoji).map { it.replace("️", "") }
        assertEquals(all.groupingBy { it }.eachCount().filterValues { it > 1 }.keys, emptySet<String>())
        for (c in Palettes.symbols + Palettes.emoji) assertTrue(c.label, c.items.size >= 8)
        val labels = (Palettes.symbols + Palettes.emoji).map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(Palettes.emoji.size >= 9 && Palettes.symbols.size >= 6)
    }

    /** Structure of each entry: valid UTF-16, VS16/ZWJ/keycap/flag parts only where a sequence allows them. */
    @Test fun everyEntryIsOneWellFormedCharacter() {
        val graphemes = android.icu.text.BreakIterator.getCharacterInstance()
        for (item in allSymbols + allEmoji) {
            assertTrue(item, item.isNotEmpty() && item.none { it.isWhitespace() })
            item.forEachIndexed { i, ch ->
                if (ch.isHighSurrogate()) assertTrue(item, i + 1 < item.length && item[i + 1].isLowSurrogate())
                if (ch.isLowSurrogate()) assertTrue(item, i > 0 && item[i - 1].isHighSurrogate())
            }
            val cps = item.codePoints().toArray()
            assertNotEquals(item, 0xFE0F, cps.first())
            assertNotEquals(item, 0x200D, cps.first())
            assertNotEquals(item, 0x200D, cps.last())
            for (i in 1 until cps.size) {
                if (cps[i] == 0xFE0F) assertTrue(item, cps[i - 1] != 0xFE0F && cps[i - 1] != 0x200D)
                if (cps[i] == 0x200D) assertTrue(item, cps[i - 1] != 0x200D)
                if (cps[i] == 0x20E3) assertTrue(item, i == 2 && cps[1] == 0xFE0F && cps[0].toChar() in "#*0123456789")
            }
            val regional = cps.count { it in 0x1F1E6..0x1F1FF }
            assertTrue(item, regional == 0 || (regional == 2 && cps.size == 2))
            graphemes.setText(item)
            graphemes.first()
            assertEquals("one user-perceived character: $item", item.length, graphemes.next())
        }
        assertTrue(allEmoji.contains(String(Character.toChars(0x1F1EF)) + String(Character.toChars(0x1F1F5)))) // 🇯🇵
        assertTrue(allEmoji.contains("1️⃣"))
        assertTrue(allEmoji.any { it.contains('‍') })
    }

    @Test fun unrenderableEntriesAreHiddenButNeverEverything() {
        val items = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), Palettes.renderable(items) { it != "b" })
        assertEquals(items, Palettes.renderable(items) { false }) // no font information: keep the list
    }

    // --- 12-key layouts ------------------------------------------------------------------------------

    private fun outputs(rows: List<List<Key>>): List<String> = rows.flatten().flatMap { k -> k.flickTexts.orEmpty().filterNotNull() + listOfNotNull(k.text) }

    @Test fun english12KeyReachesEveryLetterOnceInEachCase() {
        val letters = ('a'..'z').map { it.toString() }
        val lower = outputs(Keyboards.english12(Shift.OFF, "⏎")).filter { it.single().isLetter() }
        assertEquals(letters, lower.sorted())
        for (shift in listOf(Shift.ONCE, Shift.LOCK)) {
            val upper = outputs(Keyboards.english12(shift, "⏎")).filter { it.single().isLetter() }
            assertEquals(letters.map { it.uppercase() }, upper.sorted())
        }
        val rows = Keyboards.english12(Shift.OFF, "⏎")
        assertEquals(4, rows.size)
        val pqrs = rows.flatten().single { it.label == "pqrs" }
        assertEquals(listOf("p", "q", "r", "s", "7"), pqrs.flickTexts)
        assertEquals(listOf("w", "x", "y", "z", "9"), rows.flatten().single { it.label == "wxyz" }.flickTexts)
        assertEquals(listOf("a", "b", "c", null, "2"), rows.flatten().single { it.label == "abc" }.flickTexts)
        // 0.1.18: every down flick on the text keys is its phone-pad digit, in either case.
        for (shift in Shift.entries) {
            val downs = Keyboards.english12(shift, "⏎").map { row -> row.mapNotNull { it.flickTexts?.get(FlickDirection.DOWN.ordinal) } }
            assertEquals(listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("0")), downs)
        }
        val actions = rows.flatten().map { it.action }
        for (a in listOf(Keyboards.MODE_CYCLE, Keyboards.TO_EMOJI, Keyboards.TO_JSYMBOLS, Keyboards.SHIFT, Keyboards.BACKSPACE,
            Keyboards.SPACE, Keyboards.ENTER, Keyboards.CURSOR_LEFT, Keyboards.CURSOR_RIGHT)) assertTrue("$a", a in actions)
        assertTrue(outputs(rows).containsAll(listOf(".", ",", "?", "!", "'", "\"", ":", ";", "@", "-", "_", "/")))
        assertEquals(listOf(Shift.ONCE, Shift.LOCK, Shift.OFF), listOf(Shift.OFF.next(), Shift.ONCE.next(), Shift.LOCK.next()))
    }

    /**
     * 0.1.33: the user's Gboard English 12-key, confirmed from their screenshot: first symbol is the tap, then left, up,
     * right (center, left, up, right, down). The phone-pad digits stay on the down flick; the . key has no fifth symbol.
     */
    @Test fun englishSymbolKeysAreTheConfirmedGboardMappingInEveryShiftState() {
        for (shift in Shift.entries) {
            val keys = Keyboards.english12(shift, "⏎").flatten()
            assertEquals(listOf("@", "-", "_", "/", "1"), keys.single { it.label == "@" }.flickTexts)
            assertEquals(listOf("'", "\"", ":", ";", "0"), keys.single { it.label == "'" }.flickTexts)
            assertEquals(listOf(".", ",", "?", "!", null), keys.single { it.label == "." }.flickTexts)
            assertFalse(outputs(listOf(keys)).any { it == "&" || it == "#" || it == "(" || it == ")" })
        }
    }

    @Test fun theSymbolsLeavingTheEnglishKeysStayInThePaletteAndThe123Rows() {
        for (symbol in listOf("&", "\"")) {
            assertTrue(symbol, symbol in Palettes.symbols.first { it.label == "句読点" }.items)
        }
        for (symbol in listOf("(", ")")) assertTrue(symbol, symbol in Palettes.symbols.first { it.label == "括弧" }.items)
        for (symbol in listOf("&", "\"", "#", "(", ")")) assertTrue(symbol, symbol in outputs(Keyboards.symbols("⏎")))
        for (symbol in listOf("&", "#", "(", ")")) assertTrue(symbol, symbol in outputs(Keyboards.numeric12("⏎")))
        assertEquals("the full-width forms stay too", 1, allSymbols.count { it == "＆" })
    }

    @Test fun qwertyKeepsEveryLetterAndThePeriodKeyFlicksToHyphenAndUnderscore() {
        for (shift in Shift.entries) {
            val rows = Keyboards.letters(shift, "⏎")
            val letters = outputs(rows).filter { it.single().isLetter() }.sorted()
            assertEquals(('a'..'z').map { if (shift.upper) it.uppercase() else it.toString() }, letters)
            assertEquals(listOf(".", "-", ",", "_", "?"), rows.flatten().single { it.label == "." }.flickTexts)
            assertEquals(listOf("q", "w", "e"), rows[0].take(3).map { it.text?.lowercase() }) // key positions unchanged
            val actions = rows.flatten().map { it.action }
            for (a in listOf(Keyboards.MODE_CYCLE, Keyboards.TO_EMOJI, Keyboards.TO_JSYMBOLS, Keyboards.SHIFT, Keyboards.BACKSPACE, Keyboards.SPACE, Keyboards.ENTER)) {
                assertTrue("$a", a in actions)
            }
        }
        val kb = keyboard(Keyboards.letters(Shift.OFF, "⏎"))
        val dot = kb.keyRows.flatten().single { it.label == "." }.rect
        gesture(kb, dot.centerX(), dot.centerY(), 0f, 0f)
        gesture(kb, dot.centerX(), dot.centerY(), -30f, 0f)
        gesture(kb, dot.centerX(), dot.centerY(), 30f, 0f)
        assertEquals(listOf(".", "-", "_"), log)
        gesture(kb, dot.centerX(), dot.centerY(), 0f, -30f)
        gesture(kb, dot.centerX(), dot.centerY(), 0f, 30f)
        gesture(kb, dot.centerX(), dot.centerY(), -30f, 0f, cancel = true)
        assertEquals(listOf(".", "-", "_", ",", "?"), log)
    }

    @Test fun twelveKeySymbolFlicksTypeTheConfirmedMapping() {
        val kb = keyboard(Keyboards.english12(Shift.OFF, "⏎"))
        val directions = listOf(0f to 0f, -30f to 0f, 0f to -30f, 30f to 0f, 0f to 30f) // tap, left, up, right, down
        for (label in listOf("@", "'", ".")) {
            val r = kb.keyRows.flatten().single { it.label == label }.rect
            directions.forEach { (dx, dy) -> gesture(kb, r.centerX(), r.centerY(), dx, dy) }
        }
        assertEquals(listOf("@", "-", "_", "/", "1", "'", "\"", ":", ";", "0", ".", ",", "?", "!"), log)
    }

    @Test fun numeric12KeyIsAPhonePadWithSignAndDecimal() {
        val rows = Keyboards.numeric12("⏎")
        val centers = rows.map { row -> row.mapNotNull { it.flickTexts?.get(FlickDirection.CENTER.ordinal) } }
        assertEquals(listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("-", "0", ".")), centers)
        assertTrue(outputs(rows).containsAll(listOf("+", "-", ".", ",", "*", "/", "=", "%", "(", ")", "_")))
        val actions = rows.flatten().map { it.action }
        for (a in listOf(Keyboards.MODE_CYCLE, Keyboards.TO_EMOJI, Keyboards.TO_JSYMBOLS, Keyboards.BACKSPACE, Keyboards.SPACE, Keyboards.ENTER)) {
            assertTrue("$a", a in actions)
        }
    }

    private val log = ArrayList<String>()

    private fun keyboard(rows: List<List<Key>>) = KeyboardView(RuntimeEnvironment.getApplication()).apply {
        setRows(rows)
        listener = object : KeyboardView.Listener {
            override fun onFlick(key: dev.astra.ime.core.FlickKey, direction: FlickDirection) { log += "flick" }
            override fun onText(text: String) { log += text }
            override fun onAction(action: Int) { log += "action:$action" }
        }
        measure(MeasureSpec.makeMeasureSpec(575, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(224, MeasureSpec.EXACTLY))
        layout(0, 0, 575, 224)
    }

    private fun event(time: Long, action: Int, vararg p: Pair<Float, Float>): MotionEvent {
        val props = Array(p.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(p.size) { i -> MotionEvent.PointerCoords().apply { x = p[i].first; y = p[i].second; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(0L, time, action, p.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun gesture(view: View, x: Float, y: Float, dx: Float, dy: Float, cancel: Boolean = false) {
        listOf(event(0, ACTION_DOWN, x to y), event(10, ACTION_MOVE, x + dx to y + dy),
            event(20, if (cancel) ACTION_CANCEL else ACTION_UP, x + dx to y + dy)).forEach { view.onTouchEvent(it); it.recycle() }
    }

    @Test fun englishFlicksTypeTheHintedLetterAndBlankOrCancelTypeNothing() {
        val kb = keyboard(Keyboards.english12(Shift.OFF, "⏎"))
        val pqrs = kb.keyRows.flatten().single { it.label == "pqrs" }.rect
        val abc = kb.keyRows.flatten().single { it.label == "abc" }.rect
        gesture(kb, pqrs.centerX(), pqrs.centerY(), 0f, 0f)
        gesture(kb, pqrs.centerX(), pqrs.centerY(), -30f, 0f)
        gesture(kb, pqrs.centerX(), pqrs.centerY(), 0f, -30f)
        gesture(kb, pqrs.centerX(), pqrs.centerY(), 30f, 0f)
        assertEquals(listOf("p", "q", "r", "s"), log)
        gesture(kb, abc.centerX(), abc.centerY(), 30f, 0f) // right is blank on abc
        gesture(kb, abc.centerX(), abc.centerY(), -30f, 0f, cancel = true)
        assertEquals(listOf("p", "q", "r", "s"), log)
        gesture(kb, abc.centerX(), abc.centerY(), 0f, -30f)
        assertEquals("c", log.last())
        gesture(kb, abc.centerX(), abc.centerY(), 0f, 30f) // down is its digit (0.1.18)
        assertEquals("2", log.last())
    }

    // --- PaletteView ---------------------------------------------------------------------------------

    private val picked = ArrayList<String>()

    private fun palette(width: Int, items: List<String>, height: Int = 168) = PaletteView(RuntimeEnvironment.getApplication()).apply {
        setItems(items)
        onPick = { picked += it }
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        layout(0, 0, width, height)
    }

    @Test fun columnsAdaptAndOnlyVisibleRowsAreDrawn() {
        val items = allEmoji
        val narrow = palette(400, items)
        assertEquals(8, narrow.columns) // 50px cells, never under 48dp
        assertEquals(22, palette(1080, items).columns)
        assertEquals(PaletteView.MIN_COLUMNS, palette(150, items).columns)
        assertEquals(0 until 4, narrow.visibleRows()) // 168px of 48px rows, not all ${items.size / 8} rows
    }

    @Test fun tapCommitsWholeSequenceButDragFlingCancelAndSecondFingerNever() {
        val family = "👨‍👩‍👧‍👦"
        val items = listOf(family) + allSymbols
        val p = palette(400, items)
        gesture(p, 25f, 24f, 3f, 2f) // within slop: a tap
        assertEquals(listOf(family), picked)
        gesture(p, 75f, 100f, 0f, -80f) // drag up: scrolls only
        assertTrue(p.scrollY > 0)
        assertEquals(1, picked.size)
        val scrolled = p.scrollY
        gesture(p, 75f, 100f, 0f, 40f, cancel = true)
        assertEquals(1, picked.size)
        assertTrue(p.scrollY < scrolled)
        // Back at a cell, a second finger turns the press into a drag; nothing is committed on release.
        listOf(event(0, ACTION_DOWN, 75f to 100f), event(5, ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 75f to 100f, 200f to 100f),
            event(10, ACTION_UP, 75f to 100f)).forEach { p.onTouchEvent(it); it.recycle() }
        assertEquals(1, picked.size)
        // A press released on another cell commits nothing.
        gesture(p, 49f, 120f, 0f, 0f)
        val before = picked.size
        listOf(event(0, ACTION_DOWN, 98f to 120f), event(10, ACTION_UP, 102f to 120f)).forEach { p.onTouchEvent(it); it.recycle() }
        assertEquals(before, picked.size)
    }

    // --- The service ---------------------------------------------------------------------------------

    private var controller: ServiceController<TestIme>? = null
    private val ime: TestIme get() = controller!!.get()

    @After fun tearDown() { controller?.destroy() }

    private fun start(type: Int, imeOptions: Int = 0): FakeEditor {
        if (controller == null) controller = Robolectric.buildService(TestIme::class.java).create()
        val editor = FakeEditor(View(ime), "", 0, 0)
        val info = EditorInfo().apply { inputType = type; initialSelStart = 0; initialSelEnd = 0; this.imeOptions = imeOptions }
        ime.ic = editor
        ime.info = info
        ime.onStartInput(info, false)
        return editor
    }

    private fun root(): ViewGroup {
        start(T.TYPE_CLASS_TEXT)
        return ime.onCreateInputView() as ViewGroup
    }

    private fun layout(root: View) {
        root.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun keysOf(root: ViewGroup) = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
    private fun gridOf(root: ViewGroup) = (root.findViewWithTag<LinearLayout>(TAG_PALETTE)).getChildAt(1) as PaletteView
    private fun tabsOf(root: ViewGroup) = root.findViewWithTag<LinearLayout>(TAG_PALETTE_TABS)
    private fun toggleOf(root: ViewGroup) = root.findViewWithTag<TextView>(TAG_LAYOUT_TOGGLE)
    private fun labels(root: ViewGroup) = keysOf(root).keyRows.flatten().map { it.label }

    // --- 履歴 (0.1.32) ---------------------------------------------------------------------------------

    private fun tabLabels(root: ViewGroup) = (0 until tabsOf(root).childCount).map { (tabsOf(root).getChildAt(it) as TextView).text.toString() }
    private fun selectedTab(root: ViewGroup) =
        (0 until tabsOf(root).childCount).map { tabsOf(root).getChildAt(it) as TextView }.single { it.isSelected }.text.toString()
    /** What a tap on [entry] in the grid does. */
    private fun tapEntry(root: ViewGroup, entry: String) = gridOf(root).onPick!!(entry)
    private fun store() = Learning.store(RuntimeEnvironment.getApplication())

    @Test fun eachPaletteHasItsOwnHistoryOpenedFirstOnceItHasEntries() {
        val root = root()
        val e = start(T.TYPE_CLASS_TEXT)
        ime.onAction(Keyboards.TO_JSYMBOLS)
        assertEquals(Palettes.HISTORY_LABEL, tabLabels(root).first())
        assertEquals(Palettes.symbols.first().label, selectedTab(root)) // nothing used yet: the catalog
        tabsOf(root).getChildAt(0).performClick()
        assertTrue(gridOf(root).entries.isEmpty())
        assertTrue(gridOf(root).emptyLabel.isNotEmpty())
        tabsOf(root).getChildAt(3).performClick() // browsing records nothing
        assertTrue(store().recent(false).isEmpty())
        tapEntry(root, "→")
        tapEntry(root, "※")
        tapEntry(root, "→")
        assertEquals("→※→", e.text.toString())
        assertEquals(listOf("→", "※"), store().recent(false)) // most recent first, once each
        ime.onAction(Keyboards.TO_EMOJI) // the emoji palette has its own, still empty
        assertEquals(Palettes.emoji.first().label, selectedTab(root))
        val family = "👨‍👩‍👧"
        tapEntry(root, family)
        assertEquals(listOf(family), store().recent(true)) // the whole sequence, one entry
        assertEquals(listOf("→", "※"), store().recent(false))
        ime.onAction(Keyboards.TO_TEXT)
        ime.onAction(Keyboards.TO_JSYMBOLS)
        assertEquals(Palettes.HISTORY_LABEL, selectedTab(root))
        assertEquals(listOf("→", "※"), gridOf(root).entries)
        tapEntry(root, "※")
        assertEquals(listOf("→", "※"), gridOf(root).entries) // what is shown does not jump under the finger
        assertEquals(listOf("※", "→"), store().recent(false))
        ime.onAction(Keyboards.TO_EMOJI)
        assertEquals(listOf(family), gridOf(root).entries)
        // 学習内容を消去する clears both.
        store().clearHistory()
        ime.onAction(Keyboards.TO_JSYMBOLS)
        assertEquals(Palettes.symbols.first().label, selectedTab(root))
        assertTrue(store().recent(true).isEmpty())
        assertEquals(0, e.reads)
    }

    @Test fun anEntryCountsOnlyWhenTheTextHoldingItIsCommitted() {
        val root = root()
        val e = start(T.TYPE_CLASS_TEXT)
        ime.onFlick(FlickKey.A, FlickDirection.CENTER) // あ composing
        ime.onAction(Keyboards.TO_JSYMBOLS)
        tapEntry(root, "→")
        assertEquals("あ→", e.text.toString())
        assertEquals(0, e.composingStart)
        ime.onFinishInputView(false) // hidden: left as typed, never confirmed
        assertEquals(-1, e.composingStart)
        assertTrue(store().recent(false).isEmpty())
        ime.onAction(Keyboards.TO_TEXT)
        ime.onFlick(FlickKey.A, FlickDirection.CENTER)
        ime.onAction(Keyboards.TO_JSYMBOLS)
        tapEntry(root, "※")
        ime.onAction(Keyboards.ENTER) // 確定
        assertEquals("あ→あ※", e.text.toString())
        assertEquals(listOf("※"), store().recent(false))
        // English: inserted then deleted before 確定 is not used; inserted and confirmed is.
        ime.onAction(Keyboards.TO_TEXT)
        ime.onAction(Keyboards.MODE_CYCLE)
        ime.onText("o")
        ime.onText("k")
        ime.onAction(Keyboards.TO_EMOJI)
        tapEntry(root, "👍")
        ime.onAction(Keyboards.BACKSPACE)
        ime.onAction(Keyboards.ENTER)
        assertTrue(store().recent(true).isEmpty())
        ime.onAction(Keyboards.TO_TEXT)
        ime.onText("o")
        ime.onAction(Keyboards.TO_EMOJI)
        tapEntry(root, "👍")
        ime.onAction(Keyboards.SPACE)
        assertEquals("あ→あ※oko👍 ", e.text.toString())
        assertEquals(listOf("👍"), store().recent(true))
        // Keys that type the same character are not palette use.
        ime.onAction(Keyboards.TO_TEXT)
        ime.onText("→")
        assertEquals(listOf("※"), store().recent(false))
    }

    @Test fun noHistoryWithLearningOffOrInPrivateAndPasswordFields() {
        val root = root()
        store().useEntry(false, "→")
        val cases = listOf(
            Triple(T.TYPE_CLASS_TEXT, 0, false), // learning switched off
            Triple(T.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, true),
            Triple(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, 0, true),
        )
        for ((type, options, learningOn) in cases) {
            Learning.setEnabled(RuntimeEnvironment.getApplication(), learningOn)
            val e = start(type, options)
            ime.onAction(Keyboards.TO_JSYMBOLS)
            assertFalse(Palettes.HISTORY_LABEL in tabLabels(root)) // neither shown nor used
            assertEquals(Palettes.symbols.first().label, selectedTab(root))
            tapEntry(root, "※")
            ime.onAction(Keyboards.TO_TEXT)
            assertEquals("※", e.text.toString())
            assertEquals(listOf("→"), store().recent(false)) // nothing recorded
        }
    }

    @Test fun palettesScrollCommitWholeEntriesAndReturnToTheScreenTheyCameFrom() {
        val root = root()
        val e = start(T.TYPE_CLASS_TEXT)
        root.dispatchApplyWindowInsets(WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48)).build())
        ime.onAction(Keyboards.TO_ASCII)
        ime.onAction(Keyboards.TO_JSYMBOLS)
        layout(root)
        val box = root.findViewWithTag<View>(TAG_PALETTE)
        val kb = keysOf(root)
        assertEquals(View.VISIBLE, box.visibility)
        assertEquals(1, kb.keyRows.size) // fixed bottom actions only
        assertEquals(56, kb.height)
        assertEquals(box.bottom, kb.top)
        assertEquals(root.height - 48, kb.bottom) // keys stay above the safe area
        assertTrue(tabsOf(root).getChildAt(0).height >= 44 && tabsOf(root).getChildAt(0).width >= 48)
        val grid = gridOf(root)
        assertEquals("、", grid.entries.first())
        gesture(grid, 10f, 10f, 0f, 0f)
        assertEquals("、", e.text.toString())
        gesture(grid, 10f, 100f, 0f, -60f) // scrolling never types
        assertEquals("、", e.text.toString())
        tabsOf(root).getChildAt(2).performClick() // brackets (履歴 is the leftmost tab)
        assertEquals(0, grid.scrollY)
        assertEquals("「", grid.entries.first())
        gesture(grid, 10f, 10f, 0f, 0f)
        assertEquals("、「", e.text.toString())

        ime.onAction(Keyboards.TO_EMOJI)
        tabsOf(root).getChildAt(Palettes.emoji.lastIndex + 1).performClick() // flags
        layout(root)
        val flagIndex = grid.entries.indexOfFirst { it.codePointCount(0, it.length) == 2 && it.codePointAt(0) in 0x1F1E6..0x1F1FF }
        val cols = grid.columns
        val cw = grid.width.toFloat() / cols
        gesture(grid, (flagIndex % cols) * cw + cw / 2, (flagIndex / cols) * 48f + 24f, 0f, 0f)
        assertEquals("、「" + grid.entries[flagIndex], e.text.toString())
        assertEquals(4, grid.entries[flagIndex].length) // both regional indicators, never half a flag
        val flag = grid.entries[flagIndex]
        assertTrue(labels(root).contains("abc")) // back key names the screen the palette came from
        ime.onAction(Keyboards.TO_TEXT)
        assertEquals(View.GONE, box.visibility)
        assertTrue(labels(root).contains("a/A")) // English 12-key by default on a fresh install
        // 0.1.33: opened from English, the entries are one English composition until 確定; the toggle returns after it.
        assertEquals(0, e.composingStart)
        assertEquals(View.GONE, toggleOf(root).visibility)
        ime.onAction(Keyboards.ENTER)
        assertEquals("、「$flag", e.text.toString())
        assertEquals(-1, e.composingStart)
        assertEquals(View.VISIBLE, toggleOf(root).visibility)
        assertEquals(0, e.reads)
    }

    @Test fun kanaCompositionSurvivesAPaletteTrip() {
        val root = root()
        val e = start(T.TYPE_CLASS_TEXT)
        ime.onFlick(dev.astra.ime.core.FlickKey.KA, FlickDirection.CENTER)
        ime.onAction(Keyboards.TO_JSYMBOLS)
        assertTrue(labels(root).contains("あ"))
        assertEquals(0, e.composingStart) // still composing; nothing committed by opening the palette
        ime.onAction(Keyboards.TO_TEXT)
        assertTrue(labels(root).contains("変換")) // back on kana with the composing labels
        assertEquals(View.GONE, toggleOf(root).visibility)
        ime.onAction(Keyboards.ENTER)
        assertEquals("か", e.text.toString())
        assertEquals(-1, e.composingStart)
    }

    @Test fun layoutChoiceIsTheOnlyThingStoredAndTogglesBothScreens() {
        assertTrue(LayoutSettings.defaultTwelveKey(0, 0))
        assertTrue(LayoutSettings.defaultTwelveKey(500, 500))
        assertFalse(LayoutSettings.defaultTwelveKey(500, 900)) // updated from a QWERTY-only version
        val root = root()
        val e = start(T.TYPE_CLASS_TEXT)
        ime.onAction(Keyboards.TO_ASCII)
        assertTrue(labels(root).contains("abc"))
        assertEquals(ime.getString(R.string.layout_to_full), toggleOf(root).text.toString())
        toggleOf(root).performClick()
        assertTrue(labels(root).contains("q") && !labels(root).contains("abc"))
        ime.onText("q")
        ime.onAction(Keyboards.TO_SYMBOLS)
        assertTrue(labels(root).contains("5") && labels(root).contains("ABC")) // mode key: back to English, where opened
        toggleOf(root).performClick()
        assertTrue(labels(root).contains("記号") && !labels(root).contains("◀"))
        val prefs = ime.getSharedPreferences("astra_layout", android.content.Context.MODE_PRIVATE).all
        assertEquals(mapOf("english_12key" to false, "numeric_12key" to false), prefs)
        assertEquals("q", e.text.toString())
        // A new editor keeps the choice.
        start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        assertTrue(labels(root).contains("q"))
        assertEquals(ime.getString(R.string.layout_to_12key), toggleOf(root).text.toString())
    }

    @Test fun shiftGivesOneCapitalOrLocksAndEditorsKeepTheirPolicies() {
        val root = root()
        val pw = start(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)
        val kb = keysOf(root)
        assertTrue(labels(root).contains("a/A"))
        assertFalse(kb.showPreview)
        assertFalse(kb.collectSamples)
        ime.onAction(Keyboards.SHIFT)
        ime.onText("A")
        assertTrue(labels(root).contains("abc")) // one capital, then lower again
        ime.onAction(Keyboards.SHIFT)
        ime.onAction(Keyboards.SHIFT)
        assertTrue(labels(root).contains("A ⇪"))
        ime.onText("B")
        ime.onText("C")
        assertTrue(labels(root).contains("ABC")) // caps lock stays
        ime.onAction(Keyboards.SHIFT)
        assertTrue(labels(root).contains("abc"))
        assertEquals("ABC", pw.text.toString())
        assertEquals(-1, pw.composingStart)

        val number = start(T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_FLAG_DECIMAL or T.TYPE_NUMBER_FLAG_SIGNED)
        layout(root)
        val keys = keysOf(root)
        fun tap(label: String, dy: Float = 0f) {
            val r = keys.keyRows.flatten().single { it.label == label }.rect
            gesture(keys, r.centerX(), r.centerY(), 0f, dy)
        }
        tap("-")
        tap("1")
        tap(".")
        tap("5")
        assertEquals("-1.5", number.text.toString())
        assertEquals(-1, number.composingStart)
        assertEquals(0, pw.reads + number.reads)
    }
}
