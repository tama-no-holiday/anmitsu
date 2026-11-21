package dev.astra.ime.oss.zenz

/**
 * Reorders dictionary candidates with zenz: each candidate is scored as log P(surface | its own reading),
 * minus a channel cost per kana edit between that reading and the typed text, minus a small prior for the
 * dictionary rank. zenz reads the whole window, so a word earlier in it can change because of text typed
 * after it (e.g. 売った → 打った once バットで follows). Text before the window (the converter's fixed
 * "head") is given to zenz as left context instead of being scored, which keeps long input cheap.
 * Weights were chosen on development data only (docs/next-work.md); they are not calibrated probabilities.
 */
object ZenzRanking {
    /**
     * [learned]: nats added to a candidate that uses a word the user chose before (on-device history).
     * [contextPerRank] and [contextMargin] are the options of data/context-holdout/protocol.md, off by default, and act
     * only when [rerank] is told an external context exists: a rank prior in place of [perRank], and how many nats the
     * model must prefer an exact-reading candidate over the first one for it to move to first place. The app sets
     * [contextMargin] (`OssBackend.RANKING`), which passed the holdout; [contextPerRank] is for evaluation only.
     * [phraseGap] and [phraseShift] are the limits of [contextOutweighs]; without [phraseGap] a learned phrase always stands.
     * [contextFirst] (0.1.31, the user's decision): with [hasMeaningfulContext], the candidates reading exactly what was
     * typed are ordered among themselves by the model alone, plus [learned] for a strong learned word; dictionary rank only
     * breaks ties and the rank prior and [contextMargin] no longer apply to them. Corrections keep their places.
     */
    data class Weights(
        val perRank: Double = 1.0,
        val perEdit: Double = 2.0,
        val learned: Double = 3.0,
        val contextPerRank: Double? = null,
        val contextMargin: Double? = null,
        val phraseGap: Double? = null,
        val phraseShift: Double = 0.0,
        val contextFirst: Boolean = false,
    )

    /**
     * Whether text before the cursor can inform the model: at least one letter or ideograph survives [promptContext].
     * Punctuation, digits or symbols alone are not taken as context.
     */
    fun hasMeaningfulContext(text: String): Boolean = promptContext(text).codePoints().anyMatch { Character.isLetter(it) }

    /** Fixed leading part shared by the candidates: typed length, its converted surface and reading. */
    class Head(val typedLength: Int, val surface: String, val reading: String)

    /** Scores [candidates] converted from katakana [reading], after optional left [context] (may be ""). */
    fun interface Scorer {
        fun score(context: String, reading: String, candidates: List<String>): FloatArray?
    }

    /** Code points of left context passed to zenz; it was trained with short left contexts. */
    const val CONTEXT_CHARS = 40

    /**
     * What may reach a zenz prompt as left context: the last [CONTEXT_CHARS] code points of [text] without anything
     * that is not ordinary text. The prompt marks (U+EE00...) are private-use characters, so one in an editor's text
     * would otherwise read as a prompt delimiter. Unpaired surrogates (a
     * cut pair), line breaks and invisible format marks go too. Tokens stay bounded: zenz_scorer tokenizes per code
     * point without parsing special tokens, at most 4 byte tokens each, so a context takes at most 160 of the 352 prompt
     * tokens and text such as "</s>" stays plain text.
     */
    fun promptContext(text: String): String {
        val kept = StringBuilder(minOf(text.length, CONTEXT_CHARS * 2))
        var i = text.length
        var count = 0
        while (i > 0 && count < CONTEXT_CHARS) {
            val point = text.codePointBefore(i)
            i -= Character.charCount(point)
            if (isPromptText(point)) {
                kept.insert(0, String(Character.toChars(point)))
                count++
            }
        }
        return kept.toString()
    }

    private fun isPromptText(point: Int): Boolean = when (Character.getType(point).toByte()) {
        Character.PRIVATE_USE, Character.SURROGATE, Character.CONTROL, Character.FORMAT, Character.UNASSIGNED,
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false
        else -> true
    }

