package dev.astra.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Surfaces are invented; classification must never look at them. */
class CandidateGroupsTest {
    private fun word(start: Int, end: Int, reading: String, surface: String) = Segment(start, end, reading, surface, SegmentKind.WORD)
    private fun aligned(reading: String, surface: String, vararg segments: Segment) = Candidate(reading, surface, 0.0, segments.toList())
    private fun plain(reading: String, surface: String) = Candidate(reading, surface, 0.0)

    @Test fun alignmentMakesAnEqualReadingExact() {
        assertEquals(CandidateClass.EXACT, CandidateGroups.classify("さら", aligned("さら", "甲", word(0, 2, "さら", "甲"))))
    }

    @Test fun aWrongReadingOnAnAlignedSpanIsACorrection() {
        assertEquals(CandidateClass.CORRECTION, CandidateGroups.classify("さら", aligned("され", "甲", word(0, 2, "され", "甲"))))
    }

    @Test fun theSameSurfaceIsClassifiedByItsReadingNotItsLooks() {
        val exact = aligned("さら", "さら", word(0, 2, "さら", "さら"))
        val corrected = aligned("され", "さら", word(0, 2, "され", "さら"))
        assertEquals(CandidateClass.EXACT, CandidateGroups.classify("さら", exact))
        assertEquals(CandidateClass.CORRECTION, CandidateGroups.classify("さら", corrected))
    }

    @Test fun aDroppedKanaAtTheEndIsACorrectionEvenThoughItsReadingExtendsTheText() {
        val inserted = aligned("さらい", "乙", word(0, 2, "さらい", "乙"))
        assertEquals(CandidateClass.CORRECTION, CandidateGroups.classify("さら", inserted))
    }

    @Test fun onlyAnUnalignedLongerReadingIsAPrediction() {
        assertEquals(CandidateClass.PREDICTION, CandidateGroups.classify("さら", plain("さらに", "丙")))
        assertEquals(CandidateClass.CORRECTION, CandidateGroups.classify("さら", plain("そらに", "丙")))
        assertEquals(CandidateClass.EXACT, CandidateGroups.classify("さら", plain("さら", "丁")))
    }

    @Test fun oneWrongWordMakesAWholeSentenceACorrection() {
        val c = aligned("あめとかさ", "甲と乙", word(0, 2, "あめ", "甲"), word(2, 3, "と", "と"), word(3, 5, "かさ", "乙"))
        assertEquals(CandidateClass.EXACT, CandidateGroups.classify("あめとかさ", c))
        val wrong = aligned("あめとかき", "甲と丙", word(0, 2, "あめ", "甲"), word(2, 3, "と", "と"), word(3, 5, "かき", "丙"))
        assertEquals(CandidateClass.CORRECTION, CandidateGroups.classify("あめとかさ", wrong))
    }

    @Test fun typedKatakanaAgainstAHiraganaReadingIsStillExact() {
        assertEquals(CandidateClass.EXACT, CandidateGroups.classify("アメ", aligned("あめ", "甲", word(0, 2, "あめ", "甲"))))
    }

    @Test fun topHoldsTheFirstChoiceThenOnlyCorrectionsAndPredictions() {
        val ranked = listOf(
            aligned("さら", "甲", word(0, 2, "さら", "甲")),
            aligned("さら", "乙", word(0, 2, "さら", "乙")),
            aligned("され", "丙", word(0, 2, "され", "丙")),
            plain("さら", "さら"),
            plain("さら", "サラ"),
            plain("さらに", "丁"),
        )
        assertEquals(listOf("甲", "丙", "丁"), CandidateGroups.top("さら", ranked, emptyList()).map { it.surface })
    }

    @Test fun aCorrectionTheRankerPutFirstStaysFirstInTop() {
        val ranked = listOf(aligned("され", "丙", word(0, 2, "され", "丙")), aligned("さら", "甲", word(0, 2, "さら", "甲")))
        assertEquals("丙", CandidateGroups.top("さら", ranked, emptyList()).first().surface)
    }

    @Test fun topIsBoundedAndNeverRepeatsASurface() {
        val extras = (0 until 100).map { plain("さらに$it", "語$it") }
        val top = CandidateGroups.top("さら", extras + extras, extras)
        assertEquals(CandidateGroups.MAX_TOP, top.size)
        assertEquals(top.size, top.map { it.surface }.toSet().size)
    }

    @Test fun topNeverListsLiteralForms() {
        val ranked = listOf(plain("さら", "さら"), plain("さら", "サラ"))
        assertTrue(CandidateGroups.top("さら", ranked, emptyList()).isEmpty())
    }

    @Test fun exactStartsWithRawAndKatakanaWithoutTakingAnyDictionarySlot() {
        val dictionary = (0 until 150).map { aligned("さら", "語$it", word(0, 2, "さら", "語$it")) }
        val exact = CandidateGroups.exact("さら", dictionary, emptyList())
        assertEquals(listOf("さら", "サラ"), exact.take(2).map { it.surface })
        assertEquals(dictionary.map { it.surface }, exact.drop(2).map { it.surface })
    }

    @Test fun rankedOrderCannotMoveOrRemoveADictionaryEntry() {
        val dictionary = (0 until 40).map { aligned("さら", "語$it", word(0, 2, "さら", "語$it")) }
        val reversed = dictionary.reversed().take(8)
        val a = CandidateGroups.exact("さら", dictionary, emptyList())
        val b = CandidateGroups.exact("さら", dictionary, reversed)
        assertEquals(a.map { it.surface }, b.map { it.surface })
    }

