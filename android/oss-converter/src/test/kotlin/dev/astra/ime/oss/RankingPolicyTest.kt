package dev.astra.ime.oss

import dev.astra.ime.oss.LearnedOrder.Strength
import dev.astra.ime.oss.zenz.ZenzRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.31: context-first ordering of exact-reading candidates and strength-aware placement of learned phrases. */
class RankingPolicyTest {
    private val first = ZenzRanking.Weights(contextMargin = 2.0, contextFirst = true)
    private val reading = "かかか"
    private val typed = listOf("X", "Y", "Z", "W")
    private val readings = listOf(reading, reading, reading, "かかき") // W is a correction

    /** Scores by surface, the same with any context unless [withContext] differs. */
    private fun scorer(plain: Map<String, Float>, withContext: Map<String, Float> = plain) = ZenzRanking.Scorer { c, _, list ->
        list.map { (if (c.isEmpty()) plain else withContext).getValue(it) }.toFloatArray()
    }

    private fun order(
        weights: ZenzRanking.Weights, context: String, s: ZenzRanking.Scorer, strong: (Int) -> Boolean = { false },
    ) = ZenzRanking.rerank(reading, typed, readings, 8, weights, leftContext = context, external = context.isNotEmpty(), strongLearned = strong, learned = strong, scorer = s)

    @Test fun withContextTheModelOrdersExactCandidatesWhereRankAndMarginDefeatedIt() {
        // Z leads X by 1.5 nats: under the 0.1.30 rules rank 2 costs 2.0 and margin2 is not met; now it is first.
        val s = scorer(mapOf("X" to -3f, "Y" to -4f, "Z" to -1.5f, "W" to -0.1f))
        val old = order(first.copy(contextFirst = false), "私は歯医者です。", s)!!
        assertEquals(0, old[0])
        val now = order(first, "私は歯医者です。", s)!!
        assertEquals(listOf(2, 0, 1), now.filter { readings[it] == reading })
        // The correction keeps its own slot (rank and edit cost) and the exact lead is kept afterwards.
        assertEquals(ZenzRanking.rerank(reading, typed, readings, 8, first.copy(contextFirst = false), leftContext = "私は歯医者です。", external = true, scorer = s)!!.indexOf(3), now.indexOf(3))
        assertEquals(2, ZenzRanking.keepExactLead(now, readings, reading, correctionLeads = false)[0])
    }

    @Test fun noContextOrContextWithoutLettersKeepsTheDictionaryAndHistoryRules() {
        val s = scorer(mapOf("X" to -3f, "Y" to -4f, "Z" to -1.5f, "W" to -9f))
        assertEquals(0, order(first, "", s)!![0])
        assertEquals(0, order(first, "。、！ 123", s)!![0])
        assertFalse(ZenzRanking.contextRanks(first, external = true, leftContext = "。、！ 123"))
        assertTrue(ZenzRanking.contextRanks(first, external = true, leftContext = "雨。"))
        assertFalse(ZenzRanking.contextRanks(first, external = false, leftContext = "雨。"))
    }

    @Test fun tiesKeepDictionaryOrderAndStrongLearnedWordsCountOnlyWhenStrong() {
        val s = scorer(mapOf("X" to -2f, "Y" to -2f, "Z" to -1f, "W" to -9f))
        assertEquals(listOf(2, 0, 1), order(first, "前の文。", s)!!.filter { readings[it] == reading })
        // A strong word (explicit or registered) adds the learned bonus: Y with 3 nats passes Z.
        assertEquals(1, order(first, "前の文。", s) { it == 1 }!![0])
    }

    @Test fun aFailedOrMalformedScoreKeepsTheDictionaryOrder() {
        val nan = ZenzRanking.Scorer { _, _, list -> FloatArray(list.size) { if (it == 1) Float.NaN else -1f } }
        assertNull(order(first, "前の文。", nan))
        val inf = ZenzRanking.Scorer { _, _, list -> FloatArray(list.size) { Float.NEGATIVE_INFINITY } }
        assertNull(order(first, "前の文。", inf))
        val short = ZenzRanking.Scorer { _, _, _ -> floatArrayOf(-1f) }
        assertNull(order(first, "前の文。", short))
        assertNull(order(first, "前の文。", { _, _, _ -> null }))
    }

