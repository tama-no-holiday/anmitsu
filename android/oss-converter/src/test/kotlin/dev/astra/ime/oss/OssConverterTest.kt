package dev.astra.ime.oss

import com.kazumaproject.dictionary.models.Dictionary
import com.kazumaproject.engine.KanaKanjiEngine
import com.kazumaproject.mozc.ConnectionMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.TreeMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class OssConverterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var out: Path
    private lateinit var report: BuildReport
    private lateinit var converter: OssConverter

    @Before
    fun buildFixture() {
        val src = MozcFixture.write(temp.root.toPath().resolve("mozc"))
        out = temp.root.toPath().resolve("out")
        report = OssDictionaryBuilder.build(MozcInputs.fromDirectory(src, includeSuffix = true), out)
        converter = OssConverter(OssResources.load(out))
    }

    @Test
    fun buildCountsEveryRecordAndPreservesIdsAndCosts() {
        assertEquals(listOf(4, 9, 0, 0, 0, 0, 0, 0, 0, 0, 1), report.files.map { it.records })
        assertEquals(14, report.records)
        assertEquals(11, report.readings)
        assertEquals(1, report.exactDuplicates)
        assertEquals(1, report.spellingCorrectionRows)
        assertEquals(1, report.files[1].spellingCorrectionRows)
        assertEquals(4, report.idCount)
        assertEquals(4, report.matrixSize)
        assertTrue(report.verified)
        // Patched TokenArray: every surface renders exactly, including the cases unpatched upstream rewrote.
        assertEquals(0, report.hiraganaRewrites + report.katakanaRewrites + report.trieRewrites)
        assertTrue(report.rewriteExamples.isEmpty())
        val reportText = String(Files.readAllBytes(out.resolve(OssDictionaryBuilder.REPORT)), StandardCharsets.UTF_8)
        assertTrue(reportText, reportText.contains("spellingCorrectionRows=1 (tag counted only"))
        assertTrue(reportText, reportText.contains("surfaceRewrites hiraganaSentinel=0 katakanaSentinel=0 trie=0"))

        val resources = OssResources.load(out)
        assertEquals(-100, resources.matrix.getCost(1, 2))
        assertEquals(900, resources.matrix.getCost(3, 1))
        val termId = resources.yomi.getTermId(resources.yomi.getNodeIndex("あめ"))
        val tokens = resources.tokens.getListDictionaryByYomiTermId(termId)
        assertEquals(listOf<Short>(3500, 3600, 3500), tokens.map { it.wordCost })
        assertTrue(tokens.all { resources.tokens.leftIds[it.posTableIndex.toInt()] == 1.toShort() })
        val manifest = Files.newInputStream(out.resolve(OssResources.MANIFEST)).use { OssResources.readManifest(it) }
        assertEquals("1", manifest["format"])
        assertEquals("14", manifest["records"])
        assertEquals("1", manifest["spellingCorrectionRows"])
        assertEquals(OssDictionaryBuilder.UPSTREAM_PATCHES, manifest["upstreamPatches"])
        assertEquals(64, manifest.getValue("sha256.dictionary00.txt").length)
    }

    /** Explicit n-best opt-in: only for this tiny bounded fixture, not a production setting. */
    private fun nBestConverter() = OssConverter(OssResources.load(out), OssLimits(maxNBestRunLength = 12))

    @Test
    fun defaultIsBestViterbiThenOneWordVariantsPlusRawWithoutNBest() {
        val r = converter.convert("きょうはあめです", limit = 5)
        assertEquals(OssStatus.OK, r.status)
        assertFalse(r.nBestUsed)
        assertEquals(0, converter.limits.maxNBestRunLength)
        // Best 9250; 飴 replaces 雨 (+100), 京 replaces 今日 (+1000), and both together (+1100) after them.
        assertEquals(listOf("今日は雨です", "今日は飴です", "京は雨です", "京は飴です", "きょうはあめです"), r.candidates)
        // Homophones of a short reading, cheapest first.
        assertEquals(listOf("今日", "京", "きょう"), converter.convert("きょう").candidates)
        assertEquals(listOf("テレビ", "てれび"), converter.convert("てれび").candidates)
    }

    @Test
    fun candidatesCarryTheirWords() {
        val r = converter.convert("きょうはあめです", limit = 5)
        assertEquals(r.candidates.size, r.words.size)
        assertEquals("今日は雨です", r.words[0].joinToString("") { it.surface })
        assertEquals("きょうはあめです", r.words[0].joinToString("") { it.reading })
        assertTrue(Word("あめ", "雨") in r.words[0])
        // The 飴 variant replaces exactly that word.
        assertEquals(r.words[0].map { if (it == Word("あめ", "雨")) Word("あめ", "飴") else it }, r.words[r.candidates.indexOf("今日は飴です")])
        assertTrue(r.words.last().isEmpty()) // raw
    }

    /** Every aligned candidate: spans tile the typed text, spell the candidate and its reading; passthrough is verbatim. */
    private fun assertAligned(text: String, r: OssConversion) {
        assertEquals(r.candidates.size, r.spans.size)
        for ((i, candidate) in r.candidates.withIndex()) {
            val spans = r.spans[i]
            if (candidate == text) {
                assertEquals(null, spans) // raw: no alignment claimed
                continue
            }
            var at = 0
            for (s in spans!!) {
                assertEquals(at, s.typedStart)
                assertTrue(s.typedEnd > s.typedStart)
                if (s.kind == Span.Kind.PASSTHROUGH) {
                    assertEquals(text.substring(s.typedStart, s.typedEnd), s.surface)
                    assertEquals(s.surface, s.reading)
                }
                at = s.typedEnd
            }
            assertEquals(text.length, at)
            assertEquals(candidate, spans.joinToString("") { it.surface })
            assertEquals(r.readings[i], spans.joinToString("") { it.reading })
        }
    }

    @Test
    fun spansAlignWordsVariantsAndPassthrough() {
        val text = "きょうはabc😀あめです。"
        val r = converter.convert(text, limit = 10)
        assertAligned(text, r)
        assertEquals(
            listOf(
                Span(0, 3, "きょう", "今日", Span.Kind.WORD), Span(3, 4, "は", "は", Span.Kind.WORD),
                Span(4, 9, "abc😀", "abc😀", Span.Kind.PASSTHROUGH), // the emoji is two UTF-16 units
                Span(9, 11, "あめ", "雨", Span.Kind.WORD), Span(11, 13, "です", "です", Span.Kind.WORD),
                Span(13, 14, "。", "。", Span.Kind.PASSTHROUGH),
            ),
            r.spans[0],
        )
        // A one-word variant keeps every typed span and replaces only that word.
        val candy = r.spans[r.candidates.indexOf("今日はabc😀飴です。")]!!
        assertEquals(r.spans[0]!!.map { it.typedStart to it.typedEnd }, candy.map { it.typedStart to it.typedEnd })
        assertEquals(Span(9, 11, "あめ", "飴", Span.Kind.WORD), candy[3])
        // A whole-reading homophone is one span.
        val kyou = converter.convert("きょう")
        assertAligned("きょう", kyou)
        assertEquals(listOf(Span(0, 3, "きょう", "京", Span.Kind.WORD)), kyou.spans[kyou.candidates.indexOf("京")])
        // Spans carry user text and are redacted.
        assertFalse(r.spans[0].toString().contains("あめ"))
    }

    /** On-device report: with correction on, あし listed し/市/視/死/たし but not 芦/葦/脚. */
    @Test
    fun homophonesOfTheTypedReadingComeBeforeSlipCorrections() {
        val rows = listOf(
            "あし\t1\t1\t3875\t足", "あし\t1\t1\t4349\t脚", "あし\t1\t1\t6010\t葦", "あし\t1\t1\t6318\t芦", "あし\t1\t1\t6659\t蘆",
            "し\t1\t1\t2000\t市", "し\t1\t1\t2100\t死", "し\t1\t1\t2200\t視", "し\t1\t1\t2300\t詩", "し\t1\t1\t2400\t氏",
            "たし\t1\t1\t2500\t足し", "かし\t1\t1\t2600\t菓子", "さし\t1\t1\t2700\t差し", "なし\t1\t1\t2800\t梨",
        ) + (1..10).map { "あし\t1\t1\t${7000 + it}\t葦$it" }
        val dir = temp.root.toPath().resolve("ashi")
        val built = temp.root.toPath().resolve("ashi-out")
        OssDictionaryBuilder.build(MozcInputs.fromDirectory(MozcFixture.write(dir, dictionary00 = rows), includeSuffix = true), built)
        val c = OssConverter(OssResources.load(built))
        val r = c.convert("あし", limit = 20, correction = EditPenalties())
        val exact = listOf("足", "脚", "葦", "芦", "蘆")
        assertEquals(exact, r.candidates.take(5))
        // Every exact reading's alternative ranks before any correction (other readings).
        val firstCorrected = r.readings.indexOfFirst { it != "あし" }
        val lastExact = r.candidates.indices.filter { r.readings[it] == "あし" && r.candidates[it] != "あし" }.max()
        assertTrue("$firstCorrected > $lastExact", firstCorrected == -1 || firstCorrected > lastExact)
        // Corrections are still offered, but bounded.
        assertTrue(r.readings.count { it != "あし" } in 1..4)
        // Without correction, all 14 alternatives of the span reach the list (the old cap was 12), plus best and raw.
        val plain = c.convert("あし", limit = 20)
        assertEquals(16, plain.candidates.size)
    }

    /** A noun and its verb that are only right together: no one-word replacement of the best path spells it. */
    @Test
    fun twoWordReplacementsReachTheListAndLeaveTheBestPathAlone() {
        val rows = listOf("あか\t1\t1\t3000\t赤", "あか\t1\t1\t3200\t垢", "かい\t1\t1\t3000\t貝", "かい\t1\t1\t3200\t櫂", "は\t1\t1\t1000\tは")
        val dir = temp.root.toPath().resolve("pairs")
        val built = temp.root.toPath().resolve("pairs-out")
        OssDictionaryBuilder.build(MozcInputs.fromDirectory(MozcFixture.write(dir, dictionary00 = rows), includeSuffix = true), built)
        val resources = OssResources.load(built)
        val text = "あかはかい"
        val with = OssConverter(resources).convert(text, limit = 20)
        val without = OssConverter(resources, OssLimits(maxPairVariants = 0)).convert(text, limit = 20)
        assertTrue(with.candidates.toString(), "垢は櫂" in with.candidates)
        assertFalse("垢は櫂" in without.candidates)
        assertEquals(without.candidates.first(), with.candidates.first())
        // Everything one-word replacements gave stays listed, in the same relative order.
        assertEquals(without.candidates, with.candidates.filter { it in without.candidates })
        val i = with.candidates.indexOf("垢は櫂")
        assertEquals(text, with.readings[i])
        assertEquals(listOf(Word("あか", "垢"), Word("は", "は"), Word("かい", "櫂")), with.words[i])
        // Bounded: the list is what it was plus at most the configured pairs.
        assertTrue(with.candidates.size <= without.candidates.size + OssLimits().maxPairVariants)
    }

    @Test
    fun correctedSpansAreTypedSpansEvenWhenTheLengthChanges() {
        val fix = EditPenalties()
        // Substitution (て for で): same length.
        val sub = converter.convert("きょうはあめてす", limit = 5, correction = fix)
        assertAligned("きょうはあめてす", sub)
        assertEquals("今日は雨です", sub.candidates.first())
        assertEquals(Span(6, 8, "です", "です", Span.Kind.WORD), sub.spans[0]!!.last())

        // Extra typed kana (ああめ read as あめ): the word covers three typed characters, two reading characters.
        val extra = "きょうはああめです"
        val del = converter.convert(extra, limit = 5, correction = fix)
        assertAligned(extra, del)
        assertEquals("今日は雨です", del.candidates.first())
        assertEquals("きょうはあめです", del.readings.first())
        assertEquals(Span(4, 7, "あめ", "雨", Span.Kind.WORD), del.spans[0]!![2])
        assertEquals(Span(7, 9, "です", "です", Span.Kind.WORD), del.spans[0]!![3])

        // Dropped kana (きょ read as きょう): two typed characters, three reading characters.
        val dropped = "きょはあめです"
        val ins = converter.convert(dropped, limit = 5, correction = fix)
        assertAligned(dropped, ins)
        assertEquals("今日は雨です", ins.candidates.first())
        assertEquals(Span(0, 2, "きょう", "今日", Span.Kind.WORD), ins.spans[0]!![0])
        assertEquals(Span(2, 3, "は", "は", Span.Kind.WORD), ins.spans[0]!![1])
        // Summing reading lengths would put は at 3..4; the typed span is 2..3.
        assertEquals(dropped.length, ins.spans[0]!!.last().typedEnd)

        // Kana runs without any dictionary path stay one literal span (the result is PARTIAL anyway).
        val partial = converter.convert("ぬabcきょう")
        assertEquals(Span(0, 1, "ぬ", "ぬ", Span.Kind.LITERAL), partial.spans[0]!![0])
        assertAligned("ぬabcきょう", partial)
    }

    @Test
    fun nBestAlternativesClaimNoAlignment() {
        val r = nBestConverter().convert("きょうはあめです", limit = 5)
        assertTrue(r.nBestUsed)
        assertEquals(r.candidates.size, r.spans.size)
        // The first entries come from the opt-in n-best list; their alignment is unknown and not invented.
        assertEquals(null, r.spans[r.candidates.indexOf("京は飴です")])
    }

    @Test
    fun learnedWordsWinAndAddedWordsAppear() {
        val learned = UserWords(1, mapOf("あめ" to mapOf("飴" to 500)), emptyMap())
        val r = converter.convert("きょうはあめです", limit = 5, user = learned)
        assertEquals("今日は飴です", r.candidates.first())
        // Without the snapshot (or with an empty one) the dictionary order is back; cached runs are per version.
        assertEquals("今日は雨です", converter.convert("きょうはあめです", limit = 5).candidates.first())
        assertEquals("今日は雨です", converter.convert("きょうはあめです", limit = 5, user = UserWords.EMPTY).candidates.first())

        // A word the dictionary lacks, with the fixture's noun POS id (1): cheap enough to be chosen.
        val added = UserWords(2, emptyMap(), mapOf("あめ" to listOf(AddedWord("AME", 3000, 1))))
        val a = converter.convert("きょうはあめです", limit = 5, user = added)
        assertEquals("今日はAMEです", a.candidates.first())
        assertTrue(Word("あめ", "AME") in a.words.first())
        // Added at a higher cost it is only a variant.
        val late = UserWords(3, emptyMap(), mapOf("あめ" to listOf(AddedWord("AME", 5000, 1))))
        val b = converter.convert("きょうはあめです", limit = 10, user = late)
        assertEquals("今日は雨です", b.candidates.first())
        assertTrue("今日はAMEです" in b.candidates)
    }

    @Test
    fun englishSpellingsCopyTheKatakanaEntryAndRankAfterIt() {
        val src = temp.root.toPath().resolve("mozc")
        val sudachi = temp.root.toPath().resolve("small_lex.csv")
        MozcFixture.writeLines(
            sudachi,
            listOf(
                "IndexForm,LeftId,RightId,Cost,Headword,POS1,POS2,POS3,POS4,POS5,POS6,ReadingForm,NormalizedForm",
                "tv,1,1,5000,TV,名詞,普通名詞,一般,*,*,*,テレビ,",
                "television,1,1,6000,,名詞,普通名詞,一般,*,*,*,テレビ,\"テレビ,名詞,普通名詞,一般,*,*,*,テレビ\"",
                "tV,1,1,4000,tVvVvV,名詞,普通名詞,一般,*,*,*,テレビ,", // odd casing: dropped
                "desu,1,1,100,DEATH,名詞,普通名詞,一般,*,*,*,デス,", // no katakana entry in Mozc: expensive
                "run,1,1,100,Run,動詞,一般,*,*,*,*,ラン,", // not a noun: dropped
            ),
        )
        val english = temp.root.toPath().resolve("english.txt")
        val stats = SudachiEnglish.extract(listOf(sudachi), MozcInputs.fromDirectory(src, includeSuffix = true), english)
        assertEquals(3, stats.written)
        val rows = Files.readAllLines(english, StandardCharsets.UTF_8)
        // テレビ is "てれび 1 1 3000" in the fixture: same POS ids, cost + offset, then + 1 for the next spelling.
        // です (1500, not katakana) only gets a mapped noun id at the default cost or 2000 above it.
        assertEquals(
            listOf(
                "てれび\t1\t1\t${3000 + SudachiEnglish.COST_OFFSET}\tTV",
                "てれび\t1\t1\t${3001 + SudachiEnglish.COST_OFFSET}\ttelevision",
                "です\t1851\t1851\t${maxOf(SudachiEnglish.DEFAULT_COST, 1500 + SudachiEnglish.UNMATCHED_OFFSET)}\tDEATH",
            ),
            rows,
        )
        assertEquals(listOf("a", "b,\"c\"", "d"), SudachiEnglish.csvFields("a,\"b,\"\"c\"\"\",d,e", 3))
        assertEquals(null, SudachiEnglish.csvFields("a,b", 3))
    }

    @Test
    fun preservesExactSurfacesThatUnpatchedUpstreamRewrote() {
        assertEquals("ヴァイオリン", converter.convert("ばいおりん").candidates.first())
        assertEquals("ヴァイオリン", converter.convert("ゔぁいおりん").candidates.first())
        assertEquals("こんにちは", converter.convert("こんにちわ").candidates.first())
        assertEquals("ゔ", converter.convert("ゔ").candidates.first())
        // The tagged row converts as an ordinary entry.
        assertEquals("器用", converter.convert("きよう").candidates.first())
    }

    @Test
    fun longInputConvertsWithAFixedHeadAndVariantsOnlyInTheWindow() {
        val sentence = "きょうはあめです。"
        val text = sentence.repeat(10) // 90 chars, over the 48-char variant window
        val r = converter.convert(text, limit = 10)
        assertEquals(OssStatus.OK, r.status)
        assertEquals("今日は雨です。".repeat(10), r.candidates.first())
        assertTrue(r.headLength > 0)
        assertEquals(text.substring(0, r.headLength), r.headReading)
        assertEquals("今日は雨です。".repeat(r.headLength / sentence.length), r.headSurface)
        // Every converted candidate shares the head; only the raw text does not.
        assertTrue(r.candidates.filter { it != text }.all { it.startsWith(r.headSurface) })
        assertTrue(text in r.candidates)
        // Repeated calls (cached runs) give the same result.
        assertEquals(r.candidates, converter.convert(text, limit = 10).candidates)
    }

    @Test
    fun flickSlipCorrectionIsOptInAndKeepsLiteralResult() {
        // "で" typed as "て" (missing dakuten); at most one edit per dictionary word is allowed.
        val typed = "きょうはあめてす"
        val plain = converter.convert(typed, limit = 5)
        assertEquals(typed, plain.readings.first())
        assertTrue(plain.candidates.none { it == "今日は雨です" })

        val corrected = converter.convert(typed, limit = 5, correction = EditPenalties())
        assertEquals("今日は雨です", corrected.candidates.first())
        assertEquals("きょうはあめです", corrected.readings.first())
        // The literal conversion and the raw text stay available.
        assertTrue(typed in corrected.candidates)
        assertEquals(corrected.candidates.size, corrected.readings.size)
        // Correctly typed input is not changed by the correction lattice.
        assertEquals("今日は雨です", converter.convert("きょうはあめです", limit = 5, correction = EditPenalties()).candidates.first())
    }

    /**
     * Device report (0.1.29): しかいをつとめま led with 司会を務め, the typed ま deleted as an extra kana. The last kana
     * of the input starts the word still being typed; it is never deleted, but becomes correctable once followed.
     */
    @Test
    fun theLastTypedKanaIsNotDeletedAsExtra() {
        val rows = listOf(
            "つとめ\t1\t1\t3000\t務め", "ます\t3\t3\t1500\tます", "ま\t1\t1\t9000\t間",
            "はいしゃ\t1\t1\t3000\t歯医者", "で\t1\t1\t9000\t出", "き\t1\t1\t2000\t木", "きって\t1\t1\t3000\t切手",
        )
        val dir = temp.root.toPath().resolve("last-kana")
        val built = temp.root.toPath().resolve("last-kana-out")
        OssDictionaryBuilder.build(MozcInputs.fromDirectory(MozcFixture.write(dir, dictionary00 = rows), includeSuffix = true), built)
        val c = OssConverter(OssResources.load(built))
        val fix = EditPenalties()
        fun lead(typed: String) = c.convert(typed, limit = 20, correction = fix)

        // A verb and its auxiliary typed kana by kana; dakuten (で of です) and small kana (っ of きって) at the end.
        for (typed in listOf("つとめ", "つとめま", "つとめます", "はいしゃ", "はいしゃで", "はいしゃです", "き", "きっ", "きって")) {
            val r = lead(typed)
            assertEquals(typed, typed, r.readings.first())
            assertFalse(typed, r.correctionLeads)
            assertAligned(typed, r)
        }
        assertEquals("務めます", lead("つとめます").candidates.first())
        assertEquals("切手", lead("きって").candidates.first())

        // A truly extra kana inside the input is still deleted, also when that word ends the input.
        val inside = lead("つとめえます")
        assertTrue(inside.correctionLeads)
        assertEquals("務めます", inside.candidates.first())
        assertEquals("つとめます", inside.readings.first())
        // Tradeoff: an extra kana typed last is kept until the next key; nothing reads つとめえ, so it stays as typed.
        val last = lead("つとめえ")
        assertFalse(last.correctionLeads)
        assertEquals("つとめえ", last.readings.first())
        assertFalse(last.readings.any { it == "つとめ" })

        // Only the end of the whole input is protected: before punctuation or ASCII the word is finished, unchanged.
        for (typed in listOf("つとめま、", "つとめまa")) {
            val r = lead(typed)
            assertTrue(typed, r.correctionLeads)
            assertEquals(typed, "務め" + typed.last(), r.candidates.first())
        }
        // Without correction the reading was always kept.
        assertEquals("つとめま", c.convert("つとめま", limit = 20).readings.first())
    }

    @Test
    fun flickConfusionListsPlausibleSlipsOnly() {
        val subs = FlickConfusion.substitutions('は').toMap()
        assertEquals(FlickConfusion.Kind.MODIFIER, subs['ば'])
        assertEquals(FlickConfusion.Kind.SAME_KEY, subs['ほ'])
        assertEquals(FlickConfusion.Kind.NEIGHBOR_KEY, subs['な'])
        assertFalse('ん' in subs)
        assertEquals(1, dev.astra.ime.oss.zenz.ZenzRanking.editDistance("はしい", "ほしい"))
        assertEquals(1, dev.astra.ime.oss.zenz.ZenzRanking.editDistance("あめ", "めあ"))
    }

    @Test
    fun auditFailsOnAnySurfaceMismatch() {
        val grouped = TreeMap<String, MutableList<Dictionary>>()
        val src = temp.root.toPath().resolve("mozc")
        for (name in (0..9).map { "dictionary%02d.txt".format(it) } + "suffix.txt") {
            MozcSourceParser.readDictionary(src.resolve(name), 4, grouped)
        }
        val resources = OssResources.load(out)
        assertEquals(0, OssDictionaryBuilder.audit(resources, grouped).trie)
        // Expecting the old rewritten form must now be reported as a mismatch, not accepted.
        grouped.getValue("ばいおりん")[0] = Dictionary("ばいおりん", 1, 1, 3000, "バイオリン")
        try {
            OssDictionaryBuilder.audit(resources, grouped)
            fail("surface mismatch must fail the audit")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("Surface mismatch"))
        }
    }

    @Test
    fun optInNBestOnTinyFixtureUsesUpstreamCosts() {
        val r = nBestConverter().convert("きょうはあめです", limit = 5)
        assertEquals(OssStatus.OK, r.status)
        assertTrue(r.nBestUsed)
        // 100+3000 -100+1000 +50+3500 +200+1500 +0 = 9250; 飴 +100, 京 +1000.
        assertEquals(listOf("今日は雨です", "今日は飴です", "京は雨です", "京は飴です", "きょうはあめです"), r.candidates)
        assertEquals(listOf("今日", "京", "きょう"), nBestConverter().convert("きょう").candidates)
    }

    @Test
    fun matchesUpstreamKanaKanjiEngine() {
        val engine = KanaKanjiEngine()
        engine.buildEngineFromResourceDirectory(out.toString(), mode = 0)
        val nBest = nBestConverter()
        for (text in listOf("きょうはあめです", "あめ", "てれび", "ばいおりん")) {
            assertEquals(engine.convert(text).value, converter.convert(text).candidates.first())
            val alternatives = engine.nBestPath(text, 3)
            assertEquals(alternatives, nBest.convert(text, limit = 4).candidates.take(alternatives.size))
        }
    }

    @Test
    fun rawCandidateIsAlwaysPresentAndLimitIsRespected() {
        val r = converter.convert("きょうはあめです", limit = 2)
        assertEquals(listOf("今日は雨です", "きょうはあめです"), r.candidates)
        for (text in listOf("きょう", "ぬ", "テレビ", "abc", "😀", "きょうは😀")) {
            for (limit in 2..20) {
                val c = converter.convert(text, limit).candidates
                assertTrue(text in c)
                assertTrue(c.size <= limit)
                assertEquals(c.size, c.toSet().size)
            }
        }
        for (bad in listOf(1, 21)) {
            try {
                converter.convert("きょう", bad)
                fail("limit $bad must be rejected")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun passesThroughNonKanaAndKeepsUncoveredKana() {
        val mixed = converter.convert("きょうはabc😀あめです")
        assertEquals(OssStatus.OK, mixed.status)
        assertFalse(mixed.nBestUsed)
        // Each run gets one-word variants too (飴 +100, 京 +1000), so later text can pick an earlier word.
        assertEquals(listOf("今日はabc😀雨です", "今日はabc😀飴です", "京はabc😀雨です", "きょうはabc😀あめです"), mixed.candidates)

        assertEquals(listOf("テレビ"), converter.convert("テレビ").candidates)
        assertEquals(OssStatus.OK, converter.convert("ＡＢＣ、。\uD83D").status)

        val partial = converter.convert("ぬabcきょう")
        assertEquals(OssStatus.PARTIAL, partial.status)
        assertEquals(1, partial.unconvertedRuns)
        assertEquals(listOf("ぬabc今日", "ぬabc京", "ぬabcきょう"), partial.candidates)

        val unknown = converter.convert("きょうはぬ")
        assertEquals(OssStatus.PARTIAL, unknown.status)
        assertEquals(listOf("きょうはぬ"), unknown.candidates)
    }

    @Test
    fun boundsInputLengthAndNBest() {
        val long = "あ".repeat(257)
        val tooLong = converter.convert(long)
        assertEquals(OssStatus.INPUT_TOO_LONG, tooLong.status)
        assertEquals(listOf(long), tooLong.candidates)
        assertEquals(OssStatus.EMPTY_INPUT, converter.convert("").status)
        assertEquals(listOf(""), converter.convert("").candidates)

        val resources = OssResources.load(out)
        // Opted in, but the run is longer than the bound: 1-best plus raw.
        val shortNBest = OssConverter(resources, OssLimits(maxNBestRunLength = 3))
        val r = shortNBest.convert("きょうはあめです")
        assertFalse(r.nBestUsed)
        assertEquals(listOf("今日は雨です", "今日は飴です", "京は雨です", "京は飴です", "きょうはあめです"), r.candidates)

        val tinyGraph = OssConverter(resources, OssLimits(maxGraphNodes = 3))
        val g = tinyGraph.convert("きょうはあめです")
        assertEquals(OssStatus.PARTIAL, g.status)
        assertEquals(listOf("きょうはあめです"), g.candidates)
    }

    @Test
    fun backendFailureFallsBackToRawWithStatusOnly() {
        val loaded = OssResources.load(out)
        // A matrix too small for the dictionary IDs makes upstream ConnectionMatrix.getCost throw.
        val broken = OssResources(loaded.yomi, loaded.tango, loaded.tokens, ConnectionMatrix(1, shortArrayOf(0)))
        val failing = OssConverter(broken)
        val r = failing.convert("きょうはあめです")
        assertEquals(OssStatus.BACKEND_ERROR, r.status)
        assertEquals("IllegalArgumentException", r.failure)
        assertEquals(listOf("きょうはあめです"), r.candidates)
        assertFalse(r.toString().contains("きょう"))
        assertEquals(1L, failing.statusCounts().getValue(OssStatus.BACKEND_ERROR))
    }

    @Test
    fun concurrentCallsAgree() {
        val texts = listOf("きょうはあめです", "てれび", "きょうはabc😀あめです", "ぬ")
        val expected = texts.map { converter.convert(it).candidates }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 200).map { i -> pool.submit(Callable { i % texts.size to converter.convert(texts[i % texts.size]).candidates }) }
            for (f in futures) {
                val (index, candidates) = f.get()
                assertEquals(expected[index], candidates)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun loaderRejectsUnexpectedSerializedClassesAndMissingManifest() {
        val tampered = temp.newFolder("tampered").toPath()
        Files.list(out).use { files -> files.forEach { Files.copy(it, tampered.resolve(it.fileName)) } }
        ObjectOutputStream(Files.newOutputStream(tampered.resolve(OssResources.TANGO))).use { it.writeObject(arrayListOf("x")) }
        try {
            OssResources.load(tampered)
            fail("ArrayList must not be deserialized")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains(OssResources.TANGO))
        }

        Files.delete(tampered.resolve(OssResources.MANIFEST))
        try {
            OssResources.load(tampered)
            fail("manifest is required")
        } catch (e: java.io.IOException) {
            // expected: NoSuchFileException
        }
    }

    @Test
    fun buildRejectsInconsistentSources() {
        fun rejects(name: String, write: (Path) -> Unit) {
            val src = temp.root.toPath().resolve("bad-$name")
            write(src)
            try {
                OssDictionaryBuilder.build(MozcInputs.fromDirectory(src, includeSuffix = true), src.resolve("out"))
                fail("$name must be rejected")
            } catch (e: IllegalArgumentException) {
                // MozcFormatException or require(): expected
            } catch (e: IllegalStateException) {
                // upstream ConnectionMatrixParser error(): expected
            }
        }
        rejects("matrix-size") { MozcFixture.write(it, idDef = MozcFixture.ID_DEF + "4 extra") }
        rejects("matrix-count") { MozcFixture.write(it, matrixLines = MozcFixture.matrixLines().dropLast(1)) }
        rejects("matrix-range") { MozcFixture.write(it, matrixLines = MozcFixture.matrixLines().dropLast(1) + "40000") }
        rejects("id-range") { MozcFixture.write(it, dictionary00 = listOf("あめ\t9\t1\t3500\t雨")) }
        rejects("missing-file") { MozcFixture.write(it).also { dir -> Files.delete(dir.resolve("dictionary05.txt")) } }

        rejects("non-empty-out") { src ->
            MozcFixture.write(src)
            Files.createDirectories(src.resolve("out"))
            Files.write(src.resolve("out").resolve("keep.txt"), byteArrayOf(1))
        }
        val kept = temp.root.toPath().resolve("bad-non-empty-out").resolve("out")
        Files.list(kept).use { assertEquals(listOf("keep.txt"), it.map { p -> p.fileName.toString() }.toList()) }
    }

    @Test
    fun cliBuildsAndDemos() {
        val src = MozcFixture.write(temp.root.toPath().resolve("cli-src"))
        val cliOut = temp.root.toPath().resolve("cli-out")
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val print = PrintStream(stdout, true, "UTF-8")
        val err = PrintStream(stderr, true, "UTF-8")
        assertEquals(0, runCli(listOf("build", "--mozc-dir", src.toString(), "--out", cliOut.toString(), "--no-suffix"), print, err))
        assertTrue(stdout.toString("UTF-8").contains("records=13"))

        val fixtures = temp.root.toPath().resolve("feedback.tsv")
        MozcFixture.writeLines(
            fixtures,
            listOf(
                "case-1\tclean\tきょうはあめです\tきょうはあめです\t今日は雨です\t",
                // '|' alternatives: the second one matches.
                "case-2\tclean\tきょう\tきょう\t京|今日\t",
                "case-3\tclean\tきょう\tきょう\t京\t",
            ),
        )
        stdout.reset()
        assertEquals(0, runCli(listOf("demo", "--resources", cliOut.toString(), "--fixtures", fixtures.toString(), "--text", "てれび"), print, err))
        val text = stdout.toString("UTF-8")
        assertTrue(text, text.contains("てれび -> テレビ | てれび [OK nBest=false]"))
        assertTrue(text, text.contains("case-2\ttop1=true\ttop5=true"))
        assertTrue(text, text.contains("case-3\ttop1=false\ttop5=true"))
        assertTrue(text, text.contains("feedback.tsv: fixtures=3 top1=2 top5=3 (correct-reading conversion, not typo restoration"))

        MozcFixture.writeLines(fixtures, listOf("case-4\tclean\tきょう\tきょう\t今日|\t"))
        assertEquals(2, runCli(listOf("demo", "--resources", cliOut.toString(), "--fixtures", fixtures.toString()), print, err))

        stdout.reset()
        val bench = listOf("bench", "--resources", cliOut.toString(), "--synthetic", "5", "--warmup", "1", "--iterations", "2")
        assertEquals(0, runCli(bench, print, err))
        assertTrue(stdout.toString("UTF-8").contains("synthetic: inputs=5 calls=10"))
        assertEquals(2, runCli(listOf("nope"), print, err))
    }

    @Test
    fun pinnedRevisionMatchesSourceLock() {
        // Gradle runs tests from android/oss-converter.
        val lock = Paths.get("../../third_party/kotlin-kana-kanji-converter/source-lock.json")
        val json = String(Files.readAllBytes(lock), StandardCharsets.UTF_8)
        val revision = Regex("\"revision\"\\s*:\\s*\"([0-9a-f]{40})\"").find(json)!!.groupValues[1]
        assertEquals(revision, OssDictionaryBuilder.UPSTREAM_REVISION)

        // The compiled TokenArray must be exactly the recorded patched bytes.
        val patched = Regex("\"patchedSha256\"\\s*:\\s*\"([0-9a-f]{64})\"").find(json)!!.groupValues[1]
        val tokenArray = Paths.get("../../third_party/kotlin-kana-kanji-converter/upstream/src/main/kotlin/dictionary/TokenArray.kt")
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tokenArray))
            .joinToString("") { "%02x".format(it) }
        assertEquals(patched, actual)
    }
}
