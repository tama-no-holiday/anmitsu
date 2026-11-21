package dev.astra.ime.oss

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Prepares publicly distributed Mozc-UT dictionaries (utuhiro78/mozcdic-ut-sudachidict, -neologd,
 * -personal-names) as one extra Mozc-format source for `ossCli build --extra`. Same idea as upstream
 * merge-ut-dictionaries (assign a Mozc POS id, drop rows Mozc already has), written from scratch here; it does not
 * recompute costs from Wikipedia and needs no network.
 *
 * Decisions, all explicit and recorded in the provenance file:
 * - UT rows carry the placeholder ids `0000`. Id 0 is BOS/EOS in Mozc, so such rows are never passed through: each
 *   source declares a [Pos] and its id is looked up **by name** in the pinned id.def (whose SHA-256 must match).
 *   A row with any other id is rejected, because ids from another Mozc revision mean something else.
 * - A (reading, surface) pair that Mozc (dictionary + suffix) or a prior extra (e.g. SudachiEnglish output) already
 *   has is skipped, so base costs are kept unchanged. Within the UT sources the earlier source on the command line
 *   wins, and within one source the cheapest row wins. Every skip is counted, never reported as an addition.
 * - Cost = max(source cost, [Policy.costFloor]), and at least Mozc's cheapest cost for the same reading +
 *   [Policy.relativePenalty]. This is a heuristic, not a ranking guarantee: only the word cost is raised, and the
 *   lattice adds POS connection costs, so a rare word can still win in some contexts. `ossCli compare` (the
 *   regression gate) decides whether a build is acceptable.
 * - Spellings are never rewritten: surfaces that are not NFC, have control/format/private-use/unassigned code
 *   points, leading/trailing spaces or are too long are rejected, not normalized. Readings must be convertible
 *   hiragana (the converter cannot reach anything else).
 * - The output is sorted by reading, then surface (one row per pair), so the same inputs give the same bytes.
 */
object UtDictionaryImport {
    const val EXTRA = "ut-extra.txt"
    const val PROVENANCE = "ut-provenance.txt"
    const val SUMMARY = "ut-summary.txt"
    const val NOTICES = "notices"
    const val SOURCE_PROPERTIES = "source.properties"

    /** Mozc POS chosen per source; the id comes from the pinned id.def entry with exactly this name. */
    enum class Pos(val key: String, val idDefName: String) {
        GENERAL_NOUN("general-noun", "名詞,一般,*,*,*,*,*"),
        PERSON_NAME("person-name", "名詞,固有名詞,人名,一般,*,*,*"),
        ;

        companion object {
            fun of(key: String): Pos = entries.firstOrNull { it.key == key }
                ?: throw MozcFormatException("unknown pos '$key' (expected ${entries.joinToString { it.key }})")
        }
    }

    data class Policy(
        val costFloor: Int = 8000,
        val relativePenalty: Int = 1000,
        val maxReadingChars: Int = 32,
        val maxSurfaceChars: Int = 48,
        val maxLineChars: Int = 512,
        /** The whole run fails when more than this share of a source's rows is rejected (a format change). */
        val maxRejectRatio: Double = 0.01,
        val maxRowsPerSource: Int = 5_000_000,
        val maxSourceBytes: Long = 512L * 1024 * 1024,
    ) {
        init {
            require(costFloor in 0..Short.MAX_VALUE && relativePenalty in 0..Short.MAX_VALUE)
            require(maxReadingChars > 0 && maxSurfaceChars > 0 && maxLineChars > 0)
            require(maxRejectRatio in 0.0..1.0 && maxRowsPerSource > 0 && maxSourceBytes > 0)
        }
    }

    enum class Reject { BLANK, COLUMNS, LINE_TOO_LONG, READING, SURFACE, UNICODE, NOT_NFC, ID, COST }

