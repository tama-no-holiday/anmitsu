package dev.astra.ime.oss

import com.kazumaproject.Other.BOS
import com.kazumaproject.graph.Node
import com.kazumaproject.hiraToKata

/**
 * The dictionary lookups the converter makes, with the semantics of the upstream LOUDS/TokenArray calls named on
 * each member. Two implementations: [UpstreamLexicon] over the deserialized upstream objects (the reference, and
 * what the build audit uses) and [MappedLexicon] over a memory-mapped `lexicon.bin`.
 */
internal interface Lexicon {
    /** `LOUDSWithTermId.commonPrefixSearch(text)` on the reading trie. */
    fun prefixes(text: String): List<String>

    /** `yomi.getTermId(yomi.getNodeIndex(reading))`: a term ID, or -1. */
    fun termId(reading: String): Int

    /** `TokenArray.getListDictionaryByYomiTermId(termId)`, in order, as (POS table index, word cost, node ID). */
    fun forEachEntry(termId: Int, action: (posIndex: Int, cost: Int, nodeId: Int) -> Unit)

    /** `TokenArray.leftIds[posIndex]` / `rightIds[posIndex]`. */
    fun leftId(posIndex: Int): Short
    fun rightId(posIndex: Int): Short

    /** `tango.getLetter(nodeId)` on the surface trie (non-negative node IDs only). */
    fun surface(nodeId: Int): String

    /** Words extending [prefix]; only [MappedLexicon] walks its trie for them, the upstream reference has none. */
    fun predictions(prefix: String, limit: Int, maxNodes: Int = 20_000): List<MappedLexicon.Prediction> = emptyList()
}

/**
 * Nodes for one dictionary [reading] starting at typed offset [sPos] and covering [len] typed chars, exactly as
 * upstream `GraphBuilder.constructGraph` creates them, with [penalty] added to every word cost (0 there).
 */
internal fun Lexicon.nodes(reading: String, termId: Int, sPos: Int, len: Int, penalty: Int = 0): MutableList<Node> {
    val out = ArrayList<Node>()
    forEachEntry(termId) { posIndex, wordCost, nodeId ->
        val cost = wordCost + penalty
        out += Node(
            l = leftId(posIndex),
            r = rightId(posIndex),
            score = cost,
            f = cost,
            g = cost,
            tango = when (nodeId) {
                -2 -> reading
                -1 -> reading.hiraToKata()
                else -> surface(nodeId)
            },
            len = len.toShort(),
            sPos = sPos,
            key = reading,
            wcost = cost,
            totalCost = Int.MAX_VALUE,
        )
    }
    return out
}

/** Upstream `GraphBuilder.constructGraph(str, yomi, tango, tokens)` over any [Lexicon]: same nodes in the same order. */
internal fun Lexicon.lattice(str: String): List<MutableList<MutableList<Node>>> {
    val graph: MutableList<MutableList<MutableList<Node>>> = MutableList(str.length + 2) { mutableListOf() }
    graph[0].add(mutableListOf(BOS.copy(score = 0, f = 0, g = 0, totalCost = 0, prev = null, next = null)))
    graph[str.length + 1].add(
        mutableListOf(
            Node(
                l = 0, r = 0, score = 0, f = 0, g = 0, tango = "EOS", len = 0, sPos = str.length, key = "EOS",
                wcost = 0, totalCost = Int.MAX_VALUE,
            ),
        ),
    )
    for (i in str.indices) {
        for (reading in prefixes(str.substring(i))) {
            graph[i + reading.length].add(nodes(reading, termId(reading), i, reading.length))
        }
    }
    return graph
}

/** [Lexicon] over deserialized upstream objects; every call is the upstream call it documents. */
internal class UpstreamLexicon(private val resources: OssResources) : Lexicon {
    override fun prefixes(text: String): List<String> = resources.yomi.commonPrefixSearch(text)

    override fun termId(reading: String): Int = resources.yomi.getTermId(resources.yomi.getNodeIndex(reading))

    override fun forEachEntry(termId: Int, action: (posIndex: Int, cost: Int, nodeId: Int) -> Unit) {
        for (t in resources.tokens.getListDictionaryByYomiTermId(termId)) action(t.posTableIndex.toInt(), t.wordCost.toInt(), t.nodeId)
    }

    override fun leftId(posIndex: Int): Short = resources.tokens.leftIds[posIndex]
    override fun rightId(posIndex: Int): Short = resources.tokens.rightIds[posIndex]
    override fun surface(nodeId: Int): String = resources.tango.getLetter(nodeId)
}
