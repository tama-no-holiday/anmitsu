package dev.astra.ime.oss

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Case-insensitive index of single-word Latin spellings (OpenAI, GitHub, iPhone, ...) for English typing: the typed
 * word, lower-cased, finds its canonical spellings. Built only from surfaces of existing OSS Mozc-format sources
 * (the SudachiDict Latin extraction `english-20261015.txt` and the Mozc-UT `ut-extra.txt`); nothing is invented
 * and no reading is used. A surface qualifies when it is [MIN_WORD]..[MAX_WORD] ASCII letters, i.e. exactly what
 * the English keys can compose.
 *
 * File (UTF-8, in fact ASCII; deterministic, no timestamps): `#` provenance lines (format, each source's name,
 * SHA-256, rows and kept surfaces, totals), then one line per key sorted by key: `key \t cost \t surface...`.
 * The cost is the key's cheapest source cost; surfaces are ordered by cost, then by how many source rows carry
 * them (more first), then by code point, at most [MAX_SURFACES] per key.
 */
object EnglishIndex {
    const val FORMAT = 1
    const val MIN_WORD = 2
    const val MAX_WORD = 32
    const val MAX_SURFACES = 8

    class SourceStats(val name: String, val sha256: String, val rows: Int, val kept: Int)
    class Stats(val sources: List<SourceStats>, val keys: Int, val surfaces: Int, val bytes: Int, val sha256: String)

    fun isWord(s: CharSequence): Boolean = s.length in MIN_WORD..MAX_WORD && s.all { it in 'a'..'z' || it in 'A'..'Z' }

    /** Builds the index bytes from Mozc-format sources (reading, left id, right id, cost, surface). */
    fun build(sources: List<Path>): Pair<ByteArray, Stats> {
        require(sources.isNotEmpty()) { "no sources" }
        // surface -> [min cost, rows]
        val surfaces = HashMap<String, IntArray>()
        val sourceStats = sources.map { path ->
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = Files.readAllBytes(path)
            digest.update(bytes)
            var rows = 0
            val kept = HashSet<String>()
            String(bytes, StandardCharsets.UTF_8).lineSequence().forEach { line ->
                if (line.isEmpty()) return@forEach
                rows++
                val fields = line.split('\t')
                if (fields.size < 5) return@forEach
                val surface = fields[4]
                val cost = fields[3].toIntOrNull() ?: return@forEach
                if (!isWord(surface)) return@forEach
                kept += surface
                val v = surfaces.getOrPut(surface) { intArrayOf(cost, 0) }
                if (cost < v[0]) v[0] = cost
                v[1]++
            }
            SourceStats(path.fileName.toString(), digest.digest().joinToString("") { "%02x".format(it) }, rows, kept.size)
        }
        val byKey = java.util.TreeMap<String, MutableList<String>>()
        for (s in surfaces.keys) byKey.getOrPut(s.lowercase()) { ArrayList() } += s
        val body = StringBuilder()
        var written = 0
        for ((key, list) in byKey) {
            list.sortWith(compareBy<String>({ surfaces.getValue(it)[0] }, { -surfaces.getValue(it)[1] }).thenBy { it })
            val kept = list.take(MAX_SURFACES)
            written += kept.size
            body.append(key).append('\t').append(surfaces.getValue(kept.first())[0])
            for (s in kept) body.append('\t').append(s)
            body.append('\n')
        }
        val header = StringBuilder("# astra-english-index format=$FORMAT minWord=$MIN_WORD maxWord=$MAX_WORD maxSurfaces=$MAX_SURFACES\n")
        for (s in sourceStats) header.append("# source ${s.name} sha256=${s.sha256} rows=${s.rows} keptSurfaces=${s.kept}\n")
        header.append("# keys=${byKey.size} surfaces=$written\n")
        val out = ByteArrayOutputStream()
        out.write(header.toString().toByteArray(StandardCharsets.UTF_8))
        out.write(body.toString().toByteArray(StandardCharsets.UTF_8))
        val bytes = out.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return bytes to Stats(sourceStats, byKey.size, written, bytes.size, sha)
    }
}

/**
 * Read-only view over [EnglishIndex] bytes: one byte array plus line offsets (no String per entry). A lookup is a
 * binary search; completions scan at most [maxScan] keys after the prefix, so no call walks the whole index.
 */
class EnglishLexicon(private val bytes: ByteArray) {
    private val starts: IntArray
    /** The `#` provenance lines. */
    val header: List<String>

    init {
        val lines = ArrayList<String>()
        var i = 0
        while (i < bytes.size && bytes[i] == '#'.code.toByte()) {
            val end = indexOf('\n'.code.toByte(), i)
            lines += String(bytes, i, end - i, StandardCharsets.UTF_8).removeSuffix("\r")
            i = end + 1
        }
        header = lines
        var count = 0
        var j = i
        while (j < bytes.size) {
            count++
            j = indexOf('\n'.code.toByte(), j) + 1
        }
        starts = IntArray(count)
        var n = 0
        while (i < bytes.size) {
            starts[n++] = i
            i = indexOf('\n'.code.toByte(), i) + 1
        }
    }

    val size: Int get() = starts.size

    class Result(val exact: List<String>, val completions: List<String>)

    /**
     * Canonical spellings of [word] (any case) and up to [completions] longer words starting with it (cheapest
     * first, first spelling each) among the next [maxScan] keys. Non-words give an empty result.
     */
    fun lookup(word: String, completions: Int = 3, maxScan: Int = 4096): Result {
        if (word.length > EnglishIndex.MAX_WORD || word.isEmpty() || !word.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            return Result(emptyList(), emptyList())
        }
        val key = word.lowercase().toByteArray(StandardCharsets.US_ASCII)
        var line = lowerBound(key)
        val exact = if (line < starts.size && compareKey(line, key) == 0) surfaces(line).also { line++ } else emptyList()
        if (completions <= 0) return Result(exact, emptyList())
        val best = ArrayList<IntArray>() // [cost, line]
        var scanned = 0
        while (line < starts.size && scanned < maxScan && startsWith(line, key)) {
            val cost = cost(line)
            best += intArrayOf(cost, line)
            best.sortWith(compareBy<IntArray>({ it[0] }, { it[1] }))
            if (best.size > completions) best.removeAt(best.lastIndex)
            line++
            scanned++
        }
        return Result(exact, best.map { surfaces(it[1]).first() })
    }

    private fun indexOf(b: Byte, from: Int): Int {
        var i = from
        while (i < bytes.size && bytes[i] != b) i++
        return i
    }

    private fun keyEnd(line: Int): Int = indexOf('\t'.code.toByte(), starts[line])

    private fun compareKey(line: Int, key: ByteArray): Int {
        val start = starts[line]
        val end = keyEnd(line)
        val n = minOf(end - start, key.size)
        for (i in 0 until n) {
            val d = bytes[start + i] - key[i]
            if (d != 0) return d
        }
        return (end - start) - key.size
    }

    private fun startsWith(line: Int, key: ByteArray): Boolean {
        val start = starts[line]
        if (keyEnd(line) - start < key.size) return false
        for (i in key.indices) if (bytes[start + i] != key[i]) return false
        return true
    }

    private fun lowerBound(key: ByteArray): Int {
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareKey(mid, key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun fields(line: Int): List<String> {
        val start = starts[line]
        val end = indexOf('\n'.code.toByte(), start)
        return String(bytes, start, end - start, StandardCharsets.US_ASCII).removeSuffix("\r").split('\t')
    }

    private fun cost(line: Int): Int = fields(line)[1].toInt()

    private fun surfaces(line: Int): List<String> = fields(line).drop(2)
}
