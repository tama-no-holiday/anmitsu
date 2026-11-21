package dev.astra.ime.core

/**
 * Literal choices that every candidate list must offer, whatever the dictionary or a neural judge did: the typed
 * text as is (raw hiragana) and its full-width katakana. The katakana is a deterministic per-character mapping of
 * hiragana U+3041..U+3096 to U+30A1..U+30F6; everything else (ー, ASCII, punctuation, emoji, katakana, kanji) is
 * kept unchanged. It is not a romanization, a translation or a guess at a spelling, and it is never auto-selected.
 *
 * [ensure] runs last, after ranking, learned phrases and predictions, so nothing can push these choices out of the
 * capped list. It only inserts or moves the two literal choices; the order of every other candidate is kept.
 */
object LiteralChoices {
    /**
     * The katakana choice is placed no later than this index (0-based), so it is reachable with a few presses of
     * 変換 even when the dictionary offers many fragmented alternatives. The katakana is never put at index 0, so
     * the top candidate keeps index 0 whenever it survives the cap. It survives whenever the cap leaves room for it
     * next to the literal choices: limit >= 3 when raw and katakana differ, limit >= 2 when they are equal (the
     * workers use [MAX_LIMIT], 20). With a smaller limit the literal choices take every slot and the top candidate
     * is dropped.
     */
    const val KATAKANA_SLOT = 3

    fun katakana(text: String): String {
        val chars = CharArray(text.length)
        for (i in text.indices) {
            val c = text[i]
            chars[i] = if (c.code in 0x3041..0x3096) (c.code + 0x60).toChar() else c
        }
        return String(chars)
    }

    /**
     * Aligned segments for the katakana of [text]: every hiragana run (with ー) is one LITERAL segment, everything
     * else one PASSTHROUGH segment. Offsets are UTF-16 and never split a surrogate pair (only BMP kana change).
     */
    fun katakanaSegments(text: String): List<Segment> {
        val out = ArrayList<Segment>()
        var i = 0
        while (i < text.length) {
            val kana = isKana(text[i].code)
            val start = i
            while (i < text.length && isKana(text[i].code) == kana) i++
            val part = text.substring(start, i)
            out += if (kana) Segment(start, i, part, katakana(part), SegmentKind.LITERAL)
            else Segment(start, i, part, part, SegmentKind.PASSTHROUGH)
        }
        return out
    }

    /** [candidates] after [ensure]; [source] holds each one's index in the input, or -1 for an inserted choice. */
    class Ensured(val candidates: List<Candidate>, val source: IntArray)

    /**
     * Returns at most [limit] candidates (2..[MAX_LIMIT]) that contain the raw [text] and, when it differs, its
     * katakana at index <= [KATAKANA_SLOT]. Duplicate surfaces are dropped (first kept). When the cap is reached,
     * the lowest-ranked other candidates are removed, the top one last (see [KATAKANA_SLOT] for when even it is
     * removed). A raw candidate that was missing is appended last (raw has its own chip in the strip); a present
     * one keeps its position.
     */
    fun ensure(text: String, candidates: List<Candidate>, limit: Int = MAX_LIMIT): Ensured {
        require(limit in MIN_LIMIT..MAX_LIMIT) { "limit must be in [$MIN_LIMIT, $MAX_LIMIT]" }
        val kata = katakana(text)
        val seen = HashSet<String>()
        val items = ArrayList<Pair<Candidate, Int>>()
        candidates.forEachIndexed { i, c -> if (seen.add(c.surface)) items += c to i }

        // A katakana already within the slot (e.g. a dictionary テレビ ranked first) stays where it is.
        val kataAt = items.indexOfFirst { it.first.surface == kata }
        val pending = when {
            kata == text || kataAt in 0..KATAKANA_SLOT -> null
            kataAt > KATAKANA_SLOT -> items.removeAt(kataAt)
            else -> Candidate(text, kata, Double.MAX_VALUE, katakanaSegments(text)) to -1
        }
        if (items.none { it.first.surface == text }) items += Candidate(text, text, Double.MAX_VALUE) to -1

        val reserved = if (pending != null) 1 else 0
        // Trim the lowest-ranked other candidates; the literal choices are never removed.
        while (items.size + reserved > limit) {
            items.removeAt(items.indexOfLast { it.first.surface != text && it.first.surface != kata })
        }
        // items holds at least the raw candidate, so index 0 is never taken by the katakana.
        if (pending != null) items.add(maxOf(1, minOf(KATAKANA_SLOT, items.size)), pending)
        return Ensured(items.map { it.first }, IntArray(items.size) { items[it].second })
    }
}
