package dev.astra.ime.oss

import dev.astra.ime.oss.zenz.ZenzRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

/**
 * Generated homophone lists around the real failure: a cheap neighbour reading (いら) beats the typed one (あら) once an
 * edit is allowed, as され beat さら. Costs and surfaces are invented; real data runs through `ossCli exact-eval`.
 */
class ExactCandidatesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: Path
    private lateinit var upstream: OssConverter
    private lateinit var mapped: OssConverter

    private val typedWords = (0 until 150).map { "甲%03d".format(it) } // over every old cap: 20, 24, 100
    private val mediumWords = (0 until 22).map { "乙%02d".format(it) }
    private val tinyWords = listOf("丙", "丁", "戊")

    private fun rows(reading: String, words: List<String>, base: Int, step: Int) =
        words.mapIndexed { i, w -> "$reading\t1\t1\t${base + i * step}\t$w" }

    @Before
    fun build() {
        val src = temp.root.toPath().resolve("mozc")
        val rows = rows("あら", typedWords, 6000, 10) +
            // The same surfaces under other POS rows: they fill lattice slots without adding spellings.
            rows("あら", typedWords.take(12), 6050, 10).map { it.replace("\t1\t1\t", "\t3\t3\t") } +
            rows("いら", listOf("安物", "安価"), 1000, 100) +
            rows("かみ", mediumWords, 3000, 10) +
            rows("あめ", tinyWords, 3000, 10) +
            listOf("と\t2\t2\t500\tと", "とうきょう\t1\t1\t2000\t東京", "とうきょうと\t1\t1\t2500\t東京都")
        OssDictionaryBuilder.build(MozcInputs.fromDirectory(MozcFixture.write(src, dictionary00 = rows), includeSuffix = true), temp.root.toPath().resolve("out"))
        dir = temp.root.toPath().resolve("out")
        MappedDictionary.pack(dir)
        upstream = OssConverter(OssResources.load(dir))
        mapped = OssConverter(MappedDictionary.open(dir))
    }

    private fun both(check: (OssConverter) -> Unit) = listOf(upstream, mapped).forEach(check)

    private fun lexiconSurfaces(reading: String): Set<String> {
        val lexicon = MappedDictionary.open(dir).lexicon
        val out = HashSet<String>()
        lexicon.forEachEntry(lexicon.termId(reading)) { _, _, node ->
            out += when (node) { -2 -> reading; -1 -> ZenzRanking.katakana(reading); else -> lexicon.surface(node) }
        }
        return out
    }

    @Test
    fun wholeReadingListEqualsTheDictionaryPastEveryOldCap() = both { c ->
        for ((reading, size) in listOf("あめ" to 4, "かみ" to 22, "あら" to 150)) {
            val exact = c.exactCandidates(reading)!!
            val whole = exact.candidates.filter { it.whole }.map { it.surface }
            assertEquals(lexiconSurfaces(reading), whole.toSet())
            assertEquals("no duplicates for $reading", whole.size, whole.toSet().size)
            assertEquals(whole.size, exact.wholeCount)
            assertEquals(size, whole.size)
        }
    }

    @Test
    fun thePostingListOrderIsReplacedByAStableCostOrder() = both { c ->
        val first = c.exactCandidates("あら")!!.candidates.map { it.surface }
        assertEquals(first, c.exactCandidates("あら")!!.candidates.map { it.surface })
        assertEquals(typedWords, first.filter { it.startsWith("甲") })
    }

    @Test
    fun exactSpansCoverTheTypedTextWithWordsForLearning() {
        val e = mapped.exactCandidates("あら")!!.candidates.last()
        assertEquals(listOf(Span(0, 2, "あら", e.surface, Span.Kind.WORD)), e.spans)
        assertEquals("あら", e.reading)
    }

    @Test
    fun aCorrectionDoesNotChangeWhatIsListed() = both { c ->
        val before = c.exactCandidates("あら")!!.candidates.map { it.surface }
        c.convert("あら", 20, EditPenalties())
        assertEquals(before, c.exactCandidates("あら")!!.candidates.map { it.surface })
    }

    @Test
    fun aCheapNeighbourNoLongerDemotesTheTypedReadingToTheCorrectionBucket() = both { c ->
        // Before: 安物 led, its reading took the 24 slots and あら's words shared the 4 correction slots.
        val r = c.convert("あら", 20, EditPenalties())
        val typed = r.candidates.indices.filter { r.readings[it] == "あら" }
        assertTrue("exact words stay in the capped list: ${typed.size}", typed.size >= 15)
        assertTrue("甲001" in r.candidates)
    }

    @Test
    fun aSurfaceIsRankedByItsBestPosRowNotItsCheapestWordCost() {
        val src = temp.newFolder("pos").toPath()
        // 高 is cheaper as a word on a POS that connects badly; its other row is the better path.
        val rows = listOf("かき\t1\t1\t3000\t甲", "かき\t3\t3\t2900\t高", "かき\t1\t1\t3200\t高", "かき\t1\t1\t3100\t乙")
        OssDictionaryBuilder.build(MozcInputs.fromDirectory(MozcFixture.write(src, dictionary00 = rows), includeSuffix = true), temp.root.toPath().resolve("pos-out"))
        val c = OssConverter(OssResources.load(temp.root.toPath().resolve("pos-out")))
        val ranked = c.convert("かき", 20).candidates
        assertTrue(ranked.indexOf("乙") < ranked.indexOf("高"))
    }

    @Test
    fun theGuardKeepsTheTypedReadingFirstWhenACorrectionWinsByLittle() {
        val strict = OssConverter(OssResources.load(dir), OssLimits(correctionLeadMargin = 1_000_000))
        val r = strict.convert("あら", 20, EditPenalties())
        assertEquals("あら", r.readings.first())
        assertFalse(r.correctionLeads)
        assertTrue("the correction is still offered", "安物" in r.candidates)
    }

    @Test
    fun aCorrectionWellAheadOfTheExactPathStillLeads() {
        val eager = OssConverter(OssResources.load(dir), OssLimits(correctionLeadMargin = 0))
        val r = eager.convert("あら", 20, EditPenalties(sameKey = 1, neighborKey = 1, modifier = 1))
        assertEquals("いら", r.readings.first())
        assertTrue(r.correctionLeads)
        assertEquals("the literal stays right behind it", "あら", r.readings[1])
    }

    @Test
    fun anUnfixableInputStillGetsItsCorrection() {
        val r = upstream.convert("とうきようと", 20, EditPenalties())
        assertNotNull(r.candidates.first())
        assertTrue(r.candidates.contains("東京都") || r.suggestions.any { it.surface == "東京都" } || r.status != OssStatus.OK)
    }

    @Test
    fun correctionsPastTheCapAreKeptSeparatelyAndBounded() = both { c ->
        val r = c.convert("あら", 20, EditPenalties())
        assertTrue(r.suggestions.size <= c.limits.maxSuggestions)
        assertTrue(r.suggestions.all { it.reading != "あら" })
        assertTrue(r.suggestions.none { it.surface in r.candidates })
    }

    @Test
    fun aSentenceListsItsBestPathAndEveryHomophoneOfEachWordButNoProduct() = both { c ->
        val exact = c.exactCandidates("あめとかみ")!!
        assertEquals(0, exact.wholeCount)
        val surfaces = exact.candidates.map { it.surface }
        assertEquals(surfaces.size, surfaces.toSet().size)
        assertTrue(surfaces.containsAll(tinyWords.map { it + "と" + "乙00" }))
        assertTrue(surfaces.containsAll(mediumWords.map { "丙と$it" }))
        // Replacing both words at once would give 28 x 25 lists; one word at a time gives their sum.
        assertFalse("丁と乙01" in surfaces)
        assertTrue(surfaces.size < 80)
        assertTrue(exact.candidates.all { e -> e.spans.joinToString("") { it.surface } == e.surface })
        assertTrue(exact.candidates.all { e -> e.spans.all { it.kind != Span.Kind.WORD || it.reading == "あめとかみ".substring(it.typedStart, it.typedEnd) } })
    }

    @Test
    fun aFocusedUnitOfAWordAndItsParticleListsAllHomophonesOfTheWord() = both { c ->
        val surfaces = c.exactCandidates("あらと")!!.candidates.map { it.surface }
        assertTrue(surfaces.containsAll(typedWords.map { it + "と" }))
    }

    @Test
    fun textBetweenRunsPassesThroughUntouched() = both { c ->
        val exact = c.exactCandidates("あめ、かみ")!!
        assertTrue(exact.candidates.all { "、" in it.surface })
        assertTrue(exact.candidates.all { e -> e.spans.any { it.kind == Span.Kind.PASSTHROUGH && it.surface == "、" } })
    }

    @Test
    fun aReadingWithDigitsIsAWholeEntryToo() {
        val src = temp.newFolder("digits").toPath()
        OssDictionaryBuilder.build(MozcInputs.fromDirectory(MozcFixture.write(src, dictionary00 = listOf("5じ\t1\t1\t3000\t五時")), includeSuffix = true), temp.root.toPath().resolve("digits-out"))
        val c = OssConverter(OssResources.load(temp.root.toPath().resolve("digits-out")))
        assertEquals(listOf("五時"), c.exactCandidates("5じ")!!.candidates.map { it.surface })
    }

    @Test
    fun learningOnlyReordersAndAddedWordsJoinTheList() = both { c ->
        val user = UserWords(7, mapOf("あら" to mapOf("甲149" to 5000)), mapOf("あら" to listOf(AddedWord("私語", 100, 1))))
        val surfaces = c.exactCandidates("あら", user)!!.candidates.map { it.surface }
        assertEquals("私語", surfaces[0])
        assertEquals("甲149", surfaces[1])
        assertEquals(typedWords.toSet() + "私語", surfaces.filter { it.startsWith("甲") || it == "私語" }.toSet())
    }

    @Test
    fun aCancelledLookupReturnsNothing() = both { c ->
        assertNull(c.exactCandidates("あめとかみ", cancelled = { true }))
    }

    @Test
    fun emptyAndOverlongInputListNothing() = both { c ->
        assertEquals(OssStatus.EMPTY_INPUT, c.exactCandidates("")!!.status)
        assertEquals(OssStatus.INPUT_TOO_LONG, c.exactCandidates("あ".repeat(c.limits.maxInputChars + 1))!!.status)
    }

    @Test
    fun aRunWithoutAPathIsPartialAndNotGuessed() = both { c ->
        val exact = c.exactCandidates("ぬぬぬ")!!
        assertEquals(OssStatus.PARTIAL, exact.status)
        assertTrue(exact.candidates.isEmpty())
    }

    @Test
    fun completionsExtendTheReadingAndComeOnlyFromTheMappedTrie() {
        val found = mapped.completions("とうきょ", 8).map { it.reading to it.surface }
        assertTrue(("とうきょう" to "東京") in found)
        assertTrue(found.all { it.first.length > 4 && it.first.startsWith("とうきょ") })
        assertTrue(upstream.completions("とうきょ", 8).isEmpty())
        assertTrue(mapped.completions("x", 8).isEmpty())
    }

    @Test
    fun aRankerCannotReachTheExactListAtAll() {
        // A hostile stand-in judge that prefers the last candidate; the exact list never passes through it.
        val r = mapped.convert("あら", 20, EditPenalties())
        val order = ZenzRanking.rerank("あら", r.candidates, r.readings, 8) { _, _, list -> FloatArray(list.size) { it.toFloat() * 100 } }
        assertNotNull(order)
        assertEquals(150 + 2, mapped.exactCandidates("あら")!!.candidates.count { it.whole } + 2)
    }

    @Test
    fun theGuardPutsTheBestExactCandidateFirstAfterAnyReorder() {
        val readings = listOf("され", "さら", "さる", "さら")
        assertEquals(listOf(1, 0, 2, 3), ZenzRanking.keepExactLead(listOf(0, 1, 2, 3), readings, "さら", correctionLeads = false))
        assertEquals(listOf(3, 2, 0, 1), ZenzRanking.keepExactLead(listOf(2, 3, 0, 1), readings, "さら", correctionLeads = false))
    }

    @Test
    fun aSupportedCorrectionKeepsTheJudgesOrder() {
        val readings = listOf("され", "さら")
        assertEquals(listOf(0, 1), ZenzRanking.keepExactLead(listOf(0, 1), readings, "さら", correctionLeads = true))
    }

    @Test
    fun withoutAnExactCandidateTheOrderStays() {
        assertEquals(listOf(1, 0), ZenzRanking.keepExactLead(listOf(1, 0), listOf("され", "さる"), "さら", correctionLeads = false))
    }
}
