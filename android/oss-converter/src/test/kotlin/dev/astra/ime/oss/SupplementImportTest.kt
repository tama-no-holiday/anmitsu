package dev.astra.ime.oss

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementImportTest {
    /** Rows in the shape of the pinned Mozc files (headers, comments, blank and short rows included). */
    private fun fixture() = Files.createTempDirectory("supplement").also { dir ->
        fun file(name: String, vararg lines: String) =
            Files.write(dir.resolve(name), lines.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8))
        file("symbol.tsv",
            "POS\tCHAR\tReading (space separated)\tdescription\tadditional description\tcategory\tmemo\tunicode",
            "記号\t（）\t() 「」 かっこ まるかっこ\t丸括弧",
            "# Hentaigana",
            "記号\t→\tやじるし みぎや -> ー> みぎ\t矢印\t右\tSYMBOL",
            "記号\t→\tやじるし\t矢印",
            "",
            "記号\t☆")
        file("symbol-categorized.tsv", "# value\tcategory\tdescription", "☆\tGENERAL\tほし しろぼし", "→\tARROW\tやじるし")
        file("emoticon.tsv", "\tkeys (space separated)\tcategories (space separated)", "(^^)\tにこにこ にこ\tSMILE")
        file("emoticon-categorized.tsv", "# value\tcategory\tdescription", "( ´ ▽ ` )\tSMILE\tにこにこ")
        file("emoji_data.tsv", "# comment", "1F431\t🐱\tねこ\tCAT FACE\t猫の顔\t猫\tE0.6", "1F408 200D 2B1B\t🐈‍⬛\t\tBLACK CAT\t\t\tE13.0")
        file("single_kanji.tsv", "し\t士子𠮟", "かっこ\t括", "し\t士詩")
    }

    @Test fun everyKeyAndSurfaceComesFromTheFilesInKindOrder() {
        val dir = fixture()
        val result = SupplementImport.prepare(dir)
        assertEquals(
            listOf(
                "S\t()\t（）",
                "S\t->\t→",
                "S\t「」\t（）",
                "S\tかっこ\t（）",
                "S\tしろぼし\t☆",
                "S\tほし\t☆",
                "S\tまるかっこ\t（）",
                "S\tみぎ\t→",
                "S\tみぎや\t→",
                "S\tやじるし\t→",
                "S\tー>\t→",
                "F\tかおもじ\t(^^)\t( ´ ▽ ` )",
                "F\tにこ\t(^^)",
                "F\tにこにこ\t(^^)\t( ´ ▽ ` )",
                "E\tえもじ\t🐱\t🐈‍⬛",
                "E\tねこ\t🐱",
                "K\tかっこ\t括",
                "K\tし\t士子𠮟詩",
            ),
            result.lines,
        )
        assertEquals(mapOf('S' to 11, 'F' to 5, 'E' to 3, 'K' to 5), result.counts)
    }

    @Test fun theWrittenFileRecordsEverySourceHashAndParsesBack() {
        val dir = fixture()
        val out = dir.resolve("out/mozc-supplement.tsv")
        SupplementImport.write(dir, out)
        val text = String(Files.readAllBytes(out), StandardCharsets.UTF_8)
        for (name in SupplementImport.FILES) assertTrue(name, Regex("# file ${Regex.escape(name)} sha256=[0-9a-f]{64}").containsMatchIn(text))
        assertTrue(text.lines().filter { it.isNotEmpty() && !it.startsWith("#") }.all { Regex("^[SFEK]\t[^\t]+\t.+").matches(it) })
    }
}
