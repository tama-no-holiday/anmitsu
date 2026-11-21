package dev.astra.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementTest {
    private val table = Supplement.parse(
        """
        # astra-supplement format=1
        S	かっこ	（）	「」	【】
        S	「」	（）	『』
        S	(	（	［
        F	にこにこ	(^^)	( ´ ▽ ` )
        E	ねこ	🐱	🐈‍⬛
        K	し	士子𠮟詩
        """.trimIndent().reader().buffered(),
    )

    private fun surfaces(text: String, renderable: (String) -> Boolean = { true }) = table.lookup(text, renderable).map { it.surface }
    private fun c(surface: String, reading: String = "x") = Candidate(reading, surface, 0.0)

    @Test fun symbolsComeInMozcOrderWithTheirOtherWidth() {
        assertEquals(listOf("（）", "()", "「」", "｢｣", "【】"), surfaces("かっこ"))
        // Typed symbols: their own other width first, then the related ones.
        assertEquals(listOf("｢｣", "（）", "()", "『』"), surfaces("「」"))
        // Full-width typed ASCII finds the ASCII key.
        assertEquals(listOf("(", "［", "["), surfaces("（"))
    }

    @Test fun emoticonsKeepSpacesAndEmojiCanBeFilteredByTheFont() {
        assertEquals(listOf("(^^)", "( ´ ▽ ` )"), surfaces("にこにこ"))
        assertEquals(listOf("🐱", "🐈‍⬛"), surfaces("ねこ"))
        assertEquals(listOf("🐱"), surfaces("ねこ") { it.length <= 2 })
    }

    @Test fun singleKanjiAreSplitByCodePoint() {
        val items = table.lookup("し")
        assertEquals(listOf("士", "子", "𠮟", "詩"), items.map { it.surface })
        assertTrue(items.all { it.kind == Supplement.Kind.KANJI })
    }

    @Test fun nothingForUnknownOrSentenceLengthText() {
        assertTrue(surfaces("わからない").isEmpty())
        assertTrue(surfaces("し".repeat(Supplement.MAX_KEY + 1)).isEmpty())
        assertTrue(surfaces("").isEmpty())
    }

    @Test fun numberForms() {
        val one = Supplement.Numbers.forms("1")
        assertTrue(one.containsAll(listOf("１", "一", "①", "⑴", "⒈", "❶", "⓵", "Ⅰ", "ⅰ", "I", "壱", "¹", "₁")))
        assertFalse("1" in one)
        val twelve = Supplement.Numbers.forms("12")
        assertEquals(listOf("１２", "十二", "一二", "⑫", "⑿", "⒓", "⓬", "Ⅻ"), twelve.take(8))
        assertTrue(twelve.containsAll(listOf("XII", "壱拾弐", "¹²", "₁₂")))
        assertTrue(Supplement.Numbers.forms("21").contains("㉑"))
        assertTrue(Supplement.Numbers.forms("50").contains("㊿"))
        assertFalse(Supplement.Numbers.forms("51").any { it.length == 1 })
        assertTrue(Supplement.Numbers.forms("0").containsAll(listOf("０", "〇", "⓪", "⓿", "零")))
        // Leading zeros: digit by digit only, no value forms.
        assertEquals(listOf("０７", "〇七", "⁰⁷", "₀₇"), Supplement.Numbers.forms("07"))
        assertTrue(Supplement.Numbers.forms("1234").containsAll(listOf("千二百三十四", "1,234", "１，２３４", "MCCXXXIV")))
        assertEquals("一億二千三百四十五万六千七百八十九", Supplement.Numbers.kanji(123456789))
        assertEquals("一万", Supplement.Numbers.kanji(10000))
        assertEquals("一兆十", Supplement.Numbers.kanji(1_000_000_000_010))
        assertTrue(Supplement.Numbers.forms("12a").isEmpty())
        assertTrue(Supplement.Numbers.forms("1".repeat(17)).isEmpty())
        // Typed full-width digits are folded first.
        assertTrue(table.lookup("１２").map { it.surface }.contains("⑫"))
    }

    @Test fun widths() {
        assertEquals("（「、」）", Supplement.Widths.full("(｢､｣)"))
        assertEquals("(｢､｣)", Supplement.Widths.half("（「、」）"))
        assertFalse(Supplement.Widths.isSymbolic("あ「"))
        assertFalse(Supplement.Widths.isSymbolic("12"))
        assertTrue(Supplement.Widths.isSymbolic("「」"))
    }

    @Test fun insertKeepsTheFirstCandidateAndLeadsWithSymbolsAfterThree() {
        val ranked = listOf(c("括弧"), c("かっこ"), c("カッコ"), c("格好"), c("恰好"))
        val got = table.insert("かっこ", ranked)
        assertEquals(listOf("括弧", "かっこ", "カッコ", "（）", "()", "「」", "｢｣", "【】", "格好", "恰好"), got.candidates.map { it.surface })
        assertEquals(listOf(0, 1, 2, -1, -1, -1, -1, -1, 3, 4), got.source.toList())
        // Each added one is one whole-reading word: exact for the panel, one unit for repair.
        val added = got.candidates[3]
        assertEquals(listOf(Segment(0, 3, "かっこ", "（）", SegmentKind.WORD)), added.segments)
        assertEquals(CandidateClass.EXACT, CandidateGroups.classify("かっこ", added))
    }

    @Test fun insertPutsNumbersRightAfterTheDigitsAndTheRestAtTheEnd() {
        val got = table.insert("12", listOf(c("12", "12")))
        assertEquals("12", got.candidates.first().surface)
        assertEquals(listOf("１２", "十二"), got.candidates.drop(1).take(2).map { it.surface })
        assertEquals(1 + Supplement.Numbers.forms("12").size, got.candidates.size)
        // At most MAX_LEAD numbers/symbols lead; kanji only fill the end.
        val shi = table.insert("し", listOf(c("し"), c("死"), c("詩"), c("市")))
        assertEquals(listOf("し", "死", "詩", "市", "士", "子", "𠮟"), shi.candidates.map { it.surface })
        // Surfaces already present are not added twice; nothing found leaves the list as it was.
        val same = listOf(c("あ"))
        assertTrue(table.insert("あ", same).candidates === same)
        // An empty list gets the items in order.
        assertEquals(listOf("（）", "()"), table.insert("かっこ", emptyList()).candidates.take(2).map { it.surface })
    }

    @Test fun insertedListsSurviveTheLiteralChoicesCap() {
        val ranked = (0 until 19).map { c("語$it") }
        val inserted = table.insert("かっこ", listOf(c("かっこ")) + ranked)
        val ensured = LiteralChoices.ensure("かっこ", inserted.candidates, MAX_LIMIT)
        assertEquals(MAX_LIMIT, ensured.candidates.size)
        assertEquals("かっこ", ensured.candidates.first().surface)
        assertTrue(ensured.candidates.map { it.surface }.containsAll(listOf("（）", "「」", "カッコ")))
    }

    @Test fun malformedTablesFail() {
        for (bad in listOf("X\tかっこ\t（）", "S\t\t（）", "S\tかっこ\t", "Sかっこ\t（）", "N\t1\t１")) {
            val failed = try {
                Supplement.parse(bad.reader().buffered())
                false
            } catch (e: IllegalArgumentException) {
                true
            }
            assertTrue(bad, failed)
        }
    }
}
