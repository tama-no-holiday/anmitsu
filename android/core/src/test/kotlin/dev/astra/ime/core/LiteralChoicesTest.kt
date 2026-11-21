package dev.astra.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LiteralChoicesTest {
    private fun c(surface: String, reading: String = "") = Candidate(reading, surface, 0.0)

    private fun surfaces(e: LiteralChoices.Ensured) = e.candidates.map { it.surface }

    @Test fun katakanaIsADeterministicPerCharacterMapping() {
        assertEquals("ヌメピコ", LiteralChoices.katakana("ぬめぴこ"))
        // ー, small kana, ゔゕゖ map; ASCII, emoji, punctuation, katakana and kanji are kept.
        assertEquals("ヴァーチャル", LiteralChoices.katakana("ゔぁーちゃる"))
        assertEquals("ヵヶ", LiteralChoices.katakana("ゕゖ"))
        assertEquals("ABCテスト😀、。漢字カナ", LiteralChoices.katakana("ABCてすと😀、。漢字カナ"))
        assertEquals("", LiteralChoices.katakana(""))
        // Iteration marks (outside U+3041..U+3096) are not rewritten.
        assertEquals("ゝゞ", LiteralChoices.katakana("ゝゞ"))
    }

    @Test fun katakanaSegmentsTileTheTypedTextWithLiteralAndPassthroughRuns() {
        val typed = "くろーどabc😀です"
        val kata = LiteralChoices.katakana(typed)
        val segments = LiteralChoices.katakanaSegments(typed)
        assertNotNull(Alignment.checked(typed, kata, segments))
        assertEquals(
            listOf(
                Segment(0, 4, "くろーど", "クロード", SegmentKind.LITERAL),
                Segment(4, 9, "abc😀", "abc😀", SegmentKind.PASSTHROUGH),
                Segment(9, 11, "です", "デス", SegmentKind.LITERAL),
            ),
            segments,
        )
    }

    @Test fun ensureAddsBothLiteralChoicesWithoutChangingTheTop() {
        val e = LiteralChoices.ensure("あめ", listOf(c("雨"), c("飴")))
        assertEquals(listOf("雨", "飴", "あめ", "アメ"), surfaces(e))
        assertEquals(listOf(0, 1, -1, -1), e.source.toList())
        // Only the raw kana: katakana becomes the second choice, raw stays first.
        assertEquals(listOf("の", "ノ"), surfaces(LiteralChoices.ensure("の", listOf(c("の")))))
        // Already present early (a dictionary katakana word ranked first) keeps its rank.
        assertEquals(listOf("テレビ", "てれび"), surfaces(LiteralChoices.ensure("てれび", listOf(c("テレビ"), c("てれび")))))
        // No hiragana: katakana equals raw, nothing is added.
        assertEquals(listOf("Claude"), surfaces(LiteralChoices.ensure("Claude", listOf(c("Claude")))))
    }

    @Test fun aLateKatakanaIsMovedIntoItsSlotAndOtherOrderIsKept() {
        val text = "くろーど"
        // e.g. a judge reordered the katakana to the end.
        val ranked = listOf("苦労ど", "黒ーど", "Claude", "九郎ど", "くろーど", "クロード").map { c(it) }
        val e = LiteralChoices.ensure(text, ranked)
        assertEquals(listOf("苦労ど", "黒ーど", "Claude", "クロード", "九郎ど", "くろーど"), surfaces(e))
        assertEquals(listOf(0, 1, 2, 5, 3, 4), e.source.toList())
    }

    @Test fun theCapReservesSlotsByDroppingTheLowestRankedOthers() {
        val text = "ぬめぴこ"
        val full = (0 until MAX_LIMIT).map { c("候補$it") }
        for (limit in MIN_LIMIT..MAX_LIMIT) {
            val s = surfaces(LiteralChoices.ensure(text, full, limit))
            assertEquals(limit, s.size)
            assertEquals(s.size, s.toSet().size)
            assertTrue(text in s)
            assertTrue("ヌメピコ" in s)
            assertTrue(s.indexOf("ヌメピコ") <= LiteralChoices.KATAKANA_SLOT)
            if (limit > 2) assertEquals("候補0", s[0])
        }
        // Tiny caps: with distinct raw and katakana, limit 2 holds only the literal choices (the top is dropped);
        // without a separate katakana (ASCII), limit 2 still keeps the top.
        assertEquals(listOf(text, "ヌメピコ"), surfaces(LiteralChoices.ensure(text, full, 2)))
        assertEquals(listOf("候補0", "abc"), surfaces(LiteralChoices.ensure("abc", full, 2)))
        // Duplicates do not count against the cap.
        assertEquals(listOf("雨", "あめ", "アメ"), surfaces(LiteralChoices.ensure("あめ", listOf(c("雨"), c("雨"), c("あめ")))))
        try {
            LiteralChoices.ensure(text, full, MAX_LIMIT + 1)
            fail("limit above MAX_LIMIT must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // --- With the :core engine (development lexicon) ----------------------------------------------

    private val engine get() = CoreTest.engine

    @Test fun knownWordsKeepTheirTopCandidate() {
        for (text in listOf("あめ", "あしたはあめ", "がっこう")) {
            val converted = engine.convert(text, MAX_LIMIT)
            val ensured = LiteralChoices.ensure(text, converted)
            assertEquals(converted[0].surface, ensured.candidates[0].surface)
            // Other candidates keep their relative order; only the literal choices were inserted or moved.
            val kata = LiteralChoices.katakana(text)
            assertEquals(converted.map { it.surface }.filter { it != kata }.take(ensured.candidates.size - 1),
                ensured.candidates.map { it.surface }.filter { it != kata }.take(ensured.candidates.size - 1))
            assertTrue(ensured.candidates.indexOfFirst { it.surface == kata } in 1..LiteralChoices.KATAKANA_SLOT)
        }
    }

    @Test fun unknownWordsAlwaysGetTheirKatakana() {
        // Development examples of names/coinages absent from the handmade lexicon.
        for ((text, kata) in listOf("ぬめぴこ" to "ヌメピコ", "ぽよみん" to "ポヨミン", "ちゃっとじーぴーてぃー" to "チャットジーピーティー")) {
            val e = LiteralChoices.ensure(text, engine.convert(text, MAX_LIMIT))
            val s = surfaces(e)
            assertTrue(s.toString(), s.indexOf(kata) in 0..LiteralChoices.KATAKANA_SLOT)
            assertTrue(text in s)
            val k = e.candidates.first { it.surface == kata }
            assertNotNull(Alignment.checked(text, k.surface, k.segments))
        }
    }

    @Test fun longMixedInputPassesNonKanaThroughInTheKatakana() {
        val text = "きょうはGitHubで😀ぷるりくをだした。" + "あ".repeat(20)
        val e = LiteralChoices.ensure(text, engine.convert(text, MAX_LIMIT))
        val kata = e.candidates.first { it.surface == LiteralChoices.katakana(text) }
        assertTrue(kata.surface.startsWith("キョウハGitHubデ😀プルリクヲダシタ。"))
        assertNotNull(Alignment.checked(text, kata.surface, kata.segments))
        assertTrue(text in surfaces(e))
        // Over the engine's input bound only the identity comes back; the katakana is still offered.
        val tooLong = "あ".repeat(MAX_INPUT_CODEPOINTS + 1)
        assertEquals(listOf(tooLong, "ア".repeat(MAX_INPUT_CODEPOINTS + 1)), surfaces(LiteralChoices.ensure(tooLong, engine.convert(tooLong))))
    }
}