    /** Counts per source; [read] = [kept] + all duplicates + all rejects. */
    class SourceStats(val name: String) {
        var read = 0
        var kept = 0
        var duplicateOfBase = 0
        var duplicateOfPriorExtra = 0
        var duplicateOfEarlierSource = 0
        var duplicateWithinSource = 0
        var raisedToFloor = 0
        var raisedRelative = 0
        val rejected = Reject.entries.associateWith { 0 }.toMutableMap()
        /** First line numbers per reason (no row content). */
        val rejectedLines = Reject.entries.associateWith { ArrayList<Int>() }
        val rejectedTotal get() = rejected.values.sum()
    }

    class Result(val stats: List<SourceStats>, val rows: Int, val readings: Int, val provenance: String, val summary: String, val sha256: String?)

    /** A fetched source directory: [SOURCE_PROPERTIES], the decompressed text and its notice files. */
    class Source(val dir: Path, val props: Map<String, String>) {
        val name get() = props.getValue("name")
        val pos get() = Pos.of(props.getValue("pos"))
        val text: Path get() = dir.resolve(props.getValue("text"))
        val notices: List<String> get() = props.getValue("notices").split(',').filter { it.isNotEmpty() }
    }

    private val HEX40 = Regex("[0-9a-f]{40}")
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val NAME = Regex("[a-z0-9][a-z0-9-]{0,39}")
    private val FILE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
    private val REQUIRED = listOf("name", "repository", "revision", "archive", "archiveSha256", "text", "textSha256", "license", "pos", "notices")
    private const val MAX_EXAMPLE_LINES = 10

    /** Reads and checks a source directory (properties syntax, names, hashes of the text and every notice). */
    fun readSource(dir: Path): Source {
        val file = dir.resolve(SOURCE_PROPERTIES)
        if (!Files.isRegularFile(file)) throw MozcFormatException("Missing $SOURCE_PROPERTIES in $dir")
        val props = LinkedHashMap<String, String>()
        Files.readAllLines(file, StandardCharsets.UTF_8).forEachIndexed { i, line ->
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val eq = line.indexOf('=')
            if (eq <= 0) throw MozcFormatException("$SOURCE_PROPERTIES:${i + 1}: expected key=value")
            val key = line.substring(0, eq)
            if (props.put(key, line.substring(eq + 1)) != null) throw MozcFormatException("$SOURCE_PROPERTIES:${i + 1}: duplicate $key")
        }
        for (key in REQUIRED) if (props[key].isNullOrEmpty()) throw MozcFormatException("$SOURCE_PROPERTIES: missing $key")
        fun valid(ok: Boolean, what: String) { if (!ok) throw MozcFormatException("$SOURCE_PROPERTIES: invalid $what") }
        valid(NAME.matches(props.getValue("name")), "name")
        valid(props.getValue("repository").startsWith("https://github.com/"), "repository")
        valid(HEX40.matches(props.getValue("revision")), "revision (must be a pinned 40-hex commit)")
        valid(HEX64.matches(props.getValue("archiveSha256")), "archiveSha256")
        valid(HEX64.matches(props.getValue("textSha256")), "textSha256")
        valid(FILE.matches(props.getValue("archive")) && FILE.matches(props.getValue("text")), "file name")
        val source = Source(dir, props)
        Pos.of(props.getValue("pos"))
        valid(source.notices.isNotEmpty() && source.notices.all { FILE.matches(it) }, "notices")
        if (!Files.isRegularFile(source.text) || sha256(source.text) != props.getValue("textSha256")) {
            throw MozcFormatException("${source.name}: text missing or does not match textSha256")
        }
        for (n in source.notices) {
            val expected = props["notice.$n.sha256"]
            valid(expected != null && HEX64.matches(expected), "notice.$n.sha256")
            val path = dir.resolve(n)
            if (!Files.isRegularFile(path) || sha256(path) != expected) throw MozcFormatException("${source.name}: notice $n missing or changed")
        }
        return source
    }

    /** id.def descriptions -> ids (Mozc lines are `<id> <description>`). */
    internal fun idsByName(idDef: Path): Map<String, List<Int>> {
        MozcSourceParser.readIdDef(idDef) // same strict format checks as the builder
        val out = HashMap<String, MutableList<Int>>()
        for (line in Files.readAllLines(idDef, StandardCharsets.UTF_8)) {
            val space = line.indexOf(' ')
            out.getOrPut(line.substring(space + 1)) { ArrayList(1) } += line.substring(0, space).toInt()
        }
        return out
    }

