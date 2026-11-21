package dev.astra.ime.oss

import com.kazumaproject.dictionary.TokenArray
import com.kazumaproject.dictionary.models.Dictionary
import com.kazumaproject.hiraToKata
import com.kazumaproject.mozc.ConnectionMatrixIO
import com.kazumaproject.mozc.ConnectionMatrixParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.SortedMap
import java.util.TreeMap

/** Paths to unmodified Mozc dictionary_oss sources. */
data class MozcInputs(
    val dictionaries: List<Path>,
    val suffix: Path?,
    val idDef: Path,
    val connection: Path,
    /** Extra Mozc-format sources added after Mozc's own (e.g. [SudachiEnglish] output); same strict format. */
    val extras: List<Path> = emptyList(),
) {
    companion object {
        /** Standard layout: dictionary00.txt..dictionary09.txt, id.def, connection_single_column.txt[, suffix.txt]. */
        fun fromDirectory(dir: Path, includeSuffix: Boolean): MozcInputs {
            fun required(name: String): Path = dir.resolve(name).also {
                if (!Files.isRegularFile(it)) throw MozcFormatException("Missing Mozc source file: $name")
            }
            return MozcInputs(
                dictionaries = (0..9).map { required("dictionary%02d.txt".format(it)) },
                suffix = if (includeSuffix) required("suffix.txt") else null,
                idDef = required("id.def"),
                connection = required("connection_single_column.txt"),
            )
        }
    }
}

/**
 * Round-trip findings. With the patched TokenArray (PATCHES.md) every rendered surface must equal the source;
 * a verified build aborts on any mismatch, so the rewrite counters of a returned report are always 0 when
 * [verified]. [spellingCorrectionRows] are counted and kept as ordinary entries (see [MozcSourceParser]).
 */
data class BuildReport(
    val idCount: Int,
    val matrixSize: Int,
    val files: List<SourceFileStats>,
    val records: Int,
    val readings: Int,
    val posPairs: Int,
    val exactDuplicates: Int,
    val costVariants: Int,
    val spellingCorrectionRows: Int,
    val verified: Boolean,
    val hiraganaRewrites: Int,
    val katakanaRewrites: Int,
    val trieRewrites: Int,
    val rewriteExamples: List<String>,
    val inputSha256: Map<String, String>,
)

/**
 * Compiles Mozc sources into upstream resources using only upstream primitives:
 * TokenArray.buildPOSTable/buildPOSTableWithIndex, the PrefixTree -> LOUDS -> TokenArray steps of
 * buildAndWriteDictionaryArtifacts run sequentially by [DictionaryArtifacts], and ConnectionMatrixParser/ConnectionMatrixIO. No Mozc entry is filtered or re-costed; rows from
 * [MozcInputs.extras] are added as given.
 */
object OssDictionaryBuilder {
    const val UPSTREAM_REVISION = "13e391bc7a4a245f63aa0091c1e9c11db3dbd759"

    /** Local patches applied on top of [UPSTREAM_REVISION]; see third_party/kotlin-kana-kanji-converter/PATCHES.md. */
    const val UPSTREAM_PATCHES = "TokenArray.getNodeIdForDictionary"
    const val REPORT = "build-report.txt"

    /** Upstream reads this POS->index map only while building; it is kept beside the outputs, unused at runtime. */
    const val POS_TABLE_FOR_BUILD = "pos_table_for_build.dat"

    private const val MAX_EXAMPLES = 20

