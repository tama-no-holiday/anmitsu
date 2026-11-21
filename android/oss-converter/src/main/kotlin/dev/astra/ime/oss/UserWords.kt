package dev.astra.ime.oss

/** One dictionary word of a conversion: the reading it covers and its surface. */
data class Word(val reading: String, val surface: String) {
    /** Redacted: words carry user text. */
    override fun toString(): String = "Word(length=${surface.length})"
}

/** A word to add to the lattice that the dictionary lacks (e.g. a registered English name). */
class AddedWord(val surface: String, val cost: Int, val posId: Int)

/**
 * Immutable per-user adjustments for [OssConverter]: a cost [bonus] for learned dictionary words and extra
 * words the dictionary does not have. Built on the caller's side from on-device data; [version] must change
 * whenever the content changes because converted runs are cached by it.
 */
class UserWords(
    val version: Long,
    /** reading -> surface -> bonus (Mozc cost units, > 0 makes the word cheaper). */
    private val bonuses: Map<String, Map<String, Int>>,
    /** reading -> words missing from the dictionary. */
    private val additions: Map<String, List<AddedWord>>,
) {
    val isEmpty: Boolean = bonuses.isEmpty() && additions.isEmpty()

    /** Longest reading among [additions]; bounds the lattice scan. */
    val maxReadingLength: Int = additions.keys.maxOfOrNull { it.length } ?: 0

    fun bonus(reading: String, surface: String): Int = bonuses[reading]?.get(surface) ?: 0

    fun added(reading: String): List<AddedWord> = additions[reading].orEmpty()

    fun hasBonus(word: Word): Boolean = bonus(word.reading, word.surface) > 0

    override fun toString(): String = "UserWords(version=$version, readings=${bonuses.size}, added=${additions.size})"

    companion object {
        /**
         * Mozc dictionary_oss POS id of 名詞,固有名詞,一般 (id.def at the pinned revision d766d25); the POS given to
         * registered words. The compiled dictionary is pinned, so this cannot drift silently.
         */
        const val PROPER_NOUN_ID = 1920

        val EMPTY = UserWords(0, emptyMap(), emptyMap())
    }
}