    /** The single id.def id named [pos]; fails if the name is missing or ambiguous in this id.def. */
    fun resolve(idDef: Path, pos: Pos): Int {
        val ids = idsByName(idDef)[pos.idDefName].orEmpty()
        if (ids.size != 1) throw MozcFormatException("id.def has ${ids.size} entries named '${pos.idDefName}' (need exactly 1)")
        if (ids[0] == 0) throw MozcFormatException("'${pos.idDefName}' resolves to BOS/EOS id 0")
        return ids[0]
    }

    private class Row(val reading: String, val surface: String, val cost: Int, val sourceIndex: Int, val posId: Int)

    /**
     * Validates and merges [sources] (in order) against [mozc] and [priorExtras]. Writes [EXTRA], [PROVENANCE],
     * copies every notice under [NOTICES]/<source>/ and writes [SUMMARY] last into [out] (which must not exist or be
     * empty), or writes nothing when [out] is null (check only). Nothing outside [out] is touched.
     */
    fun prepare(
        mozc: MozcInputs,
        idDefSha256: String,
        priorExtras: List<Path>,
        sources: List<Source>,
        out: Path?,
        policy: Policy = Policy(),
    ): Result {
        require(sources.isNotEmpty()) { "no UT sources" }
        require(sources.map { it.name }.toSet().size == sources.size) { "duplicate source names" }
        if (!HEX64.matches(idDefSha256)) throw MozcFormatException("--id-def-sha256 must be 64 lowercase hex digits")
        val actualIdDef = sha256(mozc.idDef)
        if (actualIdDef != idDefSha256) throw MozcFormatException("id.def is not the pinned revision (sha256 $actualIdDef)")
        if (out != null && Files.exists(out)) {
            require(Files.isDirectory(out)) { "Output is not a directory: $out" }
            Files.list(out).use { require(!it.findAny().isPresent) { "Output directory is not empty: $out" } }
        }
        val posIds = sources.associate { it.name to resolve(mozc.idDef, it.pos) }

        // Pass 1: strict per-source parsing; cheapest row per pair within a source, earlier source wins across.
        val stats = sources.map { SourceStats(it.name) }
        val chosen = HashMap<String, Row>()
        sources.forEachIndexed { index, source ->
            val s = stats[index]
            val size = Files.size(source.text)
            if (size > policy.maxSourceBytes) throw MozcFormatException("${source.name}: $size bytes exceed ${policy.maxSourceBytes}")
            val own = HashMap<String, Row>()
            Files.newBufferedReader(source.text, StandardCharsets.UTF_8).use { reader ->
                var lineNumber = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber++
                    if (lineNumber == 1 && line.startsWith('﻿')) throw MozcFormatException("${source.name}:1: unexpected BOM")
                    s.read++
                    if (s.read > policy.maxRowsPerSource) throw MozcFormatException("${source.name}: more than ${policy.maxRowsPerSource} rows")
                    val row = when (val parsed = parse(line, policy, posIds.getValue(source.name), index)) {
                        is Row -> parsed
                        else -> {
                            val reason = parsed as Reject
                            s.rejected[reason] = s.rejected.getValue(reason) + 1
                            s.rejectedLines.getValue(reason).let { if (it.size < MAX_EXAMPLE_LINES) it += lineNumber }
                            continue
                        }
                    }
                    val key = row.reading + '\t' + row.surface
                    val old = own[key]
                    if (old != null) {
                        s.duplicateWithinSource++
                        if (row.cost < old.cost) own[key] = row
                    } else {
                        own[key] = row
                    }
                }
            }
            if (s.read == 0) throw MozcFormatException("${source.name}: no rows")
            source.props["expectedRows"]?.let { expected ->
                if (expected != s.read.toString()) throw MozcFormatException("${source.name}: ${s.read} rows, pinned expectedRows=$expected")
            }
            val ratio = s.rejectedTotal.toDouble() / s.read
            if (ratio > policy.maxRejectRatio) {
                throw MozcFormatException(
                    "${source.name}: ${s.rejectedTotal} of ${s.read} rows rejected (> ${policy.maxRejectRatio}); " +
                        "format changed? ${s.rejected.filterValues { it > 0 }}",
                )
            }
            for ((key, row) in own) {
                if (key in chosen) s.duplicateOfEarlierSource++ else chosen[key] = row
            }
        }

