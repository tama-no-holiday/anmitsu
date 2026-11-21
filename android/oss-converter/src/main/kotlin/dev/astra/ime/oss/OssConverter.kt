package dev.astra.ime.oss

import com.kazumaproject.graph.Node
import com.kazumaproject.mozc.ConnectionMatrix
import com.kazumaproject.viterbi.FindPath
import java.util.concurrent.atomic.AtomicLongArray

/** Hiragana (incl. ゔゕゖ) and the prolonged sound mark, matching :core's isKana. Only these reach upstream. */
internal fun isConvertible(c: Char): Boolean = c.code in 0x3041..0x3096 || c.code == 0x30FC

/** The typed span of a lattice node (relative to its run); [Node.key] is the dictionary reading. */
private fun nodeSpan(n: Node) = Span(n.sPos, n.sPos + n.len.toInt(), n.key, n.tango, Span.Kind.WORD)

private fun literalSpan(reading: String) = Span(0, reading.length, reading, reading, Span.Kind.LITERAL)

/**
 * Bounds chosen for an interactive IME; tune from `ossCli bench`, they are not proven worst-case limits.
 * N-best is off by default ([maxNBestRunLength] = 0), so output is the best Viterbi path plus raw: upstream
 * backwardAStar keeps whole partial paths in an unpruned priority queue, cannot be interrupted, and its
 * ordering is not reliable with negative connection costs. A positive [maxNBestRunLength] opts in for tiny
 * bounded test fixtures only; it is not a production setting.
 */
data class OssLimits(
    val maxInputChars: Int = 256,
    val maxCandidates: Int = 20,
    val maxGraphNodes: Int = 60_000,
    val maxNBestRunLength: Int = 0,
    val maxNBestGraphNodes: Int = 512,
    /** Typo correction builds a larger lattice; longer runs are converted literally. */
    val maxCorrectionRunLength: Int = 40,
    /** Trailing characters whose runs get one-word variants; earlier runs keep their best conversion. */
    val variantWindowChars: Int = 48,
    /**
     * A corrected path leads only if it stays this far below the exact path's total cost (Mozc cost units, edit
     * penalties included). A development-tuned heuristic margin, not a probability: see docs/ux-0.1.18.md.
     */
    val correctionLeadMargin: Int = 2500,
    /** Corrections kept outside the capped candidate list. */
    val maxSuggestions: Int = 12,
    /** Two-word replacements of the best path offered per run, cheapest first; 0 keeps one-word variants only. */
    val maxPairVariants: Int = 8,
) {
    init {
        require(maxInputChars > 0 && maxCandidates >= 2 && maxGraphNodes > 0)
        require(maxNBestRunLength >= 0 && maxNBestGraphNodes >= 0)
    }
}

enum class OssStatus {
    /** Every convertible run was converted (non-convertible text is passed through verbatim). */
    OK,
    EMPTY_INPUT,
    INPUT_TOO_LONG,
    /** At least one kana run had no complete dictionary path or exceeded [OssLimits.maxGraphNodes]; kept as typed. */
    PARTIAL,
    /** Upstream threw; only the raw text is returned. */
    BACKEND_ERROR,
}

/**
 * One aligned piece of a candidate: input text [typedStart, typedEnd) (UTF-16 offsets), the reading it was converted
 * from and its surface. Offsets are lattice node spans (Node.sPos/len, the typed span also for fuzzy matches), never
 * sums of reading lengths: a corrected [reading] can be longer or shorter than what was typed.
 */
data class Span(val typedStart: Int, val typedEnd: Int, val reading: String, val surface: String, val kind: Kind) {
    enum class Kind {
        /** A dictionary or user word (one lattice node). */
        WORD,
        /** Text between kana runs (ASCII, emoji, punctuation, katakana), never sent to upstream. */
        PASSTHROUGH,
        /** A kana run without a complete dictionary path, kept as typed. */
        LITERAL,
    }

    /** Redacted: spans carry user text. */
    override fun toString(): String = "Span($typedStart..$typedEnd, $kind)"
}

/**
 * [candidates] are best first and always contain the raw input. [readings] gives, per candidate, the kana it
 * was converted from: the typed text, or a corrected reading when typo correction was requested.
 * [failure] is only the exception class name: messages may echo user text and are never kept.
 */
