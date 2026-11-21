package dev.astra.ime.oss

import com.kazumaproject.Other.BOS
import com.kazumaproject.graph.Node

/**
 * Edit costs added to a dictionary word's Mozc cost when the word matches the typed kana only after one
 * edit. Same units as Mozc word/connection costs; initial values, tuned on development data only.
 */
data class EditPenalties(
    val modifier: Int = 1500,
    val sameKey: Int = 2500,
    val neighborKey: Int = 3000,
    val deleteRepeat: Int = 1500,
    val delete: Int = 3500,
    val insert: Int = 3500,
    val transpose: Int = 2500,
) {
    fun scaled(factor: Double) = EditPenalties(
        (modifier * factor).toInt(), (sameKey * factor).toInt(), (neighborKey * factor).toInt(),
        (deleteRepeat * factor).toInt(), (delete * factor).toInt(), (insert * factor).toInt(), (transpose * factor).toInt(),
    )
}

/** Standard 12-key flick layout, used only to rank which wrong kana are plausible slips. */
internal object FlickConfusion {
    // Grid rows of keys; each key lists CENTER/LEFT/UP/RIGHT/DOWN outputs ('_' = none).
    private val grid = listOf(
        listOf("あいうえお", "かきくけこ", "さしすせそ"),
        listOf("たちつてと", "なにぬねの", "はひふへほ"),
        listOf("まみむめも", "や_ゆ_よ", "らりるれろ"),
        listOf("_____", "わをんー_", "_____"),
    )
    private val cycles = listOf(
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ", "かが", "きぎ", "くぐ", "けげ", "こご",
        "さざ", "しじ", "すず", "せぜ", "そぞ", "ただ", "ちぢ", "つっづ", "てで", "とど",
        "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ", "やゃ", "ゆゅ", "よょ", "わゎ",
    )
    private val base: Map<Char, Char> = buildMap { for (c in cycles) for (ch in c) put(ch, c[0]) }
    private val cycleOf: Map<Char, String> = buildMap { for (c in cycles) for (ch in c) put(ch, c) }
    private val position: Map<Char, Triple<Int, Int, Int>> = buildMap {
        grid.forEachIndexed { r, row -> row.forEachIndexed { k, key -> key.forEachIndexed { d, ch -> if (ch != '_') put(ch, Triple(r, k, d)) } } }
    }

    enum class Kind { MODIFIER, SAME_KEY, NEIGHBOR_KEY }

    /** Plausible intended characters for a typed [c], excluding [c] itself. */
    fun substitutions(c: Char): List<Pair<Char, Kind>> {
        val out = LinkedHashMap<Char, Kind>()
        cycleOf[c]?.forEach { if (it != c) out[it] = Kind.MODIFIER }
        val b = base[c] ?: c
        val (r, k, d) = position[b] ?: return out.toList()
        grid[r][k].forEach { if (it != '_' && it != b && it !in out) out[it] = Kind.SAME_KEY }
        for ((dr, dk) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
            val ch = grid.getOrNull(r + dr)?.getOrNull(k + dk)?.getOrNull(d) ?: continue
            if (ch != '_' && ch != c && ch !in out) out[ch] = Kind.NEIGHBOR_KEY
        }
        return out.toList()
    }

    /** Characters commonly dropped while flicking; tried as insertions. */
    val insertable = listOf('っ', 'ー', 'ん', 'う', 'い')
}

/**
 * Builds the same lattice as upstream GraphBuilder, plus dictionary words that match the typed kana after
 * one edit inside the word (substitution by a plausible flick slip, deletion of an extra kana, insertion of
 * a dropped kana, or an adjacent transposition). Each word allows at most one edit, so a sentence can
 * still be corrected in several words. Node.len is the typed span; Node.key is the dictionary reading.
 * Upstream's prefix search is only given kana strings, and every hit is re-checked as a true prefix.
 */