    /**
     * Returns the new order of candidate indices, or null if scoring failed or the part after the head is
     * longer than [maxTailChars]. Only the first [maxScored] candidates that start with the head are scored and
     * reordered; all others keep their order after them.
     */
    fun rerank(
        typed: String,
        candidates: List<String>,
        readings: List<String>,
        maxScored: Int,
        weights: Weights = Weights(),
        head: Head? = null,
        maxTailChars: Int = 64,
        /** Whether candidate i uses a learned word; adds [Weights.learned] to its score. */
        learned: (Int) -> Boolean = { false },
        /** Fixed text before [typed] that is not part of it (committed text, repaired units); context only. */
        leftContext: String = "",
        /** Whether [leftContext] holds committed text from the editor. The caller says so: repaired units and the head are not it. */
        external: Boolean = false,
        /** In [Weights.contextFirst] ordering, which learned words count (explicit choices, registered words). */
        strongLearned: (Int) -> Boolean = learned,
        scorer: Scorer,
    ): List<Int>? {
        require(candidates.size == readings.size)
        val h = head ?: Head(0, "", "")
        val typedTail = typed.substring(h.typedLength)
        if (typedTail.length > maxTailChars) return null
        val scored = (0 until minOf(maxScored, candidates.size))
            .filter { candidates[it].startsWith(h.surface) && readings[it].startsWith(h.reading) }
        if (scored.size <= 1) return candidates.indices.toList()
        val context = promptContext(leftContext + h.surface)
        val logProb = HashMap<Int, Double>()
        // Candidates converted from the same reading share one prompt (and its KV cache) in the scorer.
        for ((tailReading, members) in scored.groupBy { readings[it].substring(h.reading.length) }) {
            val scores = scorer.score(context, katakana(tailReading), members.map { candidates[it].substring(h.surface.length) })
                ?: return null
            // A malformed answer (wrong length, NaN, infinity) is a failure: the dictionary order stands.
            if (scores.size != members.size || scores.any { !it.isFinite() }) return null
            members.forEachIndexed { k, i -> logProb[i] = scores[k].toDouble() }
        }
        val perRank = if (external) weights.contextPerRank ?: weights.perRank else weights.perRank
        val edits = scored.associateWith { editDistance(readings[it].substring(h.reading.length), typedTail) }
        val total = scored.associateWith { i ->
            logProb.getValue(i) - perRank * i -
                weights.perEdit * edits.getValue(i) +
                (if (learned(i)) weights.learned else 0.0)
        }
        val reordered = scored.sortedByDescending { total.getValue(it) }
        val margin = weights.contextMargin
        val final = when {
            !external -> reordered
            weights.contextFirst && hasMeaningfulContext(leftContext) -> {
                // The exact-reading candidates keep the slots they hold, refilled in the model's order (stable: ties keep rank).
                val exact = reordered.filter { edits.getValue(it) == 0 }
                val byModel = exact.sortedWith(
                    compareByDescending<Int> { logProb.getValue(it) + if (strongLearned(it)) weights.learned else 0.0 }.thenBy { it },
                ).iterator()
                reordered.map { if (edits.getValue(it) == 0) byModel.next() else it }
            }
            margin == null -> reordered
            else -> promoted(reordered, margin, { edits.getValue(it) == 0 }) { i ->
                logProb.getValue(i) - weights.perEdit * edits.getValue(i) + (if (learned(i)) weights.learned else 0.0)
            }
        }
        return final + candidates.indices.filter { it !in total }
    }

    /** Whether [rerank] with these arguments orders exact-reading candidates by the model ([Weights.contextFirst]). */
    fun contextRanks(weights: Weights, external: Boolean, leftContext: String): Boolean =
        external && weights.contextFirst && hasMeaningfulContext(leftContext)

    /**
     * Whether the text before the cursor speaks against a phrase the user learned for exactly [text], in favour of [leader].
     * The model must prefer [leader] by [Weights.phraseGap] nats with [context], and the context itself must be the reason:
     * that lead has to grow by [Weights.phraseShift] over the one without it ([plainContext], the repaired units alone).
     * A surface the model merely finds rare stays the user's choice. No scores or failure: the phrase stands.
     * The second prompt runs only when the first passes, and this runs only for a phrase whose whole reading was typed.
     */
    fun contextOutweighs(
        text: String,
        phrase: String,
        leader: String,
        context: String,
        plainContext: String,
        weights: Weights,
        scorer: Scorer,
    ): Boolean {
        val gap = weights.phraseGap ?: return false
        val withContext = promptContext(context)
        val without = promptContext(plainContext)
        if (phrase == leader || withContext == without) return false
        val reading = katakana(text)
        val pair = listOf(leader, phrase)
        fun lead(prompt: String) = scorer.score(prompt, reading, pair)?.let { (it[0] - it[1]).toDouble() }
        val lead = lead(withContext)?.takeIf { it >= gap } ?: return false
        return lead - (lead(without) ?: return false) >= weights.phraseShift
    }

    /** [order] with the [eligible] candidate the model likes best, rank aside, first if it beats the first by [margin] nats. */
    private fun promoted(order: List<Int>, margin: Double, eligible: (Int) -> Boolean, unranked: (Int) -> Double): List<Int> {
        val best = order.filter(eligible).maxByOrNull(unranked) ?: return order
        return if (best != order[0] && unranked(best) - unranked(order[0]) >= margin) listOf(best) + (order - best) else order
    }

    /**
     * Each candidate is scored given its own reading, so log P(され | サレ) and log P(更 | サラ) are not comparable and a
     * reorder alone can put a typo fix above what was typed. Unless the dictionary found the correction worth leading
     * ([correctionLeads]), the best candidate of [order] reading exactly [text] comes first; the rest keeps its order.
     */
    fun keepExactLead(order: List<Int>, readings: List<String>, text: String, correctionLeads: Boolean): List<Int> {
        if (correctionLeads || order.isEmpty() || readings[order[0]] == text) return order
        val exact = order.firstOrNull { readings[it] == text } ?: return order
        return listOf(exact) + order.filter { it != exact }
    }

    /** Hiragana to katakana for zenz prompts; everything else unchanged. */
    fun katakana(s: String): String = buildString {
        for (c in s) append(if (c.code in 0x3041..0x3096) (c.code + 0x60).toChar() else c)
    }

    /** Levenshtein distance with adjacent transposition (optimal string alignment). */
    fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