    fun build(inputs: MozcInputs, outDir: Path, verify: Boolean = true): BuildReport {
        if (Files.exists(outDir)) {
            require(Files.isDirectory(outDir)) { "Output is not a directory: $outDir" }
            Files.list(outDir).use { require(!it.findAny().isPresent) { "Output directory is not empty: $outDir" } }
        }
        Files.createDirectories(outDir)

        val idCount = MozcSourceParser.readIdDef(inputs.idDef)
        // Upstream parser: header line = dimension, then one signed cost per line, streamed into a ShortArray.
        val matrix = ConnectionMatrixParser.parse(inputs.connection)
        if (matrix.size != idCount) {
            throw MozcFormatException("Connection matrix size ${matrix.size} != id.def count $idCount")
        }

        val grouped = TreeMap<String, MutableList<Dictionary>>()
        val files = (inputs.dictionaries + listOfNotNull(inputs.suffix)).map {
            MozcSourceParser.readDictionary(it, idCount, grouped)
        } + inputs.extras.map { MozcSourceParser.readDictionary(it, idCount, grouped, rejectBosEos = true) }
        val records = files.sumOf { it.records }
        if (records == 0) throw MozcFormatException("No dictionary records")

        var exactDuplicates = 0
        var costVariants = 0
        val pairs = LinkedHashSet<Pair<Short, Short>>()
        for (list in grouped.values) {
            for (d in list) pairs += d.leftId to d.rightId
            if (list.size < 2) continue
            val exact = HashSet<Dictionary>()
            val variants = HashSet<Triple<Short, Short, String>>()
            for (d in list) {
                if (!exact.add(d)) exactDuplicates++ else if (!variants.add(Triple(d.leftId, d.rightId, d.tango))) costVariants++
            }
        }
        // Upstream stores POS-table indices as Short.
        if (pairs.size - 1 > Short.MAX_VALUE) throw MozcFormatException("${pairs.size} POS pairs exceed upstream Short index")

        // Erasure-safe: upstream only reads these lists. Natural String order gives prefixes before extensions,
        // which PrefixTreeWithTermId relies on to assign each reading's term ID.
        @Suppress("UNCHECKED_CAST")
        val dictionaries = grouped as SortedMap<String, List<Dictionary>>
        val posTable = outDir.resolve(OssResources.POS_TABLE)
        val posForBuild = outDir.resolve(POS_TABLE_FOR_BUILD)
        TokenArray().buildPOSTable(dictionaries, mode = 0, outputPath = posTable.toString())
        TokenArray().buildPOSTableWithIndex(dictionaries, mode = 0, outputPath = posForBuild.toString())
        // Upstream writers print and continue on I/O errors; fail here instead.
        requireWritten(posTable, posForBuild)
        // Byte-identical to upstream buildAndWriteDictionaryArtifacts(mode = 0, skipKanaOnlyTango = false), one trie at a time.
        DictionaryArtifacts.write(
            dictionaries = dictionaries,
            yomiOutputPath = outDir.resolve(OssResources.YOMI).toString(),
            tangoOutputPath = outDir.resolve(OssResources.TANGO).toString(),
            tokenOutputPath = outDir.resolve(OssResources.TOKEN).toString(),
            posTableForBuildPath = posForBuild.toString(),
        )
        requireWritten(outDir.resolve(OssResources.YOMI), outDir.resolve(OssResources.TANGO), outDir.resolve(OssResources.TOKEN))
        ConnectionMatrixIO.writeRaw(matrix, outDir.resolve(OssResources.CONNECTION))

        val resources = OssResources.loadUnchecked { Files.newInputStream(outDir.resolve(it)) }
        check(resources.matrix == matrix) { "Connection matrix did not round-trip" }
        val audit = if (verify) audit(resources, grouped) else null

        val sha = (inputs.dictionaries + listOfNotNull(inputs.suffix, inputs.idDef, inputs.connection) + inputs.extras)
            .associate { it.fileName.toString() to sha256(it) }
        val report = BuildReport(
            idCount = idCount,
            matrixSize = matrix.size,
            files = files,
            records = records,
            readings = grouped.size,
            posPairs = pairs.size,
            exactDuplicates = exactDuplicates,
            costVariants = costVariants,
            spellingCorrectionRows = files.sumOf { it.spellingCorrectionRows },
            verified = audit != null,
            hiraganaRewrites = audit?.hiragana ?: 0,
            katakanaRewrites = audit?.katakana ?: 0,
            trieRewrites = audit?.trie ?: 0,
            rewriteExamples = audit?.examples ?: emptyList(),
            inputSha256 = sha,
        )
        Files.write(outDir.resolve(REPORT), formatReport(report).toByteArray(StandardCharsets.UTF_8))
        // Written last: its presence marks a complete, verified build for OssResources.load.
        Files.write(outDir.resolve(OssResources.MANIFEST), formatManifest(report).toByteArray(StandardCharsets.UTF_8))
        return report
    }

    internal class Audit(val hiragana: Int, val katakana: Int, val trie: Int, val examples: List<String>)

