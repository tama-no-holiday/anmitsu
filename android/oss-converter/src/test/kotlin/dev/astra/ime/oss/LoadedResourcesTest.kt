package dev.astra.ime.oss

import com.kazumaproject.Louds.LOUDS
import com.kazumaproject.Louds.with_term_id.LOUDSWithTermId
import com.kazumaproject.dictionary.TokenArray
import com.kazumaproject.dictionary.models.Dictionary
import com.kazumaproject.mozc.ConnectionMatrixIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ObjectInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import java.util.TreeMap

/** Compact [OssResources.load] against the boxed loading it replaced, plus [LoadedList] read-only semantics. */
class LoadedResourcesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: Path
    private lateinit var grouped: TreeMap<String, MutableList<Dictionary>>

    @Before
    fun build() {
        val inputs = MozcInputs.fromDirectory(MozcFixture.write(temp.root.toPath().resolve("mozc"), dictionary00 = RICH), includeSuffix = true)
        dir = temp.root.toPath().resolve("out")
        assertTrue(OssDictionaryBuilder.build(inputs, dir).verified)
        val idCount = MozcSourceParser.readIdDef(inputs.idDef)
        grouped = TreeMap()
        for (p in inputs.dictionaries + listOfNotNull(inputs.suffix)) MozcSourceParser.readDictionary(p, idCount, grouped)
    }

    /** The loader before compact lists: same files and field order, boxed `toMutableList()`/`toList()` copies. */
    private fun loadBoxed(dir: Path): OssResources {
        fun <T> read(name: String, block: ObjectInputStream.() -> T): T =
            ObjectInputStream(Files.newInputStream(dir.resolve(name)).buffered()).use { it.block() }
        val yomi = read(OssResources.YOMI) {
            val lbs = readObject() as BitSet
            val isLeaf = readObject() as BitSet
            val labels = readObject() as CharArray
            LOUDSWithTermId(lbs, labels.toMutableList(), isLeaf, readObject() as IntArray)
        }
        val tango = read(OssResources.TANGO) {
            val lbs = readObject() as BitSet
            val isLeaf = readObject() as BitSet
            LOUDS(lbs, (readObject() as CharArray).toMutableList(), isLeaf)
        }
        val tokens = read(OssResources.TOKEN) { TokenArray().also { it.readExternalNotCompressStrict(this) } }
        read(OssResources.POS_TABLE) {
            tokens.leftIds = (readObject() as ShortArray).toList()
            tokens.rightIds = (readObject() as ShortArray).toList()
        }
        val matrix = Files.newInputStream(dir.resolve(OssResources.CONNECTION)).use { ConnectionMatrixIO.read(it, OssResources.CONNECTION) }
        return OssResources(yomi, tango, tokens, matrix)
    }

    @Test
    fun compactLoadMatchesBoxedLoadForEveryValueLookupAndCandidate() {
        val old = loadBoxed(dir)
        val new = OssResources.load(dir)
        assertTrue(new.yomi.labels is LoadedCharList && new.tango.labels is LoadedCharList)
        assertTrue(new.tokens.leftIds is LoadedShortList && new.tokens.rightIds is LoadedShortList)

        assertEquals(old.yomi.labels, new.yomi.labels)
        assertEquals(old.tango.labels, new.tango.labels)
        assertArrayEquals(old.yomi.termIdsSave, new.yomi.termIdsSave)
        assertEquals(old.tokens.leftIds, new.tokens.leftIds)
        assertEquals(old.tokens.rightIds, new.tokens.rightIds)
        assertEquals(old.tokens.entryCount, new.tokens.entryCount)
        assertTrue(old.matrix == new.matrix)
        sameOutOfRange(old.yomi.labels, new.yomi.labels)
        sameOutOfRange(old.tango.labels, new.tango.labels)
        sameOutOfRange(old.tokens.leftIds, new.tokens.leftIds)

        // Every reading's term ID and token entries (POS index, cost incl. negative, node ID incl. -1/-2 sentinels).
        var entries = 0
        for ((reading, list) in grouped) {
            val termId = new.yomi.getTermId(new.yomi.getNodeIndex(reading))
            assertEquals(reading, old.yomi.getTermId(old.yomi.getNodeIndex(reading)), termId)
            val tokens = new.tokens.getListDictionaryByYomiTermId(termId)
            assertEquals(reading, old.tokens.getListDictionaryByYomiTermId(termId), tokens)
            assertEquals(reading, list.map { it.cost }, tokens.map { it.wordCost })
            for (t in tokens) {
                val pos = t.posTableIndex.toInt()
                assertEquals(old.tokens.leftIds[pos] to old.tokens.rightIds[pos], new.tokens.leftIds[pos] to new.tokens.rightIds[pos])
                if (t.nodeId >= 0) assertEquals(old.tango.getLetter(t.nodeId), new.tango.getLetter(t.nodeId))
            }
            assertEquals(old.yomi.commonPrefixSearch(reading), new.yomi.commonPrefixSearch(reading))
            entries += tokens.size
        }
        assertEquals(new.tokens.entryCount, entries)
        val costs = grouped.values.flatten().map { it.cost }
        assertTrue(Short.MIN_VALUE in costs && Short.MAX_VALUE in costs && (-50).toShort() in costs)
        val nodeIds = grouped.keys.flatMap { r -> new.tokens.getListDictionaryByYomiTermId(new.yomi.getTermId(new.yomi.getNodeIndex(r))) }.map { it.nodeId }
        assertTrue(-2 in nodeIds && -1 in nodeIds && nodeIds.any { it >= 0 })

        // Term IDs outside the dictionary behave the same (same result or same exception class).
        val maxTermId = new.yomi.termIdsSave.max()
        for (termId in listOf(-1, maxTermId + 1, maxTermId + 100)) {
            val a = runCatching { old.tokens.getListDictionaryByYomiTermId(termId) }
            val b = runCatching { new.tokens.getListDictionaryByYomiTermId(termId) }
            assertEquals("$termId", a.getOrNull(), b.getOrNull())
            assertEquals("$termId", a.exceptionOrNull()?.javaClass, b.exceptionOrNull()?.javaClass)
        }

        val oldConverter = OssConverter(old)
        val newConverter = OssConverter(new)
        for (text in PROBES) {
            for (correction in listOf(null, EditPenalties())) {
                val a = oldConverter.convert(text, limit = 8, correction = correction)
                val b = newConverter.convert(text, limit = 8, correction = correction)
                assertEquals(text, a.status, b.status)
                assertEquals(text, a.failure, b.failure)
                assertEquals(text, a.candidates, b.candidates)
                assertEquals(text, a.readings, b.readings)
                assertEquals(text, a.words, b.words)
                assertEquals(text, a.spans, b.spans)
            }
        }
    }

    @Test
    fun loadedResourcesAreIndependentOfFilesAndOtherLoads() {
        val first = OssResources.load(dir)
        val expected = PROBES.map { OssConverter(first).convert(it, limit = 8).candidates }
        val second = OssResources.load(dir)
        assertEquals(first.tokens.leftIds, second.tokens.leftIds)

        // Delete the files, then load a build with different costs: already loaded resources keep their values.
        Files.list(dir).use { files -> files.forEach { deleteLeaked(it) } }
        val other = MozcFixture.write(temp.root.toPath().resolve("mozc2"), dictionary00 = MozcFixture.DICTIONARY00.map { it.replace("\t3000\t今日", "\t9000\t今日") })
        val otherDir = temp.root.toPath().resolve("out2")
        assertTrue(OssDictionaryBuilder.build(MozcInputs.fromDirectory(other, includeSuffix = true), otherDir).verified)
        val rebuilt = OssResources.load(otherDir)
        assertNotEquals(expected, PROBES.map { OssConverter(rebuilt).convert(it, limit = 8).candidates })

        assertEquals(expected, PROBES.map { OssConverter(first).convert(it, limit = 8).candidates })
        assertEquals(expected, PROBES.map { OssConverter(second).convert(it, limit = 8).candidates })
    }

    @Test
    fun loadedTokenColumnsRejectMutation() {
        val tokens = OssResources.load(dir).tokens
        val left = tokens.leftIds as MutableList<Short>
        val before = left.toList()
        assertThrows(UnsupportedOperationException::class.java) { left[0] = 9 }
        assertEquals(before, tokens.leftIds)
    }

    @Test
    fun viewsMatchBoxedListsIncludingExtremeAndNonBmpValues() {
        // Index 8 is a combining voiced mark (NFD う+U+3099), 12..13 a surrogate pair, 14 U+FFFF.
        val chars = ("\u0000 aZきゃゔう" + Char(0x3099) + "ヴァ漢😊" + Char(0xFFFF)).toCharArray()
        val shorts = shortArrayOf(Short.MIN_VALUE, -32767, -129, -128, -1, 0, 1, 127, 128, Short.MAX_VALUE)
        val ints = intArrayOf(Int.MIN_VALUE, -2, -1, 0, 1, 65_536, Int.MAX_VALUE)
        assertView(chars.toMutableList(), LoadedCharList(chars))
        assertView(shorts.toMutableList(), LoadedShortList(shorts))
        assertView(ints.toMutableList(), LoadedIntList(ints))
        assertView(mutableListOf(), LoadedIntList(IntArray(0)))
        assertEquals("😊", LoadedCharList(chars).subList(12, 14).joinToString(""))
    }

    @Test
    fun viewsAreFixedSizeAndReadOnly() {
        val values = shortArrayOf(-3, 1, 2)
        val view = LoadedShortList(values)
        val unsupported = listOf<(MutableList<Short>) -> Unit>(
            { it[0] = 5 },
            { it.add(4) },
            { it.add(0, 4) },
            { it.addAll(listOf<Short>(4)) },
            { it.removeAt(0) },
            { it.remove(1.toShort()) },
            { it.removeAll(listOf(1.toShort())) },
            { it.retainAll(listOf(1.toShort())) },
            { it.clear() },
            { it.sortWith(naturalOrder()) },
            { it.iterator().apply { next() }.remove() },
            { it.listIterator().apply { next() }.set(9) },
            { it.listIterator().add(9) },
            { it.subList(0, 2).clear() },
        )
        for ((i, mutate) in unsupported.withIndex()) {
            assertThrows("mutator $i", UnsupportedOperationException::class.java) { mutate(view) }
            assertEquals("mutator $i", listOf<Short>(-3, 1, 2), view)
        }
        // Mutators that would change nothing succeed, as for java.util.Arrays.asList.
        assertTrue(!view.addAll(emptyList()) && !view.removeAll(listOf(7.toShort())))
        LoadedCharList(CharArray(0)).clear()
        assertArrayEquals(shortArrayOf(-3, 1, 2), values)
    }

    /**
     * Upstream TokenArray.readPOSTableWithIndex never closes pos_table_for_build.dat; on Windows the file cannot be
     * deleted until the leaked stream is collected, so let GC close it and retry.
     */
    private fun deleteLeaked(path: Path) {
        repeat(20) {
            try {
                Files.delete(path)
                return
            } catch (e: java.nio.file.FileSystemException) {
                System.gc()
                Thread.sleep(50)
            }
        }
        Files.delete(path)
    }

    private fun <E> assertView(boxed: MutableList<E>, view: MutableList<E>) {
        assertTrue(view is RandomAccess)
        assertEquals(boxed.size, view.size)
        for (i in boxed.indices) assertEquals(boxed[i], view[i])
        assertEquals(boxed, view)
        assertEquals(view, boxed)
        assertEquals(boxed.hashCode(), view.hashCode())
        assertEquals(boxed.toString(), view.toString())
        assertEquals(boxed, view.iterator().asSequence().toList())
        val back = view.listIterator(view.size)
        val reversed = mutableListOf<E>()
        while (back.hasPrevious()) reversed.add(back.previous())
        assertEquals(boxed.reversed(), reversed)
        for (e in boxed) {
            assertTrue(e in view)
            assertEquals(boxed.indexOf(e), view.indexOf(e))
            assertEquals(boxed.lastIndexOf(e), view.lastIndexOf(e))
        }
        sameOutOfRange(boxed, view)
    }

    /** Out-of-range reads throw exactly IndexOutOfBoundsException (not the array subclass), like ArrayList. */
    private fun <E> sameOutOfRange(boxed: List<E>, view: List<E>) {
        for (index in listOf(-1, boxed.size, boxed.size + 1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val expected = assertThrows(IndexOutOfBoundsException::class.java) { boxed[index] }
            val actual = assertThrows(IndexOutOfBoundsException::class.java) { view[index] }
            assertEquals("$index", expected.javaClass, actual.javaClass)
            assertEquals("$index", IndexOutOfBoundsException::class.java, actual.javaClass)
        }
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
            "abc\t1\t1\t3000\tABC",
            "ゔぇ\t1\t1\t3000\tヴェ",
            "ぱぱ\t1\t1\t3000\tパパ",
            // Extreme and negative costs, other POS pairs.
            "の\t2\t3\t-50\tの",
            "な\t3\t2\t2000\tな",
            "ぬ\t2\t3\t-32768\t奴",
            "ね\t3\t2\t32767\t根",
        )

        private val PROBES = listOf(
            "きょうはあめです", "きょうと", "き", "てぃーしゃつ", "あい", "えっくす", "ばいおりん", "ゔぁいおりん",
            "こんにちわ", "ゔ", "てれび", "にこ", "ほし", "のな", "ぬね", "きよう", "さん", "abc", "ゔぇ", "ぱぱ",
            "きょうはabc😀あめです", "きようはあめでs",
        )
    }
}
