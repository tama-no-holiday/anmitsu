package dev.astra.ime.oss

/**
 * Where a phrase the user committed for exactly the typed reading goes among the ranked candidates (0.1.31). Shared by
 * the app (`LearningSnapshot.arrange`) and `ossCli ranking-policy-diag`, so the diagnostic runs this very rule.
 *
 * What a commit is worth depends on what the user did, never on the text around it:
 * - [Strength.EXPLICIT]: the user chose a candidate other than the one ranked first, or chose a segment's candidate.
 *   It goes first; with context it yields second place only as in 0.1.29 ([outweighed]).
 * - [Strength.IMPLICIT]: the user accepted what was ranked first. It is never put first: that would only repeat the
 *   ranking that produced it and harden it against later context. It stays where the ranking puts it.
 * - [Strength.LEGACY]: history from before provenance was kept, whose actions are unknown. Without context ranking it
 *   goes first as before; when the model ranks with context ([contextRanks]) it stays where the ranking puts it.
 * A phrase holding a word the user registered for part of its reading counts as explicit.
 */
object LearnedOrder {
    enum class Strength(val code: Char) {
        IMPLICIT('i'), LEGACY('l'), EXPLICIT('e');

        companion object {
            fun of(code: String): Strength? = entries.firstOrNull { it.code.toString() == code }
        }
    }

    class Phrase(val reading: String, val surface: String, val strength: Strength = Strength.LEGACY)

    /** Keep where the ranking put it (second place if it is not in the list at all). */
    const val KEEP = -1

    /**
     * The phrase that speaks for [text] from [phrases] (latest first): the latest explicit one, else the latest legacy
     * one, else the latest implicit one. Null if none reads exactly [text].
     */
    fun pick(text: String, phrases: List<Phrase>): Phrase? {
        val exact = phrases.filter { it.reading == text }
        return exact.firstOrNull { it.strength == Strength.EXPLICIT }
            ?: exact.firstOrNull { it.strength == Strength.LEGACY }
            ?: exact.firstOrNull()
    }

    /** Whether [phrase] uses a word registered for part of its reading ([registered]: reading to surface). */
    fun registeredIn(phrase: Phrase, registered: Set<Pair<String, String>>): Boolean =
        registered.any { (r, s) -> phrase.reading.contains(r) && phrase.surface.contains(s) }

    /**
     * 0 (first), 1 (second) or [KEEP] for [phrase]. [leaderReadsText]: the first other candidate reads exactly the
     * typed text; [outweighed] is asked only when it could matter (an explicit or legacy phrase that would go first).
     */
    fun place(
        phrase: Phrase,
        registered: Set<Pair<String, String>>,
        contextRanks: Boolean,
        leaderReadsText: Boolean,
        outweighed: () -> Boolean,
    ): Int {
        val strength = if (registeredIn(phrase, registered)) Strength.EXPLICIT else phrase.strength
        return when (strength) {
            Strength.IMPLICIT -> KEEP
            Strength.LEGACY -> if (contextRanks) KEEP else 0
            Strength.EXPLICIT -> if (!registeredIn(phrase, registered) && leaderReadsText && outweighed()) 1 else 0
        }
    }
}
