package dev.astra.ime.core

/**
 * Caret steps over user-perceived characters, so a caret never lands inside an emoji sequence, a surrogate pair or a
 * base letter and its combining mark. A simplified form of Unicode's extended grapheme clusters (UAX #29), written out
 * because `:core` has no ICU: a code point followed by combining marks, variation selectors, emoji modifiers, tag
 * characters, a keycap mark or a ZWJ and the code point it joins; two regional indicators make one flag. Not covered:
 * Hangul syllable sequences from conjoining jamo and Indic conjuncts (each jamo or virama step is one character here).
 */
object Graphemes {
    /** The boundary after the character starting at [i] (the end when [i] is at or past it). */
    fun next(s: CharSequence, i: Int): Int {
        if (i >= s.length) return s.length
        var at = i.coerceAtLeast(0)
        val first = Character.codePointAt(s, at)
        at += Character.charCount(first)
        if (isRegional(first) && at < s.length && isRegional(Character.codePointAt(s, at))) {
            at += Character.charCount(Character.codePointAt(s, at))
        }
        while (at < s.length) {
            val cp = Character.codePointAt(s, at)
            when {
                cp == ZWJ -> {
                    at += 1
                    if (at < s.length) at += Character.charCount(Character.codePointAt(s, at))
                }
                extends(cp) -> at += Character.charCount(cp)
                else -> break
            }
        }
        return at
    }

    /** The boundary before [i]: the start of the character that ends at or contains [i] - 1. */
    fun previous(s: CharSequence, i: Int): Int {
        if (i <= 0) return 0
        var at = 0
        while (true) {
            val n = next(s, at)
            if (n >= i) return at
            at = n
        }
    }

    /** Whether [i] is between two characters (or at either end). */
    fun isBoundary(s: CharSequence, i: Int): Boolean {
        if (i <= 0 || i >= s.length) return i == 0 || i == s.length
        var at = 0
        while (at < i) at = next(s, at)
        return at == i
    }

    private const val ZWJ = 0x200D

    private fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun extends(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
    }
}
