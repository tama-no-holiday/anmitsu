package dev.astra.ime.oss

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Selects Latin-alphabet spellings (Google, Claude, iPhone, ...) with their katakana readings from unmodified
 * SudachiDict raw lexicons (Apache-2.0, https://github.com/WorksApplications/SudachiDict) and writes them as an
 * extra Mozc-format source file for [OssDictionaryBuilder]. Mozc dictionary_oss has few Latin surfaces, so
 * reading グーグル only converts to katakana without this. Nothing is invented: every row comes from one
 * SudachiDict noun entry. When Mozc has a katakana entry for the reading, the spelling copies its POS ids and
 * costs [COST_OFFSET] more, so it behaves exactly like that katakana word but ranks right after it (learning then
 * moves it up). Otherwise its Sudachi POS is mapped to a Mozc noun id, at no less than [DEFAULT_COST] and
 * [UNMATCHED_OFFSET] above Mozc's cheapest entry for the reading, so it cannot displace function words
 * (e.g. です must not become DEATH).
 */
object SudachiEnglish {
    /** Added to the cost of Mozc's cheapest katakana entry for the reading. */
    const val COST_OFFSET = 300

    /** Minimum cost of a spelling without a Mozc katakana entry. */
    const val DEFAULT_COST = 6000

    /** Added to Mozc's cheapest entry of any surface when there is no katakana entry. */
    const val UNMATCHED_OFFSET = 2000

    /**
     * At most this many distinct spellings per reading (case-insensitive). Spellings from the smaller, curated
     * lexicons (small, then core) win; notcore spellings are used only for readings the others lack.
     */
    const val MAX_PER_READING = 2

    /** Sudachi costs at or above this are rare/noisy entries (e.g. KLO-D for クロード). */
    private const val MAX_SUDACHI_COST = 20000

    // Mozc dictionary_oss POS ids (id.def at the pinned revision).
    private const val NOUN = 1851 // 名詞,一般
    private const val PROPER = 1920 // 名詞,固有名詞,一般
    private const val PERSON = 1921 // 名詞,固有名詞,人名,一般
    private const val PLACE = 1924 // 名詞,固有名詞,地域,一般

    private val LATIN = Regex("[A-Za-z][A-Za-z0-9 .&'+\\-]{0,31}")

    class Stats(val rowsRead: Int, val candidates: Int, val written: Int, val readings: Int)

    private class Entry(
        val reading: String, val surface: String, val sudachiCost: Int, val hasHeadword: Boolean, val posId: Int, val tier: Int,
    )

    /** small_lex 0, core_lex 1, anything else (notcore_lex) 2. */
    private fun tier(path: Path): Int {
        val name = path.fileName.toString()
        return when {
            name.startsWith("small") -> 0
            name.startsWith("core") -> 1
            else -> 2
        }
    }

    /** Rejects odd casing such as zOoOoOm: more than two lower-to-upper changes. */
    private fun naturalCase(s: String): Boolean = s.zipWithNext().count { (a, b) -> a.isLowerCase() && b.isUpperCase() } <= 2

    fun extract(lexicons: List<Path>, mozc: MozcInputs, out: Path): Stats {
        var rowsRead = 0
        val byReading = HashMap<String, MutableList<Entry>>()
        for (path in lexicons) {
            val tier = tier(path)
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                var first = true
                while (true) {
                    val line = reader.readLine() ?: break
                    if (first) {
                        first = false
                        if (line.startsWith("IndexForm,")) continue
                    }
                    rowsRead++
                    val f = csvFields(line, 12) ?: continue
                    if (f[5] != "名詞") continue
                    val surface = f[4].ifEmpty { f[0] }
                    if (!LATIN.matches(surface) || surface.length < 2 || !naturalCase(surface)) continue
                    val katakana = f[11]
                    if (katakana.length < 2 || !katakana.all { it.code in 0x30A1..0x30F6 || it == 'ー' }) continue
                    val reading = hiragana(katakana)
                    val cost = f[3].toIntOrNull()?.takeIf { it < MAX_SUDACHI_COST } ?: continue
                    val pos = when {
                        f[6] == "固有名詞" && f[7] == "人名" -> PERSON
                        f[6] == "固有名詞" && f[7] == "地名" -> PLACE
                        f[6] == "固有名詞" -> PROPER
                        else -> NOUN
                    }
                    byReading.getOrPut(reading) { ArrayList(2) } += Entry(reading, surface, cost, f[4].isNotEmpty(), pos, tier)
                }
            }
        }
        val candidates = byReading.values.sumOf { it.size }
        val base = mozcBaseCosts(mozc, byReading.keys)
        val rows = ArrayList<String>()
        for ((reading, list) in byReading.toSortedMap()) {
            val maxTier = maxOf(list.minOf { it.tier }, 1)
            val chosen = list.filter { it.tier <= maxTier }
                .sortedWith(compareBy<Entry> { it.tier }.thenBy { !it.hasHeadword }.thenBy { it.sudachiCost }.thenBy { it.surface })
                .distinctBy { it.surface.lowercase() }
                .take(MAX_PER_READING)
            val kata = base.katakana[reading]
            val cost = (kata?.cost?.plus(COST_OFFSET) ?: maxOf(DEFAULT_COST, (base.any[reading] ?: 0) + UNMATCHED_OFFSET))
                .coerceAtMost(Short.MAX_VALUE.toInt() - MAX_PER_READING)
            chosen.forEachIndexed { k, e ->
                val left = kata?.leftId ?: e.posId
                val right = kata?.rightId ?: e.posId
                rows += "$reading\t$left\t$right\t${cost + k}\t${e.surface}"
            }
        }
        Files.write(out, rows.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8))
        return Stats(rowsRead, candidates, rows.size, byReading.size)
    }

    private class Katakana(val cost: Int, val leftId: Int, val rightId: Int)

    private class Base(val katakana: Map<String, Katakana>, val any: Map<String, Int>)

    /** Per reading: Mozc's cheapest katakana entry (cost and POS ids) and cheapest cost of any surface. */
    private fun mozcBaseCosts(mozc: MozcInputs, readings: Set<String>): Base {
        val katakana = HashMap<String, Katakana>()
        val any = HashMap<String, Int>()
        for (path in mozc.dictionaries + listOfNotNull(mozc.suffix)) {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val f = line.split('\t')
                    if (f.size < 5 || f[0] !in readings) continue
                    val cost = f[3].toIntOrNull() ?: continue
                    any.merge(f[0], cost, ::minOf)
                    if (f[4] == katakana(f[0]) && (katakana[f[0]]?.cost ?: Int.MAX_VALUE) > cost) {
                        katakana[f[0]] = Katakana(cost, f[1].toInt(), f[2].toInt())
                    }
                }
            }
        }
        return Base(katakana, any)
    }

    /** First [count] fields of a CSV line (RFC 4180 quoting), or null if the line has fewer. */
    internal fun csvFields(line: String, count: Int): List<String>? {
        val fields = ArrayList<String>(count)
        var i = 0
        while (fields.size < count) {
            if (i > line.length) return null
            val sb = StringBuilder()
            if (i < line.length && line[i] == '"') {
                i++
                while (i < line.length) {
                    if (line[i] == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') {
                            sb.append('"')
                            i += 2
                        } else {
                            i++
                            break
                        }
                    } else {
                        sb.append(line[i++])
                    }
                }
            } else {
                while (i < line.length && line[i] != ',') sb.append(line[i++])
            }
            fields += sb.toString()
            if (i < line.length && line[i] != ',') return null
            i++ // skip the comma (or step past the end)
        }
        return fields
    }

    private fun hiragana(s: String) = buildString { for (c in s) append(if (c.code in 0x30A1..0x30F6) (c.code - 0x60).toChar() else c) }

    private fun katakana(s: String) = buildString { for (c in s) append(if (c.code in 0x3041..0x3096) (c.code + 0x60).toChar() else c) }
}
