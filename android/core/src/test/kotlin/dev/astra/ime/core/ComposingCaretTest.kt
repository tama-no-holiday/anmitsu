package dev.astra.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.34: the composing caret ([RepairSession.caretMode]), its character steps and its place in the surface. */
class ComposingCaretTest {
    /** 明日は (あした + は) | 雨 (あめ): reading and surface lengths differ. */
    private fun tomorrowRain() = listOf(
        RepairUnit("あしたは", "あしたは", "明日は",
            listOf(Segment(0, 3, "あした", "明日", SegmentKind.WORD), Segment(3, 4, "は", "は", SegmentKind.WORD)), emptyList()),
        RepairUnit("あめ", "あめ", "雨", listOf(Segment(0, 2, "あめ", "雨", SegmentKind.WORD)), emptyList()),
    )

    private fun caretSession(units: List<RepairUnit> = tomorrowRain()) = RepairSession(units).also { it.caretMode = true }

    private fun shown(r: RepairSession) = r.shownSurface.let { it.substring(0, r.shownCaret) + "|" + it.substring(r.shownCaret) }

    @Test fun graphemesKeepEmojiSequencesFlagsMarksAndPairsWhole() {
        val family = "👨‍👩‍👧"
        val text = "a${family}👍🏽🇯🇵が1️⃣😀"
        val stops = ArrayList<Int>()
        var i = 0
        while (i < text.length) { i = Graphemes.next(text, i); stops += i }
        val pieces = (listOf(0) + stops).zipWithNext { a, b -> text.substring(a, b) }
        assertEquals(listOf("a", family, "👍🏽", "🇯🇵", "が", "1️⃣", "😀"), pieces)
        assertEquals(text.length - 2, Graphemes.previous(text, text.length)) // 😀 is one surrogate pair
        assertEquals(1, Graphemes.previous(text, 1 + family.length))
        assertTrue(Graphemes.isBoundary(text, 1) && !Graphemes.isBoundary(text, 2))
        assertEquals(0, Graphemes.previous(text, 0))
        assertEquals(text.length, Graphemes.next(text, text.length))
    }

    @Test fun theSurfaceOffsetIsExactOnlyWhereTheCaretHasAPlace() {
        val u = tomorrowRain()[0]
        assertEquals(0, Alignment.surfaceOffset(u, 0))
        assertNull(Alignment.surfaceOffset(u, 1)) // あ|した inside 明日: no surface position
        assertNull(Alignment.surfaceOffset(u, 2))
        assertEquals(2, Alignment.surfaceOffset(u, 3)) // 明日|は: a segment boundary
        assertEquals(3, Alignment.surfaceOffset(u, 4))
        val kana = RepairUnit("あめ", "あめ", "あめ", emptyList(), emptyList())
        assertEquals(1, Alignment.surfaceOffset(kana, 1))
        val unaligned = RepairUnit("あめ", "あめ", "雨", emptyList(), emptyList())
        assertNull(Alignment.surfaceOffset(unaligned, 1))
    }

    @Test fun theCaretWalksTheReadingAndAWordHoldingItShowsItsKana() {
        val r = caretSession()
        assertEquals("明日は雨|", shown(r))
        assertTrue(r.caretLeft())
        assertEquals("明日はあ|め", shown(r)) // inside 雨: its kana, the caret after あ
        assertEquals(1, r.focus)
        assertTrue(r.caretLeft())
        assertEquals("明日は|雨", shown(r)) // the boundary belongs to the end of 明日は: 雨 is back
        assertEquals(0, r.focus)
        assertTrue(r.caretLeft())
        assertEquals("明日|は雨", shown(r))
        assertTrue(r.caretLeft())
        assertEquals("あし|たは雨", shown(r))
        assertTrue(r.caretToStart())
        assertEquals("|明日は雨", shown(r))
        assertFalse(r.caretLeft())
        assertFalse(r.caretToStart())
        assertTrue(r.caretRight())
        assertEquals("あ|したは雨", shown(r))
        assertTrue(r.caretToEnd())
        assertTrue(r.caretAtEnd)
        assertFalse(r.caretRight())
        assertEquals(listOf("明日は", "雨"), r.units.map { it.surface }) // moving changed nothing
        assertFalse(r.canUndo)
    }

    private fun surfaces(units: List<RepairUnit>) = units.map { it.surface }

