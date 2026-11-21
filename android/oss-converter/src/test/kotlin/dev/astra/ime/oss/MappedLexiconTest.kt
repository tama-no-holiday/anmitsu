package dev.astra.ime.oss

import com.kazumaproject.bitset.SuccinctBitVector
import com.kazumaproject.dictionary.models.Dictionary
import com.kazumaproject.graph.GraphBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import java.util.Random
import java.util.TreeMap

/** [MappedLexicon] against the upstream objects it was packed from: bit vectors, every lookup, lattices, candidates. */
class MappedLexiconTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: Path
    private lateinit var grouped: TreeMap<String, MutableList<Dictionary>>

    @Before
    fun build() {
        val inputs = MozcInputs.fromDirectory(MozcFixture.write(temp.root.toPath().resolve("mozc"), dictionary00 = RICH), includeSuffix = true)
        dir = temp.root.toPath().resolve("out")
        assertTrue(OssDictionaryBuilder.build(inputs, dir).verified)
        MappedLexicon.pack(dir)
        val idCount = MozcSourceParser.readIdDef(inputs.idDef)
        grouped = TreeMap()
        for (p in inputs.dictionaries + listOfNotNull(inputs.suffix)) MozcSourceParser.readDictionary(p, idCount, grouped)
    }

    @Test
    fun bitsAnswerExactlyWhatSuccinctBitVectorAndBitSetAnswer() {
        val random = Random(7)
        val sets = ArrayList<BitSet>()
        for (n in listOf(0, 1, 63, 64, 65, 511, 512, 513, 1000, 4097)) {
            for (density in listOf(0.0, 0.03, 0.5, 0.97, 1.0)) {
                sets += BitSet(n).also { b -> for (i in 0 until n) if (random.nextDouble() < density) b.set(i) }
            }
        }
        // Deserialized BitSets have size() trimmed to the words in use, unlike constructed ones.
        sets += sets.toList().map { roundTrip(it) }
        sets += BitSet().also { it.set(0); it.set(1); it.set(3) } // LOUDS-like start "1101"
        for (bits in sets) {
            val reference = SuccinctBitVector(bits)
            val mapped = MappedBits(MappedBits.encode(bits))
            val size = bits.size()
            assertEquals(size, mapped.size)
            for (i in -2..size + 2) {
                assertEquals("rank1 $i/$size", reference.rank1(i), mapped.rank1(i))
                assertEquals("rank0 $i/$size", reference.rank0(i), mapped.rank0(i))
                if (i >= 0) assertEquals("get $i/$size", bits[i], mapped[i])
            }
            for (k in -1..size + 2) {
                assertEquals("select1 $k/$size", reference.select1(k), mapped.select1(k))
                assertEquals("select0 $k/$size", reference.select0(k), mapped.select0(k))
            }
            assertThrows(IndexOutOfBoundsException::class.java) { mapped[-1] }
        }
    }

    @Test
    fun everyLookupMatchesUpstream() {
        val upstream = OssResources.load(dir)
        val reference = UpstreamLexicon(upstream)
        val mapped = MappedDictionary.open(dir).lexicon
        val maxTermId = upstream.yomi.termIdsSave.max()

        val surfaces = HashSet<Int>()
        for (reading in grouped.keys) {
            val termId = reference.termId(reading)
            assertTrue(reading, termId >= 1)
            assertEquals(reading, termId, mapped.termId(reading))
            assertEquals(reading, entries(reference, termId), entries(mapped, termId))
            mapped.forEachEntry(termId) { _, _, n -> if (n >= 0) surfaces += n }
        }
        assertTrue(surfaces.isNotEmpty())
        for (n in surfaces) assertEquals("node $n", reference.surface(n), mapped.surface(n))

        // Prefix search and term IDs on readings, their suffixes and strings that are not readings.
        val probes = (grouped.keys.flatMap { r -> r.indices.map { r.substring(it) } } + PROBES + listOf("", "ん", "きょうはあめでs", "ゔぁ", "abcd"))
        for (s in probes.toSet()) {
            assertEquals(s, reference.prefixes(s), mapped.prefixes(s))
            assertEquals(s, reference.termId(s), mapped.termId(s))
        }

        // Term IDs outside the dictionary: same entries or the same exception class.
        for (termId in listOf(-1, 0, maxTermId + 1, maxTermId + 50)) {
            val a = runCatching { entries(reference, termId) }
            val b = runCatching { entries(mapped, termId) }
            assertEquals("$termId", a.getOrNull(), b.getOrNull())
            assertEquals("$termId", a.exceptionOrNull()?.javaClass, b.exceptionOrNull()?.javaClass)
        }
        for (p in 0 until upstream.tokens.leftIds.size) {
            assertEquals(reference.leftId(p), mapped.leftId(p))
            assertEquals(reference.rightId(p), mapped.rightId(p))
        }
    }

    @Test
    fun latticesEqualUpstreamGraphBuilderAndCandidatesAreIdentical() {
        val upstream = OssResources.load(dir)
        val dictionary = MappedDictionary.open(dir)
        val graphBuilder = GraphBuilder()
        for (text in PROBES.filter { s -> s.all(::isConvertible) }) {
            val expected = graphBuilder.constructGraph(text, upstream.yomi, upstream.tango, upstream.tokens)
            assertEquals(text, expected, UpstreamLexicon(upstream).lattice(text))
            assertEquals(text, expected, dictionary.lexicon.lattice(text))
        }
        val user = UserWords(1, mapOf("あめ" to mapOf("飴" to 3000)), mapOf("こーでっくす" to listOf(AddedWord("Codex", 3000, 1))))
        val old = OssConverter(upstream, OssLimits(maxNBestRunLength = 12))
        val new = OssConverter(dictionary, OssLimits(maxNBestRunLength = 12))
        for (text in PROBES + "こーでっくすできょうはあめ") {
            for (correction in listOf(null, EditPenalties())) {
                for (u in listOf(null, user)) {
                    val a = old.convert(text, limit = 8, correction = correction, user = u)
                    val b = new.convert(text, limit = 8, correction = correction, user = u)
                    assertEquals(text, a.status, b.status)
                    assertEquals(text, a.failure, b.failure)
                    assertEquals(text, a.candidates, b.candidates)
                    assertEquals(text, a.readings, b.readings)
                    assertEquals(text, a.words, b.words)
                    assertEquals(text, a.spans, b.spans)
                    assertEquals(text, a.nBestUsed, b.nBestUsed)
                }
            }
        }
    }

    @Test
    fun malformedFilesAreRejected() {
        val bytes = Files.readAllBytes(dir.resolve(MappedLexicon.FILE))
        fun open(b: ByteArray) = MappedLexicon.open(ByteBuffer.wrap(b))
        open(bytes) // intact
        assertThrows(IllegalStateException::class.java) { open(bytes.copyOf(8)) }
        assertThrows(IllegalStateException::class.java) { open(bytes.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertThrows(IllegalStateException::class.java) { open(bytes.copyOf(bytes.size - 1)) }
        // Version, and a section offset pointing past the end.
        assertThrows(IllegalStateException::class.java) { open(bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 2) }) }
        assertThrows(IllegalStateException::class.java) {
            open(bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(16, bytes.size.toLong()) })
        }
        // A bit vector header that disagrees with its length.
        val first = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(16).toInt()
        assertThrows(IllegalStateException::class.java) {
            open(bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(first, 64L * 1_000_000) })
        }
        // A POS ID outside the connection matrix.
        val small = Files.newInputStream(dir.resolve(OssResources.CONNECTION)).use { it.readBytes() }.copyOf(2)
        assertThrows(IllegalStateException::class.java) {
            MappedDictionary.open(MappedLexicon.map(dir.resolve(MappedLexicon.FILE)), ByteArrayInputStream(small))
        }
        // A manifest for another resource format.
        assertThrows(IllegalStateException::class.java) {
            MappedDictionary.open(
                MappedLexicon.map(dir.resolve(MappedLexicon.FILE)),
                Files.newInputStream(dir.resolve(OssResources.CONNECTION)),
                ByteArrayInputStream("format=2\n".toByteArray()),
            )
        }
    }

    @Test
    fun packRefusesToOverwriteAndTheAuditCatchesADifferentSource() {
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { MappedLexicon.pack(dir) }
        // Another build's lexicon against these upstream files fails the audit.
        val other = MozcFixture.write(temp.root.toPath().resolve("mozc2"), dictionary00 = RICH.map { it.replace("\t3000\t今日", "\t9000\t今日") })
        val otherDir = temp.root.toPath().resolve("out2")
        assertTrue(OssDictionaryBuilder.build(MozcInputs.fromDirectory(other, includeSuffix = true), otherDir).verified)
        MappedLexicon.pack(otherDir)
        val wrong = MappedLexicon.open(MappedLexicon.map(otherDir.resolve(MappedLexicon.FILE)))
        assertThrows(IllegalStateException::class.java) { MappedLexicon.audit(OssResources.load(dir), wrong) }
    }

    private fun entries(lexicon: Lexicon, termId: Int): List<Triple<Int, Int, Int>> =
        ArrayList<Triple<Int, Int, Int>>().also { out -> lexicon.forEachEntry(termId) { p, c, n -> out += Triple(p, c, n) } }

    private fun roundTrip(bits: BitSet): BitSet {
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(bits) } }.toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as BitSet }
    }

    companion object {
        private val RICH = MozcFixture.DICTIONARY00 + listOf(
            "き\t1\t1\t4200\t木",
            "きょうと\t1\t1\t3200\t京都",
            "きょう\t1\t1\t4500\tきょう",
            "きょう\t1\t1\t4600\tキョウ",
            "あい\t1\t1\t2000\tAI",
            "てぃーしゃつ\t1\t1\t3000\tTシャツ",
            "えっくす\t1\t1\t3000\tＸ",
            "ほし\t1\t1\t3000\t★",
            "にこ\t1\t1\t3000\t😊",
            // Two adjacent surrogate pairs: getLetter reverses chars one by one, not code points.
            "にこにこ\t1\t1\t3000\t😊😀",
            "いしだ\t1\t1\t3000\t𠀋𡈽田",
            "abc\t1\t1\t3000\tABC",
            "ゔぇ\t1\t1\t3000\tヴェ",
            "ぱぱ\t1\t1\t3000\tパパ",
            "の\t2\t3\t-50\tの",
            "な\t3\t2\t2000\tな",
            "ぬ\t2\t3\t-32768\t奴",
            "ね\t3\t2\t32767\t根",
        )

        private val PROBES = listOf(
            "きょうはあめです", "きょうと", "き", "てぃーしゃつ", "あい", "えっくす", "ばいおりん", "ゔぁいおりん",
            "こんにちわ", "ゔ", "てれび", "にこ", "ほし", "のな", "ぬね", "きよう", "さん", "abc", "ゔぇ", "ぱぱ",
            "きょうはabc😀あめです", "きようはあめでs", "にこにこ", "いしだ",
        )
    }
}
