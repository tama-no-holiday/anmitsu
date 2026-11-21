package dev.astra.ime.core

import kotlin.math.ceil

/** Synthetic development-only host JVM evaluation. Never reads live editor data. */
object FeedbackBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val engine = CoreTest.engine
        val cases = FeedbackBenchmark::class.java.getResourceAsStream("/feedback.tsv")!!
            .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }.map { it.split('\t') }
        cases.forEach { engine.convert(it[2]) }
        println("CASE\tid\tkind\ttop1\trecall10\treadingChanged\tforbidden\tp50ms\tp95ms")
        for (c in cases) {
            val times = ArrayList<Double>()
            var result = emptyList<Candidate>()
            repeat(10) {
                val start = System.nanoTime()
                result = engine.convert(c[2])
                times += (System.nanoTime() - start) / 1_000_000.0
            }
            times.sort()
            fun percentile(p: Double) = times[ceil(p * times.size).toInt() - 1]
            val expected = c[4].split('|')
            val forbidden = c.getOrElse(5) { "" }.split('|').filter { it.isNotEmpty() }
            println(listOf("CASE", c[0], c[1], result[0].surface in expected,
                result.any { it.surface in expected }, result[0].reading != c[3],
                result.any { it.surface in forbidden }, percentile(.5), percentile(.95)).joinToString("\t"))
        }
    }
}
