package dev.astra.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Behavioural checks of correction abstention. feedback.tsv is synthetic development data seen while
// building this fix, not a blind holdout; passing it says nothing about general accuracy.
class CorrectionPolicyTest {
    private val engine get() = CoreTest.engine

    // Deliberately lacks ありがとう so the guard, not a dictionary lookup, has to keep fabricated edits out.
    private val minimal = ConversionEngine(listOf(
        LexEntry("ある", "ある", 1.8, "verb"), LexEntry("あり", "あり", 2.0, "verb"),
        LexEntry("が", "が", 1.2, "particle"), LexEntry("と", "と", 1.1, "particle"),
        LexEntry("に", "に", 1.0, "particle"), LexEntry("がっこう", "学校", 2.2, "noun"),
        LexEntry("いく", "行く", 2.0, "verb"),
    ))
    private val fabricated = listOf("ある学校", "あるがとう", "あり学校")

    private fun surfaces(c: List<Candidate>) = c.map { it.surface }

    private fun assertNoFabrication(text: String, result: List<Candidate>) {
        assertTrue("raw missing: ${surfaces(result)}", text in surfaces(result))
        assertTrue(surfaces(result).toString(), result.none { c -> fabricated.any { it in c.surface } })
    }

    @Test fun unknownWordIsNotRewrittenIntoKnownWords() {
        val result = minimal.convert("ありがとう")
        assertNoFabrication("ありがとう", result)
        assertEquals("ありがとう", result[0].surface)
    }

    @Test fun symbolTailsDoNotWidenTheEditBudget() {
        for (tail in listOf(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", "😀".repeat(40), "。".repeat(40), "!?".repeat(20),
            "、がっこうにいく", // a clean kana run elsewhere must not lend its length either
        )) {
            val text = "ありがとう$tail"
            assertNoFabrication(text, minimal.convert(text, MAX_LIMIT))
        }
    }

    @Test fun closeTyposAreStillCorrected() {
        assertEquals("学校", minimal.convert("がこう")[0].surface)
        for ((text, expected) in listOf(
            "ありがとうござます" to "ありがとうございます", // deletion
            "おはよございます" to "おはようございます",     // deletion
            "こんにちわ" to "こんにちは",                   // substitution
            "あしたはがこうにいく" to "明日は学校に行く",
        )) assertEquals(text, expected, engine.convert(text)[0].surface)
    }

    @Test fun cleanInputKeepsItsReading() {
        for (text in listOf(
            "ありがとう", "きょう", "はいしゃ", "かいしゃ", "ほしい", "すみません",
            "よろしくおねがいします", "あしたはがっこうにいく", "きょうはきょくをきく",
        )) {
            val result = engine.convert(text, MAX_LIMIT)
            assertTrue(text in surfaces(result))
            // Homophone surfaces are fine; a different reading would be an uninvited correction.
            assertTrue(surfaces(result).toString(), result.all { it.reading == text })
        }
        assertTrue(surfaces(engine.convert("ありがとう")).none { c -> fabricated.any { it in c } })
    }

    @Test fun unknownNamesAndCoinagesStayRaw() {
        for (text in listOf("ぽよみん", "ぬめぴこ", "みゅらの", "るぴぴあ", "ぴょるたX", "ほげぴよだにゃ")) {
            val result = engine.convert(text)
            assertEquals(surfaces(result).toString(), text, result[0].surface)
            assertTrue(surfaces(result).toString(), result.all { it.reading == text })
        }
        // A familiar word inside an unknown string may be offered, but never above the typed text.
        val result = engine.convert("るぴぴあめ")
        assertEquals("るぴぴあめ", result[0].surface)
        assertTrue(surfaces(result).toString(), "るぴぴ雨" in surfaces(result))
    }

    @Test fun feedbackDevelopmentCases() {
        val rows = CorrectionPolicyTest::class.java.getResourceAsStream("/feedback.tsv")!!
            .bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { it.isNotBlank() }.map { it.split('\t') }
        val checkedTop = listOf("feedback-", "daily-", "unknown-", "oov-", "typo-")
        for (c in rows) {
            val result = engine.convert(c[2])
            val forbidden = c.getOrElse(5) { "" }.split('|').filter { it.isNotEmpty() }
            assertTrue(c[0], c[2] in surfaces(result))
            assertTrue(c[0], result.none { it.surface in forbidden })
            if (c[2] == c[3]) assertEquals(c[0], c[3], result[0].reading)
            if (checkedTop.any { c[0].startsWith(it) }) {
                assertTrue("${c[0]}: ${surfaces(result)}", result[0].surface in c[4].split('|'))
            }
        }
    }
}