    @Test fun exactNeverHoldsCorrectionsOrPredictions() {
        val ranked = listOf(aligned("され", "丙", word(0, 2, "され", "丙")), plain("さらに", "丁"), plain("そら", "戊"))
        assertEquals(listOf("さら", "サラ"), CandidateGroups.exact("さら", emptyList(), ranked).map { it.surface })
    }

    @Test fun aLearnedPhraseTheDictionaryLacksComesRightAfterTheLiterals() {
        val dictionary = listOf(aligned("さら", "甲", word(0, 2, "さら", "甲")))
        val exact = CandidateGroups.exact("さら", dictionary, listOf(plain("さら", "記憶語")))
        assertEquals(listOf("さら", "サラ", "記憶語", "甲"), exact.map { it.surface })
    }

    @Test fun duplicatesAcrossSourcesCollapseToOneEntry() {
        val c = aligned("さら", "甲", word(0, 2, "さら", "甲"))
        assertEquals(3, CandidateGroups.exact("さら", listOf(c, c), listOf(c)).size)
    }

    @Test fun theLexiconFallbackListsEveryEntryOfTheReading() {
        val engine = ConversionEngine((0 until 30).map { LexEntry("さら", "語$it", it.toDouble(), "noun") } + LexEntry("さる", "猿", 1.0, "noun"))
        val entries = engine.exactEntries("さら")
        assertEquals(30, entries.size)
        assertEquals("語0", entries[0].surface)
        assertTrue(entries.all { CandidateGroups.classify("さら", it) == CandidateClass.EXACT })
        assertTrue(engine.exactEntries("さらさら").isEmpty())
        assertEquals(30, engine.exactEntries("サラ").size)
    }

    private fun applied(session: InputSession, text: String): SessionToken {
        session.type(text)
        val token = session.request()!!
        assertTrue(session.apply(token, listOf(Candidate(text, text, 0.0))))
        return token
    }

    @Test fun aCandidateOutsideTheCappedListCommitsAfterTheHead() {
        val session = InputSession().apply { startInput(false) }
        session.replaceComposition(listOf(RepairUnit("あめ", "あめ", "甲", emptyList(), emptyList())))
        val token = applied(session, "さら")
        assertEquals("甲語140", session.choose(token, Candidate("さら", "語140", 0.0)))
        assertFalse(session.hasComposition)
    }

    @Test fun aStaleTokenCommitsNothing() {
        val session = InputSession().apply { startInput(false) }
        val token = applied(session, "さら")
        session.type("に")
        assertNull(session.choose(token, Candidate("さら", "語", 0.0)))
        assertEquals("さらに", session.composingText)
    }

    @Test fun aChoiceFromTheLastEditorCommitsNothing() {
        val session = InputSession().apply { startInput(false) }
        val token = applied(session, "さら")
        session.startInput(false)
        assertNull(session.choose(token, Candidate("さら", "語", 0.0)))
    }

    @Test fun aSensitiveEditorTakesOnlyTheRawText() {
        val session = InputSession().apply { startInput(true) }
        val token = applied(session, "さら")
        assertNull(session.choose(token, Candidate("さら", "語", 0.0)))
        assertEquals("さら", session.choose(token, Candidate("さら", "さら", 0.0)))
    }

    private fun repair(): RepairSession {
        val unit = RepairUnit("さら", "さら", "甲", listOf(word(0, 2, "さら", "甲")), emptyList())
        val other = RepairUnit("と", "と", "と", listOf(word(0, 1, "と", "と")), emptyList())
        return RepairSession(listOf(other, unit), 1)
    }

    private fun filled(r: RepairSession) {
        val request = r.request()!!
        assertTrue(r.apply(request, listOf(Candidate("さら", "甲", 0.0, listOf(word(0, 2, "さら", "甲")))), preview = false))
    }

    @Test fun aLateExactWordLocksTheFocusedUnitWithItsWordAlignment() {
        val r = repair()
        filled(r)
        val late = Candidate("さら", "語140", 0.0, listOf(word(0, 2, "さら", "語140")))
        assertTrue(r.choose(late))
        val unit = r.units[1]
        assertEquals("語140", unit.surface)
        assertTrue(unit.locked)
        assertEquals("さら", unit.typed)
        assertEquals(listOf(word(0, 2, "さら", "語140")), unit.segments)
        assertEquals("と", r.units[0].surface)
        assertEquals("語140", r.candidates[r.candidateIndex].surface)
    }

    @Test fun aLateChoiceCanBeUndoneAndConvertCanReturnToIt() {
        val r = repair()
        filled(r)
        assertTrue(r.choose(Candidate("さら", "語140", 0.0, listOf(word(0, 2, "さら", "語140")))))
        assertTrue(r.undo())
        assertEquals("甲", r.units[1].surface)
    }

    @Test fun aListDrawnForAnotherUnitIsRejected() {
        val r = repair()
        filled(r)
        r.focus(0)
        assertFalse(r.choose(Candidate("さら", "語140", 0.0, listOf(word(0, 2, "さら", "語140")))))
        assertEquals("と", r.units[0].surface)
    }

    @Test fun aCandidateAlreadyListedIsNotAddedTwice() {
        val r = repair()
        filled(r)
        val size = r.candidates.size
        assertTrue(r.choose(Candidate("さら", "甲", 0.0, listOf(word(0, 2, "さら", "甲")))))
        assertEquals(size, r.candidates.size)
    }
}
