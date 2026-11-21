package dev.astra.ime

import dev.astra.ime.core.Alignment
import dev.astra.ime.core.CandidateClass
import dev.astra.ime.core.CandidateGroups
import dev.astra.ime.core.Segment
import dev.astra.ime.core.SegmentKind
import dev.astra.ime.oss.EditPenalties
import dev.astra.ime.oss.MappedDictionary
import dev.astra.ime.oss.MozcInputs
import dev.astra.ime.oss.OssConverter
import dev.astra.ime.oss.OssDictionaryBuilder
import dev.astra.ime.oss.OssResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Plain JVM: builds a tiny Mozc-format dictionary with :oss-converter and loads it like the app does. */
class OssBackendTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var compiled: Path

    @Before
    fun buildTinyDictionary() {
        val src = temp.newFolder("mozc").toPath()
        fun write(name: String, lines: List<String>) =
            Files.write(src.resolve(name), lines.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8))
        write("id.def", listOf("0 BOS/EOS", "1 名詞", "2 助詞"))
        write("connection_single_column.txt", listOf("3", "0", "100", "900", "0", "300", "-100", "0", "50", "900"))
        write("dictionary00.txt", listOf("きょう\t1\t1\t3000\t今日", "あめ\t1\t1\t3500\t雨", "は\t2\t2\t1000\tは"))
        for (i in 1..9) write("dictionary%02d.txt".format(i), emptyList())
        compiled = temp.root.toPath().resolve("compiled")
        OssDictionaryBuilder.build(MozcInputs.fromDirectory(src, includeSuffix = false), compiled)
        MappedDictionary.pack(compiled)
        // The files the APK ships (android/app/oss-assets.lock): the mapped lexicon, the matrix and the manifest.
        val lock = listOf(MappedDictionary.FILE, OssResources.CONNECTION, OssResources.MANIFEST)
            .joinToString("") { "${sha256(Files.readAllBytes(compiled.resolve(it)))}  $it\n" }
        Files.write(compiled.resolve(OssBackend.HASHES), lock.toByteArray(Charsets.UTF_8))
    }

    private fun load(dir: Path = compiled) = OssBackend.load(
        open = { name -> Files.newInputStream(dir.resolve(name)) },
        exists = { name -> Files.exists(dir.resolve(name)) },
        map = { name -> FileChannel.open(dir.resolve(name)).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) } },
    )

    @Test
    fun loadsVerifiedAssetsAndConverts() {
        val loaded = load()
        assertEquals(OssBackend.LoadState.READY, loaded.state)
        val candidates = OssBackend.candidatesOrNull(loaded.converter!!, "きょうはあめ", 10)!!
        assertEquals(listOf("今日は雨", "きょうはあめ"), candidates.map { it.surface })
        assertTrue(candidates.all { it.reading == "きょうはあめ" })
    }

    @Test
    fun theExactPanelCarriesAlignedWordsThatClassifyAsExact() {
        val converter = load().converter!!
        val panel = OssBackend.exactOrNull(converter, "あめ", null, null)!!
        assertEquals(1, panel.wholeCount)
        val word = panel.exact.first { it.surface == "雨" }
        assertEquals(listOf(Segment(0, 2, "あめ", "雨", SegmentKind.WORD)), Alignment.checked("あめ", word.surface, word.segments))
        assertEquals(CandidateClass.EXACT, CandidateGroups.classify("あめ", word))
    }

    @Test
    fun aSentenceHasNoWholeEntryButKeepsItsBestPath() {
        val panel = OssBackend.exactOrNull(load().converter!!, "きょうはあめ", null, null)!!
        assertEquals(0, panel.wholeCount)
        assertEquals("今日は雨", panel.exact.first().surface)
        assertEquals(listOf("今日", "は", "雨"), Alignment.checked("きょうはあめ", "今日は雨", panel.exact.first().segments)!!.map { it.surface })
    }

    @Test
    fun anExactAnswerThatIsNotCleanFallsBackLikeConversion() {
        assertNull(OssBackend.exactOrNull(load().converter!!, "ぬぬ", null, null))
    }

    @Test
    fun aCancelledExactLookupGivesNothing() {
        assertNull(OssBackend.exactOrNull(load().converter!!, "きょうはあめ", null, null, cancelled = { true }))
    }

    @Test
    fun theLeadGuardStaysOnThePathToTheCandidateList() {
        val converted = OssBackend.convertOrNull(load().converter!!, "あめ", 10, EditPenalties())!!
        assertEquals("雨", converted.candidates.first().surface)
        assertEquals("あめ", converted.candidates.first().reading)
    }

    @Test
    fun candidatesCarryCheckedSegmentsForRepairUnits() {
        val converter = load().converter!!
        val converted = OssBackend.convertOrNull(converter, "きょうは😀あめ", 10)!!
        val best = converted.candidates.first()
        assertEquals("今日は😀雨", best.surface)
        val segments = Alignment.checked("きょうは😀あめ", best.surface, best.segments)!!
        assertEquals(listOf(SegmentKind.WORD, SegmentKind.WORD, SegmentKind.PASSTHROUGH, SegmentKind.WORD), segments.map { it.kind })
        assertEquals(listOf("今日は", "😀", "雨"), Alignment.units("きょうは😀あめ", best).map { it.surface })
        // The raw candidate claims no alignment: one unit.
        assertNull(converted.candidates.last().segments)
    }

    @Test
    fun finalListsAlwaysOfferRawAndKatakanaWithTheirWordsKeptInOrder() {
        val converter = load().converter!!
        // A judge that prefers the reverse dictionary order (stands in for zenz; no model needed).
        val reversed = dev.astra.ime.oss.zenz.ZenzRanking.Scorer { _, _, list -> FloatArray(list.size) { it * 10f } }
        val text = "きょうはあめ"
        val converted = OssBackend.convertOrNull(converter, text, 10)!!
        val (candidates, words) = ConversionWorker.withLiteralChoices(text, converted.candidates, converted.words)
        assertEquals(listOf("今日は雨", "きょうはあめ", "キョウハアメ"), candidates.map { it.surface })
        assertEquals(converted.words[0], words[0]) // words follow their candidate
        assertTrue(words[2].isEmpty()) // the inserted katakana carries no words (nothing word-level to learn)
        val kata = candidates[2]
        assertEquals(listOf("キョウハアメ"), Alignment.units(text, kata).map { it.surface })
        // Reordered by the judge, then capped: both literal choices are still there.
        val order = dev.astra.ime.oss.zenz.ZenzRanking.rerank(text, converted.candidates.map { it.surface },
            converted.candidates.map { it.reading }, 8, scorer = reversed)!!
        val judged = order.map { converted.candidates[it] }
        val final = ConversionWorker.withLiteralChoices(text, judged, order.map { converted.words[it] }).first.map { it.surface }
        assertTrue(text in final)
        assertTrue(final.indexOf("キョウハアメ") <= dev.astra.ime.core.LiteralChoices.KATAKANA_SLOT)
    }

    @Test
    fun nonOkStatusFallsBackToCore() {
        val converter = OssConverter(OssResources.load(compiled))
        // "ぬ" has no dictionary path (PARTIAL): the caller must use :core instead.
        assertNull(OssBackend.candidatesOrNull(converter, "ぬ", 10))
        assertNull(OssBackend.candidatesOrNull(converter, "あ".repeat(257), 10))
    }

    @Test
    fun missingOrTamperedAssetsNeverLoad() {
        val empty = temp.newFolder("empty").toPath()
        assertEquals(OssBackend.LoadState.MISSING, load(empty).state)

        // One changed byte in any shipped file, or a file missing from the hash list, fails the load.
        for (name in listOf(MappedDictionary.FILE, OssResources.CONNECTION, OssResources.MANIFEST)) {
            val tampered = temp.newFolder("tampered-$name").toPath()
            Files.list(compiled).use { files -> files.forEach { Files.copy(it, tampered.resolve(it.fileName)) } }
            val bytes = Files.readAllBytes(tampered.resolve(name))
            bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
            Files.write(tampered.resolve(name), bytes)
            val loaded = load(tampered)
            assertEquals(name, OssBackend.LoadState.FAILED, loaded.state)
            assertNull(name, loaded.converter)
        }
        val unlisted = temp.newFolder("unlisted").toPath()
        Files.list(compiled).use { files -> files.forEach { Files.copy(it, unlisted.resolve(it.fileName)) } }
        val hashes = unlisted.resolve(OssBackend.HASHES)
        Files.write(hashes, Files.readAllLines(hashes).filter { !it.endsWith(MappedDictionary.FILE) })
        assertEquals(OssBackend.LoadState.FAILED, load(unlisted).state)
    }

    @Test
    fun theLexiconIsHashedOncePerInstall() {
        val stamp = temp.root.toPath().resolve("stamp/verified").toFile()
        fun copy(name: String) = temp.newFolder(name).toPath().also { dir ->
            Files.list(compiled).use { files -> files.forEach { Files.copy(it, dir.resolve(it.fileName)) } }
        }
        var dir = copy("intact")
        fun loadWith(install: String) = OssBackend.load(
            open = { name -> Files.newInputStream(dir.resolve(name)) },
            exists = { name -> Files.exists(dir.resolve(name)) },
            map = { name -> FileChannel.open(dir.resolve(name)).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) } },
            verified = VerifiedStamp(stamp, install),
        )
        assertEquals(OssBackend.LoadState.READY, loadWith("12:1000").state)
        assertTrue(stamp.isFile)

        // Corrupt one word cost (structurally still valid). Under the same install the hash is not re-run, which is
        // the point of the stamp; a new install (update) hashes again and refuses it. (A separate copy: Windows cannot
        // rewrite a file while it is mapped.)
        dir = copy("corrupted")
        val file = dir.resolve(MappedDictionary.FILE)
        val bytes = Files.readAllBytes(file)
        // Header: magic (8), version, count, then (offset, length) per section; section 7 is the word costs.
        val costs = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(16 + 7 * 16).toInt()
        bytes[costs] = (bytes[costs] + 1).toByte()
        Files.write(file, bytes)
        assertEquals(OssBackend.LoadState.READY, loadWith("12:1000").state)
        assertEquals(OssBackend.LoadState.FAILED, loadWith("12:2000").state)
        // A failed check records nothing: the stamp still names the first install.
        assertTrue(stamp.readText().startsWith("12:1000 "))
        // A missing or unreadable stamp just means "hash again".
        stamp.delete()
        assertEquals(OssBackend.LoadState.FAILED, loadWith("12:1000").state)
        stamp.mkdirs()
        assertEquals(OssBackend.LoadState.FAILED, loadWith("12:1000").state)
    }

    @Test
    fun verifyingStreamHashesUnreadRemainderOnClose() {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        VerifyingInputStream(ByteArrayInputStream(data), sha256(data)).use { it.read(ByteArray(10)) }
        VerifyingInputStream(ByteArrayInputStream(data), sha256(data)).use { it.skip(50_000); it.read() }
        try {
            VerifyingInputStream(ByteArrayInputStream(data), sha256(data.copyOf(99_999))).use { it.read() }
            fail("hash mismatch must throw on close")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("mismatch"))
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