    /** Review follow-up: a boundary at the caret, only shown while the caret is there; the units never change by moving. */
    @Test fun theCaretShowsAProvisionalBoundaryAndItsScopeWithoutChangingTheUnits() {
        val r = caretSession()
        val initial = r.units
        r.caretLeft()
        r.caretLeft() // 明日は|雨: a segment boundary, no split; the scope is 明日は
        assertEquals(listOf("明日は", "雨"), surfaces(r.shownUnits))
        assertEquals("あしたは", r.scopeTyped)
        assertEquals(0 until 3, r.shownFocusRange)
        val revision = r.revision
        r.caretLeft() // 明日|は雨: split inside 明日は at its word boundary, words kept
        assertTrue(r.revision != revision) // a new scope: older candidates and results are void
        assertEquals(listOf("明日", "は", "雨"), surfaces(r.shownUnits))
        assertEquals(listOf("あした", "は", "あめ"), r.shownUnits.map { it.typed })
        assertEquals("あした", r.scopeTyped)
        assertEquals("明日", r.scopeSurface)
        assertEquals(0 until 2, r.shownFocusRange)
        assertEquals(2, r.shownCaret)
        assertEquals(listOf(0, 0, 1), (0..2).map { r.unitOfShown(it) })
        r.caretLeft() // あし|たは雨: inside 明日, both parts as kana
        assertEquals(listOf("あし", "たは", "雨"), surfaces(r.shownUnits))
        assertEquals("あし", r.scopeTyped)
        assertEquals("あし|たは雨", shown(r))
        r.caretToEnd()
        assertEquals(listOf("明日は", "雨"), surfaces(r.shownUnits)) // walked back untouched: the conversion as it was
        assertTrue(initial.indices.all { r.units[it] === initial[it] })
        assertFalse(r.canUndo)
        assertEquals(initial, r.finish())
    }

    @Test fun aStaleResultForAnotherBoundaryIsRejected() {
        val r = caretSession()
        repeat(3) { r.caretLeft() } // 明日|は雨
        val old = r.request()!!
        assertEquals("あした", old.text)
        r.caretLeft() // あし|た: another scope
        assertFalse(r.apply(old, listOf(Candidate("あした", "明日", 0.0)), preview = true))
        assertTrue(r.candidates.isEmpty())
        val now = r.request()!!
        assertEquals("あし", now.text)
        assertTrue(r.apply(now, listOf(Candidate("あし", "足", 0.0)), preview = true))
        assertEquals("あし|たは雨", shown(r)) // a result shows nothing by itself
        assertEquals(listOf("明日は", "雨"), surfaces(r.units))
    }

    @Test fun editsAtTheCaretMakeOneBoundaryRealAndLeaveTheOtherUnitsAlone() {
        val r = caretSession()
        repeat(2) { r.caretLeft() } // 明日は|雨
        assertTrue(r.insert("の")) // a new unit between them; 明日は and 雨 are kept as they are
        assertEquals(listOf("あしたは", "の", "あめ"), r.units.map { it.typed })
        assertEquals(listOf("明日は", "の", "雨"), surfaces(r.units))
        assertEquals("明日はの|雨", shown(r))
        assertEquals("の", r.scopeTyped)
        assertTrue(r.insert("に")) // typing goes on in the unit it builds, no further boundary
        assertEquals(listOf("あしたは", "のに", "あめ"), r.units.map { it.typed })
        assertEquals("のに", r.request()!!.text)
        assertTrue(r.deleteBackward())
        assertTrue(r.deleteBackward()) // the new unit is gone
        assertEquals(listOf("明日は", "雨"), surfaces(r.units))
        assertEquals("明日は|雨", shown(r))
        assertTrue(r.deleteBackward()) // は, at 明日's boundary: 明日 is kept, nothing to convert again
        assertEquals(listOf("明日", "雨"), surfaces(r.units))
        assertEquals("明日|雨", shown(r))
        assertFalse(r.hasUnconverted)
        r.caretLeft() // あし|た雨
        assertTrue(r.toggleBefore { if (it == "し") "じ" else null })
        assertEquals(listOf("あじ", "た", "あめ"), r.units.map { it.typed })
        assertEquals("あじ|た雨", shown(r))
        assertEquals("あじ", r.request()!!.text) // the edited part converts; た, the cut word's rest, alongside
        assertTrue(r.hasUnconverted)
        assertEquals(1, r.request()!!.neighbor)
        assertEquals("雨", r.units[2].surface)
        assertTrue(r.canUndo)
    }

