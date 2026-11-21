package dev.astra.ime.oss

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Synthetic Mozc-UT rows only (no downloaded data). Fixture ids: 1 = 名詞,一般 (see [MozcFixture]). */
class UtDictionaryImportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun sha256(path: Path) =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private val revision = "13129ddebe1cb8eabb63feccbe157adec4e1de01" // any 40-hex pin; the data here is synthetic

    /** A fetched-source directory as scripts/fetch-ut-dictionaries.py writes it. */
    private fun source(name: String, rows: List<String>, pos: String = "general-noun", props: Map<String, String> = emptyMap()): Path {
        val dir = temp.root.toPath().resolve("ut-$name")
        Files.createDirectories(dir)
        MozcFixture.writeLines(dir.resolve("$name.txt"), rows)
        MozcFixture.writeLines(dir.resolve("LICENSE"), listOf("Apache License 2.0 (synthetic test notice)"))
        val lines = linkedMapOf(
            "name" to name,
            "repository" to "https://github.com/utuhiro78/mozcdic-ut-$name",
            "revision" to revision,
            "archive" to "$name.txt.bz2",
            "archiveSha256" to "0".repeat(64),
            "text" to "$name.txt",
            "textSha256" to sha256(dir.resolve("$name.txt")),
            "license" to "Apache-2.0",
            "pos" to pos,
            "notices" to "LICENSE",
            "notice.LICENSE.sha256" to sha256(dir.resolve("LICENSE")),
        )
        lines.putAll(props)
        MozcFixture.writeLines(dir.resolve(UtDictionaryImport.SOURCE_PROPERTIES), lines.map { "${it.key}=${it.value}" })
        return dir
    }

    private fun mozc(): MozcInputs = MozcInputs.fromDirectory(MozcFixture.write(temp.root.toPath().resolve("mozc")), includeSuffix = true)

    private fun prior(): Path = temp.root.toPath().resolve("english.txt").also { MozcFixture.writeLines(it, listOf("てれび\t1\t1\t3300\tTV")) }

    private val first = listOf(
        "ぬめぴこ\t0000\t0000\t8500\tヌメピコ", // new word: kept at its own cost
        "あめ\t0000\t0000\t5000\tアメ", // below the floor: raised to it
        "きょう\t0000\t0000\t3000\t今日", // Mozc has it: skipped, Mozc's cost stays
        "てれび\t0000\t0000\t9000\tTV", // a prior extra has it
        "ぬめぴこ\t0000\t0000\t9000\tヌメピコ", // same pair again in this source: the cheaper row wins
    )
    private val second = listOf(
        "ぬめぴこ\t0000\t0000\t7000\tヌメピコ", // an earlier source already has it
        "ふじい\t0000\t0000\t7000\t藤井",
    )

    private fun prepare(out: Path?, policy: UtDictionaryImport.Policy = UtDictionaryImport.Policy(), sources: List<Path>? = null): UtDictionaryImport.Result {
        val m = mozc()
        return UtDictionaryImport.prepare(
            m, sha256(m.idDef), listOf(prior()),
            (sources ?: listOf(source("sudachidict", first), source("personal-names", second))).map(UtDictionaryImport::readSource),
            out, policy,
        )
    }

    @Test
    fun placeholderIdsAreResolvedByNameDuplicatesSkippedAndCostsFloored() {
        val out = temp.root.toPath().resolve("staged")
        val r = prepare(out)
        assertEquals(
            listOf("あめ\t1\t1\t8000\tアメ", "ぬめぴこ\t1\t1\t8500\tヌメピコ", "ふじい\t1\t1\t8000\t藤井"),
            Files.readAllLines(out.resolve(UtDictionaryImport.EXTRA), StandardCharsets.UTF_8),
        )
        val (a, b) = r.stats
        assertEquals(5, a.read)
        assertEquals(2, a.kept)
        assertEquals(1, a.duplicateOfBase)
        assertEquals(1, a.duplicateOfPriorExtra)
        assertEquals(1, a.duplicateWithinSource)
        assertEquals(1, a.raisedToFloor)
        assertEquals(2, b.read)
        assertEquals(1, b.kept)
        assertEquals(1, b.duplicateOfEarlierSource)
        for (s in r.stats) {
            // Source rows are never reported as additions: every row is kept, a duplicate or rejected exactly once.
            assertEquals(s.read, s.kept + s.duplicateOfBase + s.duplicateOfPriorExtra + s.duplicateOfEarlierSource + s.duplicateWithinSource + s.rejectedTotal)
        }
        assertEquals(3, r.rows)
        // Provenance: per-source revision, hashes, licence, POS name and the id it resolved to; notices copied.
        val provenance = String(Files.readAllBytes(out.resolve(UtDictionaryImport.PROVENANCE)), StandardCharsets.UTF_8)
        assertTrue(provenance, provenance.contains("source.0.sudachidict.revision=$revision"))
        assertTrue(provenance, provenance.contains("source.0.sudachidict.license=Apache-2.0"))
        assertTrue(provenance, provenance.contains("source.0.sudachidict.posName=名詞,一般,*,*,*,*,*"))
        assertTrue(provenance, provenance.contains("source.0.sudachidict.posId=1"))
        assertTrue(provenance, provenance.contains("outputSha256=${sha256(out.resolve(UtDictionaryImport.EXTRA))}"))
        assertTrue(provenance, provenance.contains("priorExtra.english.txt.sha256="))
        assertTrue(Files.isRegularFile(out.resolve(UtDictionaryImport.NOTICES).resolve("personal-names").resolve("LICENSE")))
        val summary = String(Files.readAllBytes(out.resolve(UtDictionaryImport.SUMMARY)), StandardCharsets.UTF_8)
        assertTrue(summary, summary.contains("source sudachidict: read=5 kept=2 duplicateOfBase=1 duplicateOfPriorExtra=1"))
    }

    @Test
    fun theStagedFileBuildsAndRanksNewWordsWithoutDisplacingKnownOnes() {
        val out = temp.root.toPath().resolve("staged")
        prepare(out)
        val src = temp.root.toPath().resolve("mozc")
        val compiled = temp.root.toPath().resolve("compiled")
        val report = OssDictionaryBuilder.build(
            MozcInputs.fromDirectory(src, includeSuffix = true).copy(extras = listOf(prior(), out.resolve(UtDictionaryImport.EXTRA))),
            compiled,
        )
        assertTrue(report.verified)
        assertEquals(3, report.files.last().records)
        val converter = OssConverter(OssResources.load(compiled))
        assertEquals("ヌメピコ", converter.convert("ぬめぴこ").candidates.first())
        // Known words keep their rank; the floored アメ is only a later variant.
        val known = converter.convert("きょうはあめです", limit = 10).candidates
        assertEquals("今日は雨です", known.first())
        assertTrue(known.indexOf("今日はアメです") > known.indexOf("今日は飴です"))
        assertEquals(listOf("今日", "京", "きょう"), converter.convert("きょう").candidates)
    }

    @Test
    fun relativePenaltyKeepsRareRowsBehindMozcsCheapestReading() {
        val rows = listOf("あめ\t0000\t0000\t3000\tアメ", "あめ\t0000\t0000\t9000\tAME")
        val r = prepare(null, UtDictionaryImport.Policy(costFloor = 0, relativePenalty = 1000), listOf(source("neologd", rows)))
        // Mozc's cheapest あめ is 3500: 3000 is raised to 4500, 9000 stays.
        assertEquals(1, r.stats.single().raisedRelative)
        assertEquals(0, r.stats.single().raisedToFloor)
        assertTrue(r.summary, r.summary.contains("check only, nothing written"))
    }

    @Test
    fun malformedPlaceholderAndOutOfRangeRowsAreRejectedAndCounted() {
        val bad = listOf(
            "ぬめぴこ\t0000\t0000\t8500\tヌメピコ",
            "", // BLANK
            "only\ttwo", // COLUMNS
            "あ\t0000\t0000\t100\tX\textra", // COLUMNS
            "あ\t1851\t1851\t100\tX", // ID: a real id from some Mozc revision is never copied
            "あ\t0000\t1\t100\tX", // ID
            "あ\t0000\t0000\tcheap\tX", // COST
            "あ\t0000\t0000\t-5\tX", // COST
            "あ\t0000\t0000\t40000\tX", // COST: outside upstream Short
            "abc\t0000\t0000\t100\tX", // READING: not convertible
            "ア\t0000\t0000\t100\tX", // READING: katakana
            "あ".repeat(33) + "\t0000\t0000\t100\tX", // READING: too long
            "あ\t0000\t0000\t100\t X", // SURFACE: leading space (not trimmed)
            "あ\t0000\t0000\t100\t" + "X".repeat(49), // SURFACE: too long
            "あ\t0000\t0000\t100\tA​B", // UNICODE: zero-width space (format)
            "あ\t0000\t0000\t100\tA\u0007", // UNICODE: control
            "あ\t0000\t0000\t100\t", // UNICODE: private use
            "あ\t0000\t0000\t100\tA　B", // UNICODE: ideographic space
            "あ\t0000\t0000\t100\tが", // NOT_NFC: decomposed が is rejected, not normalized
            "あ\t0000\t0000\t100\t" + "x".repeat(600), // LINE_TOO_LONG
        )
        val r = prepare(null, UtDictionaryImport.Policy(maxRejectRatio = 1.0), listOf(source("neologd", bad)))
        val s = r.stats.single()
        assertEquals(bad.size, s.read)
        assertEquals(1, s.kept)
        assertEquals(
            mapOf("BLANK" to 1, "COLUMNS" to 2, "LINE_TOO_LONG" to 1, "READING" to 3, "SURFACE" to 2, "UNICODE" to 4, "NOT_NFC" to 1, "ID" to 2, "COST" to 3),
            s.rejected.mapKeys { it.key.name }.filterValues { it > 0 },
        )
        assertEquals(listOf(5, 6), s.rejectedLines.getValue(UtDictionaryImport.Reject.ID))
        assertEquals(bad.size - 1, s.rejectedTotal)
        // Accepted: an ordinary space inside a spelling, emoji with a variation selector, NFC text.
        assertTrue(UtDictionaryImport.validCodePoints("Taylor Swift"))
        assertTrue(UtDictionaryImport.validCodePoints("☺️"))
        // Rejected: replacement character (a decoding error upstream) and an unpaired surrogate.
        assertFalse(UtDictionaryImport.validCodePoints("�"))
        assertFalse(UtDictionaryImport.validCodePoints("A\uD800"))
    }

    @Test
    fun tooManyRejectsFailTheRunAndWriteNothing() {
        val out = temp.root.toPath().resolve("staged")
        val rows = listOf("ぬめぴこ\t0000\t0000\t8500\tヌメピコ", "broken")
        try {
            prepare(out, sources = listOf(source("sudachidict", rows)))
            fail("50% rejects must fail with the default 1% limit")
        } catch (e: MozcFormatException) {
            assertTrue(e.message!!, e.message!!.contains("format changed"))
        }
        assertFalse(Files.exists(out))
        // A BOM is a format change too.
        try {
            prepare(out, sources = listOf(source("neologd", listOf("﻿ぬめぴこ\t0000\t0000\t8500\tヌメピコ"))))
            fail("BOM must be rejected")
        } catch (e: MozcFormatException) {
            // expected
        }
    }

    @Test
    fun outputIsDeterministicAndCheckOnlyWritesNothing() {
        val one = temp.root.toPath().resolve("one")
        val two = temp.root.toPath().resolve("two")
        prepare(one)
        val check = prepare(null)
        prepare(two)
        for (name in listOf(UtDictionaryImport.EXTRA, UtDictionaryImport.PROVENANCE, UtDictionaryImport.SUMMARY)) {
            assertArrayEquals(name, Files.readAllBytes(one.resolve(name)), Files.readAllBytes(two.resolve(name)))
        }
        assertEquals(sha256(one.resolve(UtDictionaryImport.EXTRA)), check.sha256)
    }

    @Test
    fun stagingNeverOverwritesAndInputsMustMatchTheirPins() {
        // A non-empty output directory (e.g. the current assets) is refused and left untouched.
        val current = temp.newFolder("current").toPath()
        MozcFixture.writeLines(current.resolve(UtDictionaryImport.EXTRA), listOf("keep"))
        try {
            prepare(current)
            fail("non-empty output must be refused")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(listOf("keep"), Files.readAllLines(current.resolve(UtDictionaryImport.EXTRA)))

        val m = mozc()
        val src = UtDictionaryImport.readSource(source("sudachidict", first))
        try {
            UtDictionaryImport.prepare(m, "f".repeat(64), emptyList(), listOf(src), null)
            fail("an id.def other than the pinned one must be refused")
        } catch (e: MozcFormatException) {
            assertTrue(e.message!!.contains("pinned"))
        }

        // The text or a notice changed after fetching: refused.
        val dir = source("neologd", second)
        Files.write(dir.resolve("neologd.txt"), "ふじい\t0000\t0000\t1\t藤井\n".toByteArray(StandardCharsets.UTF_8))
        expectFormatError { UtDictionaryImport.readSource(dir) }
        val dir2 = source("personal-names", second)
        Files.write(dir2.resolve("LICENSE"), byteArrayOf(1))
        expectFormatError { UtDictionaryImport.readSource(dir2) }
        // A branch name instead of a pinned commit, an unknown POS or a path in a file name: refused.
        expectFormatError { UtDictionaryImport.readSource(source("a", second, props = mapOf("revision" to "main"))) }
        expectFormatError { UtDictionaryImport.readSource(source("b", second, props = mapOf("pos" to "verb"))) }
        expectFormatError { UtDictionaryImport.readSource(source("c", second, props = mapOf("notices" to "../LICENSE"))) }
        // A pinned row count (from the lock) that the file does not have: refused.
        expectFormatError { prepare(null, sources = listOf(source("d", second, props = mapOf("expectedRows" to "99")))) }
        assertEquals(2, prepare(null, sources = listOf(source("e", second, props = mapOf("expectedRows" to "2")))).stats.single().read)
    }

    private fun expectFormatError(block: () -> Unit) {
        try {
            block()
            fail("expected MozcFormatException")
        } catch (e: MozcFormatException) {
            // expected
        }
    }

    @Test
    fun posIdsComeFromIdDefNamesNotFromNumbers() {
        val idDef = temp.root.toPath().resolve("id.def")
        MozcFixture.writeLines(idDef, listOf("0 BOS/EOS,*,*,*,*,*,*", "1 助詞,係助詞,*,*,*,*,は", "2 名詞,固有名詞,人名,一般,*,*,*", "3 名詞,一般,*,*,*,*,*"))
        assertEquals(3, UtDictionaryImport.resolve(idDef, UtDictionaryImport.Pos.GENERAL_NOUN))
        assertEquals(2, UtDictionaryImport.resolve(idDef, UtDictionaryImport.Pos.PERSON_NAME))
        MozcFixture.writeLines(idDef, listOf("0 BOS/EOS,*,*,*,*,*,*", "1 名詞,一般,*,*,*,*,*", "2 名詞,一般,*,*,*,*,*"))
        expectFormatError { UtDictionaryImport.resolve(idDef, UtDictionaryImport.Pos.GENERAL_NOUN) } // ambiguous
        expectFormatError { UtDictionaryImport.resolve(idDef, UtDictionaryImport.Pos.PERSON_NAME) } // missing
    }

    @Test
    fun rawPlaceholderRowsCanNeverBeBuiltAsAnExtra() {
        val src = temp.root.toPath().resolve("mozc")
        MozcFixture.write(src)
        val raw = temp.root.toPath().resolve("raw-ut.txt")
        MozcFixture.writeLines(raw, listOf("ぬめぴこ\t0000\t0000\t8500\tヌメピコ"))
        try {
            OssDictionaryBuilder.build(MozcInputs.fromDirectory(src, includeSuffix = true).copy(extras = listOf(raw)), temp.root.toPath().resolve("o"))
            fail("id 0 in an extra must be refused")
        } catch (e: MozcFormatException) {
            assertTrue(e.message!!, e.message!!.contains("BOS/EOS"))
        }
    }

    @Test
    fun cliPreparesAndComparesOldAndNewBuilds() {
        val stdout = ByteArrayOutputStream()
        val print = PrintStream(stdout, true, "UTF-8")
        val err = PrintStream(ByteArrayOutputStream(), true, "UTF-8")
        val m = mozc()
        val mozcDir = m.idDef.parent.toString()
        val sudachi = source("sudachidict", first).toString()
        val staged = temp.root.toPath().resolve("staged")
        val base = listOf("ut-prepare", "--mozc-dir", mozcDir, "--id-def-sha256", sha256(m.idDef), "--source", sudachi, "--prior-extra", prior().toString())
        assertEquals(2, runCli(base, print, err)) // needs --out or --check-only
        assertEquals(0, runCli(base + "--check-only", print, err))
        assertFalse(Files.exists(staged))
        assertEquals(0, runCli(base + listOf("--out", staged.toString()), print, err))
        assertTrue(stdout.toString("UTF-8"), stdout.toString("UTF-8").contains("source sudachidict: read=5 kept=2"))

        val old = temp.root.toPath().resolve("old")
        val new = temp.root.toPath().resolve("new")
        assertEquals(0, runCli(listOf("build", "--mozc-dir", mozcDir, "--extra", prior().toString(), "--out", old.toString()), print, err))
        assertEquals(
            0,
            runCli(listOf("build", "--mozc-dir", mozcDir, "--extra", prior().toString(), "--extra", staged.resolve(UtDictionaryImport.EXTRA).toString(), "--out", new.toString()), print, err),
        )
        val probes = temp.root.toPath().resolve("probes.tsv")
        MozcFixture.writeLines(
            probes,
            listOf("p1\tcommon\tきょうはあめです\tきょうはあめです\t今日は雨です", "p2\tnew-word\tぬめぴこ\tぬめぴこ\tヌメピコ"),
        )
        stdout.reset()
        assertEquals(0, runCli(listOf("compare", "--old", old.toString(), "--new", new.toString(), "--fixtures", probes.toString()), print, err))
        val text = stdout.toString("UTF-8")
        assertTrue(text, text.contains("p2\tnew-word\timproved"))
        assertTrue(text, text.contains("probes.tsv:common: fixtures=1 top1 1 -> 1"))
        assertTrue(text, text.contains("regressions=0 improvements=1"))
        // Swapped, the same change is a regression and fails the gate.
        assertEquals(3, runCli(listOf("compare", "--old", new.toString(), "--new", old.toString(), "--fixtures", probes.toString()), print, err))
    }
}