class OssConversion(
    val candidates: List<String>,
    val status: OssStatus,
    val unconvertedRuns: Int = 0,
    val nBestUsed: Boolean = false,
    val failure: String? = null,
    val readings: List<String> = candidates,
    /**
     * Typed text before [headLength] lies outside the variant window: every converted candidate starts with
     * [headSurface] (converted from [headReading]) and differs only after it. 0/"" when the window covers all.
     */
    val headLength: Int = 0,
    val headSurface: String = "",
    val headReading: String = "",
    /**
     * Per candidate, the dictionary words it was built from in order (text between runs is not a word). Empty
     * for the raw candidate and wherever segmentation is unknown. Used to learn choices word by word.
     */
    val words: List<List<Word>> = candidates.map { emptyList() },
    /**
     * Per candidate, spans tiling the input in order, or null where the alignment is unknown (the raw candidate,
     * opt-in n-best alternatives).
     */
    val spans: List<List<Span>?> = candidates.map { null },
    /** The first candidate reads a run differently from what was typed, past the lead guard ([OssLimits.correctionLeadMargin]). */
    val correctionLeads: Boolean = false,
    /** Corrections that did not fit in [candidates]: bounded on their own so exact words never compete with them. */
    val suggestions: List<Suggestion> = emptyList(),
) {
    /** Redacted: candidates carry user text. */
    override fun toString(): String =
        "OssConversion(candidates=${candidates.size}, status=$status, unconvertedRuns=$unconvertedRuns, nBestUsed=$nBestUsed)"
}

/** A corrected reading offered outside the capped list; [spans] align it with the typed text. */
class Suggestion(val surface: String, val reading: String, val spans: List<Span>) {
    /** Redacted: suggestions carry user text. */
    override fun toString(): String = "Suggestion(length=${surface.length})"
}

/**
 * One way to spell exactly the typed text. [whole]: a single dictionary (or user) entry for the entire reading.
 * Otherwise the best segmented path of the text, or that path with one word replaced by a homophone.
 */
class ExactCandidate(val surface: String, val reading: String, val cost: Long, val whole: Boolean, val spans: List<Span>) {
    /** Redacted: candidates carry user text. */
    override fun toString(): String = "ExactCandidate(length=${surface.length}, whole=$whole)"
}

/**
 * Everything [OssConverter.exactCandidates] found: [candidates] holds, in a stable order, all [wholeCount] unique
 * whole-reading surfaces first, then the segmented ones. No ranker, correction or candidate limit touches this list.
 */
class ExactConversion(val candidates: List<ExactCandidate>, val wholeCount: Int, val status: OssStatus) {
    /** Redacted: candidates carry user text. */
    override fun toString(): String = "ExactConversion(candidates=${candidates.size}, whole=$wholeCount, status=$status)"
}

/** A dictionary word whose reading extends the typed text; a completion, never an exact or corrected reading. */
class Completion(val surface: String, val reading: String) {
    /** Redacted: completions carry user text. */
    override fun toString(): String = "Completion(length=${surface.length})"
}

/**
 * Ordinary kana-kanji conversion through the upstream engine primitives exactly as KanaKanjiEngine composes
 * them (GraphBuilder.constructGraph, reproduced over a [Lexicon] by [lattice] -> FindPath.findBestPath / backwardAStar),
 * with no n-gram assets. The dictionary is either deserialized upstream objects ([OssResources]) or a memory-mapped
 * [MappedDictionary]; both give the same lattices.
 * This is not Mozc's converter (no segmenter, suggestion, rewriter, user history) and not Zenzai.
 *
 * Input is split into runs of [isConvertible] characters; everything else (katakana, ASCII, emoji,
 * punctuation, unpaired surrogates) is passed through untouched and never sent to upstream.
 * Calls are serialized on one lock; a running conversion cannot be cancelled or pre-empted, so callers
 * should use a single worker and discard stale results. Nothing is logged.
 */
