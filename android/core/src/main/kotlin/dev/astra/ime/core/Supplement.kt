package dev.astra.ime.core

import java.io.BufferedReader

/**
 * Whole-reading candidates that are not dictionary words, as Mozc offers them through its rewriters rather than its
 * dictionary: symbols (かっこ → 「」（）【】, やじるし → →←↑↓, 「」 → 『』【】), emoticons, emoji and single kanji, read from
 * Mozc's own data (`ossCli supplement-prepare`, see third_party/mozc/README.md), plus generated number forms (1 → １ ①
 * 一 Ⅰ …) and full/half-width forms of typed symbols.
 *
 * They are offered only when the whole typed text is the key, never inside a sentence, so the lattice, its costs and
 * every dictionary ranking stay as they were: [insert] adds them to a finished candidate list and [lookup] feeds the
 * expanded panel. Keys and typed text are compared after [fold] (full-width ASCII as ASCII).
 */
class Supplement private constructor(private val table: Map<String, List<Group>>) {

    /** In priority order: earlier kinds come first for the same key. */
    enum class Kind(val code: Char) {
        NUMBER('N'), SYMBOL('S'), EMOTICON('F'), EMOJI('E'), KANJI('K');

        /** Kinds that may lead the capped list right after its first entries; the rest only fill its end. */
        val leads: Boolean get() = this == NUMBER || this == SYMBOL

        companion object {
            fun of(code: Char): Kind? = entries.firstOrNull { it.code == code }
        }
    }

    class Item(val surface: String, val kind: Kind)

    /** One source line: [surfaces] are tab-separated, or one surface per code point for [Kind.KANJI]. */
    private class Group(val kind: Kind, val surfaces: String)

    val keyCount: Int get() = table.size

    /**
     * Every supplementary candidate for exactly [text], unique, without [text] itself: number forms, width forms of
     * typed symbols, then the data's groups in kind order (each in Mozc's order). [renderable] drops emoji the device
     * font cannot draw.
     */
    fun lookup(text: String, renderable: (String) -> Boolean = { true }): List<Item> {
        if (text.isEmpty() || text.length > MAX_KEY) return emptyList()
        val key = fold(text)
        val seen = hashSetOf(text)
        val out = ArrayList<Item>()
        fun add(surface: String, kind: Kind) {
            if (surface.isNotEmpty() && seen.add(surface)) out += Item(surface, kind)
        }
        Numbers.forms(key).forEach { add(it, Kind.NUMBER) }
        if (Widths.isSymbolic(text)) {
            add(Widths.full(text), Kind.SYMBOL)
            add(Widths.half(text), Kind.SYMBOL)
        }
        val groups = table[key].orEmpty().sortedBy { it.kind.ordinal }
        for (g in groups) {
            when (g.kind) {
                Kind.KANJI -> {
                    var i = 0
                    while (i < g.surfaces.length) {
                        val n = Character.charCount(g.surfaces.codePointAt(i))
                        add(g.surfaces.substring(i, i + n), Kind.KANJI)
                        i += n
                    }
                }
                else -> for (s in g.surfaces.split('\t')) {
                    if (g.kind == Kind.EMOJI && !renderable(s)) continue
                    add(s, g.kind)
                    // A bracket pair or symbol offered in one width is offered in the other one too.
                    if (g.kind == Kind.SYMBOL && Widths.isSymbolic(s)) {
                        add(Widths.full(s), Kind.SYMBOL)
                        add(Widths.half(s), Kind.SYMBOL)
                    }
                }
            }
        }
        return out
    }

    /** [candidates] with supplementary ones added; [source] as in [LiteralChoices.Ensured] (-1: added here). */
    class Inserted(val candidates: List<Candidate>, val source: IntArray)

    /**
     * Adds [lookup] results missing from [candidates]: at most [MAX_LEAD] numbers/symbols right after the first
     * [LEAD_AT] entries (after the raw digits for a number), everything else at the end. The existing order is kept and
     * index 0 never changes, so live conversion and the first candidate are untouched. Run before
     * [LiteralChoices.ensure], whose cap then trims the end first; the expanded panel lists every item regardless.
     */
    fun insert(text: String, candidates: List<Candidate>, renderable: (String) -> Boolean = { true }): Inserted {
        val present = candidates.mapTo(HashSet()) { it.surface }
        val items = lookup(text, renderable).filter { it.surface !in present }
        if (items.isEmpty()) return Inserted(candidates, IntArray(candidates.size) { it })
        val lead = items.filter { it.kind.leads }.take(MAX_LEAD)
        val tail = items - lead.toSet()
        val at = minOf(if (lead.firstOrNull()?.kind == Kind.NUMBER) 1 else LEAD_AT, candidates.size)
        val out = ArrayList<Candidate>(candidates.size + items.size)
        val source = ArrayList<Int>(candidates.size + items.size)
        candidates.forEachIndexed { i, c ->
            if (i == at) lead.forEach { out += candidate(text, it); source += -1 }
            out += c
            source += i
        }
        if (at >= candidates.size) lead.forEach { out += candidate(text, it); source += -1 }
        tail.forEach { out += candidate(text, it); source += -1 }
        return Inserted(out, source.toIntArray())
    }

