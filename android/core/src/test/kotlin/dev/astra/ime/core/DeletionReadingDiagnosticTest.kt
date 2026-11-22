package dev.astra.ime.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostic only: prints what the fixture engine ranks for the readings InputUiTest reaches by deleting in a segment
 * (あし, あしは; the fixture has あした and あさ but no あし). Asserts nothing about order.
 */
class DeletionReadingDiagnosticTest {
    private val engine get() = CoreTest.engine

    private fun dump(text: String) {
        val result = engine.convert(text)
        println("convert($text): ${result.size} candidates, exactEntries=${engine.exactEntries(text).map { it.surface }}")
        result.forEachIndexed { i, c -> println("  $i surface=${c.surface} reading=${c.reading} cost=${c.cost}") }
        assertTrue("exact input missing for $text", result.any { it.surface == text && it.reading == text })
    }

    @Test fun ashi() = dump("あし")

    @Test fun ashiha() = dump("あしは")
}
