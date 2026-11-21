package dev.astra.ime.oss

import com.kazumaproject.dictionary.models.Dictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.TreeMap

class MozcSourceParserTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(vararg lines: String): Path =
        temp.newFile().toPath().also { MozcFixture.writeLines(it, lines.toList()) }

    private fun parse(vararg lines: String): Pair<SourceFileStats, TreeMap<String, MutableList<Dictionary>>> {
        val map = TreeMap<String, MutableList<Dictionary>>()
        return MozcSourceParser.readDictionary(file(*lines), idCount = 4, into = map) to map
    }

    private fun assertRejected(fragment: String, vararg lines: String) {
        try {
            parse(*lines)
            fail("expected rejection containing '$fragment'")
        } catch (e: MozcFormatException) {
            assertTrue("${e.message}", e.message!!.contains(fragment))
            assertTrue("line number is reported", e.message!!.contains(":"))
        }
    }

    @Test
    fun keepsIdsCostsAndOrderIncludingDuplicates() {
        val (stats, map) = parse("あめ\t1\t2\t3500\t雨", "あめ\t1\t2\t-20\t飴", "あめ\t1\t2\t3500\t雨", "abc\t0\t3\t0\tABC")
        assertEquals(4, stats.records)
        assertEquals(1, stats.negativeCosts)
        assertEquals(1, stats.readingsOutsideConvertibleSet)
        assertEquals(listOf("雨", "飴", "雨"), map.getValue("あめ").map { it.tango })
        assertEquals(Dictionary("あめ", 1, 2, (-20).toShort(), "飴"), map.getValue("あめ")[1])
    }

    @Test
    fun acceptsSpellingCorrectionTagAndKeepsFirstFiveFields() {
        val (stats, map) = parse("きよう\t1\t2\t5000\t器用\tSPELLING_CORRECTION", "あめ\t1\t1\t3500\t雨")
        assertEquals(2, stats.records)
        assertEquals(1, stats.spellingCorrectionRows)
        assertEquals(Dictionary("きよう", 1, 2, 5000.toShort(), "器用"), map.getValue("きよう").single())
        assertEquals("雨", map.getValue("あめ").single().tango)
    }

    @Test
    fun rejectsMalformedRows() {
        assertRejected("5 tab-separated columns", "あめ\t1\t1\t3500")
        assertRejected("unknown sixth-column tag", "あめ\t1\t1\t3500\t雨\textra")
        assertRejected("unknown sixth-column tag", "あめ\t1\t1\t3500\t雨\tSPELLING_CORRECTION ")
        assertRejected("unknown sixth-column tag", "あめ\t1\t1\t3500\t雨\t")
        assertRejected("5 tab-separated columns", "あめ\t1\t1\t3500\t雨\tSPELLING_CORRECTION\tx")
        assertRejected("blank line", "あめ\t1\t1\t3500\t雨", "")
        assertRejected("blank reading", " \t1\t1\t3500\t雨")
        assertRejected("blank surface", "あめ\t1\t1\t3500\t")
        assertRejected("left id 4 outside", "あめ\t4\t1\t3500\t雨")
        assertRejected("right id -1 outside", "あめ\t1\t-1\t3500\t雨")
        assertRejected("non-decimal left id", "あめ\t+1\t1\t3500\t雨")
        assertRejected("non-decimal cost", "あめ\t1\t1\t35.0\t雨")
        assertRejected("outside upstream Short range", "あめ\t1\t1\t40000\t雨")
        assertRejected("BOM", "﻿あめ\t1\t1\t3500\t雨")
    }

    @Test
    fun idDefMustBeContiguous() {
        assertEquals(4, MozcSourceParser.readIdDef(file(*MozcFixture.ID_DEF.toTypedArray())))
        for (bad in listOf(arrayOf("0 BOS", "2 名詞"), arrayOf("0BOS"), arrayOf("x 名詞"), emptyArray())) {
            try {
                MozcSourceParser.readIdDef(file(*bad))
                fail("expected rejection")
            } catch (e: MozcFormatException) {
                // expected
            }
        }
    }

    @Test
    fun strictIntIsDecimalOnly() {
        assertEquals(-12, MozcSourceParser.strictInt("-12"))
        assertNull(MozcSourceParser.strictInt("-"))
        assertNull(MozcSourceParser.strictInt(" 1"))
        assertNull(MozcSourceParser.strictInt("99999999999"))
    }
}
