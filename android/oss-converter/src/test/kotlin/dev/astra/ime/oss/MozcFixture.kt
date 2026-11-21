package dev.astra.ime.oss

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * A tiny, generated Mozc-format source tree (same file names and column layout as dictionary_oss).
 * IDs: 0 BOS/EOS, 1 noun, 2 particle, 3 auxiliary. Costs are invented for tests only.
 */
internal object MozcFixture {
    val ID_DEF = listOf(
        "0 BOS/EOS,*,*,*,*,*,*",
        "1 名詞,一般,*,*,*,*,*",
        "2 助詞,係助詞,*,*,*,*,は",
        "3 助動詞,*,*,*,特殊・デス,基本形,です",
    )

    // MATRIX[prev.rightId][next.leftId]; written row-major, one signed value per line.
    val MATRIX = arrayOf(
        intArrayOf(0, 100, 900, 900),
        intArrayOf(0, 300, -100, 200),
        intArrayOf(0, 50, 900, 900),
        intArrayOf(0, 900, 900, 900),
    )

    val DICTIONARY00 = listOf(
        "きょう\t1\t1\t3000\t今日",
        "きょう\t1\t1\t4000\t京",
        "あめ\t1\t1\t3500\t雨",
        "あめ\t1\t1\t3600\t飴",
    )

    val DICTIONARY01 = listOf(
        "は\t2\t2\t1000\tは",
        "です\t3\t3\t1500\tです",
        "てれび\t1\t1\t3000\tテレビ",
        // Katakana surface that is not the reading's katakana: unpatched upstream rendered バイオリン.
        "ばいおりん\t1\t1\t3000\tヴァイオリン",
        // Exact duplicate of a dictionary00 row: kept and counted, never merged.
        "あめ\t1\t1\t3500\t雨",
        // Hiragana surface spelled differently from the reading: unpatched upstream rendered こんにちわ.
        "こんにちわ\t1\t1\t3000\tこんにちは",
        // hiraToKata leaves ゔ unchanged: unpatched upstream rendered ゔァイオリン.
        "ゔぁいおりん\t1\t1\t3100\tヴァイオリン",
        // Decomposed (NFD) surface う+U+3099: unpatched upstream rendered the precomposed reading ゔ.
        "ゔ\t1\t1\t4000\tゔ",
        // Mozc's optional sixth column: counted, kept as an ordinary entry.
        "きよう\t1\t1\t5000\t器用\tSPELLING_CORRECTION",
    )

    // Same 5-column layout as the pinned Mozc suffix.txt.
    val SUFFIX = listOf("さん\t1\t1\t2500\tさん")

    /** Writes dictionary00..09 (02..09 empty), id.def, connection_single_column.txt and suffix.txt. */
    fun write(
        dir: Path,
        dictionary00: List<String> = DICTIONARY00,
        idDef: List<String> = ID_DEF,
        matrixLines: List<String> = matrixLines(),
    ): Path {
        Files.createDirectories(dir)
        for (i in 0..9) {
            val lines = when (i) {
                0 -> dictionary00
                1 -> DICTIONARY01
                else -> emptyList()
            }
            writeLines(dir.resolve("dictionary%02d.txt".format(i)), lines)
        }
        writeLines(dir.resolve("suffix.txt"), SUFFIX)
        writeLines(dir.resolve("id.def"), idDef)
        writeLines(dir.resolve("connection_single_column.txt"), matrixLines)
        return dir
    }

    fun matrixLines(): List<String> = listOf(MATRIX.size.toString()) + MATRIX.flatMap { row -> row.map { it.toString() } }

    fun writeLines(path: Path, lines: List<String>) {
        Files.write(path, lines.joinToString("") { "$it\n" }.toByteArray(StandardCharsets.UTF_8))
    }
}
