package dev.astra.ime.core

/** How a candidate relates to what was typed; decided from its alignment and reading, never from its surface. */
enum class CandidateClass {
    /** Spells exactly the typed reading. */
    EXACT,

    /** Reads at least one typed span differently: a flick-slip correction, whatever its length. */
    CORRECTION,

    /** No alignment, and its reading extends what was typed: a completion. */
    PREDICTION,
}

/**
 * The two sections of the expanded candidate panel. [top] is what the rankers suggest (予測・補正) and stays short;
 * [exact] is every spelling of the typed reading (変換), with no limit. Neither takes anything from the other.
 */
object CandidateGroups {
    /** Entries of the top section. */
    const val MAX_TOP = 32

    /**
     * Aligned segments decide: an insertion at the end of a word (typed さら for さらい) has a longer reading that merely
     * looks like a completion, but its segment says the typed span was read differently. Only an unaligned candidate
     * (learned phrase, dictionary completion, raw) is judged by its reading.
     */
    fun classify(typed: String, candidate: Candidate): CandidateClass {
        val segments = Alignment.checked(typed, candidate.surface, candidate.segments)
        if (segments != null) {
            val same = segments.all { hiragana(it.reading) == hiragana(typed.substring(it.start, it.end)) }
            return if (same) CandidateClass.EXACT else CandidateClass.CORRECTION
        }
        val want = hiragana(typed)
        val read = hiragana(candidate.reading)
        return when {
            read == want -> CandidateClass.EXACT
            read.length > want.length && read.startsWith(want) -> CandidateClass.PREDICTION
            else -> CandidateClass.CORRECTION
        }
    }

    /**
     * The rankers' first choice (exact or not), then corrections and predictions in their ranked order, then the
     * bounded [extras] (corrections past the capped list, dictionary completions). Literal kana and katakana stay out:
     * they are the exact section's first entries.
     */
    fun top(typed: String, ranked: List<Candidate>, extras: List<Candidate>): List<Candidate> {
        val katakana = LiteralChoices.katakana(typed)
        val seen = hashSetOf(typed, katakana)
        val out = ArrayList<Candidate>()
        ranked.firstOrNull { it.surface != typed && it.surface != katakana }?.let { if (seen.add(it.surface)) out += it }
        for (c in ranked + extras) {
            if (out.size >= MAX_TOP) break
            if (classify(typed, c) != CandidateClass.EXACT && seen.add(c.surface)) out += c
        }
        return out
    }

    /**
     * Raw kana and katakana, exact phrases the rankers hold that the dictionary does not (a learned phrase), then
     * [dictionary] in its own stable order, then any other exact candidate of [ranked]. Duplicates are dropped; a
     * surface is never dropped for any other reason, and [ranked]'s order cannot move a dictionary entry.
     */
    fun exact(typed: String, dictionary: List<Candidate>, ranked: List<Candidate>): List<Candidate> {
        val seen = HashSet<String>()
        val out = ArrayList<Candidate>(dictionary.size + 2)
        fun add(c: Candidate) {
            if (seen.add(c.surface)) out += c
        }
        add(ranked.firstOrNull { it.surface == typed } ?: Candidate(typed, typed, Double.MAX_VALUE))
        val katakana = LiteralChoices.katakana(typed)
        add(ranked.firstOrNull { it.surface == katakana } ?: Candidate(typed, katakana, Double.MAX_VALUE, LiteralChoices.katakanaSegments(typed)))
        val mine = ranked.filter { classify(typed, it) == CandidateClass.EXACT }
        mine.filter { it.segments == null }.forEach(::add)
        dictionary.forEach(::add)
        mine.forEach(::add)
        return out
    }

    private fun hiragana(s: String): String {
        val cps = s.codePoints().toArray()
        return String(IntArray(cps.size) { toHiragana(cps[it]) }, 0, cps.size)
    }
}
