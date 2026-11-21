package dev.astra.ime.core

import java.util.Collections
import java.util.concurrent.CancellationException

/** One lexicon row as loaded by the app (reading is hiragana, lower cost is preferred). */
data class LexEntry(val reading: String, val surface: String, val cost: Double, val pos: String)

/**
 * A ranked surface hypothesis; lower cost is better. [segments] align it with the typed text it was converted
 * from (see [Segment]); null when unknown, e.g. the raw identity, a learned phrase or a prediction.
 */
data class Candidate(val reading: String, val surface: String, val cost: Double, val segments: List<Segment>? = null) {
    /** Redacted: candidates carry user text. */
    override fun toString(): String = "Candidate(length=${surface.length}, cost=$cost)"
}

const val MAX_INPUT_CODEPOINTS = 128
const val MIN_LIMIT = 2
const val MAX_LIMIT = 20

private const val BEAM_WIDTH = 32
private const val MIN_READINGS = 8
private const val OOV_KANA_COST = 3.0
private const val SYMBOL_COST = 0.3
private const val IDENTITY_PENALTY = 1.0
private const val EDIT_WEIGHT = 1.5 // Development-tuned; resist deleting valid short words to save lexical cost.
private const val MAX_ARCS_PER_OFFSET = 24
private const val SEGMENTATIONS_PER_READING = 3
private const val SURFACES_PER_SEGMENTATION = 3
private const val CONVERTER_BEAM = 6
private const val EXACT_BEAM = 16
// Unweighted edits allowed per contiguous kana run: max(1, floor(kana / 5)). Not a calibrated probability.
private const val KANA_PER_EDIT = 5

// Noisy channel: weighted optimal-string-alignment costs (ported from ime_poc/channel.py).
private const val VOICING_COST = 0.8      // か/が, ふ/ぷ, や/ゃ, つ/っ
private const val SAME_ROW_COST = 1.0     // か/こ: same consonant row, different vowel
private const val SAME_VOWEL_COST = 1.4   // か/さ: same vowel, different consonant
private const val OTHER_SUB_COST = 2.0
private const val INDEL_COST = 1.2
private const val LIGHT_INDEL_COST = 0.8  // small kana, っ, ん, ー are easy to drop or double
private const val TRANSPOSE_COST = 1.0

private val POS_TAGS = setOf("noun", "verb", "adj", "adverb", "particle", "aux", "adnominal", "conj")
private val PUNCT = "、。，．,.!?！？「」『』（）()・".codePoints().toArray().toSet()

// Small penalties between general POS classes; nothing sentence-specific.
private val TRANSITIONS = mapOf(
    ("BOS" to "particle") to 1.5, ("BOS" to "aux") to 1.0,
    ("punct" to "particle") to 1.5, ("punct" to "aux") to 1.0,
    ("particle" to "particle") to 0.5, ("noun" to "noun") to 0.4,
    ("adnominal" to "verb") to 1.0, ("adnominal" to "aux") to 1.0,
)

private val ROWS = listOf(
    "a" to "あいうえお", "k" to "かきくけこ", "s" to "さしすせそ", "t" to "たちつてと",
    "n" to "なにぬねの", "h" to "はひふへほ", "m" to "まみむめも", "y" to "や_ゆ_よ",
    "r" to "らりるれろ", "w" to "わ___を", "g" to "がぎぐげご", "z" to "ざじずぜぞ",
    "d" to "だぢづでど", "b" to "ばびぶべぼ", "p" to "ぱぴぷぺぽ", "xa" to "ぁぃぅぇぉ",
    "xy" to "ゃ_ゅ_ょ", "xt" to "__っ__",
)

// Rows related by voicing (dakuten/handakuten) or small/large size.
private val FAMILY = mapOf("g" to "k", "z" to "s", "d" to "t", "xt" to "t", "b" to "h", "p" to "h", "xa" to "a", "xy" to "y")
private val KANA: Map<Int, Pair<String, Int>> = buildMap {
    for ((row, chars) in ROWS) chars.forEachIndexed { v, ch -> if (ch != '_') put(ch.code, row to v) }
}
private val LIGHT = "ぁぃぅぇぉゃゅょっんー".map { it.code }.toSet()

internal fun isKana(cp: Int): Boolean = cp in 0x3041..0x3096 || cp == 0x30FC

