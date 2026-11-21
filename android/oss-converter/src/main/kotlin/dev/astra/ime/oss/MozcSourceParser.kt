package dev.astra.ime.oss

import com.kazumaproject.dictionary.models.Dictionary
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

/** Input that does not match the expected Mozc source format; never silently skipped. */
class MozcFormatException(message: String) : IllegalArgumentException(message)

/**
 * Per-file load counts. Readings outside [isConvertible] can never be reached by [OssConverter].
 * [spellingCorrectionRows] carry the sixth-column [MozcSourceParser.SPELLING_CORRECTION] tag; they are
 * included in [records] and kept as ordinary entries.
 */
data class SourceFileStats(
    val name: String,
    val records: Int,
    val readingsOutsideConvertibleSet: Int,
    val negativeCosts: Int,
    val spellingCorrectionRows: Int = 0,
)

/**
 * Thin, strict readers for unmodified Mozc dictionary_oss sources. Each data line must be exactly
 * `reading\tleftId\trightId\tcost\tsurface`, optionally followed by one sixth column that is exactly
 * [SPELLING_CORRECTION]. The first five fields are kept as-is (upstream stores IDs and costs as Short).
 * The tag is only counted for provenance: upstream's Dictionary has no field for it and Mozc's
 * spelling-correction handling is not implemented, so tagged rows convert like any other entry.
 * Any malformed line aborts with file and line number: blank lines, BOM, extra/missing columns, unknown tags,
 * non-decimal numbers, IDs outside id.def, or values that do not fit upstream's Short fields.
 */
object MozcSourceParser {
    const val SPELLING_CORRECTION = "SPELLING_CORRECTION"

    /** Returns the number of POS IDs; Mozc id.def lines are `<id> <description>` with IDs 0..n-1 in order. */
    fun readIdDef(path: Path): Int {
        var count = 0
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            var lineNumber = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber++
                val where = "${path.fileName}:$lineNumber"
                if (lineNumber == 1 && line.startsWith('﻿')) throw MozcFormatException("$where: unexpected BOM")
                val space = line.indexOf(' ')
                if (space <= 0 || space == line.length - 1) throw MozcFormatException("$where: expected '<id> <pos>'")
                val id = strictInt(line.substring(0, space))
                    ?: throw MozcFormatException("$where: non-decimal id")
                if (id != count) throw MozcFormatException("$where: id $id is not contiguous (expected $count)")
                count++
            }
        }
        if (count == 0) throw MozcFormatException("${path.fileName}: no ids")
        if (count - 1 > Short.MAX_VALUE) throw MozcFormatException("${path.fileName}: $count ids exceed upstream Short range")
        return count
    }

    /**
     * Appends every record of [path] to [into] (grouped by reading, file order kept within a reading). With
     * [rejectBosEos] (extra sources), id 0 is refused: it is BOS/EOS, and Mozc-UT files use `0000` as a placeholder
     * that must be resolved first (see [UtDictionaryImport]).
     */
    fun readDictionary(
        path: Path,
        idCount: Int,
        into: TreeMap<String, MutableList<Dictionary>>,
        rejectBosEos: Boolean = false,
    ): SourceFileStats {
        var records = 0
        var outside = 0
        var negative = 0
        var tagged = 0
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            var lineNumber = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber++
                val where = "${path.fileName}:$lineNumber"
                if (lineNumber == 1 && line.startsWith('﻿')) throw MozcFormatException("$where: unexpected BOM")
                if (line.isEmpty()) throw MozcFormatException("$where: blank line")
                val fields = line.split('\t')
                if (fields.size != 5 && fields.size != 6) {
                    throw MozcFormatException("$where: expected 5 tab-separated columns (or 6 with $SPELLING_CORRECTION), got ${fields.size}")
                }
                if (fields.size == 6) {
                    if (fields[5] != SPELLING_CORRECTION) throw MozcFormatException("$where: unknown sixth-column tag")
                    tagged++
                }
                val (reading, left, right, cost, surface) = fields
                if (reading.isBlank()) throw MozcFormatException("$where: blank reading")
                if (surface.isBlank()) throw MozcFormatException("$where: blank surface")
                val leftId = id(left, idCount, "left id", where)
                val rightId = id(right, idCount, "right id", where)
                if (rejectBosEos && (leftId.toInt() == 0 || rightId.toInt() == 0)) {
                    throw MozcFormatException("$where: id 0 (BOS/EOS) in an extra source; resolve UT placeholders with ut-prepare")
                }
                val costValue = strictInt(cost) ?: throw MozcFormatException("$where: non-decimal cost")
                if (costValue !in Short.MIN_VALUE..Short.MAX_VALUE) {
                    throw MozcFormatException("$where: cost $costValue outside upstream Short range")
                }
                if (costValue < 0) negative++
                if (!reading.all(::isConvertible)) outside++
                into.getOrPut(reading) { ArrayList(2) }
                    .add(Dictionary(reading, leftId, rightId, costValue.toShort(), surface))
                records++
            }
        }
        return SourceFileStats(path.fileName.toString(), records, outside, negative, tagged)
    }

    private fun id(text: String, idCount: Int, label: String, where: String): Short {
        val value = strictInt(text) ?: throw MozcFormatException("$where: non-decimal $label")
        if (value !in 0 until idCount) throw MozcFormatException("$where: $label $value outside id.def range 0..${idCount - 1}")
        return value.toShort()
    }

    /** Optional '-' followed by ASCII digits only; no '+', spaces or overflow. */
    internal fun strictInt(text: String): Int? {
        if (text.isEmpty() || text.length > 11) return null
        val start = if (text[0] == '-') 1 else 0
        if (start == text.length) return null
        for (i in start until text.length) if (text[i] !in '0'..'9') return null
        return text.toIntOrNull()
    }
}