    @Test fun typingAtTheStartAddsAUnitBeforeAndDeletingInsideAWordCutsOnlyThatUnit() {
        val r = caretSession()
        r.caretToStart()
        assertTrue(r.insert("お"))
        assertEquals(listOf("お", "明日は", "雨"), surfaces(r.units))
        assertEquals("お|明日は雨", shown(r))
        val s = caretSession()
        s.caretLeft() // あ|め, inside 雨
        assertTrue(s.deleteBackward())
        assertEquals(listOf("明日は", "め"), surfaces(s.units))
        assertEquals("明日は|め", shown(s))
    }

    @Test fun aChoiceAtTheCaretReplacesItsScopeAndKeepsTheBoundary() {
        val r = caretSession()
        r.caretLeft() // あ|め: the scope is あ
        val request = r.request()!!
        assertEquals("あ", request.text)
        assertTrue(r.apply(request, listOf(Candidate("あ", "亜", 0.0, listOf(Segment(0, 1, "あ", "亜", SegmentKind.WORD)))), preview = true))
        assertEquals("明日はあ|め", shown(r)) // a result alone changes nothing
        assertTrue(r.choose(r.candidates.indexOfFirst { it.surface == "亜" }))
        assertEquals("明日は亜|め", shown(r))
        assertEquals(listOf("明日は", "亜", "め"), surfaces(r.finish()))
        assertTrue(r.hasUnconverted) // め converts again
        // At a unit's end the scope is the whole unit, as for a selected segment.
        val s = caretSession()
        repeat(2) { s.caretLeft() } // 明日は|雨
        val whole = s.request()!!
        assertEquals("あしたは", whole.text)
        assertTrue(s.apply(whole, listOf(Candidate("あしたは", "明日わ", 0.0)), preview = true))
        assertTrue(s.choose(s.candidates.indexOfFirst { it.surface == "明日わ" }))
        assertEquals("明日わ|雨", shown(s))
        assertEquals(listOf("明日わ", "雨"), surfaces(s.units))
    }

    @Test fun finishingWithTheCaretInsideAWordKeepsTheWordNotTheShownKana() {
        val r = caretSession()
        r.caretLeft()
        assertEquals("明日はあ|め", shown(r))
        assertEquals(listOf("明日は", "雨"), surfaces(r.finish())) // moving alone never turns 雨 into あめ
        r.caretMode = false // a selected segment: the word again, the caret at its end
        assertEquals("明日は雨", r.shownSurface)
    }

    @Test fun englishInTheMiddleOfAKanaCompositionSplitsAtTheCaret() {
        val units = listOf(
            RepairUnit("あした", "あした", "明日", listOf(Segment(0, 3, "あした", "明日", SegmentKind.WORD)), emptyList()),
            RepairUnit("abc", "abc", "abc", listOf(Segment(0, 3, "abc", "abc", SegmentKind.PASSTHROUGH)), emptyList()),
            RepairUnit("あめ", "あめ", "雨", listOf(Segment(0, 2, "あめ", "雨", SegmentKind.WORD)), emptyList()),
        )
        val r = caretSession(units)
        repeat(3) { r.caretLeft() } // 明日ab|c雨
        assertEquals("明日ab|c雨", shown(r))
        assertEquals(listOf("明日", "ab", "c", "雨"), surfaces(r.shownUnits))
        assertEquals("ab", r.scopeTyped)
        assertTrue(r.insert("x"))
        assertEquals(listOf("あした", "abx", "c", "あめ"), r.units.map { it.typed })
        assertEquals(listOf("明日", "abx", "c", "雨"), surfaces(r.units))
        assertEquals("明日abx|c雨", shown(r))
        assertEquals(SegmentKind.PASSTHROUGH, r.units[2].segments.single().kind)
    }

    @Test fun anEmojiInTheReadingIsOneStepAndOneDelete() {
        val units = listOf(RepairUnit("あ👨‍👩‍👧", "あ👨‍👩‍👧", "あ👨‍👩‍👧",
            listOf(Segment(0, 1, "あ", "あ", SegmentKind.LITERAL), Segment(1, 9, "👨‍👩‍👧", "👨‍👩‍👧", SegmentKind.PASSTHROUGH)), emptyList()))
        val r = caretSession(units)
        assertTrue(r.caretLeft())
        assertEquals(1, r.shownCaret)
        assertTrue(r.caretRight())
        assertEquals(units[0].surface.length, r.shownCaret)
        assertTrue(r.deleteBackward())
        assertEquals("あ", r.typed)
    }
}