internal fun toHiragana(cp: Int): Int = if (cp in 0x30A1..0x30F6) cp - 0x60 else cp

private fun indel(cp: Int): Double = if (cp in LIGHT) LIGHT_INDEL_COST else INDEL_COST

private fun substitution(observed: Int, intended: Int): Double {
    if (observed == intended) return 0.0
    val (ra, va) = KANA[observed] ?: return OTHER_SUB_COST
    val (rb, vb) = KANA[intended] ?: return OTHER_SUB_COST
    if (ra == rb) return SAME_ROW_COST
    if ((FAMILY[ra] ?: ra) == (FAMILY[rb] ?: rb)) return if (va == vb) VOICING_COST else SAME_VOWEL_COST
    return if (va == vb) SAME_VOWEL_COST else OTHER_SUB_COST
}

/** Distances of every prefix of norm[from, to) to the whole [intended]; infinite once two rows exceed [cap]. */
private fun prefixDistances(norm: IntArray, from: Int, to: Int, intended: IntArray, cap: Double): DoubleArray {
    val nObs = to - from
    val nInt = intended.size
    val out = DoubleArray(nObs + 1) { Double.POSITIVE_INFINITY }
    var prev2 = DoubleArray(nInt + 1)
    var prev = DoubleArray(nInt + 1)
    for (b in 1..nInt) prev[b] = prev[b - 1] + indel(intended[b - 1])
    out[0] = prev[nInt]
    for (a in 1..nObs) {
        val oc = norm[from + a - 1]
        val ins = indel(oc)
        val cur = DoubleArray(nInt + 1)
        cur[0] = prev[0] + ins
        for (b in 1..nInt) {
            val tc = intended[b - 1]
            var v = prev[b - 1] + substitution(oc, tc)
            var x = prev[b] + ins
            if (x < v) v = x
            x = cur[b - 1] + indel(tc)
            if (x < v) v = x
            if (a > 1 && b > 1 && oc != tc && oc == intended[b - 2] && norm[from + a - 2] == tc) {
                x = prev2[b - 2] + TRANSPOSE_COST
                if (x < v) v = x
            }
            cur[b] = v
        }
        out[a] = cur[nInt]
        if (cur.min() > cap && prev.min() > cap) break
        prev2 = prev
        prev = cur
    }
    return out
}

/** Per-token weighted edit budget; short words must match almost exactly. */
private fun editCap(length: Int): Double = when (length) { 1 -> 0.3; 2 -> 1.0; 3 -> 1.6; else -> 2.2 }

private fun spanSlack(length: Int): Int = if (length == 1) 0 else if (length <= 3) 1 else 2

