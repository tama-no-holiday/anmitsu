package dev.astra.ime.oss

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishIndexTest {
    private fun source(name: String, vararg rows: String): Path {
        val dir = Files.createTempDirectory("english-index")
        return dir.resolve(name).also { Files.write(it, rows.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8)) }
    }

    private val sudachi get() = source(
        "english.txt",
        "おーぷんえーあい\t1920\t1920\t6000\tOpenAI",
        "ぎっとはぶ\t1851\t1851\t8620\tGitHub",
        "あいふぉーん\t1920\t1920\t6431\tiPhone",
        "あいふぉーんえすいー\t1920\t1920\t6000\tiPhone SE", // two words: not composable
        "あっぷる\t1851\t1851\t5398\tApple",
        "あっぷる\t1851\t1851\t5500\tapple",
        "えっくす\t1851\t1851\t4000\tX", // one letter
        "あいぴーぶい\t1851\t1851\t6000\tIPv6", // digit
        "じゃば\t1851\t1851\t7000\tJava",
        "じゃばすくりぷと\t1920\t1920\t7093\tJavaScript",
        "じゃばし\t1920\t1920\t9000\tJavaSE",
        "かな\t1851\t1851\t3000\t仮名",
    )

    private val ut get() = source(
        "ut-extra.txt",
        "まっくおーえす\t1851\t1851\t11000\tmacOS",
        "まっくおーえす\t1851\t1851\t11000\tMacOS",
        "まっくおーえす2\t1851\t1851\t11000\tmacOS",
        "おーぷんえーあい\t1851\t1851\t11000\tOpenAI",
        "broken row",
    )

    @Test fun keepsOnlyAsciiWordsWithProvenanceAndIsDeterministic() {
        val (bytes, stats) = EnglishIndex.build(listOf(sudachi, ut))
        val (again, _) = EnglishIndex.build(listOf(sudachi, ut))
        assertArrayEquals(bytes, again)
        val lex = EnglishLexicon(bytes)
        assertEquals(listOf("english.txt", "ut-extra.txt"), stats.sources.map { it.name })
        assertEquals(12, stats.sources[0].rows)
        assertEquals(8, stats.sources[0].kept) // OpenAI GitHub iPhone Apple apple Java JavaScript JavaSE
        assertTrue(lex.header.first().startsWith("# astra-english-index format=1"))
        assertTrue(lex.header.any { it.startsWith("# source english.txt sha256=") && it.contains("rows=12") })
        assertEquals(stats.keys, lex.size)
        assertEquals(listOf("OpenAI"), lex.lookup("openai").exact)
        assertEquals(listOf("OpenAI"), lex.lookup("OPENAI").exact)
        assertEquals(listOf("iPhone"), lex.lookup("Iphone").exact)
        assertEquals(listOf("Apple", "apple"), lex.lookup("apple").exact) // cheapest first
        // Same cost: the spelling on more rows first.
        assertEquals(listOf("macOS", "MacOS"), lex.lookup("macos").exact)
        for (w in listOf("x", "iphonese", "ipv6", "仮名", "", "a b")) assertTrue(w, lex.lookup(w).exact.isEmpty())
    }

    @Test fun completionsAreCheapestFirstAndBounded() {
        val lex = EnglishLexicon(EnglishIndex.build(listOf(sudachi, ut)).first)
        val java = lex.lookup("java")
        assertEquals(listOf("Java"), java.exact)
        assertEquals(listOf("JavaScript", "JavaSE"), java.completions)
        assertEquals(listOf("JavaScript"), lex.lookup("java", completions = 1).completions)
        assertEquals(listOf("JavaSE"), lex.lookup("javase").exact)
        assertEquals(1, lex.lookup("ja", maxScan = 1).completions.size) // scans one key only
        assertTrue(lex.lookup("zz").completions.isEmpty())
    }

    @Test fun readsCrLfCheckouts() {
        val bytes = EnglishIndex.build(listOf(sudachi)).first
        val crlf = String(bytes, StandardCharsets.US_ASCII).replace("\n", "\r\n").toByteArray(StandardCharsets.US_ASCII)
        val lex = EnglishLexicon(crlf)
        assertEquals(listOf("OpenAI"), lex.lookup("openai").exact)
        assertEquals(listOf("JavaScript", "JavaSE"), lex.lookup("java").completions)
        assertTrue(lex.header.none { it.endsWith("\r") })
    }
}
