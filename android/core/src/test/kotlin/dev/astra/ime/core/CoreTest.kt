package dev.astra.ime.core

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Development fixture (seen while building the lexicon; not a blind holdout).
private const val RAW = "きょうきょくいうと、かのくらいのごたいふならすいさつしてへんかんされてはしい"
private const val READING = "きゅうきょくいうと、このくらいのごたいぷならすいさつしてへんかんされてほしい"
private const val SURFACE = "究極言うと、このくらいの誤タイプなら推察して変換されてほしい"

class CoreTest {
    companion object {
        val lexicon: List<LexEntry> by lazy {
            val stream = CoreTest::class.java.getResourceAsStream("/lexicon.tsv") ?: error("missing /lexicon.tsv fixture")
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.removePrefix("\uFEFF").trimEnd('\r') }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.split('\t') }
                    .filter { it[0] != "reading" }
                    .map { f -> require(f.size == 4) { "bad TSV row" }; LexEntry(f[0], f[1], f[2].toDouble(), f[3]) }
                    .toList()
            }
        }
        val engine by lazy { ConversionEngine(lexicon) }
    }

    private fun surfaces(c: List<Candidate>) = c.map { it.surface }

    private fun checkResult(text: String, result: List<Candidate>, limit: Int = 10) {
        assertTrue(result.size <= limit)
        assertTrue("raw input missing: ${surfaces(result)}", text in surfaces(result))
        assertTrue(result.all { it.cost >= 0.0 && it.cost.isFinite() })
        assertEquals(result.size, result.map { it.surface }.toSet().size)
    }

    private fun assertTop(text: String, expected: String, limit: Int = 10): List<Candidate> {
        val result = engine.convert(text, limit)
        checkResult(text, result, limit)
        assertEquals(surfaces(result).toString(), expected, result[0].surface)
        return result
    }

    // --- ConversionEngine ---

    @Test fun lexiconHasNoSentenceEntries() {
        assertTrue(lexicon.isNotEmpty())
        assertTrue(lexicon.none { it.surface == SURFACE || it.reading == READING })
        assertTrue(lexicon.all { it.reading.length <= 8 })
    }

    @Test fun representativeTop1() {
        val result = assertTop(RAW, SURFACE)
        assertEquals(READING, result[0].reading)
    }

    @Test fun representativeLimit2KeepsIdentity() {
        val result = assertTop(RAW, SURFACE, limit = 2)
        assertEquals(listOf(SURFACE, RAW), surfaces(result))
    }

    @Test fun composedCleanSentences() {
        for ((text, expected) in listOf(
            "あしたはがっこうにいく" to "明日は学校に行く",
            "きょうはあめです" to "今日は雨です",
            "このぶんしょうをへんかんしてほしい" to "この文章を変換してほしい",
            "でんしゃでかいしゃにいく" to "電車で会社に行く",
            "ぱんをたべる" to "パンを食べる",
            "しりょうをかくにんする" to "資料を確認する",
            "かいぎのじかんをかくにんする" to "会議の時間を確認する",
        )) assertTop(text, expected)
    }

    @Test fun composedNoisySentences() {
        for ((text, expected) in listOf(
            "あしたはがっこおにいく" to "明日は学校に行く",   // substitution (same row)
            "ぱんをたぺる" to "パンを食べる",                 // substitution (voicing)
            "あしたはがこうにいく" to "明日は学校に行く",     // deletion
            "しりょうをかくにんんする" to "資料を確認する",   // insertion
            "でんしゃでかしいゃにいく" to "電車で会社に行く", // transposition
        )) assertTop(text, expected)
    }

    @Test fun legitimateCompetitorsNotOvercorrected() {
        for ((text, expected) in listOf(
            "はいしゃにいく" to "歯医者に行く",
            "かいしゃにいく" to "会社に行く",
            "きょうはきょくをきく" to "今日は曲を聞く",
            "かれはがっこうにいく" to "彼は学校に行く",
        )) assertTop(text, expected)
    }

    @Test fun negationKeepsSuruStem() {
        for ((text, expected) in listOf("へんかんしない" to "変換しない", "かくにんしない" to "確認しない")) {
            assertEquals(text, assertTop(text, expected)[0].reading)
        }
    }

    @Test fun preservesEmojiMixedPunctuationAndKatakana() {
        assertTop("😀", "😀")
        val mixed = assertTop("ABCがっこう😀", "ABC学校😀")
        assertTrue(mixed.all { it.surface.startsWith("ABC") && it.surface.endsWith("😀") })
        assertTop("パンをたべる", "パンを食べる")
        assertTrue(engine.convert("がっこう。").all { it.surface.endsWith("。") })
    }

    @Test fun emptyAndOverlongAreIdentity() {
        assertEquals(listOf(Candidate("", "", 0.0)), engine.convert(""))
        val long = "あ".repeat(MAX_INPUT_CODEPOINTS + 1)
        assertEquals(listOf(Candidate(long, long, 0.0)), engine.convert(long))
        val emojiLong = "😀".repeat(MAX_INPUT_CODEPOINTS + 1)
        assertEquals(listOf(Candidate(emojiLong, emojiLong, 0.0)), engine.convert(emojiLong))
    }

    @Test fun maxLengthIsCodePointAware() {
        // 128 emoji are 256 UTF-16 units but within the code point limit, so they are converted.
        val emoji = "😀".repeat(MAX_INPUT_CODEPOINTS)
        val result = engine.convert(emoji)
        checkResult(emoji, result)
        assertEquals(emoji, result[0].surface)
        val edge = "がっこう".repeat(MAX_INPUT_CODEPOINTS / 4)
        assertTop(edge, "学校".repeat(MAX_INPUT_CODEPOINTS / 4))
    }

    @Test fun limitsAreEnforced() {
        for (bad in listOf(1, 0, -3, MAX_LIMIT + 1)) {
            assertThrows(IllegalArgumentException::class.java) { engine.convert("あめ", bad) }
        }
        checkResult(RAW, engine.convert(RAW, MAX_LIMIT), MAX_LIMIT)
        checkResult("あめ", engine.convert("あめ", 2), 2)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun resultIsImmutable() {
        val result = engine.convert("あめ")
        assertThrows(UnsupportedOperationException::class.java) {
            (result as MutableList<Candidate>).add(Candidate("x", "x", 0.0))
        }
    }

    @Test fun cancellationStopsConversion() {
        assertThrows(CancellationException::class.java) { engine.convert(RAW, isCancelled = { true }) }
    }

    @Test fun alternativeSegmentationPaysItsOwnCost() {
        val tiny = ConversionEngine(listOf(LexEntry("あめ", "雨", 2.0, "noun")))
        assertEquals(mapOf("雨" to 2.0, "あめ" to 6.0), tiny.convert("あめ").associate { it.surface to it.cost })
    }

    @Test fun invalidLexiconRejected() {
        for (bad in listOf(
            LexEntry("ame", "雨", 1.0, "noun"), LexEntry("あめ", "", 1.0, "noun"),
            LexEntry("あめ", "雨", -1.0, "noun"), LexEntry("あめ", "雨", Double.NaN, "noun"),
            LexEntry("あめ", "雨", 1.0, "sentence"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { ConversionEngine(listOf(bad)) }
        }
    }

    // --- FlickLayout ---

    @Test fun flickDirections() {
        val expected = mapOf(
            FlickKey.A to "あいうえお", FlickKey.KA to "かきくけこ", FlickKey.SA to "さしすせそ",
            FlickKey.TA to "たちつてと", FlickKey.NA to "なにぬねの", FlickKey.HA to "はひふへほ",
            FlickKey.MA to "まみむめも", FlickKey.RA to "らりるれろ",
        )
        val order = listOf(FlickDirection.CENTER, FlickDirection.LEFT, FlickDirection.UP, FlickDirection.RIGHT, FlickDirection.DOWN)
        for ((key, chars) in expected) {
            assertEquals(chars, order.joinToString("") { FlickLayout.kana(key, it)!! })
        }
        assertEquals(listOf("や", "「", "ゆ", "」", "よ"), order.map { FlickLayout.kana(FlickKey.YA, it) })
        assertEquals(listOf("わ", "を", "ん", "ー", null), order.map { FlickLayout.kana(FlickKey.WA, it) })
        assertEquals(listOf("、", "。", "？", "！", null), order.map { FlickLayout.kana(FlickKey.PUNCT, it) })
        assertTrue(order.all { FlickLayout.kana(FlickKey.MODIFIER, it) == null })
        assertEquals(mapOf(FlickDirection.CENTER to "や", FlickDirection.LEFT to "「", FlickDirection.UP to "ゆ", FlickDirection.RIGHT to "」", FlickDirection.DOWN to "よ"),
            FlickLayout.labels(FlickKey.YA))
        assertEquals(FlickKey.entries.toSet(), FlickLayout.rows.flatten().toSet())
        assertEquals(listOf("小゛゜", "わ", "、。？！"), FlickLayout.rows[3].map { it.label })
    }

    @Test fun modifierCycles() {
        fun cycle(start: String): List<String> {
            val seen = mutableListOf(start)
            while (true) {
                val n = FlickLayout.toggle(seen.last())!!
                if (n == start) return seen
                seen += n
            }
        }
        assertEquals(listOf("か", "が"), cycle("か"))
        assertEquals(listOf("は", "ば", "ぱ"), cycle("は"))
        assertEquals(listOf("つ", "っ", "づ"), cycle("つ"))
        assertEquals(listOf("あ", "ぁ"), cycle("あ"))
        assertEquals(listOf("や", "ゃ"), cycle("や"))
        assertEquals(listOf("う", "ぅ", "ゔ"), cycle("う"))
        assertNull(FlickLayout.toggle("ん"))
        assertNull(FlickLayout.toggle("。"))
    }

    // --- InputSession ---

    private fun started(sensitive: Boolean = false) = InputSession().also { it.startInput(sensitive) }

    @Test fun editingAndUnicodeBackspace() {
        val s = InputSession()
        assertFalse(s.type("あ"))
        s.startInput(false)
        assertTrue(s.type("か"))
        assertTrue(s.toggleLast())
        assertEquals("が", s.composingText)
        assertTrue(s.type("😀"))
        assertTrue(s.backspace())
        assertEquals("が", s.composingText)
        assertTrue(s.backspace())
        assertFalse(s.backspace())
        assertFalse(s.toggleLast())
        assertNull(s.request())
    }

    @Test fun revisionIsMonotonic() {
        val s = started()
        var last = s.revision
        for (step in listOf<() -> Unit>({ s.type("あ") }, { s.backspace() }, { s.startInput(false) }, { s.finishInput() })) {
            step()
            assertTrue(s.revision > last)
            last = s.revision
        }
    }

    @Test fun staleResultRejected() {
        val s = started()
        s.type("あめ")
        val token = s.request()!!
        s.type("あ")
        assertFalse(s.apply(token, engine.convert(token.text)))
        assertTrue(s.candidates.isEmpty())
        s.backspace() // same text again, but the old token stays stale
        assertFalse(s.apply(token, engine.convert(token.text)))
        val superseded = s.request()!!
        val fresh = s.request()!!
        assertFalse(s.apply(superseded, engine.convert(superseded.text)))
        assertTrue(s.apply(fresh, engine.convert(fresh.text)))
        assertEquals("雨", s.candidates[0].surface)
    }

    @Test fun applyRequiresRawCandidate() {
        val s = started()
        s.type("あめ")
        val token = s.request()!!
        assertFalse(s.apply(token, listOf(Candidate("あめ", "雨", 1.0))))
        assertFalse(s.apply(token, emptyList()))
        assertTrue(s.apply(token, engine.convert(token.text)))
    }

    @Test fun explicitChooseThenClears() {
        val s = started()
        s.type("あめ")
        val token = s.request()!!
        assertNull(s.choose(token, 0)) // nothing shown yet
        assertTrue(s.apply(token, engine.convert(token.text)))
        assertNull(s.choose(token, s.candidates.size))
        assertNull(s.choose(token, -1))
        assertEquals("雨", s.choose(token, 0))
        assertEquals("", s.composingText)
        assertTrue(s.candidates.isEmpty())
        assertNull(s.choose(token, 0))
    }

    @Test fun editAfterShowInvalidatesChoice() {
        val s = started()
        s.type("あめ")
        val token = s.request()!!
        assertTrue(s.apply(token, engine.convert(token.text)))
        s.backspace()
        assertTrue(s.candidates.isEmpty())
        assertNull(s.choose(token, 0))
        assertEquals("あ", s.composingText)
    }

    @Test fun finishSelectionAndNewEditorInvalidate() {
        for (end in listOf<(InputSession) -> Unit>({ it.finishInput() }, { it.onSelectionMoved() }, { it.startInput(false) })) {
            val s = started()
            s.type("あめ")
            val token = s.request()!!
            val result = engine.convert(token.text)
            end(s)
            assertFalse(s.apply(token, result))
            assertNull(s.choose(token, 0))
            assertEquals("", s.composingText)
            s.type("あめ") // same text in the new state: old token is still rejected
            assertFalse(s.apply(token, result))
        }
    }

    @Test fun sensitiveShowsOnlyRaw() {
        val s = started(sensitive = true)
        s.type("あめ")
        val token = s.request()!!
        assertTrue(token.sensitive)
        assertTrue(s.apply(token, engine.convert(token.text)))
        assertEquals(listOf("あめ"), surfaces(s.candidates))
        assertEquals("あめ", s.choose(token, 0))
    }

    @Test fun commitRawIsExplicitOnly() {
        val s = started()
        assertNull(s.commitRaw())
        s.type("へんかん")
        assertEquals("へんかん", s.commitRaw())
        assertEquals("", s.composingText)
    }

    @Test fun rawTextNotInToString() {
        val s = started()
        s.type("ひみつ")
        val token = s.request()!!
        assertTrue(s.apply(token, engine.convert(token.text)))
        for (text in listOf(s.toString(), token.toString(), s.candidates.toString())) {
            assertFalse(text, text.contains("ひみつ") || text.contains("秘密"))
        }
    }

    @Test fun sessionIsThreadConfined() {
        val s = InputSession()
        var error: Throwable? = null
        val t = Thread { error = runCatching { s.type("あ") }.exceptionOrNull() }
        t.start()
        t.join()
        assertTrue(error is IllegalStateException)
    }
}