    companion object {
        /** Numbers/symbols allowed before the rest of the capped list. */
        const val MAX_LEAD = 8

        /** Dictionary entries that stay ahead of supplementary symbols (e.g. かっこ: 括弧 かっこ カッコ). */
        const val LEAD_AT = 3

        /** Longer text is a sentence: no key is this long, so it is not even looked up. */
        const val MAX_KEY = 24

        val EMPTY = Supplement(emptyMap())

        /** One whole-text word segment, so the candidate is exact for the panel and one repair unit. */
        fun candidate(text: String, item: Item): Candidate =
            Candidate(text, item.surface, Double.MAX_VALUE, listOf(Segment(0, text.length, text, item.surface, SegmentKind.WORD)))

        /** Full-width ASCII (U+FF01..FF5E) and the ideographic space as ASCII; everything else unchanged. */
        fun fold(s: String): String {
            val chars = CharArray(s.length)
            for (i in s.indices) {
                val c = s[i]
                chars[i] = when (c.code) {
                    in 0xFF01..0xFF5E -> (c.code - 0xFEE0).toChar()
                    0x3000 -> ' '
                    else -> c
                }
            }
            return String(chars)
        }

        /**
         * Reads the `astra-supplement` format: `#` comment lines, then `<kind code>\t<key>\t<surface>[\t<surface>…]`
         * (one line per kind and key; for K the third field is the kanji run). Keys are stored folded.
         */
        fun parse(reader: BufferedReader): Supplement {
            val table = HashMap<String, MutableList<Group>>()
            var first = true
            reader.forEachLine { raw ->
                val line = if (first) raw.removePrefix("﻿") else raw
                first = false
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val tab1 = line.indexOf('\t')
                val tab2 = if (tab1 < 0) -1 else line.indexOf('\t', tab1 + 1)
                if (tab1 != 1 || tab2 < 0) throw IllegalArgumentException("Malformed supplement line")
                val kind = Kind.of(line[0]) ?: throw IllegalArgumentException("Unknown supplement kind")
                val key = fold(line.substring(tab1 + 1, tab2))
                val surfaces = line.substring(tab2 + 1)
                if (key.isEmpty() || surfaces.isEmpty() || kind == Kind.NUMBER) throw IllegalArgumentException("Malformed supplement line")
                table.getOrPut(key) { ArrayList(1) } += Group(kind, surfaces)
            }
            return Supplement(table)
        }
    }

    /** Generated number forms of a run of ASCII digits; nothing for anything else. */
    object Numbers {
        private const val KANJI = "〇一二三四五六七八九"
        private const val DAIJI = "零壱弐参四五六七八九"

        fun forms(digits: String): List<String> {
            if (digits.isEmpty() || digits.length > 16 || digits.any { it !in '0'..'9' }) return emptyList()
            val out = LinkedHashSet<String>()
            out += Widths.full(digits)
            val leadingZero = digits.length > 1 && digits[0] == '0'
            val n = if (leadingZero) -1L else digits.toLong()
            if (n >= 0) {
                out += kanji(n)
                if (n >= 1000) out += grouped(digits).let { listOf(it, Widths.full(it)) }
            }
            out += digits.map { KANJI[it - '0'] }.joinToString("")
            if (n in 0..50) circled(n.toInt())?.let { out += it }
            if (n in 1..20) {
                out += String(Character.toChars(0x2474 + n.toInt() - 1)) // ⑴
                out += String(Character.toChars(0x2488 + n.toInt() - 1)) // ⒈
            }
            when (n) {
                0L -> out += "⓿"
                in 1L..10L -> out += String(Character.toChars(0x2776 + n.toInt() - 1)) // ❶
                in 11L..20L -> out += String(Character.toChars(0x24EB + n.toInt() - 11)) // ⓫
            }
            if (n in 1..10) out += String(Character.toChars(0x24F5 + n.toInt() - 1)) // ⓵
            if (n in 1..12) {
                out += String(Character.toChars(0x2160 + n.toInt() - 1)) // Ⅰ
                out += String(Character.toChars(0x2170 + n.toInt() - 1)) // ⅰ
            }
            if (n in 1..3999) roman(n.toInt()).let { out += it; out += it.lowercase() }
            if (n >= 0) daiji(n)?.let { out += it }
            out += digits.map { SUPERSCRIPT[it - '0'] }.joinToString("")
            out += digits.map { SUBSCRIPT[it - '0'] }.joinToString("")
            out.remove(digits)
            return out.toList()
        }

