package dev.astra.ime.core

import dev.astra.ime.core.SegmentKind.LITERAL
import dev.astra.ime.core.SegmentKind.PASSTHROUGH
import dev.astra.ime.core.SegmentKind.WORD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairTest {
    private val engine get() = CoreTest.engine

    private fun seg(start: Int, end: Int, reading: String, surface: String, kind: SegmentKind = WORD) =
        Segment(start, end, reading, surface, kind)

    /** きょうはあめ shown as 今日は雨, word by word. */
    private val typed = "きょうはあめ"
    private val best = Candidate(typed, "今日は雨", 0.0, listOf(seg(0, 3, "きょう", "今日"), seg(3, 4, "は", "は"), seg(4, 6, "あめ", "雨")))

    private fun ame(vararg surfaces: String) = surfaces.map { Candidate("あめ", it, 0.0, listOf(seg(0, 2, "あめ", it))) }

    // --- Added choices of the scope (0.1.35) -----------------------------------------------------------

    /** m-1 | グランプリ, as the OSS converter shows m-1 taken over by ぐらんぷり. */
    private fun grandPrix(focus: Int) = RepairSession(
        listOf(
            RepairUnit("m-1", "m-1", "m-1", listOf(seg(0, 3, "m-1", "m-1", PASSTHROUGH)), emptyList()),
            RepairUnit("ぐらんぷり", "ぐらんぷり", "グランプリ", listOf(seg(0, 5, "ぐらんぷり", "グランプリ")), emptyList()),
        ),
        focus,
    )

    private fun forms(typed: String, vararg surfaces: String) = surfaces.map { Candidate(typed, it, 0.0) }

    @Test fun supplementedChoicesFollowTheResultKeepItsIndicesAndAPickReplacesOnlyTheScope() {
        val r = grandPrix(0)
        assertFalse(r.supplement("m-1", forms("m-1", "M-1"))) // no result for the scope yet
        val request = r.request()!!
        assertTrue(r.apply(request, listOf(Candidate("m-1", "m-1", 0.0), Candidate("m-1", "mー1", 1.0)), preview = true))
        val revision = r.revision
        val before = r.candidates.map { it.surface }
        assertFalse(r.supplement("m-", forms("m-", "M-"))) // another text than the scope's
        assertTrue(r.supplement("m-1", forms("m-1", "m-1", "M-1", "Ｍ－１") + Candidate("x", "X", 0.0)))
        assertEquals(before + listOf("M-1", "Ｍ－１"), r.candidates.map { it.surface }) // appended, each once, read as typed only
        assertEquals(revision, r.revision) // taps on earlier chips stay valid
        assertFalse(r.supplement("m-1", forms("m-1", "M-1"))) // nothing new
        assertTrue(r.choose(r.candidates.indexOfFirst { it.surface == "M-1" }))
        assertEquals(listOf("M-1", "グランプリ"), r.units.map { it.surface })
        assertEquals(listOf("m-1", "ぐらんぷり"), r.units.map { it.typed })
        assertTrue(r.units[0].locked)
        assertFalse(r.units[1].locked)
        assertEquals("M-1グランプリ", r.finish().joinToString("") { it.surface })
    }

    @Test fun supplementedChoicesGoWithTheScopeAndFollowTheCaretSplit() {
        val r = grandPrix(1).also { it.caretMode = true }
        assertTrue(r.caretLeft()) // ぐらんぷ|り: another scope
        repeat(4) { r.caretLeft() } // m-1|グランプリ
        assertEquals("m-1", r.scopeTyped)
        r.apply(r.request()!!, listOf(Candidate("m-1", "m-1", 0.0)), preview = true)
        assertTrue(r.supplement("m-1", forms("m-1", "M-1")))
        assertTrue(r.caretLeft()) // m-|1: the scope is the part before the caret, its choices dropped
        assertEquals("m-", r.scopeTyped)
        assertTrue(r.candidates.isEmpty())
        assertFalse(r.supplement("m-1", forms("m-1", "M-1")))
        r.apply(r.request()!!, listOf(Candidate("m-", "m-", 0.0)), preview = true)
        assertTrue(r.supplement("m-", forms("m-", "M-")))
        assertTrue(r.choose(r.candidates.indexOfFirst { it.surface == "M-" }))
        assertEquals(listOf("M-", "1", "グランプリ"), r.units.map { it.surface }) // only the part before the caret
    }

    // --- ⌫ on a selected unit --------------------------------------------------------------------

    /** ここで | 区切られて | いた場合, as reported; 区切 and 場合 are words of several kana. */
    private fun reported(focus: Int): RepairSession {
        fun u(typed: String, surface: String, segments: List<Segment>) = RepairUnit(typed, typed, surface, segments, emptyList())
        return RepairSession(
            listOf(
                u("ここで", "ここで", listOf(seg(0, 2, "ここ", "ここ"), seg(2, 3, "で", "で"))),
                u("くぎられて", "区切られて", listOf(seg(0, 2, "くぎ", "区切"), seg(2, 5, "られて", "られて"))),
                u("いたばあい", "いた場合", listOf(seg(0, 2, "いた", "いた"), seg(2, 5, "ばあい", "場合"))),
            ),
            focus,
        )
    }

    @Test fun backspaceDeletesBeforeTheCaretOfTheSelectedUnitOnly() {
        val r = reported(0)
        assertTrue(r.deleteBackward())
        assertEquals(listOf("ここ", "くぎられて", "いたばあい"), r.units.map { it.typed })
        assertEquals("ここ区切られていた場合", r.surface) // the edited unit shows its kana; the others are untouched
        assertEquals(0, r.focus)
        assertTrue(r.deleteBackward())
        assertEquals(listOf("こ", "くぎられて", "いたばあい"), r.units.map { it.typed })
        assertTrue(r.deleteBackward()) // emptied: removed, the next unit selected with the caret at its start
        assertEquals(listOf("くぎられて", "いたばあい"), r.units.map { it.typed })
        assertEquals("区切られていた場合", r.surface)
        assertEquals(0 to 0, r.focus to r.cursor)
        assertFalse(r.deleteBackward()) // nothing before the composition is ever touched
        assertTrue(r.undo())
        assertEquals(listOf("こ", "くぎられて", "いたばあい"), r.units.map { it.typed })
    }

    @Test fun backspaceOnALaterUnitSelectsThePreviousOneWhenItEmptiesOrTheCaretIsAtItsStart() {
        val middle = reported(1)
        assertTrue(middle.deleteBackward())
        assertEquals("ここで区切られていた場合".replace("区切られて", "くぎられ"), middle.surface)
        repeat(4) { assertTrue(middle.deleteBackward()) }
        assertEquals(listOf("ここで", "いたばあい"), middle.units.map { it.typed }) // くぎられて gone, one press each
        assertEquals(0 to 3, middle.focus to middle.cursor)
        assertEquals("ここでいた場合", middle.surface)

        val last = reported(2)
        assertTrue(last.place(2, 0))
        assertTrue(last.deleteBackward()) // caret at the start: only the selection moves back
        assertEquals("ここで区切られていた場合", last.surface)
        assertEquals(1 to 5, last.focus to last.cursor)

        val only = RepairSession(listOf(RepairUnit("で", "で", "で", emptyList(), emptyList())))
        assertTrue(only.deleteBackward())
        assertEquals("", only.typed) // the composition is gone; the caller ends it
        assertTrue(only.finish().isEmpty())
    }

    @Test fun aPlacedCaretIsHonouredAndKeepsCandidatesWithinTheUnit() {
        val r = reported(2)
        val request = r.request()!!
        assertTrue(r.place(2, 2)) // いた|ばあい
        assertTrue(r.apply(request, listOf(Candidate("いたばあい", "いた場合", 0.0)), preview = false)) // still valid
        assertTrue(r.deleteBackward())
        assertEquals("いばあい", r.units[2].typed) // た, before the caret
        assertEquals("ここで区切られていばあい", r.surface)
        assertEquals(1, r.cursor)
        val pair = RepairSession(listOf(RepairUnit("あ🍣い", "あ🍣い", "あ🍣い", emptyList(), emptyList())))
        assertTrue(pair.place(0, 2)) // the middle of the pair goes before it
        assertEquals(1, pair.cursor)
        assertTrue(pair.place(0, 3))
        assertTrue(pair.deleteBackward())
        assertEquals("あい", pair.typed)
        assertFalse(r.place(7, 0))
    }

    @Test fun aSurfaceCaretMapsToTheTypedKanaOnlyWhereTheyAgree() {
        val units = reported(0).units
        assertEquals(1, Alignment.typedOffset(units[0], 1)) // こ|こで
        assertEquals(2, Alignment.typedOffset(units[1], 1)) // 区|切: after the word くぎ
        assertEquals(3, Alignment.typedOffset(units[1], 3)) // 区切ら|れて
        assertEquals(2, Alignment.typedOffset(units[2], 2)) // いた|場合: before the word
        assertEquals(5, Alignment.typedOffset(units[2], 3)) // いた場|合: after it
        val unaligned = RepairUnit("しかい", "しかい", "司会", emptyList(), emptyList())
        assertEquals(3, Alignment.typedOffset(unaligned, 1))
    }

    // --- Alignment from the engines ------------------------------------------------------------

    @Test fun coreSegmentsTileTheTypedText() {
        val top = engine.convert("あしたはあめ")[0]
        assertEquals("明日は雨", top.surface)
        assertEquals(listOf(seg(0, 3, "あした", "明日"), seg(3, 4, "は", "は"), seg(4, 6, "あめ", "雨")), top.segments)
        assertNotNull(Alignment.checked("あしたはあめ", top.surface, top.segments))
        // The identity returned for empty/overlong input claims no alignment: it is one span.
        assertNull(engine.convert("").single().segments)
    }

    @Test fun coreSegmentsKeepPassthroughAndSurrogatePairs() {
        val top = engine.convert("ABCがっこう😀")[0]
        assertEquals("ABC学校😀", top.surface)
        val s = Alignment.checked("ABCがっこう😀", top.surface, top.segments)!!
        assertEquals(listOf("A", "B", "C"), s.take(3).map { it.surface })
        assertTrue(s.take(3).all { it.kind == PASSTHROUGH })
        assertEquals(seg(3, 7, "がっこう", "学校"), s[3])
        assertEquals(seg(7, 9, "😀", "😀", PASSTHROUGH), s[4]) // UTF-16 offsets around the pair
    }

    @Test fun coreCorrectedReadingsKeepTypedSpans() {
        // Development fixture with typos: typed spans tile the typed text, readings spell the corrected reading.
        val raw = "きょうきょくいうと、かのくらいのごたいふならすいさつしてへんかんされてはしい"
        val top = engine.convert(raw)[0]
        val s = Alignment.checked(raw, top.surface, top.segments)!!
        assertEquals(top.reading, s.joinToString("") { it.reading })
        assertEquals(seg(raw.indexOf('、'), raw.indexOf('、') + 1, "、", "、", PASSTHROUGH), s.single { it.surface == "、" })
        for (x in s) assertEquals(x.surface == "、", x.kind == PASSTHROUGH)
    }

    @Test fun checkedRejectsAnythingThatDoesNotTileTheTypedText() {
        assertNotNull(Alignment.checked(typed, best.surface, best.segments))
        assertNull(Alignment.checked(typed, best.surface, null))
        assertNull(Alignment.checked(typed, best.surface, emptyList()))
        assertNull(Alignment.checked(typed, "今日は飴", best.segments)) // does not spell the candidate
        assertNull(Alignment.checked(typed, best.surface, listOf(seg(0, 3, "きょう", "今日"), seg(4, 6, "あめ", "雨")))) // gap
        assertNull(Alignment.checked(typed, best.surface, listOf(seg(0, 4, "きょう", "今日"), seg(3, 6, "はあめ", "は雨")))) // overlap
        assertNull(Alignment.checked(typed, "今日は", listOf(seg(0, 3, "きょう", "今日"), seg(3, 4, "は", "は")))) // short
        assertNull(Alignment.checked("a😀", "a😀", listOf(seg(0, 2, "a\uD83D", "a\uD83D", PASSTHROUGH), seg(2, 3, "\uDE00", "\uDE00", PASSTHROUGH))))
        assertNull(Alignment.checked("a。", "a、", listOf(seg(0, 1, "a", "a", PASSTHROUGH), seg(1, 2, "。", "、", PASSTHROUGH))))
    }

    @Test fun unitsUseTypedSpansEvenWhenCorrectionChangesTheLength() {
        // Typed きょはあめ (う dropped), read as きょうはあめ. Summing reading lengths would split at きょはあ|め.
        val typo = "きょはあめ"
        val corrected = Candidate("きょうはあめ", "今日は雨", 0.0, listOf(seg(0, 2, "きょう", "今日"), seg(2, 3, "は", "は"), seg(3, 5, "あめ", "雨")))
        val units = Alignment.units(typo, corrected)
        assertEquals(listOf("きょは", "あめ"), units.map { it.typed })
        assertEquals(listOf("きょうは", "あめ"), units.map { it.reading })
        assertEquals(listOf("今日は", "雨"), units.map { it.surface })
        // Offsets built from reading lengths overrun the typed text and are refused: one honest span instead.
        val bogus = Candidate("きょうはあめ", "今日は雨", 0.0, listOf(seg(0, 3, "きょう", "今日"), seg(3, 4, "は", "は"), seg(4, 6, "あめ", "雨")))
        val single = Alignment.units(typo, bogus).single()
        assertEquals(typo, single.typed)
        assertEquals("今日は雨", single.surface)
        assertTrue(single.segments.isEmpty())
        // An extra typed kana (ああめ read as あめ) stays inside its word's unit.
        val extra = Candidate("きょうはあめ", "今日は雨", 0.0, listOf(seg(0, 3, "きょう", "今日"), seg(3, 4, "は", "は"), seg(4, 7, "あめ", "雨")))
        assertEquals(listOf("きょうは", "ああめ"), Alignment.units("きょうはああめ", extra).map { it.typed })
    }

    @Test fun groupingAttachesHiraganaWordsAndIsolatesPassthrough() {
        val units = Alignment.units(typed, best)
        assertEquals(listOf("きょうは", "あめ"), units.map { it.typed })
        assertEquals(listOf(seg(0, 2, "あめ", "雨")), units[1].segments) // relative to the unit
        val mixed = Candidate("", "今日は、雨です", 0.0, listOf(
            seg(0, 3, "きょう", "今日"), seg(3, 4, "は", "は"), seg(4, 5, "、", "、", PASSTHROUGH),
            seg(5, 7, "あめ", "雨"), seg(7, 9, "です", "です"),
        ))
        assertEquals(listOf("今日は", "、", "雨です"), Alignment.units("きょうは、あめです", mixed).map { it.surface })
        // Unknown kana (LITERAL) is a unit of its own so it can be fixed alone.
        val unknown = Candidate("", "ぬ雨", 0.0, listOf(seg(0, 1, "ぬ", "ぬ", LITERAL), seg(1, 3, "あめ", "雨")))
        assertEquals(listOf("ぬ", "雨"), Alignment.units("ぬあめ", unknown).map { it.surface })
        // Learned phrase / prediction without alignment: one unit. Live conversion off: boundaries, kana shown.
        assertEquals(listOf("今日は雨"), Alignment.units(typed, Candidate(typed, "今日は雨", 0.0)).map { it.surface })
        assertEquals(listOf("きょうは", "あめ"), Alignment.units(typed, best, kana = true).map { it.surface })
        assertEquals(listOf(typed), Alignment.units(typed, null).map { it.surface })
    }

    // --- RepairSession ---------------------------------------------------------------------------

    private fun session() = RepairSession(Alignment.units(typed, best, best.segments!!))

    @Test fun choosingAnAlternativeChangesOnlyTheFocusedUnit() {
        val r = session()
        assertEquals(1, r.focus) // the last unit
        val before = r.units[0]
        val request = r.request()!!
        assertEquals("あめ", request.text)
        assertEquals("今日は", request.context)
        assertTrue(r.apply(request, ame("雨", "飴", "あめ"), preview = true))
        assertEquals("今日は雨", r.surface) // not edited: a result never changes what is shown
        assertEquals(0, r.candidateIndex)
        assertTrue(r.choose(1))
        assertEquals("今日は飴", r.surface)
        assertTrue(r.units[1].locked)
        assertSame(before, r.units[0])
        assertEquals(typed, r.typed)
        assertEquals(3 until 4, r.focusRange)
        assertTrue(r.next()) // 変換: the next candidate that differs from what is shown
        assertEquals("今日はあめ", r.surface)
    }

    @Test fun staleAndSupersededResultsAreRejected() {
        val r = session()
        val first = r.request()!!
        val second = r.request()!!
        assertFalse(r.apply(first, ame("雨", "飴"), preview = true)) // superseded
        assertTrue(r.apply(second, ame("雨", "飴"), preview = true))
        assertFalse(r.apply(second, ame("飴"), preview = true)) // applied once
        val pending = r.request()!!
        assertTrue(r.choose(1))
        assertFalse(r.apply(pending, ame("雨"), preview = true)) // a choice made it stale
        val other = r.request()!!
        r.focus(0)
        assertFalse(r.apply(other, ame("雨"), preview = true)) // another unit is focused now
        assertEquals("今日は飴", r.surface)
        assertFalse(r.apply(r.request()!!, emptyList(), preview = true))
    }

    @Test fun aLockedChoiceIsNeverReplacedByAPreview() {
        val r = session()
        r.deleteBefore() // edit あめ -> あ
        r.insert("め") // and back: edited, shown as kana until a result
        assertEquals("今日はあめ", r.surface)
        assertTrue(r.apply(r.request()!!, ame("雨", "飴", "あめ"), preview = true))
        assertEquals("今日は雨", r.surface) // preview of the first candidate, not locked
        assertFalse(r.units[1].locked)
        assertTrue(r.choose(1))
        // A later result for the same unit (e.g. after moving the cursor) does not undo the explicit choice.
        assertTrue(r.apply(r.request()!!, ame("雨", "飴"), preview = true))
        assertEquals("今日は飴", r.surface)
        assertEquals(1, r.candidateIndex)
        // Without preview (live conversion off) an edited unit keeps its kana.
        r.insert("ゆ")
        assertTrue(r.apply(r.request()!!, ame("雨"), preview = false))
        assertEquals("今日はあめゆ", r.surface)
    }

    @Test fun readingEditAndCursorMoveAcrossUnits() {
        val r = session()
        assertEquals(2, r.cursor)
        assertTrue(r.moveLeft())
        assertEquals(1, r.cursor)
        assertTrue(r.moveLeft())
        assertFalse(r.deleteBefore()) // start of the unit: boundaries do not move
        assertTrue(r.moveLeft()) // to the end of the previous unit
        assertEquals(0, r.focus)
        assertEquals(4, r.cursor)
        assertTrue(r.moveLeft())
        assertTrue(r.deleteBefore()) // きょうは -> きょは
        assertEquals("きょは", r.units[0].typed)
        assertEquals("きょは雨", r.surface)
        assertTrue(r.insert("う"))
        assertEquals("きょうは", r.units[0].typed)
        assertEquals(3, r.cursor)
        assertTrue(r.toggleBefore(FlickLayout::toggle)) // う -> ぅ
        assertEquals("きょぅは", r.units[0].typed)
        assertEquals("雨", r.units[1].surface) // the other unit is untouched
        assertTrue(r.moveRight())
        assertTrue(r.moveRight()) // end of unit 0 -> start of unit 1
        assertEquals(1, r.focus)
        assertEquals(0, r.cursor)
        while (r.moveRight()) Unit
        assertEquals(2, r.cursor)
        assertFalse(r.moveRight())
        // Deleting a whole unit's reading drops it on finish.
        r.deleteBefore()
        r.deleteBefore()
        assertNull(r.request())
        assertEquals(listOf("きょぅは"), r.finish().map { it.typed })
    }

    @Test fun undoRevertsChoicesAndEditsStepByStep() {
        val r = session()
        assertFalse(r.canUndo)
        r.apply(r.request()!!, ame("雨", "飴", "あめ"), preview = true)
        r.choose(1) // 飴
        r.focus(0) // focus changes are not undo steps
        r.deleteBefore()
        r.insert("x")
        assertEquals("きょうxあめ", r.typed)
        assertTrue(r.undo())
        assertEquals("きょうあめ", r.typed)
        assertTrue(r.undo())
        assertEquals("今日は飴", r.surface)
        assertTrue(r.undo())
        assertEquals("今日は雨", r.surface)
        assertFalse(r.undo())
        assertEquals(r.initialUnits(), r.units)
        // An undo makes an outstanding request stale and clears the candidates.
        r.apply(r.request()!!, ame("雨", "飴"), preview = true)
        r.choose(1)
        val pending = r.request()!!
        r.undo()
        assertTrue(r.candidates.isEmpty())
        assertFalse(r.apply(pending, ame("飴"), preview = true))
    }

    @Test fun aResultAfterUndoListsCandidatesButKeepsTheRestoredState() {
        val r = session()
        r.deleteBefore()
        r.deleteBefore()
        r.insert("ゆき") // edited, still kana: its result was never applied
        r.insert("の")
        assertTrue(r.undo())
        assertEquals("今日はゆき", r.surface)
        assertTrue(r.apply(r.request()!!, ame("雪", "行き"), preview = true))
        assertEquals("今日はゆき", r.surface) // the undone state is shown as it was, not replaced by 雪
        assertEquals(-1, r.candidateIndex)
        // The unit's raw kana and its katakana are always added (LiteralChoices).
        assertEquals(listOf("雪", "行き", "ゆき", "ユキ"), r.candidates.map { it.surface })
        assertTrue(r.next()) // an explicit 変換 still picks the first candidate
        assertEquals("今日は雪", r.surface)
        // A focus change back to an edited unit does not preview either; a new edit does.
        r.focus(0)
        r.focus(1)
        r.undo() // back to ゆき as kana
        assertTrue(r.apply(r.request()!!, ame("雪"), preview = true))
        assertEquals("今日はゆき", r.surface)
        r.insert("の")
        assertTrue(r.apply(r.request()!!, ame("雪の"), preview = true))
        assertEquals("今日は雪の", r.surface)
    }

    @Test fun anUndoneReadingSelectsNothingEvenWhenTheResultAlreadyListsItsRawKana() {
        // Results from the app's worker already end with the raw kana and its katakana (LiteralChoices).
        fun yuki() = listOf("雪", "行き", "ゆき", "ユキ").map { Candidate("ゆき", it, 0.0) }
        val r = session()
        r.deleteBefore()
        r.deleteBefore()
        r.insert("ゆき")
        r.insert("の")
        assertTrue(r.undo())
        assertTrue(r.apply(r.request()!!, yuki(), preview = true))
        assertEquals("今日はゆき", r.surface) // not replaced
        assertEquals(listOf("雪", "行き", "ゆき", "ユキ"), r.candidates.map { it.surface })
        assertEquals(-1, r.candidateIndex) // the restored kana awaits conversion; it is not a chosen candidate
        assertTrue(r.next())
        assertEquals("今日は雪", r.surface) // the first real alternative, not the one after the raw kana
        // Chosen explicitly, the raw kana is a selection like any other: 変換 continues after it.
        assertTrue(r.choose(2))
        assertTrue(r.apply(r.request()!!, yuki(), preview = true))
        assertEquals("今日はゆき", r.surface)
        assertEquals(2, r.candidateIndex)
        assertTrue(r.next())
        assertEquals("今日はユキ", r.surface)
        // A unit that was never edited and shows kana (unknown word) keeps that kana selected.
        val unknown = RepairSession(listOf(RepairUnit("ぬめ", "ぬめ", "ぬめ", emptyList(), emptyList())))
        assertTrue(unknown.apply(unknown.request()!!, listOf(Candidate("ぬめ", "ぬ目", 0.0)), preview = true))
        assertEquals(listOf("ぬめ", "ぬ目", "ヌメ"), unknown.candidates.map { it.surface })
        assertEquals(0, unknown.candidateIndex)
    }

    @Test fun unitAtMapsSurfaceOffsetsNotReadingOffsets() {
        val units = Alignment.units(typed, best) // 今日は (typed きょうは) | 雨 (typed あめ)
        assertEquals(listOf(0, 0, 0, 1), (0..3).map { Alignment.unitAt(units, it) })
        // Typed offset 3 would still be in きょう|は; the surface offset 3 is 雨. A boundary selects the next unit.
        assertNull(Alignment.unitAt(units, 4)) // the end
        assertNull(Alignment.unitAt(units, -1))
        // Surrogate pairs and punctuation: offsets are UTF-16 and never land inside a pair.
        fun u(surface: String) = RepairUnit(surface, surface, surface, emptyList(), emptyList())
        val mixed = listOf(u("雨"), u("😀"), u("、"), u(""), u("あ"))
        assertEquals(1, Alignment.unitAt(mixed, 1))
        assertNull(Alignment.unitAt(mixed, 2)) // between the halves of 😀
        assertEquals(2, Alignment.unitAt(mixed, 3))
        assertEquals(4, Alignment.unitAt(mixed, 4)) // an empty unit is never selected
        assertNull(Alignment.unitAt(mixed, 5))
        assertNull(Alignment.unitAt(emptyList(), 0))
    }

    @Test fun aFocusedUnitAlwaysOffersItsRawKanaAndKatakanaWithinTheCap() {
        // Unknown name typed into the last unit; the result is full and lacks both literal choices.
        val name = "ぬめぴこ"
        val r = RepairSession(
            listOf(RepairUnit("きょうは", "きょうは", "今日は", emptyList(), emptyList()), RepairUnit(name, name, "ぬ目ぴ子", emptyList(), emptyList())),
        )
        val full = (0 until MAX_LIMIT).map { Candidate(name, "候補$it", it.toDouble()) }
        assertTrue(r.apply(r.request()!!, full, preview = true))
        val surfaces = r.candidates.map { it.surface }
        assertEquals(MAX_LIMIT, surfaces.size)
        assertEquals("ぬ目ぴ子", surfaces[0]) // the unit's current surface stays first and selected
        assertEquals(0, r.candidateIndex)
        assertEquals("ヌメピコ", surfaces[LiteralChoices.KATAKANA_SLOT])
        assertTrue(name in surfaces)
        // The katakana is aligned as one literal segment, so the unit keeps a valid alignment after the choice.
        assertTrue(r.choose(LiteralChoices.KATAKANA_SLOT))
        assertEquals("今日はヌメピコ", r.surface)
        assertEquals(listOf(seg(0, 4, name, "ヌメピコ", LITERAL)), r.units[1].segments)
        // A unit without hiragana (ASCII) has no separate katakana choice.
        val ascii = RepairSession(listOf(RepairUnit("abc", "abc", "abc", emptyList(), emptyList())))
        assertTrue(ascii.apply(ascii.request()!!, listOf(Candidate("abc", "abc", 0.0)), preview = true))
        assertEquals(listOf("abc"), ascii.candidates.map { it.surface })
    }

    @Test fun repairIsThreadConfinedAndRedacted() {
        val r = session()
        var error: Throwable? = null
        val t = Thread { error = runCatching { r.insert("あ") }.exceptionOrNull() }
        t.start()
        t.join()
        assertTrue(error is IllegalStateException)
        for (text in listOf(r.toString(), r.units.toString(), r.request().toString(), best.segments.toString())) {
            assertFalse(text, text.contains("あめ") || text.contains("雨"))
        }
    }

    // --- InputSession with a repaired head ------------------------------------------------------

    private fun repaired(): InputSession {
        val s = InputSession().also { it.startInput(false) }
        val r = session()
        r.apply(r.request()!!, ame("雨", "飴"), preview = true)
        r.choose(1)
        assertTrue(s.replaceComposition(r.finish()))
        return s
    }

    @Test fun headStaysFixedWhileTheTailConverts() {
        val s = repaired()
        assertTrue(s.hasComposition)
        assertEquals("", s.composingText)
        assertEquals("今日は飴", s.headSurface)
        assertEquals(typed, s.typedText)
        assertNull(s.request()) // nothing to convert yet
        s.type("の")
        val token = s.request()!!
        assertEquals("の", token.text) // only the tail is converted
        assertTrue(s.apply(token, engine.convert(token.text)))
        assertEquals("今日は飴の", s.choose(token, 0))
        assertFalse(s.hasComposition)
        assertEquals("", s.typedText)
    }

    @Test fun commitRawBackspaceAndToggleWithAHead() {
        val s = repaired()
        s.type("の")
        assertEquals("今日は飴の", s.commitRaw())
        val b = repaired()
        assertTrue(b.backspace()) // empty tail: the last unit (飴, typed あめ) is released and edited
        assertEquals("あ", b.composingText)
        assertEquals("今日は", b.headSurface)
        assertTrue(b.backspace())
        assertTrue(b.backspace()) // releases 今日は (きょうは) -> きょう
        assertEquals("きょう", b.composingText)
        assertTrue(b.headUnits.isEmpty())
    }

    @Test fun toggleOnAnEmptyTailReleasesTheLastUnitOnlyWhenItApplies() {
        val s = repaired()
        assertFalse(s.toggleLast()) // め has no small/voiced form: the head is unchanged
        assertEquals("今日は飴", s.headSurface)
        s.type("か")
        assertTrue(s.toggleLast())
        assertEquals("が", s.composingText)
        assertEquals("今日は飴", s.headSurface)
    }

    @Test fun replacingTheCompositionInvalidatesTokensAndSelectionClearsTheHead() {
        val s = InputSession().also { it.startInput(false) }
        s.type("あめ")
        val token = s.request()!!
        s.replaceComposition(Alignment.units("あめ", Candidate("あめ", "雨", 0.0, ame("雨")[0].segments)))
        assertFalse(s.apply(token, engine.convert("あめ")))
        assertEquals("雨", s.headSurface)
        s.onSelectionMoved()
        assertFalse(s.hasComposition)
        val sensitive = InputSession().also { it.startInput(true) }
        assertFalse(sensitive.replaceComposition(Alignment.units("あめ", null)))
    }
}