/** Unweighted optimal string alignment distance (adjacent transpositions count once). */
private fun osa(a: IntArray, b: IntArray): Int {
    val d = Array(a.size + 1) { i -> IntArray(b.size + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
    for (i in 1..a.size) for (j in 1..b.size) {
        var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
        d[i][j] = v
    }
    return d[a.size][b.size]
}

/** runEnd[i]: exclusive end of the contiguous kana run containing i (0 for non-kana). */
private fun runEnds(norm: IntArray): IntArray {
    val n = norm.size
    val runEnd = IntArray(n)
    for (i in n - 1 downTo 0) {
        if (isKana(norm[i])) runEnd[i] = if (i + 1 < n && isKana(norm[i + 1])) runEnd[i + 1] else i + 1
    }
    return runEnd
}

private fun cpString(cps: IntArray, start: Int, length: Int): String = String(cps, start, length)

/** Orders strings by Unicode code point, like Python string comparison. */
private val CP_ORDER = Comparator<String> { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val x = a.codePointAt(i)
        val y = b.codePointAt(j)
        if (x != y) return@Comparator x.compareTo(y)
        i += Character.charCount(x)
        j += Character.charCount(y)
    }
    (i < a.length).compareTo(j < b.length)
}

private val LIST_ORDER = Comparator<List<String>> { a, b ->
    for (i in 0 until minOf(a.size, b.size)) {
        val c = CP_ORDER.compare(a[i], b[i])
        if (c != 0) return@Comparator c
    }
    a.size.compareTo(b.size)
}

private val CANDIDATE_ORDER = compareBy<Candidate> { it.cost }
    .thenComparing({ it.surface }, CP_ORDER).thenComparing({ it.reading }, CP_ORDER)

private enum class Kind { LEX, OOV, SYM }

/** One segment of the observed input; [observed] is only kept for OOV/symbol tokens. */
private class Token(val end: Int, val reading: String, val observed: String, val kind: Kind, val cost: Double)

private class Path(val cost: Double, val key: List<String>, val tokens: List<Token>)

private class Hypothesis(val reading: String, val cost: Double, val segmentations: List<List<Token>>)

private class State(val cost: Double, val surfaces: List<String>, val pos: String)

private class Option(val surface: String, val extra: Double, val pos: String)

/** [partial]: the segmentation passed at least one unknown kana through literally. */
private class Scored(val candidate: Candidate, val partial: Boolean)

private val SCORED_ORDER = Comparator<Scored> { a, b -> CANDIDATE_ORDER.compare(a.candidate, b.candidate) }
private val TOKEN_ORDER = compareBy<Token>({ it.cost }, { it.end }).thenComparing({ it.reading }, CP_ORDER)
private val PATH_ORDER = compareBy<Path> { it.cost }.thenComparing({ it.key }, LIST_ORDER)
private val STATE_ORDER = compareBy<State> { it.cost }.thenComparing({ it.surfaces }, LIST_ORDER).thenBy { it.pos }

/**
 * Offline, non-neural baseline: weighted noisy-channel correction of kana over a lexicon-guided
 * beam search, then per-token surface choice with general POS-class transition penalties.
 * Ported from ime_poc/engine.py without touch, motor or language-profile learning.
 *
 * Correction abstention (Android only; heuristic, scores are not calibrated probabilities):
 * - The exact reading is searched separately, so correctly typed input is never pruned by
 *   cheaper fabricated edits; unknown kana stay literal there.
 * - If every kana run is exactly segmentable into lexicon words, no changed reading is offered
 *   (same-reading homophones still are). This can hide a correction that spells another valid word.
 * - Otherwise a changed reading must stay within max(1, kana/5) unweighted edits of each typed kana
 *   run and must not pass unknown kana through in an edited run. Too-far readings are dropped.
 * - Without a complete exact path, the raw text ranks above conversions that still contain unknown kana.
 *
 * Thread-safe after construction: all request state is local to [convert]; nothing is cached
 * or logged across requests.
 */
class ConversionEngine(entries: List<LexEntry>) {
    private val byReading: Map<String, List<LexEntry>>
    private val readings: List<String>
    private val readingCps: List<IntArray>
    private val minCosts: DoubleArray
    private val maxReadingLength: Int

    init {
        val grouped = LinkedHashMap<String, MutableList<LexEntry>>()
        val seen = HashSet<Pair<String, String>>()
        for (e in entries) {
            require(e.reading.isNotEmpty() && e.reading.codePoints().allMatch { isKana(it) }) { "lexicon reading must be hiragana" }
            require(e.surface.isNotEmpty()) { "empty lexicon surface" }
            require(e.cost.isFinite() && e.cost >= 0.0) { "lexicon cost must be finite and >= 0" }
            require(e.pos in POS_TAGS) { "unknown POS ${e.pos}" }
            require(seen.add(e.reading to e.surface)) { "duplicate lexicon entry" }
            grouped.getOrPut(e.reading) { ArrayList() }.add(e)
        }
        byReading = grouped.mapValues { (_, v) -> v.sortedWith(compareBy<LexEntry> { it.cost }.thenComparing({ it.surface }, CP_ORDER)) }
        readings = byReading.keys.sortedWith(CP_ORDER)
        readingCps = readings.map { it.codePoints().toArray() }
        minCosts = DoubleArray(readings.size) { byReading.getValue(readings[it])[0].cost }
        maxReadingLength = readingCps.maxOfOrNull { it.size } ?: 0
    }

    /**
     * Returns at most [limit] (2..20) candidates, best first. The raw [text] is always among them.
     * Empty input or input longer than [MAX_INPUT_CODEPOINTS] code points yields only the raw text.
     * Throws [CancellationException] if the thread is interrupted or [isCancelled] returns true.
     * The returned list is unmodifiable.
     */
    fun convert(text: String, limit: Int = 10, isCancelled: () -> Boolean = { false }): List<Candidate> {
        require(limit in MIN_LIMIT..MAX_LIMIT) { "limit must be in [$MIN_LIMIT, $MAX_LIMIT]" }
        val identity = Candidate(text, text, 0.0)
        val cps = text.codePoints().toArray()
        if (cps.isEmpty() || cps.size > MAX_INPUT_CODEPOINTS) return Collections.singletonList(identity)
        val checkpoint: () -> Unit = {
            if (Thread.currentThread().isInterrupted || isCancelled()) throw CancellationException("conversion cancelled")
        }
        val norm = IntArray(cps.size) { toHiragana(cps[it]) }
        val normText = cpString(norm, 0, norm.size)
        val runEnd = runEnds(norm)
        val exactTokens = exactArcs(cps, norm, runEnd)
        // Contiguous kana runs: symbols, ASCII and emoji never widen a run's edit budget.
        val runs = ArrayList<IntArray>()
        val runOf = IntArray(norm.size) { -1 }
        var covered = true
        for (i in norm.indices) {
            if (!isKana(norm[i]) || (i > 0 && isKana(norm[i - 1]))) continue
            for (j in i until runEnd[i]) runOf[j] = runs.size
            runs.add(intArrayOf(i, runEnd[i]))
            covered = covered && lexicallyCovered(exactTokens, i, runEnd[i])
        }
        // The exact reading is searched on its own so cheap fabricated edits cannot prune correct input.
        val exact = search(exactTokens, EXACT_BEAM, checkpoint)
        val noisy = if (covered) emptyList() else search(arcs(cps, norm, runEnd, checkpoint), BEAM_WIDTH, checkpoint)
            .mapNotNull { if (it.reading == normText) it else plausible(it, norm, runs, runOf) }
        val hyps = exact + noisy.take(maxOf(limit, MIN_READINGS))

        var idCost = 0.0
        for (c in norm) idCost += if (isKana(c)) OOV_KANA_COST else SYMBOL_COST
        for (h in hyps) if (h.reading == normText && h.cost < idCost) idCost = h.cost

        // UTF-16 offset of each code point boundary, for the typed spans of candidate segments.
        val utf16 = IntArray(cps.size + 1)
        for (i in cps.indices) utf16[i + 1] = utf16[i] + Character.charCount(cps[i])
        val pool = LinkedHashMap<String, Scored>()
        for (hyp in hyps) {
            checkpoint()
            for (s in convertHypothesis(hyp, SEGMENTATIONS_PER_READING * SURFACES_PER_SEGMENTATION, utf16)) {
                val old = pool[s.candidate.surface]
                if (old == null || s.candidate.cost < old.candidate.cost) pool[s.candidate.surface] = s
            }
        }
        // Compare the literal alternative with an actual same-reading conversion including POS costs.
        pool.values.filter { it.candidate.reading == normText }.minOfOrNull { it.candidate.cost }?.let { idCost = it }
        var raw = identity.copy(cost = idCost + IDENTITY_PENALTY)
        var cands = pool.values.map { it.candidate }
        if (!covered) {
            // No complete exact path: keep typed text above conversions that still guess around unknown kana.
            raw = raw.copy(cost = minOf(raw.cost, pool[text]?.candidate?.cost ?: raw.cost))
            cands = pool.values.map { s ->
                when {
                    s.candidate.surface == text -> s.candidate.copy(cost = raw.cost)
                    s.partial -> s.candidate.copy(cost = maxOf(s.candidate.cost, raw.cost + IDENTITY_PENALTY))
                    else -> s.candidate
                }
            }
        }
        return keepIdentity(cands.sortedWith(CANDIDATE_ORDER), raw, limit)
    }

    /** Every lexicon entry for exactly [text] as one word, cheapest first; nothing when it is not one lexicon reading. */
    fun exactEntries(text: String): List<Candidate> {
        val cps = text.codePoints().map(::toHiragana).toArray()
        val reading = cpString(cps, 0, cps.size)
        return byReading[reading].orEmpty().map { Candidate(reading, it.surface, it.cost, listOf(Segment(0, text.length, reading, it.surface, SegmentKind.WORD))) }
    }

    /** Exact lexicon matches only, plus literal passthrough of every kana. */
    private fun exactArcs(cps: IntArray, norm: IntArray, runEnd: IntArray): List<List<Token>> = List(norm.size) { i ->
        val observed = cpString(cps, i, 1)
        if (!isKana(norm[i])) return@List listOf(Token(i + 1, observed, observed, Kind.SYM, SYMBOL_COST))
        val found = ArrayList<Token>()
        for (len in 1..minOf(maxReadingLength, runEnd[i] - i)) {
            val reading = cpString(norm, i, len)
            byReading[reading]?.let { found.add(Token(i + len, reading, "", Kind.LEX, it[0].cost)) }
        }
        found.add(Token(i + 1, cpString(norm, i, 1), observed, Kind.OOV, OOV_KANA_COST))
        found
    }

    private fun lexicallyCovered(arcs: List<List<Token>>, start: Int, end: Int): Boolean {
        val reach = BooleanArray(end - start + 1)
        reach[0] = true
        for (i in start until end) {
            if (reach[i - start]) for (tok in arcs[i]) if (tok.kind == Kind.LEX) reach[tok.end - start] = true
        }
        return reach[end - start]
    }

    /**
     * Keeps a changed reading only if each edited kana run stays within its own edit budget and is
     * fully lexical; segmentations that pass unknown kana through an edited run are dropped.
     */
    private fun plausible(hyp: Hypothesis, norm: IntArray, runs: List<IntArray>, runOf: IntArray): Hypothesis? {
        val intended = Array(runs.size) { StringBuilder() }
        var start = 0
        for (tok in hyp.segmentations[0]) {
            if (runOf[start] >= 0) intended[runOf[start]].append(tok.reading)
            start = tok.end
        }
        val changed = BooleanArray(runs.size)
        for ((r, run) in runs.withIndex()) {
            val typed = norm.copyOfRange(run[0], run[1])
            val read = intended[r].codePoints().toArray()
            if (typed contentEquals read) continue
            if (osa(typed, read) > maxOf(1, typed.size / KANA_PER_EDIT)) return null
            changed[r] = true
        }
        val segs = hyp.segmentations.filter { !guessesAroundUnknown(it, runOf, changed) }
        if (segs.isEmpty()) return null
        return Hypothesis(hyp.reading, segs[0].sumOf { it.cost }, segs)
    }

    private fun guessesAroundUnknown(seg: List<Token>, runOf: IntArray, changed: BooleanArray): Boolean {
        var start = 0
        for (tok in seg) {
            if (tok.kind == Kind.OOV && changed[runOf[start]]) return true
            start = tok.end
        }
        return false
    }

    private fun arcs(cps: IntArray, norm: IntArray, runEnd: IntArray, checkpoint: () -> Unit): List<List<Token>> {
        val n = norm.size
        val cache = HashMap<String, DoubleArray>() // request-local window/word edit costs
        val arcs = ArrayList<List<Token>>(n)
        for (i in 0 until n) {
            checkpoint()
            val observed = cpString(cps, i, 1)
            if (!isKana(norm[i])) {
                arcs.add(listOf(Token(i + 1, observed, observed, Kind.SYM, SYMBOL_COST)))
                continue
            }
            val found = ArrayList<Token>()
            for (r in readings.indices) {
                val reading = readingCps[r]
                val size = reading.size
                val slack = spanSlack(size)
                val lo = maxOf(1, size - slack)
                val hi = minOf(runEnd[i], i + size + slack)
                if (hi - i < lo) continue
                val cap = editCap(size)
                val dists = cache.getOrPut(cpString(norm, i, hi - i) + "|" + readings[r]) {
                    prefixDistances(norm, i, hi, reading, cap)
                }
                for (a in lo..hi - i) {
                    if (dists[a] <= cap) found.add(Token(i + a, readings[r], "", Kind.LEX, EDIT_WEIGHT * dists[a] + minCosts[r]))
                }
            }
            found.sortWith(TOKEN_ORDER)
            val kept = ArrayList<Token>(found.subList(0, minOf(found.size, MAX_ARCS_PER_OFFSET)))
            kept.add(Token(i + 1, cpString(norm, i, 1), observed, Kind.OOV, OOV_KANA_COST))
            arcs.add(kept)
        }
        return arcs
    }

    /** Beam search over [arcs]; returns readings grouped with their cheapest segmentations, best first. */
    private fun search(arcs: List<List<Token>>, width: Int, checkpoint: () -> Unit): List<Hypothesis> {
        val n = arcs.size
        // beams[i]: token-reading sequence -> best path ending at offset i
        val beams = Array(n + 1) { HashMap<List<String>, Path>() }
        beams[0][emptyList()] = Path(0.0, emptyList(), emptyList())
        for (i in 0 until n) {
            checkpoint()
            if (beams[i].isEmpty()) continue
            val kept = beams[i].values.sortedWith(PATH_ORDER).take(width)
            beams[i] = HashMap()
            for (path in kept) {
                for (tok in arcs[i]) {
                    val key = path.key + tok.reading
                    val cost = path.cost + tok.cost
                    val slot = beams[tok.end]
                    val old = slot[key]
                    if (old == null || cost < old.cost) slot[key] = Path(cost, key, path.tokens + tok)
                }
            }
        }
        val grouped = LinkedHashMap<String, MutableList<Path>>()
        for (path in beams[n].values.sortedWith(PATH_ORDER).take(width)) {
            grouped.getOrPut(path.key.joinToString("")) { ArrayList() }.add(path)
        }
        return grouped.map { (reading, segs) ->
            Hypothesis(reading, segs[0].cost, segs.take(SEGMENTATIONS_PER_READING).map { it.tokens })
        }.sortedWith(compareBy<Hypothesis> { it.cost }.thenComparing({ it.reading }, CP_ORDER))
    }

    private fun options(tok: Token): List<Option> = when (tok.kind) {
        Kind.LEX -> byReading.getValue(tok.reading).let { es -> es.map { Option(it.surface, it.cost - es[0].cost, it.pos) } }
        Kind.SYM -> listOf(Option(tok.observed, 0.0, if (tok.observed.codePointAt(0) in PUNCT) "punct" else "sym"))
        Kind.OOV -> listOf(Option(tok.observed, 0.0, "unk"))
    }

    /** Tokens span typed code points [previous end, end); their readings may be corrected, so offsets come from the tokens. */
    private fun segments(seg: List<Token>, surfaces: List<String>, utf16: IntArray): List<Segment> {
        val out = ArrayList<Segment>(seg.size)
        var start = 0
        for ((k, tok) in seg.withIndex()) {
            val kind = when (tok.kind) {
                Kind.LEX -> SegmentKind.WORD
                Kind.SYM -> SegmentKind.PASSTHROUGH
                Kind.OOV -> SegmentKind.LITERAL
            }
            out += Segment(utf16[start], utf16[tok.end], tok.reading, surfaces[k], kind)
            start = tok.end
        }
        return out
    }

    private fun convertHypothesis(hyp: Hypothesis, limit: Int, utf16: IntArray): List<Scored> {
        val best = LinkedHashMap<String, Scored>()
        for (seg in hyp.segmentations) {
            val partial = seg.any { it.kind == Kind.OOV }
            var states = listOf(State(0.0, emptyList(), "BOS"))
            for (tok in seg) {
                val opts = options(tok)
                val next = ArrayList<State>(states.size * opts.size)
                for (s in states) for (o in opts) {
                    next.add(State(s.cost + o.extra + (TRANSITIONS[s.pos to o.pos] ?: 0.0), s.surfaces + o.surface, o.pos))
                }
                states = next.sortedWith(STATE_ORDER).take(CONVERTER_BEAM)
            }
            // Each segmentation pays its own channel/lexical cost; the hypothesis cost is only the cheapest one.
            var segCost = 0.0
            for (tok in seg) segCost += tok.cost
            for (s in states.take(SURFACES_PER_SEGMENTATION)) {
                val surface = s.surfaces.joinToString("")
                val total = segCost + s.cost
                val old = best[surface]
                if (old == null || total < old.candidate.cost) {
                    best[surface] = Scored(Candidate(hyp.reading, surface, total, segments(seg, s.surfaces, utf16)), partial)
                }
            }
        }
        return best.values.sortedWith(SCORED_ORDER).take(limit)
    }
}

/** Bound to [k] while guaranteeing the raw input survives; keeps ranked order. */
private fun keepIdentity(items: List<Candidate>, identity: Candidate, k: Int): List<Candidate> {
    var top = items.take(k)
    if (top.none { it.surface == identity.surface }) {
        val trimmed = ArrayList(top.take(k - 1))
        val pos = trimmed.indexOfFirst { it.cost > identity.cost }
        trimmed.add(if (pos < 0) trimmed.size else pos, identity)
        top = trimmed
    }
    return Collections.unmodifiableList(ArrayList(top))
}