internal class FuzzyLattice(private val lexicon: Lexicon, private val penalties: EditPenalties) {

    /**
     * With [keepLast], the last typed kana is never deleted as extra: at the end of the input it is usually the start
     * of the word still being typed (しかいをつとめま is on its way to つとめます). Once another kana follows, it can be
     * deleted again. A truly extra final kana is therefore left as typed until the next key.
     */
    fun build(typed: String, keepLast: Boolean = false): List<MutableList<MutableList<Node>>> {
        val n = typed.length
        val graph: MutableList<MutableList<MutableList<Node>>> = MutableList(n + 2) { mutableListOf() }
        graph[0].add(mutableListOf(BOS.copy(score = 0, f = 0, g = 0, totalCost = 0, prev = null, next = null)))
        graph[n + 1].add(mutableListOf(eos(n)))
        // (start, span, reading) -> cheapest extra penalty; words are expanded once per key.
        val words = HashMap<Triple<Int, Int, String>, Int>()
        fun offer(start: Int, span: Int, reading: String, penalty: Int) {
            if (span <= 0 || start + span > n) return
            val key = Triple(start, span, reading)
            val old = words[key]
            if (old == null || penalty < old) words[key] = penalty
        }
        for (i in 0 until n) {
            val end = minOf(n, i + MAX_WORD)
            val window = typed.substring(i, end)
            for (w in prefixes(window)) offer(i, w.length, w, 0)
            for (p in 0 until window.length) {
                val before = window.substring(0, p)
                val after = window.substring(p + 1)
                val c = window[p]
                // Substitution: span unchanged; the edited kana must lie inside the word.
                for ((alt, kind) in FlickConfusion.substitutions(c)) {
                    val cost = when (kind) {
                        FlickConfusion.Kind.MODIFIER -> penalties.modifier
                        FlickConfusion.Kind.SAME_KEY -> penalties.sameKey
                        FlickConfusion.Kind.NEIGHBOR_KEY -> penalties.neighborKey
                    }
                    for (w in prefixes(before + alt + after)) if (w.length > p) offer(i, w.length, w, cost)
                }
                // Deletion of an extra typed kana: the word covers one more typed character.
                if (!(keepLast && i + p == n - 1)) {
                    val del = if (p > 0 && window[p - 1] == c) penalties.deleteRepeat else penalties.delete
                    for (w in prefixes(before + after)) if (w.length >= p && (p > 0 || w.isNotEmpty())) offer(i, w.length + 1, w, del)
                }
                // Adjacent transposition.
                if (p + 1 < window.length) {
                    val swapped = before + window[p + 1] + c + window.substring(p + 2)
                    for (w in prefixes(swapped)) if (w.length > p + 1) offer(i, w.length, w, penalties.transpose)
                }
            }
            // Insertion of a dropped kana at offset p (also at the end of the window).
            for (p in 1..window.length) {
                val before = window.substring(0, p)
                val after = window.substring(p)
                for (c in FlickConfusion.insertable) {
                    for (w in prefixes(before + c + after)) if (w.length > p) offer(i, w.length - 1, w, penalties.insert)
                }
            }
        }
        for ((key, penalty) in words) {
            val (start, span, reading) = key
            val termId = lexicon.termId(reading)
            if (termId < 1) continue
            graph[start + span].add(lexicon.nodes(reading, termId, start, span, penalty))
        }
        return graph
    }

    private fun prefixes(s: String): List<String> =
        if (s.isEmpty()) emptyList() else lexicon.prefixes(s).filter { it.isNotEmpty() && s.startsWith(it) }

    private fun eos(n: Int) = Node(
        l = 0, r = 0, score = 0, f = 0, g = 0, tango = "EOS", len = 0, sPos = n, key = "EOS", wcost = 0, totalCost = Int.MAX_VALUE,
    )

    private companion object {
        /** Longest typed span searched per start; Mozc readings are rarely longer for one word. */
        const val MAX_WORD = 10
    }
}