        // Pass 2: Mozc and prior extras, only for the readings that matter.
        val readings = chosen.values.mapTo(HashSet()) { it.reading }
        val baseMin = HashMap<String, Int>()
        val basePairs = HashSet<String>()
        val priorPairs = HashSet<String>()
        fun scan(path: Path, isBase: Boolean) {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val tab = line.indexOf('\t')
                    if (tab <= 0 || line.substring(0, tab) !in readings) continue
                    val f = line.split('\t')
                    if (f.size < 5) throw MozcFormatException("${path.fileName}: malformed row")
                    val key = f[0] + '\t' + f[4]
                    if (isBase) {
                        basePairs += key
                        val cost = MozcSourceParser.strictInt(f[3]) ?: throw MozcFormatException("${path.fileName}: non-decimal cost")
                        baseMin.merge(f[0], cost, ::minOf)
                    } else {
                        priorPairs += key
                    }
                }
            }
        }
        for (p in mozc.dictionaries + listOfNotNull(mozc.suffix)) scan(p, isBase = true)
        for (p in priorExtras) scan(p, isBase = false)

        val kept = ArrayList<Row>()
        for ((key, row) in chosen) {
            val s = stats[row.sourceIndex]
            when (key) {
                in basePairs -> s.duplicateOfBase++
                in priorPairs -> s.duplicateOfPriorExtra++
                else -> kept += row
            }
        }
        kept.sortWith(compareBy<Row> { it.reading }.thenBy { it.surface })
        val lines = StringBuilder()
        for (row in kept) {
            val s = stats[row.sourceIndex]
            var cost = row.cost
            if (cost < policy.costFloor) {
                cost = policy.costFloor
                s.raisedToFloor++
            }
            val min = baseMin[row.reading]
            if (min != null && cost < min + policy.relativePenalty) {
                cost = min + policy.relativePenalty
                s.raisedRelative++
            }
            cost = cost.coerceAtMost(Short.MAX_VALUE.toInt())
            s.kept++
            lines.append(row.reading).append('\t').append(row.posId).append('\t').append(row.posId).append('\t')
                .append(cost).append('\t').append(row.surface).append('\n')
        }
        val bytes = lines.toString().toByteArray(StandardCharsets.UTF_8)
        val outSha = if (kept.isEmpty()) null else sha256(bytes)
        val provenance = formatProvenance(sources, posIds, actualIdDef, priorExtras, policy, stats, kept.size, outSha)
        val summary = formatSummary(stats, kept.size, kept.mapTo(HashSet()) { it.reading }.size, out == null)
        if (out != null) {
            if (kept.isEmpty()) throw MozcFormatException("No rows left to add; nothing written")
            Files.createDirectories(out)
            Files.write(out.resolve(EXTRA), bytes)
            Files.write(out.resolve(PROVENANCE), provenance.toByteArray(StandardCharsets.UTF_8))
            for (source in sources) {
                val dir = Files.createDirectories(out.resolve(NOTICES).resolve(source.name))
                for (n in source.notices) Files.copy(source.dir.resolve(n), dir.resolve(n))
            }
            // Written last: its presence marks a complete staged output.
            Files.write(out.resolve(SUMMARY), summary.toByteArray(StandardCharsets.UTF_8))
        }
        return Result(stats, kept.size, kept.mapTo(HashSet()) { it.reading }.size, provenance, summary, outSha)
    }

    /** A valid [Row] or the [Reject] reason. */
    private fun parse(line: String, policy: Policy, posId: Int, sourceIndex: Int): Any {
        if (line.isEmpty()) return Reject.BLANK
        if (line.length > policy.maxLineChars) return Reject.LINE_TOO_LONG
        val f = line.split('\t')
        if (f.size != 5) return Reject.COLUMNS
        val (reading, left, right, cost, surface) = f
        val l = MozcSourceParser.strictInt(left)
        val r = MozcSourceParser.strictInt(right)
        // Only the placeholder is accepted; it never reaches the output.
        if (l != 0 || r != 0) return Reject.ID
        val c = MozcSourceParser.strictInt(cost) ?: return Reject.COST
        if (c !in 0..Short.MAX_VALUE) return Reject.COST
        if (reading.isEmpty() || reading.length > policy.maxReadingChars || !reading.all(::isConvertible)) return Reject.READING
        if (surface.isEmpty() || surface.length > policy.maxSurfaceChars || surface.first() == ' ' || surface.last() == ' ') {
            return Reject.SURFACE
        }
        if (!validCodePoints(surface)) return Reject.UNICODE
        if (!Normalizer.isNormalized(surface, Normalizer.Form.NFC)) return Reject.NOT_NFC
        return Row(reading, surface, c, sourceIndex, posId)
    }

    /** No controls, format characters, unpaired surrogates, private use, unassigned, U+FFFD or whitespace other than ' '. */
    internal fun validCodePoints(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0xFFFD) return false
            if (cp != ' '.code && (Character.isWhitespace(cp) || Character.isSpaceChar(cp))) return false
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                -> return false
            }
        }
        return true
    }

    private fun formatProvenance(
        sources: List<Source>,
        posIds: Map<String, Int>,
        idDefSha: String,
        priorExtras: List<Path>,
        policy: Policy,
        stats: List<SourceStats>,
        rows: Int,
        outSha: String?,
    ): String = buildString {
        appendLine("format=1")
        appendLine("tool=ossCli ut-prepare (UtDictionaryImport)")
        appendLine("output=$EXTRA")
        appendLine("outputRows=$rows")
        appendLine("outputSha256=${outSha ?: "-"}")
        appendLine("mozcIdDefSha256=$idDefSha")
        // Unicode validity checks use the running JDK's character database.
        appendLine("unicodeCheck=java.lang.Character (JDK ${Runtime.version().feature()})")
        appendLine(
            "policy.costFloor=${policy.costFloor} policy.relativePenalty=${policy.relativePenalty} " +
                "policy.maxReadingChars=${policy.maxReadingChars} policy.maxSurfaceChars=${policy.maxSurfaceChars} " +
                "policy.maxRejectRatio=${policy.maxRejectRatio}",
        )
        for (p in priorExtras) appendLine("priorExtra.${p.fileName}.sha256=${sha256(p)}")
        sources.forEachIndexed { i, src ->
            val prefix = "source.$i.${src.name}"
            for (key in REQUIRED) appendLine("$prefix.$key=${src.props.getValue(key)}")
            for (n in src.notices) appendLine("$prefix.notice.$n.sha256=${src.props.getValue("notice.$n.sha256")}")
            appendLine("$prefix.posName=${src.pos.idDefName}")
            appendLine("$prefix.posId=${posIds.getValue(src.name)}")
            appendLine("$prefix.kept=${stats[i].kept}")
        }
    }

    internal fun formatSummary(stats: List<SourceStats>, rows: Int, readings: Int, checkOnly: Boolean): String = buildString {
        appendLine("UT import${if (checkOnly) " (check only, nothing written)" else ""}: rows=$rows readings=$readings")
        for (s in stats) {
            appendLine(
                "source ${s.name}: read=${s.read} kept=${s.kept} duplicateOfBase=${s.duplicateOfBase} " +
                    "duplicateOfPriorExtra=${s.duplicateOfPriorExtra} duplicateOfEarlierSource=${s.duplicateOfEarlierSource} " +
                    "duplicateWithinSource=${s.duplicateWithinSource} rejected=${s.rejectedTotal} " +
                    "raisedToFloor=${s.raisedToFloor} raisedRelative=${s.raisedRelative}",
            )
            for ((reason, n) in s.rejected) if (n > 0) appendLine("  rejected $reason=$n firstLines=${s.rejectedLines.getValue(reason)}")
        }
        appendLine("kept counts unique (reading, surface) pairs added to the engine, not source rows.")
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