    /**
     * Reads every record back through the upstream lookups GraphBuilder uses and compares IDs, cost and
     * rendered surface. Any mismatch aborts the build; surface mismatches are counted per storage kind with up
     * to [MAX_EXAMPLES] examples in the message.
     */
    internal fun audit(resources: OssResources, grouped: Map<String, List<Dictionary>>): Audit {
        var hiragana = 0
        var katakana = 0
        var trie = 0
        val examples = ArrayList<String>()
        val left = resources.tokens.leftIds
        val right = resources.tokens.rightIds
        for ((reading, list) in grouped) {
            val termId = resources.yomi.getTermId(resources.yomi.getNodeIndex(reading))
            check(termId >= 1) { "Reading missing from compiled LOUDS (record count ${list.size})" }
            val tokens = resources.tokens.getListDictionaryByYomiTermId(termId)
            check(tokens.size == list.size) { "Token count mismatch: expected ${list.size}, got ${tokens.size}" }
            for (i in list.indices) {
                val d = list[i]
                val t = tokens[i]
                val pos = t.posTableIndex.toInt()
                check(pos in left.indices && left[pos] == d.leftId && right[pos] == d.rightId) { "POS ID mismatch after build" }
                check(t.wordCost == d.cost) { "Cost mismatch after build" }
                val rendered = when (t.nodeId) {
                    -2 -> reading
                    -1 -> reading.hiraToKata()
                    else -> resources.tango.getLetter(t.nodeId)
                }
                if (rendered == d.tango) continue
                when (t.nodeId) {
                    -2 -> hiragana++
                    -1 -> katakana++
                    else -> trie++
                }
                if (examples.size < MAX_EXAMPLES) examples += "$reading\t${d.tango}\t->\t$rendered"
            }
        }
        check(hiragana + katakana + trie == 0) {
            "Surface mismatch after build: hiraganaSentinel=$hiragana katakanaSentinel=$katakana trie=$trie\n" +
                examples.joinToString("\n")
        }
        return Audit(hiragana, katakana, trie, examples)
    }

    private fun requireWritten(vararg paths: Path) {
        for (p in paths) check(Files.isRegularFile(p) && Files.size(p) > 0) { "Upstream writer did not produce ${p.fileName}" }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatManifest(r: BuildReport): String = buildString {
        appendLine("format=${OssResources.FORMAT_VERSION}")
        appendLine("upstreamRevision=$UPSTREAM_REVISION")
        appendLine("upstreamPatches=$UPSTREAM_PATCHES")
        appendLine("records=${r.records}")
        appendLine("readings=${r.readings}")
        appendLine("idCount=${r.idCount}")
        appendLine("spellingCorrectionRows=${r.spellingCorrectionRows}")
        appendLine("verified=${r.verified}")
        for ((name, sha) in r.inputSha256.toSortedMap()) appendLine("sha256.$name=$sha")
    }

    internal fun formatReport(r: BuildReport): String = buildString {
        appendLine(
            "OSS converter build (upstream kotlin-kana-kanji-converter $UPSTREAM_REVISION + patch $UPSTREAM_PATCHES; " +
                "not Mozc's engine, not Zenzai)",
        )
        appendLine("ids=${r.idCount} matrix=${r.matrixSize}x${r.matrixSize} posPairs=${r.posPairs}")
        for (f in r.files) {
            appendLine(
                "file ${f.name}: records=${f.records} readingsOutsideConvertibleSet=${f.readingsOutsideConvertibleSet} " +
                    "negativeCosts=${f.negativeCosts} spellingCorrectionRows=${f.spellingCorrectionRows}",
            )
        }
        appendLine("records=${r.records} readings=${r.readings} (all records kept)")
        appendLine("exactDuplicates=${r.exactDuplicates} costVariants=${r.costVariants} (kept, not merged)")
        appendLine(
            "spellingCorrectionRows=${r.spellingCorrectionRows} (tag counted only; converted as ordinary entries, " +
                "Mozc's spelling-correction handling not implemented)",
        )
        appendLine("verified=${r.verified} (every record's IDs, cost and exact surface read back through upstream lookups)")
        appendLine("surfaceRewrites hiraganaSentinel=${r.hiraganaRewrites} katakanaSentinel=${r.katakanaRewrites} trie=${r.trieRewrites}")
        for (e in r.rewriteExamples) appendLine("  rewrite $e")
    }
}
