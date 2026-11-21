package dev.astra.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.23: ◀ ▶ on a selected segment move the boundary after it ([RepairSession.resize]). */
class SegmentResizeTest {
    private fun unit(typed: String, surface: String) =
        RepairUnit(typed, typed, surface, listOf(Segment(0, typed.length, typed, surface, SegmentKind.WORD)), emptyList())

    private fun word(typed: String, surface: String) = Candidate(typed, surface, 0.0, listOf(Segment(0, typed.length, typed, surface, SegmentKind.WORD)))

    private fun session(focus: Int) = RepairSession(listOf(unit("した", "した"), unit("にゅうりょく", "入力"), unit("じ", "時")), focus)

    @Test fun extendTakesTheNextSegmentsFirstKanaAndDropsItWhenEmpty() {
        val r = session(1)
        assertTrue(r.resize(extend = true))
        assertEquals(listOf("した", "にゅうりょくじ"), r.units.map { it.typed })
        assertEquals(listOf("した", "にゅうりょくじ"), r.units.map { it.surface }) // kana until its result
        assertEquals(1, r.focus)
        assertFalse(r.resize(extend = true)) // nothing after it any more
        assertEquals("したにゅうりょくじ", r.typed)
    }

    @Test fun shrinkGivesTheLastKanaToTheNextSegmentOrANewOne() {
        val r = session(1)
        assertTrue(r.resize(extend = false))
        assertEquals(listOf("した", "にゅうりょ", "くじ"), r.units.map { it.typed })
        val last = session(2)
        assertFalse(last.resize(extend = false)) // じ has only one kana
        val two = RepairSession(listOf(unit("あめ", "雨")), 0)
        assertTrue(two.resize(extend = false))
        assertEquals(listOf("あ", "め"), two.units.map { it.typed })
        assertEquals("あめ", two.typed)
    }

    @Test fun bothChangedSegmentsConvertInOneRequestAndOnlyAMatchingResultLands() {
        val r = session(1)
        r.resize(extend = true)
        val request = r.request()!!
        assertEquals("にゅうりょくじ", request.text)
        assertEquals(-1, request.neighbor) // the old next segment disappeared: nothing else changed
        assertTrue(r.apply(request, listOf(word("にゅうりょくじ", "入力時")), preview = true))
        assertEquals(listOf("した", "入力時"), r.units.map { it.surface })

        val s = session(0)
        s.resize(extend = true) // したに | ゅうりょく: both changed
        val both = s.request()!!
        assertEquals("したに", both.text)
        assertEquals(1, both.neighbor)
        assertEquals("ゅうりょく", both.neighborText)
        assertTrue(s.apply(both, listOf(word("したに", "下に")), preview = true, neighborResult = listOf(word("ゅうりょく", "ゅう力"))))
        assertEquals(listOf("下に", "ゅう力", "時"), s.units.map { it.surface })
        assertEquals(-1, s.request()!!.neighbor) // converted: not asked for again

        // A result for an earlier boundary is dropped, and the neighbor still shows kana until the next request.
        val t = session(1)
        t.resize(extend = false)
        val old = t.request()!!
        t.resize(extend = false)
        assertFalse(t.apply(old, listOf(word("にゅうりょ", "x")), preview = true, neighborResult = listOf(word("くじ", "y"))))
        assertEquals(listOf("した", "にゅうり", "ょくじ"), t.units.map { it.surface })
        val fresh = t.request()!!
        assertEquals(2, fresh.neighbor)
        assertEquals("ょくじ", fresh.neighborText)
    }

    @Test fun withoutLiveConversionTheNeighborKeepsItsKanaAndAResizeIsUndoable() {
        val r = session(0)
        r.resize(extend = true)
        val request = r.request()!!
        assertTrue(r.apply(request, listOf(word("したに", "下に")), preview = false, neighborResult = listOf(word("ゅうりょく", "ゅう力"))))
        assertEquals("ゅうりょく", r.units[1].surface)
        assertTrue(r.undo())
        assertEquals(listOf("した", "にゅうりょく", "じ"), r.units.map { it.typed })
        assertEquals(listOf("した", "入力", "時"), r.units.map { it.surface })
    }
}