class OssConverter internal constructor(
    private val lexicon: Lexicon,
    private val matrix: ConnectionMatrix,
    val limits: OssLimits = OssLimits(),
) {
    constructor(resources: OssResources, limits: OssLimits = OssLimits()) : this(UpstreamLexicon(resources), resources.matrix, limits)
    constructor(dictionary: MappedDictionary, limits: OssLimits = OssLimits()) : this(dictionary.lexicon, dictionary.matrix, limits)

    private val lock = Any()
    private val findPath = FindPath()
    private val statusCounts = AtomicLongArray(OssStatus.entries.size)

    /**
     * Returns 2..[OssLimits.maxCandidates] candidates (fewer if fewer exist), best first, raw included.
     * With [correction], dictionary words may match after one flick-slip edit each (see [FuzzyLattice]), the
     * cheapest path is first, and the best path for the literal reading is always kept right after it.
     */
    fun convert(text: String, limit: Int = 5, correction: EditPenalties? = null, user: UserWords? = null): OssConversion {
        require(limit in 2..limits.maxCandidates) { "limit must be in [2, ${limits.maxCandidates}]" }
        val result = convertBounded(text, limit, correction, user)
        statusCounts.incrementAndGet(result.status.ordinal)
        return result
    }

    /** Cumulative per-status counts since construction (no text). */
    fun statusCounts(): Map<OssStatus, Long> = OssStatus.entries.associateWith { statusCounts.get(it.ordinal) }

    private fun convertBounded(text: String, limit: Int, correction: EditPenalties?, user: UserWords?): OssConversion {
        if (text.isEmpty()) return OssConversion(listOf(text), OssStatus.EMPTY_INPUT)
        if (text.length > limits.maxInputChars) return OssConversion(listOf(text), OssStatus.INPUT_TOO_LONG)
        val runs = convertibleRuns(text)
        if (runs.isEmpty()) return OssConversion(listOf(text), OssStatus.OK)
        return try {
            synchronized(lock) { convertRuns(text, runs, limit, correction, user) }
        } catch (e: Exception) {
            OssConversion(listOf(text), OssStatus.BACKEND_ERROR, failure = e.javaClass.simpleName)
        } catch (e: StackOverflowError) {
            OssConversion(listOf(text), OssStatus.BACKEND_ERROR, failure = e.javaClass.simpleName)
        }
    }

    /** One converted run: best surface/reading (possibly corrected), the literal best, and one-word variants. */
    private class RunResult(
        val converted: Boolean,
        val surface: String,
        val reading: String,
        val literal: String,
        val variants: List<Variant>,
        val words: List<Word> = emptyList(),
        val literalWords: List<Word> = emptyList(),
        /** Spans of [surface] and [literal], relative to the run. */
        val spans: List<Span> = listOf(literalSpan(reading)),
        val literalSpans: List<Span> = listOf(literalSpan(reading)),
        val correctionLeads: Boolean = false,
        /** The corrected path the guard held back; one-word variants of the exact path cannot reach it. */
        val heldBack: List<Node> = emptyList(),
    )

    private data class RunKey(val reading: String, val correction: EditPenalties?, val userVersion: Long, val endsInput: Boolean)

    // Typing changes only the last run; earlier runs (split at punctuation etc.) are reused. Guarded by [lock].
    private val runCache = object : LinkedHashMap<RunKey, RunResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RunKey, RunResult>) = size > RUN_CACHE_SIZE
    }

    /** [endsInput]: the run's last kana is the last character of the whole input, so correction never deletes it. */
    private fun convertRun(reading: String, correction: EditPenalties?, user: UserWords?, endsInput: Boolean): RunResult =
        runCache.getOrPut(RunKey(reading, correction, user?.version ?: -1, endsInput)) {
            val exact = lexicon.lattice(reading)
            user?.let { personalize(exact, reading, it) }
            val exactPath = bestPath(exact, reading.length)
            val fuzzy = if (correction != null && reading.length <= limits.maxCorrectionRunLength) {
                FuzzyLattice(lexicon, correction).build(reading, keepLast = endsInput).also { g -> user?.let { personalize(g, reading, it) } }
            } else {
                null
            }
            // A fuzzy lattice over the node bound (or without a path) falls back to the literal one.
            val fuzzyPath = fuzzy?.let { bestPath(it, reading.length) }.orEmpty()
            val graph = if (fuzzyPath.isNotEmpty()) fuzzy!! else exact
            // Corrections stay in the lattice as variants either way; the guard only decides what comes first.
            val correctionLeads = fuzzyPath.isNotEmpty() && fuzzyPath.any { it.key != reading.substring(it.sPos, it.sPos + it.len) } &&
                (exactPath.isEmpty() || pathCost(fuzzyPath) + limits.correctionLeadMargin <= pathCost(exactPath))
            val path = if (correctionLeads) fuzzyPath else exactPath.ifEmpty { fuzzyPath }
            if (path.isEmpty()) return@getOrPut RunResult(false, reading, reading, reading, emptyList())
            RunResult(
                converted = true,
                surface = path.joinToString("") { it.tango },
                reading = path.joinToString("") { it.key },
                literal = if (exactPath.isEmpty()) reading else exactPath.joinToString("") { it.tango },
                // Bounded alternatives from the same lattice: no queue, at most one word replaced per candidate.
                variants = segmentVariants(graph, path, reading),
                words = path.map { Word(it.key, it.tango) },
                literalWords = exactPath.map { Word(it.key, it.tango) },
                spans = path.map(::nodeSpan),
                literalSpans = if (exactPath.isEmpty()) listOf(literalSpan(reading)) else exactPath.map(::nodeSpan),
                correctionLeads = correctionLeads,
                heldBack = if (correctionLeads || !fuzzyPath.any { it.key != reading.substring(it.sPos, it.sPos + it.len) }) emptyList() else fuzzyPath,
            )
        }

    /**
     * Applies [user] to a lattice over [reading]: learned dictionary words get cheaper, and user words that are
     * not in the dictionary are added (POS and cost from [UserWords]). Upstream nodes are replaced, not mutated.
     */
    private fun personalize(graph: List<MutableList<MutableList<Node>>>, reading: String, user: UserWords) {
        if (user.isEmpty) return
        for (end in graph.indices) {
            for (list in graph[end]) {
                for (k in list.indices) {
                    val node = list[k]
                    if (node.key == "EOS" && node.tango == "EOS") continue
                    val bonus = user.bonus(node.key, node.tango)
                    if (bonus <= 0) continue
                    val cost = node.wcost - bonus
                    list[k] = node.copy(score = cost, f = cost, g = cost, wcost = cost, totalCost = Int.MAX_VALUE, prev = null, next = null)
                }
            }
        }
        for (start in reading.indices) {
            for (len in 1..minOf(user.maxReadingLength, reading.length - start)) {
                val key = reading.substring(start, start + len)
                val added = user.added(key)
                if (added.isEmpty()) continue
                val end = start + len
                val present = graph[end].flatten().filter { it.sPos == start && it.len.toInt() == len }.map { it.tango }.toSet()
                val nodes = added.filter { it.surface !in present }.map {
                    val cost = it.cost - user.bonus(key, it.surface)
                    Node(
                        l = it.posId.toShort(), r = it.posId.toShort(), score = cost, f = cost, g = cost,
                        tango = it.surface, len = len.toShort(), sPos = start, key = key, wcost = cost, totalCost = Int.MAX_VALUE,
                    )
                }
                if (nodes.isNotEmpty()) graph[end].add(nodes.toMutableList())
            }
        }
    }

    /**
     * All spellings of exactly [text], in a stable order and without a count limit. Whole-reading entries are read
     * straight from the dictionary, so every unique surface stored for the reading is there however rare; the number
     * is the dictionary's, at most a few hundred for one reading. Text that is not one dictionary reading has no such
     * entries: it gets its best exact path plus every same-reading replacement of each word of that path in the
     * variant window, never the exponential product of all segmentations. Correction and rankers are not involved.
     * Null when [cancelled] says the result is no longer wanted.
     */
    fun exactCandidates(text: String, user: UserWords? = null, cancelled: () -> Boolean = { false }): ExactConversion? {
        if (text.isEmpty()) return ExactConversion(emptyList(), 0, OssStatus.EMPTY_INPUT)
        if (text.length > limits.maxInputChars) return ExactConversion(emptyList(), 0, OssStatus.INPUT_TOO_LONG)
        return try {
            synchronized(lock) { collectExact(text, convertibleRuns(text), user, cancelled) }
        } catch (e: Exception) {
            ExactConversion(emptyList(), 0, OssStatus.BACKEND_ERROR)
        } catch (e: StackOverflowError) {
            ExactConversion(emptyList(), 0, OssStatus.BACKEND_ERROR)
        }
    }

    private fun collectExact(text: String, runs: List<IntRange>, user: UserWords?, cancelled: () -> Boolean): ExactConversion? {
        // Any text can be a reading: the dictionary also has entries such as 5じだい that a kana run would split.
        val whole = wholeEntries(text, user)
        if (runs.isEmpty()) return ExactConversion(whole, whole.size, OssStatus.OK)
        var windowStart = runs.last().first
        for (r in runs.asReversed()) if (text.length - r.first <= limits.variantWindowChars) windowStart = r.first else break
        // The best exact spelling of every run, then replacements of one word of it; text between runs is kept.
        val pieces = ArrayList<List<Node>?>()
        var total = 0L
        var unconverted = 0
        for (run in runs) {
            if (cancelled()) return null
            val reading = text.substring(run.first, run.last + 1)
            val graph = lexicon.lattice(reading)
            user?.let { personalize(graph, reading, it) }
            val path = bestPath(graph, reading.length)
            if (path.isEmpty()) {
                unconverted++
                pieces += null
            } else {
                pieces += path
                total += pathCost(path)
            }
        }
        val status = if (unconverted > 0) OssStatus.PARTIAL else OssStatus.OK
        val found = LinkedHashMap<String, ExactCandidate>()
        whole.forEach { found[it.surface] = it }
        if (unconverted > 0) return ExactConversion(found.values.toList(), whole.size, status)
        fun build(run: Int, word: Int, replacement: Node?): List<Span> {
            val spans = ArrayList<Span>()
            var cursor = 0
            runs.forEachIndexed { r, range ->
                if (cursor < range.first) spans += text.substring(cursor, range.first).let { Span(cursor, range.first, it, it, Span.Kind.PASSTHROUGH) }
                pieces[r]!!.forEachIndexed { i, node ->
                    val chosen = if (r == run && i == word) replacement!! else node
                    spans += nodeSpan(chosen).shifted(range.first)
                }
                cursor = range.last + 1
            }
            if (cursor < text.length) spans += text.substring(cursor).let { Span(cursor, text.length, it, it, Span.Kind.PASSTHROUGH) }
            return spans
        }
        val segmented = ArrayList<ExactCandidate>()
        fun offer(spans: List<Span>, cost: Long) {
            segmented += ExactCandidate(spans.joinToString("") { it.surface }, text, cost, false, spans)
        }
        offer(build(-1, -1, null), total)
        runs.forEachIndexed { r, range ->
            if (range.first < windowStart) return@forEachIndexed
            val reading = text.substring(range.first, range.last + 1)
            val graph = lexicon.lattice(reading)
            user?.let { personalize(graph, reading, it) }
            val path = pieces[r]!!
            for (i in path.indices) {
                if (cancelled()) return null
                val node = path[i]
                val prevRight: Short = if (i == 0) 0 else path[i - 1].r
                val nextLeft: Short = if (i == path.lastIndex) 0 else path[i + 1].l
                val base = localCost(node, prevRight, nextLeft)
                graph[node.sPos + node.len].flatten()
                    .filter { it.sPos == node.sPos && it.len == node.len && it.key == node.key && it.tango != node.tango }
                    .forEach { offer(build(r, i, it), total - base + localCost(it, prevRight, nextLeft)) }
            }
        }
        // Cheapest total first; the surface breaks ties, so the order does not depend on hash or trie iteration.
        for (c in segmented.sortedWith(compareBy<ExactCandidate> { it.cost }.thenBy { it.surface })) {
            if (c.surface !in found) found[c.surface] = c
        }
        return ExactConversion(found.values.toList(), whole.size, status)
    }

    /** Every unique surface stored for exactly [reading] plus the user's own, cheapest first by the Viterbi local cost. */
    private fun wholeEntries(reading: String, user: UserWords?): List<ExactCandidate> {
        val best = HashMap<String, Long>()
        fun offer(surface: String, cost: Long) {
            val old = best[surface]
            if (old == null || cost < old) best[surface] = cost
        }
        val termId = lexicon.termId(reading)
        if (termId >= 1) {
            for (node in lexicon.nodes(reading, termId, 0, reading.length)) {
                offer(node.tango, localCost(node, 0, 0).toLong() - (user?.bonus(reading, node.tango) ?: 0))
            }
        }
        user?.added(reading)?.forEach { added ->
            val id = added.posId.toShort()
            offer(added.surface, matrix.getCost(0, added.posId).toLong() + added.cost + matrix.getCost(id.toInt(), 0) - user.bonus(reading, added.surface))
        }
        return best.entries.sortedWith(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).map { (surface, cost) ->
            ExactCandidate(surface, reading, cost, true, listOf(Span(0, reading.length, reading, surface, Span.Kind.WORD)))
        }
    }

    /**
     * Dictionary words whose reading starts with [text] and is longer, cheapest word first. Bounded by [limit] and the
     * lexicon's node walk; meant for an explicit panel, not for every key press. Empty unless [text] is one kana run.
     */
    fun completions(text: String, limit: Int): List<Completion> {
        if (text.isEmpty() || text.length > MAX_COMPLETION_PREFIX || !text.all(::isConvertible)) return emptyList()
        return try {
            synchronized(lock) { lexicon.predictions(text, limit, COMPLETION_NODES).map { Completion(it.surface, it.reading) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun convertRuns(
        text: String,
        runs: List<IntRange>,
        limit: Int,
        correction: EditPenalties?,
        user: UserWords?,
    ): OssConversion {
        val best = StringBuilder()
        val bestReading = StringBuilder()
        val literal = StringBuilder()
        val bestWords = ArrayList<Word>()
        val literalWords = ArrayList<Word>()
        val bestSpans = ArrayList<Span>()
        val literalSpans = ArrayList<Span>()
        var unconverted = 0
        var alternatives: List<String> = emptyList()
        // One-word replacements from the runs in the window, as (run's span in best/bestReading, replacement).
        class RunVariant(val bestStart: Int, val bestEnd: Int, val readingStart: Int, val readingEnd: Int,
                         val surface: String, val reading: String, val delta: Long, val corrected: Boolean, val whole: Boolean,
                         val wordStart: Int, val wordEnd: Int, val words: List<Word>,
                         val spanStart: Int, val spanEnd: Int, val spans: List<Span>)
        val runVariants = ArrayList<RunVariant>()
        var nBestUsed = false
        var correctionLeads = false
        var heldBack = false
        // The best result with every held-back correction applied.
        val held = StringBuilder()
        val heldReading = StringBuilder()
        val heldWords = ArrayList<Word>()
        val heldSpans = ArrayList<Span>()
        // Only the trailing runs within [OssLimits.variantWindowChars] get variants; earlier text is fixed to its
        // best conversion (the "head"), which a judge such as zenz can read as context.
        var windowStart = runs.last().first
        for (r in runs.asReversed()) if (text.length - r.first <= limits.variantWindowChars) windowStart = r.first else break
        var headSurface = ""
        var headReading = ""
        var cursor = 0
        for (run in runs) {
            val between = text.substring(cursor, run.first)
            best.append(between)
            bestReading.append(between)
            literal.append(between)
            held.append(between)
            heldReading.append(between)
            if (between.isNotEmpty()) {
                val pass = Span(cursor, run.first, between, between, Span.Kind.PASSTHROUGH)
                bestSpans += pass
                literalSpans += pass
                heldSpans += pass
            }
            if (run.first == windowStart) {
                headSurface = best.toString()
                headReading = bestReading.toString()
            }
            cursor = run.last + 1
            val reading = text.substring(run.first, cursor)
            // Only the input's last kana is protected: before punctuation or ASCII the word was finished.
            val result = convertRun(reading, correction, user, endsInput = cursor == text.length)
            if (!result.converted) unconverted++
            if (result.correctionLeads) correctionLeads = true
            val bestStart = best.length
            val readingStart = bestReading.length
            val wordStart = bestWords.size
            val spanStart = bestSpans.size
            best.append(result.surface)
            bestReading.append(result.reading)
            literal.append(result.literal)
            bestWords += result.words
            literalWords += result.literalWords
            bestSpans += result.spans.map { it.shifted(run.first) }
            literalSpans += result.literalSpans.map { it.shifted(run.first) }
            if (result.heldBack.isNotEmpty()) {
                heldBack = true
                held.append(result.heldBack.joinToString("") { it.tango })
                heldReading.append(result.heldBack.joinToString("") { it.key })
                heldWords += result.heldBack.map { Word(it.key, it.tango) }
                heldSpans += result.heldBack.map { nodeSpan(it).shifted(run.first) }
            } else {
                held.append(result.surface)
                heldReading.append(result.reading)
                heldWords += result.words
                heldSpans += result.spans.map { it.shifted(run.first) }
            }
            // Kept for every window run, so text after "。" can later select a different word before it.
            if (run.first >= windowStart) {
                for (v in result.variants) {
                    runVariants += RunVariant(
                        bestStart, best.length, readingStart, bestReading.length, v.surface, v.reading, v.delta,
                        // Reads the run as neither typed nor the best path does: a flick-slip correction the best path did not make.
                        v.reading != result.reading && v.reading != reading,
                        // One dictionary word for the run: comes before fragments such as は死 for はし.
                        v.spans.size == 1 && result.spans.size > 1,
                        wordStart, bestWords.size, v.words,
                        spanStart, bestSpans.size, v.spans.map { it.shifted(run.first) },
                    )
                }
            }
            if (runs.size == 1 && result.converted && reading.length <= limits.maxNBestRunLength) {
                val exact = lexicon.lattice(reading)
                if (exact.sumOf { ends -> ends.sumOf { it.size } } <= limits.maxNBestGraphNodes) {
                    // backwardAStar reads only IDs, word costs and spans, not Viterbi scores.
                    alternatives = findPath.backwardAStar(exact, reading.length, matrix, limit, reading)
                        .map { text.substring(0, run.first) + it + text.substring(cursor) }
                    nBestUsed = true
                }
            }
        }
        val tail = text.substring(cursor)
        best.append(tail)
        bestReading.append(tail)
        literal.append(tail)
        held.append(tail)
        heldReading.append(tail)
        if (tail.isNotEmpty()) {
            val pass = Span(cursor, text.length, tail, tail, Span.Kind.PASSTHROUGH)
            bestSpans += pass
            literalSpans += pass
            heldSpans += pass
        }

        // Surface -> (reading it was converted from, words, spans); the first reading offered for a surface wins.
        class Ranked(val reading: String, val words: List<Word>, val spans: List<Span>?)
        val ranked = LinkedHashMap<String, Ranked>()
        fun add(surface: String, reading: String, words: List<Word>, spans: List<Span>?) {
            if (surface !in ranked) ranked[surface] = Ranked(reading, words, spans)
        }
        val bestText = best.toString()
        val bestReadingText = bestReading.toString()
        add(bestText, bestReadingText, bestWords, bestSpans)
        if (correction != null) add(literal.toString(), text, literalWords, literalSpans)
        alternatives.forEach { add(it, text, emptyList(), null) }
        // Cheapest extra cost first across runs; each replaces one word of one run in the best result. Alternatives that
        // keep the typed or best reading (homophones such as 芦/葦 for あし) come before ones that add a slip correction,
        // which would otherwise crowd them out because rare words cost more than common words plus an edit penalty.
        val sortedVariants = runVariants.sortedWith(compareBy({ it.corrected }, { !it.whole }, { it.delta }))
        // Right after the first alternative: visible and within the judge's reach, yet behind what was typed.
        if (heldBack && sortedVariants.isEmpty()) add(held.toString(), heldReading.toString(), heldWords, heldSpans)
        for ((index, v) in sortedVariants.withIndex()) {
            add(
                bestText.substring(0, v.bestStart) + v.surface + bestText.substring(v.bestEnd),
                bestReadingText.substring(0, v.readingStart) + v.reading + bestReadingText.substring(v.readingEnd),
                bestWords.subList(0, v.wordStart) + v.words + bestWords.subList(v.wordEnd, bestWords.size),
                bestSpans.subList(0, v.spanStart) + v.spans + bestSpans.subList(v.spanEnd, bestSpans.size),
            )
            if (index == 0 && heldBack) add(held.toString(), heldReading.toString(), heldWords, heldSpans)
        }
        val top = ranked.entries.take(limit).map { it.key to it.value }.toMutableList()
        if (top.none { it.first == text }) {
            if (top.size == limit) top.removeAt(top.lastIndex)
            top += text to Ranked(text, emptyList(), null)
        }
        val status = if (unconverted > 0) OssStatus.PARTIAL else OssStatus.OK
        val suggestions = ranked.entries.drop(top.size).filter { it.value.reading != text && it.value.spans != null }
            .take(limits.maxSuggestions).map { Suggestion(it.key, it.value.reading, it.value.spans!!) }
        return OssConversion(
            top.map { it.first }, status, unconverted, nBestUsed, readings = top.map { it.second.reading },
            headLength = windowStart, headSurface = headSurface, headReading = headReading,
            words = top.map { if (it.first == text) emptyList() else it.second.words },
            spans = top.map { if (it.first == text) null else it.second.spans },
            correctionLeads = correctionLeads, suggestions = suggestions,
        )
    }

    private fun Span.shifted(by: Int) = copy(typedStart = typedStart + by, typedEnd = typedEnd + by)

    /** Best path covering the whole run, or empty if EOS is unreachable or the lattice is over the bound. */
    private fun bestPath(graph: List<MutableList<MutableList<Node>>>, length: Int): List<Node> {
        val nodes = graph.sumOf { ends -> ends.sumOf { it.size } }
        if (nodes > limits.maxGraphNodes) return emptyList()
        val path = findPath.findBestPath(graph, length, matrix)
        // An unreachable EOS yields an empty path; require the typed spans to cover the run exactly.
        return if (path.isNotEmpty() && path.sumOf { it.len.toInt() } == length) path else emptyList()
    }

    /** Total path cost as Viterbi counts it, BOS and EOS connections included. */
    private fun pathCost(path: List<Node>): Long {
        var total = 0L
        for (i in path.indices) total += matrix.getCost(if (i == 0) 0 else path[i - 1].r.toInt(), path[i].l.toInt()) + path[i].wcost
        return total + matrix.getCost(path.last().r.toInt(), 0)
    }

    private fun localCost(node: Node, prevRight: Short, nextLeft: Short): Int =
        matrix.getCost(prevRight.toInt(), node.l.toInt()) + node.wcost + matrix.getCost(node.r.toInt(), nextLeft.toInt())

    /**
     * Surfaces that differ from the best [path] in one word with the same reading span, plus single words
     * covering the whole reading (homophones such as 漢字/感じ/幹事 for かんじ). Each is scored with the same
     * word and connection costs Viterbi uses and returned cheapest first; at most [MAX_VARIANT_WORDS] words
     * per span are tried, so the work is linear in the lattice size. Words that are only right together (a noun and
     * its verb's kanji) need both replaced, so up to [OssLimits.maxPairVariants] of the cheapest two-word replacements
     * compete with them on the same cost.
     */
    private class Variant(val surface: String, val reading: String, val delta: Long, val words: List<Word>, val spans: List<Span>)

    private fun segmentVariants(
        graph: List<MutableList<MutableList<Node>>>,
        path: List<Node>,
        typed: String,
    ): List<Variant> {
        val length = typed.length
        fun local(node: Node, prevRight: Short, nextLeft: Short) = localCost(node, prevRight, nextLeft)
        class Scored(val cost: Long, val reading: String, val words: List<Word>, val spans: List<Span>)
        val scored = HashMap<String, Scored>()
        fun offer(surface: String, reading: String, cost: Long, words: List<Word>, spans: List<Span>) {
            val old = scored[surface]
            if (old == null || cost < old.cost) scored[surface] = Scored(cost, reading, words, spans)
        }
        val pathWords = path.map { Word(it.key, it.tango) }
        // A replacement node has the same typed span (sPos/len) as the node it replaces.
        val pathSpans = path.map(::nodeSpan)
        val keys = path.map { it.key }
        // BOS -> path -> EOS; BOS/EOS use context ID 0 as in GraphBuilder.
        val total = pathCost(path)
        val surfaces = path.map { it.tango }
        val swaps = path.map { ArrayList<Swap>() }
        for (i in path.indices) {
            val node = path[i]
            val prevRight: Short = if (i == 0) 0 else path[i - 1].r
            val nextLeft: Short = if (i == path.lastIndex) 0 else path[i + 1].l
            val base = total - local(node, prevRight, nextLeft)
            alternatives(
                graph[node.sPos + node.len].flatten().filter { it.sPos == node.sPos && it.len == node.len && it.tango != node.tango },
                node.key, typed.substring(node.sPos, node.sPos + node.len),
            )
                .forEach { alt ->
                    val surface = buildString {
                        for (j in surfaces.indices) append(if (j == i) alt.tango else surfaces[j])
                    }
                    val reading = buildString {
                        for (j in keys.indices) append(if (j == i) alt.key else keys[j])
                    }
                    val words = pathWords.toMutableList().also { it[i] = Word(alt.key, alt.tango) }
                    val spans = pathSpans.toMutableList().also { it[i] = nodeSpan(alt) }
                    val cost = base + local(alt, prevRight, nextLeft)
                    offer(surface, reading, cost, words, spans)
                    if (alt.key == node.key) swaps[i] += Swap(alt, cost - total)
                }
        }
        for (p in cheapestPairs(path, total, swaps)) {
            val replaced = path.toMutableList().also {
                it[p.first] = p.firstNode
                it[p.second] = p.secondNode
            }
            offer(replaced.joinToString("") { it.tango }, replaced.joinToString("") { it.key }, p.cost, replaced.map { Word(it.key, it.tango) }, replaced.map(::nodeSpan))
        }
        if (path.size > 1) {
            alternatives(graph[length].flatten().filter { it.sPos == 0 && it.len.toInt() == length }, keys.joinToString(""), typed)
                .forEach { offer(it.tango, it.key, local(it, 0, 0).toLong(), listOf(Word(it.key, it.tango)), listOf(nodeSpan(it))) }
        }
        // A one-word replacement is never cheaper than the Viterbi path, so delta >= 0 (up to ties).
        return scored.entries.sortedBy { it.value.cost }
            .map { Variant(it.key, it.value.reading, it.value.cost - total, it.value.words, it.value.spans) }
    }

    /** One replacement of a path word with the same reading and span; [delta] over the path's total cost. */
    private class Swap(val node: Node, val delta: Long)

    private class PairSwap(val first: Int, val firstNode: Node, val second: Int, val secondNode: Node, val cost: Long)

    /**
     * The cheapest [OssLimits.maxPairVariants] replacements of two words among the last [MAX_PAIR_PATH_WORDS] words of
     * [path], each from its [MAX_PAIR_WORDS] cheapest [swaps]. Costs are Viterbi's: the two deltas add up unless the
     * words touch, when the connection between them is priced again.
     */
    private fun cheapestPairs(path: List<Node>, total: Long, swaps: List<List<Swap>>): List<PairSwap> {
        if (limits.maxPairVariants == 0) return emptyList()
        val cheapest = swaps.map { list -> list.sortedBy { it.delta }.distinctBy { it.node.tango }.take(MAX_PAIR_WORDS) }
        val pairs = ArrayList<PairSwap>()
        for (i in maxOf(0, path.size - MAX_PAIR_PATH_WORDS) until path.size) for (j in i + 1 until path.size) {
            for (a in cheapest[i]) for (b in cheapest[j]) {
                val cost = if (j > i + 1) {
                    total + a.delta + b.delta
                } else {
                    // Both words' outer connections and the one between them, with the second word's outer edge once.
                    val prevRight = if (i == 0) 0 else path[i - 1].r.toInt()
                    val nextLeft = if (j == path.lastIndex) 0 else path[j + 1].l.toInt()
                    fun span(x: Node, y: Node) = matrix.getCost(prevRight, x.l.toInt()) + x.wcost.toLong() +
                        matrix.getCost(x.r.toInt(), y.l.toInt()) + y.wcost + matrix.getCost(y.r.toInt(), nextLeft)
                    total - span(path[i], path[j]) + span(a.node, b.node)
                }
                pairs += PairSwap(i, a.node, j, b.node, cost)
            }
        }
        return pairs.sortedBy { it.cost }.take(limits.maxPairVariants)
    }

    /**
     * The cheapest [MAX_VARIANT_WORDS] surfaces reading the [typed] span or the path's [key], plus the cheapest
     * [MAX_CORRECTED_WORDS] with another reading (fuzzy-lattice corrections). Corrections cannot take every slot of a
     * span, and a corrected path cannot demote what was actually typed into the correction bucket. These only feed the
     * capped list: [exactCandidates] is what guarantees every exact word stays reachable.
     */
    private fun alternatives(nodes: List<Node>, key: String, typed: String): List<Node> {
        val (same, other) = nodes.sortedBy { it.wcost }.partition { it.key == typed || it.key == key }
        // Slots are per surface, but every POS row of a kept surface stays: the caller scores them with connection costs.
        val kept = same.map { it.tango }.distinct().take(MAX_VARIANT_WORDS).toHashSet()
        val corrected = other.map { it.tango }.distinct().filter { it !in kept }.take(MAX_CORRECTED_WORDS).toHashSet()
        return same.filter { it.tango in kept } + other.filter { it.tango in corrected }
    }

    private companion object {
        const val MAX_VARIANT_WORDS = 24
        const val MAX_CORRECTED_WORDS = 4
        const val MAX_PAIR_WORDS = 4
        const val MAX_PAIR_PATH_WORDS = 12
        const val RUN_CACHE_SIZE = 64
        const val MAX_COMPLETION_PREFIX = 24
        /** Trie nodes one completion lookup may visit: breadth first, so shorter words are found first. */
        const val COMPLETION_NODES = 5_000
    }

    private fun convertibleRuns(text: String): List<IntRange> {
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            if (!isConvertible(text[i])) {
                i++
                continue
            }
            val start = i
            while (i < text.length && isConvertible(text[i])) i++
            runs += start until i
        }
        return runs
    }
}