    @Test fun onlyTheScoredCandidatesAreReordered() {
        val many = (0 until 12).map { "S$it" }
        val s = ZenzRanking.Scorer { _, _, list -> list.map { if (it == "S11") 0f else -5f + 0.1f * it.drop(1).toInt() }.toFloatArray() }
        val o = ZenzRanking.rerank(reading, many, many.map { reading }, 8, first, leftContext = "前の文。", external = true, scorer = s)!!
        assertEquals(7, o[0]) // S11 is unscored and stays where the dictionary put it
        assertEquals(11, o.last())
    }

    // --- Learned phrases ---------------------------------------------------------------------------

    private val never = { false }
    private val always = { true }

    @Test fun anAcceptedPhraseIsNeverPinnedAndAChosenOneIsFirstUnlessContextOutweighsIt() {
        val implicit = LearnedOrder.Phrase("しかい", "歯科医", Strength.IMPLICIT)
        val explicit = LearnedOrder.Phrase("しかい", "歯科医", Strength.EXPLICIT)
        for (ranks in listOf(true, false)) assertEquals(LearnedOrder.KEEP, LearnedOrder.place(implicit, emptySet(), ranks, true, always))
        assertEquals(0, LearnedOrder.place(explicit, emptySet(), contextRanks = false, leaderReadsText = true, outweighed = never))
        assertEquals(0, LearnedOrder.place(explicit, emptySet(), contextRanks = true, leaderReadsText = true, outweighed = never))
        assertEquals(1, LearnedOrder.place(explicit, emptySet(), contextRanks = true, leaderReadsText = true, outweighed = always))
        assertEquals(0, LearnedOrder.place(explicit, emptySet(), contextRanks = true, leaderReadsText = false, outweighed = always))
    }

    @Test fun legacyHistoryLeadsOnlyWithoutContextRankingAndIsNeverPromotedToExplicit() {
        val legacy = LearnedOrder.Phrase("しかい", "歯科医")
        assertEquals(Strength.LEGACY, legacy.strength)
        assertEquals(0, LearnedOrder.place(legacy, emptySet(), contextRanks = false, leaderReadsText = true, outweighed = always))
        assertEquals(LearnedOrder.KEEP, LearnedOrder.place(legacy, emptySet(), contextRanks = true, leaderReadsText = true, outweighed = never))
    }

    @Test fun theLatestExplicitPhraseSpeaksForAReadingAndRegisteredWordsOnlyCountForTheirOwnReading() {
        val phrases = listOf(
            LearnedOrder.Phrase("しかい", "視界", Strength.IMPLICIT),
            LearnedOrder.Phrase("しかい", "司会", Strength.EXPLICIT),
            LearnedOrder.Phrase("しかい", "歯科医", Strength.LEGACY),
            LearnedOrder.Phrase("しかい", "死海", Strength.EXPLICIT),
        )
        assertEquals("司会", LearnedOrder.pick("しかい", phrases)!!.surface)
        assertEquals("視界", LearnedOrder.pick("しかい", phrases.take(1))!!.surface)
        assertNull(LearnedOrder.pick("しか", phrases))
        val named = LearnedOrder.Phrase("かずきがきた", "一希が来た", Strength.IMPLICIT)
        assertEquals(0, LearnedOrder.place(named, setOf("かずき" to "一希"), contextRanks = true, leaderReadsText = true, outweighed = always))
        // The same surface registered for another reading is not this phrase's word.
        assertEquals(LearnedOrder.KEEP, LearnedOrder.place(named, setOf("いっき" to "一希"), contextRanks = true, leaderReadsText = true, outweighed = always))
    }
}
