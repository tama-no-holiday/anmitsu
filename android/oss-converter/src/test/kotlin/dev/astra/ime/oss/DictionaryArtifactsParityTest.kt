package dev.astra.ime.oss

import com.kazumaproject.buildAndWriteDictionaryArtifacts
import com.kazumaproject.dictionary.TokenArray
import com.kazumaproject.dictionary.models.Dictionary
import com.kazumaproject.hiraToKata
import com.kazumaproject.mozc.ConnectionMatrixIO
import com.kazumaproject.mozc.ConnectionMatrixParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.SortedMap
import java.util.TreeMap

/** [DictionaryArtifacts] (sequential phases) against the upstream reference builder on the same rich fixture. */
class DictionaryArtifactsParityTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var inputs: MozcInputs
    private lateinit var grouped: TreeMap<String, MutableList<Dictionary>>
    private lateinit var built: Path
    private lateinit var reference: Path

    @Before
    fun buildBoth() {
        val root = temp.root.toPath()
        inputs = MozcInputs.fromDirectory(MozcFixture.write(root.resolve("mozc"), dictionary00 = RICH), includeSuffix = true)
        built = root.resolve("new")
        assertTrue(OssDictionaryBuilder.build(inputs, built).verified)
        reference = root.resolve("reference")
        grouped = referenceBuild(inputs, reference)
    }

    /** Upstream's routine as OssDictionaryBuilder called it before DictionaryArtifacts. */
    private fun referenceBuild(inputs: MozcInputs, dir: Path): TreeMap<String, MutableList<Dictionary>> {
        Files.createDirectories(dir)
        val idCount = MozcSourceParser.readIdDef(inputs.idDef)
        val grouped = TreeMap<String, MutableList<Dictionary>>()
        for (p in inputs.dictionaries + listOfNotNull(inputs.suffix)) MozcSourceParser.readDictionary(p, idCount, grouped)
        val dictionaries = sorted(grouped)
        val posForBuild = dir.resolve(OssDictionaryBuilder.POS_TABLE_FOR_BUILD).toString()
        TokenArray().buildPOSTable(dictionaries, mode = 0, outputPath = dir.resolve(OssResources.POS_TABLE).toString())
        TokenArray().buildPOSTableWithIndex(dictionaries, mode = 0, outputPath = posForBuild)
        buildAndWriteDictionaryArtifacts(
            dictionaryList = dictionaries,
            yomiOutputPath = dir.resolve(OssResources.YOMI).toString(),
            tangoOutputPath = dir.resolve(OssResources.TANGO).toString(),
            tokenOutputPath = dir.resolve(OssResources.TOKEN).toString(),
            mode = 0,
            skipKanaOnlyTango = false,
            posTableForBuildPath = posForBuild,
        )
        ConnectionMatrixIO.writeRaw(ConnectionMatrixParser.parse(inputs.connection), dir.resolve(OssResources.CONNECTION))
        return grouped
    }

    @Suppress("UNCHECKED_CAST")
    private fun sorted(grouped: TreeMap<String, MutableList<Dictionary>>) = grouped as SortedMap<String, List<Dictionary>>

    @Test
    fun builderOutputsAreByteIdenticalToUpstreamReference() {
        for (name in listOf(
            OssResources.YOMI, OssResources.TANGO, OssResources.TOKEN, OssResources.POS_TABLE,
            OssDictionaryBuilder.POS_TABLE_FOR_BUILD, OssResources.CONNECTION,
        )) {
            assertArrayEquals(name, Files.readAllBytes(reference.resolve(name)), Files.readAllBytes(built.resolve(name)))
        }
    }

    @Test
    fun helperAloneIsByteIdenticalToUpstreamReference() {
        val direct = Files.createDirectories(temp.root.toPath().resolve("direct"))
        DictionaryArtifacts.write(
            dictionaries = sorted(grouped),
            yomiOutputPath = direct.resolve(OssResources.YOMI).toString(),
            tangoOutputPath = direct.resolve(OssResources.TANGO).toString(),
            tokenOutputPath = direct.resolve(OssResources.TOKEN).toString(),
            posTableForBuildPath = reference.resolve(OssDictionaryBuilder.POS_TABLE_FOR_BUILD).toString(),
        )
        for (name in listOf(OssResources.YOMI, OssResources.TANGO, OssResources.TOKEN)) {
            assertArrayEquals(name, Files.readAllBytes(reference.resolve(name)), Files.readAllBytes(direct.resolve(name)))
        }
    }

    @Test
    fun lookupsTermIdsAndCandidatesMatchUpstreamReference() {
        val new = OssResources.load(built)
        val old = OssResources.loadUnchecked { Files.newInputStream(reference.resolve(it)) }
        assertEquals(0, OssDictionaryBuilder.audit(old, grouped).trie)
        assertArrayEquals(old.yomi.termIdsSave, new.yomi.termIdsSave)
        assertEquals(old.tokens.leftIds, new.tokens.leftIds)
        assertEquals(old.tokens.rightIds, new.tokens.rightIds)
        assertTrue(old.matrix == new.matrix)

        val nodeIds = HashMap<Pair<String, String>, Int>()
        for ((reading, list) in grouped) {
            val termId = new.yomi.getTermId(new.yomi.getNodeIndex(reading))
            assertEquals(reading, old.yomi.getTermId(old.yomi.getNodeIndex(reading)), termId)
            val tokens = new.tokens.getListDictionaryByYomiTermId(termId)
            assertEquals(reading, old.tokens.getListDictionaryByYomiTermId(termId), tokens)
            assertEquals(reading, list.size, tokens.size)
            for (i in list.indices) {
                val d = list[i]
                val t = tokens[i]
                val pos = t.posTableIndex.toInt()
                assertEquals(d.leftId to d.rightId, new.tokens.leftIds[pos] to new.tokens.rightIds[pos])
                assertEquals(d.cost, t.wordCost)
                val rendered = when (t.nodeId) {
                    -2 -> reading
                    -1 -> reading.hiraToKata()
                    else -> new.tango.getLetter(t.nodeId)
                }
                assertEquals(d.tango, rendered)
                nodeIds[reading to d.tango] = t.nodeId
            }
        }
        // Sentinels: hiragana (-2), katakana (-1), and the ゔ exception that hiraToKata cannot produce (trie).
        assertEquals(-2, nodeIds.getValue("きょう" to "きょう"))
        assertEquals(-1, nodeIds.getValue("きょう" to "キョウ"))
        assertEquals(-1, nodeIds.getValue("てれび" to "テレビ"))
        assertTrue(nodeIds.getValue("ゔぁいおりん" to "ヴァイオリン") >= 0)
        assertTrue(nodeIds.getValue("ばいおりん" to "ヴァイオリン") >= 0)
        assertEquals(nodeIds.getValue("ばいおりん" to "ヴァイオリン"), nodeIds.getValue("ゔぁいおりん" to "ヴァイオリン"))

        val newConverter = OssConverter(new)
        val oldConverter = OssConverter(old)
        for (text in PROBES) {
            val a = oldConverter.convert(text, limit = 8)
            val b = newConverter.convert(text, limit = 8)
            assertEquals(text, a.status, b.status)
            assertEquals(text, a.candidates, b.candidates)
            assertEquals(text, a.words, b.words)
        }
    }

    companion object {
        private val RICH = MozcFixture.DICTIONARY00 + listOf(
            // Prefix readings (き < きょ < きょう < きょうと) and prefix surfaces (京 < 京都, 今 < 今日).
            "き\t1\t1\t4200\t木",
            "きょ\t1\t1\t5200\t虚",
            "きょうと\t1\t1\t3200\t京都",
            "いま\t1\t1\t3300\t今",
            // Duplicate reading with both sentinels next to trie surfaces.
            "きょう\t1\t1\t4500\tきょう",
            "きょう\t1\t1\t4600\tキョウ",
            // Mixed scripts: Latin, full-width, kana-kanji, symbol, surrogate pair, ASCII reading.
            "あい\t1\t1\t2000\tAI",
            "てぃーしゃつ\t1\t1\t3000\tTシャツ",
            "えっくす\t1\t1\t3000\tＸ",
            "おくりがな\t1\t1\t3000\t送り仮名",
            "ほし\t1\t1\t3000\t★",
            "にこ\t1\t1\t3000\t😊",
            "abc\t1\t1\t3000\tABC",
            // Other POS pairs and a negative cost.
            "の\t2\t3\t-50\tの",
            "な\t3\t2\t2000\tな",
        )

        private val PROBES = listOf(
            "きょうはあめです", "きょうと", "きょ", "き", "いま", "てぃーしゃつ", "あい", "えっくす", "おくりがな",
            "ばいおりん", "ゔぁいおりん", "こんにちわ", "ゔ", "てれび", "にこ", "ほし", "のな", "きよう", "さん", "abc",
        )
    }
}