        private const val SUPERSCRIPT = "⁰¹²³⁴⁵⁶⁷⁸⁹"
        private const val SUBSCRIPT = "₀₁₂₃₄₅₆₇₈₉"

        private fun circled(n: Int): String? = when (n) {
            0 -> "⓪"
            in 1..20 -> String(Character.toChars(0x2460 + n - 1))
            in 21..35 -> String(Character.toChars(0x3251 + n - 21))
            in 36..50 -> String(Character.toChars(0x32B1 + n - 36))
            else -> null
        }

        private fun grouped(digits: String): String =
            digits.reversed().chunked(3).joinToString(",").reversed()

        /** 十二, 三千四百五, 一億二千万: positional kanji with 万 億 兆 千兆 groups. */
        fun kanji(n: Long): String {
            if (n == 0L) return "〇"
            val units = arrayOf("", "万", "億", "兆")
            val sb = StringBuilder()
            var rest = n
            val groups = ArrayList<Int>()
            while (rest > 0) {
                groups += (rest % 10000).toInt()
                rest /= 10000
            }
            for (g in groups.indices.reversed()) {
                val v = groups[g]
                if (v == 0) continue
                sb.append(under10000(v, KANJI, "十百千", omitOne = true)).append(units[g])
            }
            return sb.toString()
        }

        /** 壱拾弐 etc. for numbers below 10,000 (old-style 大字, as on documents); none above. */
        private fun daiji(n: Long): String? = when {
            n == 0L -> "零"
            n < 10000 -> under10000(n.toInt(), DAIJI, "拾百千", omitOne = false)
            else -> null
        }

        private fun under10000(v: Int, digits: String, places: String, omitOne: Boolean): String {
            val sb = StringBuilder()
            val parts = intArrayOf(v / 1000, v / 100 % 10, v / 10 % 10)
            for (i in 0..2) {
                val d = parts[i]
                if (d == 0) continue
                if (d != 1 || !omitOne) sb.append(digits[d])
                sb.append(places[2 - i])
            }
            if (v % 10 != 0) sb.append(digits[v % 10])
            return sb.toString()
        }

        fun roman(n: Int): String {
            val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            val sb = StringBuilder()
            var rest = n
            for (i in values.indices) while (rest >= values[i]) {
                sb.append(symbols[i])
                rest -= values[i]
            }
            return sb.toString()
        }
    }

    /** Full- and half-width forms of symbol text (ASCII, the ideographic space and the half-width katakana marks). */
    object Widths {
        private const val FULL_MARKS = "「」、。・ー゛゜"
        private const val HALF_MARKS = "｢｣､｡･ｰﾞﾟ"

        /** True when every character has another width or is ASCII: no kana, kanji or letters outside ASCII. */
        fun isSymbolic(s: String): Boolean = s.isNotEmpty() && s.all { c ->
            c.code in 0x21..0x7E || c.code in 0xFF01..0xFF5E || c == '　' || c in FULL_MARKS || c in HALF_MARKS
        } && s.any { !it.isLetterOrDigit() }

        fun full(s: String): String = String(CharArray(s.length) { i ->
            val c = s[i]
            when {
                c.code in 0x21..0x7E -> (c.code + 0xFEE0).toChar()
                c == ' ' -> '　'
                HALF_MARKS.indexOf(c) >= 0 -> FULL_MARKS[HALF_MARKS.indexOf(c)]
                else -> c
            }
        })

        fun half(s: String): String = String(CharArray(s.length) { i ->
            val c = s[i]
            when {
                c.code in 0xFF01..0xFF5E -> (c.code - 0xFEE0).toChar()
                c == '　' -> ' '
                FULL_MARKS.indexOf(c) >= 0 -> HALF_MARKS[FULL_MARKS.indexOf(c)]
                else -> c
            }
        })
    }
}
