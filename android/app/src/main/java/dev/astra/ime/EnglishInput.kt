package dev.astra.ime

import android.content.res.AssetManager
import android.os.Handler
import dev.astra.ime.core.Candidate
import dev.astra.ime.core.Supplement
import dev.astra.ime.oss.EnglishLexicon
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Case of the uncommitted English word. AS_TYPED keeps the letters as entered (e.g. iPh). */
internal enum class CaseForm {
    AS_TYPED, LOWER, TITLE, UPPER;

    fun apply(word: String): String = when (this) {
        AS_TYPED -> word
        LOWER -> word.lowercase()
        TITLE -> word.take(1).uppercase() + word.drop(1).lowercase()
        UPPER -> word.uppercase()
    }

    companion object {
        /** The form [word] already has; mixed case (iPh, OpenAI) is AS_TYPED. A single capital counts as TITLE. */
        fun of(word: String): CaseForm = when {
            word == word.lowercase() -> LOWER
            word == TITLE.apply(word) -> TITLE
            word == word.uppercase() -> UPPER
            else -> AS_TYPED
        }
    }
}

/**
 * The uncommitted English composition: letters, punctuation, digits and palette entries in the order typed, with a
 * composing caret between them (0.1.34). Typing, ⌫ and the case key act at the caret; the arrow keys move it one entry a
 * press and never commit. Like a kana composition it ends only by an explicit confirm (Enter, Space, a candidate), a
 * kana key that takes it over, or a cursor move; switching screens keeps it. Nothing here touches the editor.
 */
internal class EnglishWord {
    /** One entry per letter or insertion (an emoji sequence stays one entry), so ⌫ and the caret never split an entry. */
    private val entries = ArrayList<String>()

    /** Entries before the caret, 0..entries.size. */
    var caret = 0
        private set

    val shown: String get() = entries.joinToString("")
    val isEmpty: Boolean get() = entries.isEmpty()
    val caretAtEnd: Boolean get() = caret == entries.size

    /** UTF-16 offset of the caret in [shown]: always between two entries, so never inside a character. */
    val caretOffset: Int get() = (0 until caret).sumOf { entries[it].length }

    /** The word the caret is in or right after (its letters as typed), what completions are for; empty when none. */
    val typed: String get() {
        var from = caret
        while (from > 0 && isLetter(entries[from - 1])) from--
        var to = caret
        while (to < entries.size && isLetter(entries[to])) to++
        return entries.subList(from, to).joinToString("")
    }

    fun type(letter: Char) = insert(letter.toString())

    /** [text] at the caret, which moves past it. */
    fun insert(text: String) {
        if (text.isEmpty()) return
        entries.add(caret, text)
        caret++
    }

    /** ⌫: the entry before the caret, whole. False when there is none (the caret is at the start). */
    fun backspace(): Boolean {
        if (caret == 0) return false
        entries.removeAt(--caret)
        return true
    }

    fun clear() {
        entries.clear()
        caret = 0
    }

    fun moveLeft(): Boolean = if (caret > 0) { caret--; true } else false
    fun moveRight(): Boolean = if (caret < entries.size) { caret++; true } else false
    fun moveToStart(): Boolean = if (caret > 0) { caret = 0; true } else false
    fun moveToEnd(): Boolean = if (caret < entries.size) { caret = entries.size; true } else false

    /** The case key: flips the character before the caret if it is a letter (never another one); false when it is not. */
    fun toggleLastCase(): Boolean {
        val e = entries.getOrNull(caret - 1)?.takeIf(::isLetter) ?: return false
        val c = e[0]
        entries[caret - 1] = (if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar()).toString()
        return true
    }

    /** Case key label while composing: what a tap does to the letter before the caret, or null when it would do nothing. */
    fun caseLabel(): String? = entries.getOrNull(caret - 1)?.takeIf(::isLetter)?.let { if (it[0].isUpperCase()) "A→a" else "a→A" }

    /** Runs of one kind of entry ([TokenKind]), in order; what whole-composition candidates are built from. */
    fun tokens(): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < entries.size) {
            val kind = kindOf(entries[i])
            var j = i + 1
            if (kind != TokenKind.OTHER) while (j < entries.size && kindOf(entries[j]) == kind) j++
            out += Token(kind, entries.subList(i, j).joinToString(""), i, j)
            i = j
        }
        return out
    }

    /** Index in [tokens] of the token the caret is in or right after (0 at the start). */
    fun caretToken(tokens: List<Token> = tokens()): Int =
        tokens.indexOfFirst { caret > it.from && caret <= it.to }.coerceAtLeast(0)

    enum class TokenKind { LETTERS, DIGITS, PUNCTUATION, OTHER }

    /** [text] of entries [from] until [to]. */
    class Token(val kind: TokenKind, val text: String, val from: Int, val to: Int)

    companion object {
        private fun kindOf(entry: String): TokenKind = when {
            isLetter(entry) -> TokenKind.LETTERS
            isDigits(entry) -> TokenKind.DIGITS
            isPunctuation(entry) -> TokenKind.PUNCTUATION
            else -> TokenKind.OTHER
        }

        fun isLetter(text: String): Boolean = text.length == 1 && (text[0] in 'a'..'z' || text[0] in 'A'..'Z')

        private val PUNCTUATION = setOf(
            Character.START_PUNCTUATION, Character.END_PUNCTUATION, Character.OTHER_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.CONNECTOR_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL,
        ).map { it.toInt() }.toSet()

        /** Brackets, marks, math and currency signs only: never letters, digits, spaces, emoji or other symbols. */
        fun isPunctuation(text: String): Boolean =
            text.isNotEmpty() && text.codePoints().allMatch { Character.getType(it) in PUNCTUATION }

        /** ASCII digits only (what the number screens and down flicks type). */
        fun isDigits(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' }

        /**
         * Candidates for a composition that is the one word [typed]: the letters as typed first (what Space/Enter commit),
         * then the dictionary's canonical spellings (case kept: OpenAI), completions, the other case forms. All-lowercase
         * dictionary spellings follow a Title or UPPER word (Appl → Apple); mixed-case ones never change.
         */
        fun wordCandidates(typed: String, exact: List<String>, completions: List<String>): List<String> {
            if (typed.isEmpty()) return emptyList()
            val list = LinkedHashSet<String>()
            list += typed
            exact.forEach { list += styled(typed, it) }
            completions.forEach { list += styled(typed, it) }
            for (f in listOf(CaseForm.LOWER, CaseForm.TITLE, CaseForm.UPPER)) list += f.apply(typed)
            return list.toList()
        }

        /** [spelling] from the dictionary in the case [typed] has: lowercase spellings follow a Title or UPPER word. */
        fun styled(typed: String, spelling: String): String {
            val form = CaseForm.of(typed).takeIf { it == CaseForm.TITLE || it == CaseForm.UPPER }
            return if (form != null && spelling == spelling.lowercase()) form.apply(spelling) else spelling
        }
    }
}

/**
 * Candidates for the whole English composition (0.1.34): what was typed first, then whole-composition forms and
 * single-token variants, each candidate the complete text a pick commits (never a part, so nothing before or after it
 * is lost or committed by itself). Built per render from small inputs: the composition's [EnglishWord.tokens], index
 * results for at most [MAX_LOOKUPS] of its words and the supplement's forms for one token at a time.
 *
 * Order, each once (later duplicates dropped), at most [MAX] in all:
 * 1. the composition as typed;
 * 2. every word in its dictionary spelling (openai123 → OpenAI123), completions of the word at the caret ([MAX_COMPLETIONS]);
 * 3. whole case forms: Title words, UPPER, lower (m-1 → M-1);
 * 4. whole width forms of 1 to 3 (ｍ－１, Ｍ－１), and half width when something full-width was typed;
 * 5. bracket styles applied to every matched (), [] or {} pair at once ((12) → （12） 「12」 【12】), from the
 *    supplement's own pairs ([MAX_STYLES] per kind), so a pair is never broken into two styles;
 * 6. token forms, alone and combined, cheapest first ([variants]). A part is a token ([MAX_TOKENS] nearest the caret;
 *    [PER_TOKEN] forms each, [MAX_STYLES] for punctuation): words in their spellings, case and full width; digits in their
 *    number forms (m-1 → m-①, m-１); punctuation in its widths and brackets (m-1 → m－1); or the brackets of one kind
 *    paired across tokens, styled together ((1-2) → 「1-2」). Forms cost 1 (widths, case, the first dictionary spelling,
 *    「」『』【】（）, circled numbers) or more (the supplement's other forms). In this order:
 *    6a. one part in a form of cost 1, the rest as typed (m-1 → m-①, m－1);
 *    6b. one part in one of its first [BASED_RANK] forms inside a whole form of the rest (UPPER, Title, full width and
 *        both: m-1x → M-①X, Ｍ－①Ｘ), rank by rank over all parts, at most [MAX_BASED];
 *    6c. the rest of the search ([variants]): two or more parts changed together, cheapest first (m-1 → M-①, ｍ-①;
 *        abc() → ABC「」; a(1) → A「①」), and the costlier forms of one part.
 *
 * Not every combination: combinations take each part's first [COMBO_RANK] forms, at most [MAX_COMBOS] of them, from at
 * most [MAX_POPS] steps of the search, and the list stops at [MAX], so the work is bounded on the main thread; the
 * service builds it once per state of the composition, not per render. A composition of one kind keeps 0.1.33's
 * lists: one word its word candidates, one punctuation or digit run itself and its supplement forms, emoji alone nothing.
 * Without [widths] (URL and e-mail fields, which also get no number forms) a mixed composition gets only 1 to 3, 5 and
 * word forms (case and dictionary, alone and combined).
 */
internal object WholeCandidates {
    const val MAX = 96
    const val MAX_LOOKUPS = 4
    const val MAX_COMPLETIONS = 3
    /** Mozc's table has 16 pairs for (), 「」 and 【】 among the later ones; widths add a few. */
    const val MAX_STYLES = 20
    const val MAX_TOKENS = 8
    const val PER_TOKEN = 8
    /** Combinations of two or more parts: each part's first forms only, this many combinations, this many queue steps. */
    const val COMBO_RANK = 5
    const val MAX_COMBOS = 40
    const val MAX_POPS = 600
    /** Forms of one part inside a whole form of the rest (6b): each part's first this many, at most this many in all. */
    const val BASED_RANK = 3
    const val MAX_BASED = 40
    /** Supplement forms after the first ones cost one more every this many. */
    private const val COST_STEP = 3

    class Result(val list: List<String>, val predictions: Set<String>)

    private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}')

    /** Lower-cased words of [word] to look up, nearest the caret first. */
    fun lookups(word: EnglishWord): List<String> {
        val tokens = word.tokens()
        val at = word.caretToken(tokens)
        return byDistance(tokens.indices.toList(), at).map { tokens[it] }.filter { it.kind == EnglishWord.TokenKind.LETTERS }
            .map { it.text.lowercase() }.distinct().take(MAX_LOOKUPS)
    }

    /**
     * [lookup] answers index results by lower-cased word (null: not looked up yet); [alternatives] the supplement's forms
     * of one token (empty where not offered, e.g. number forms in address fields).
     */
    fun of(
        word: EnglishWord,
        lookup: (String) -> EnglishLexicon.Result?,
        alternatives: (String) -> List<String>,
        widths: Boolean,
    ): Result {
        val tokens = word.tokens()
        if (tokens.isEmpty()) return Result(emptyList(), emptySet())
        val source = word.shown
        if (tokens.size == 1) {
            val t = tokens[0]
            return when (t.kind) {
                EnglishWord.TokenKind.LETTERS -> {
                    val r = lookup(t.text.lowercase())
                    val exact = r?.exact.orEmpty()
                    val completions = r?.completions.orEmpty()
                    val spellings = EnglishWord.wordCandidates(t.text, exact, emptyList()).toSet()
                    val all = EnglishWord.wordCandidates(t.text, exact, completions)
                    Result(all, all.filter { it !in spellings }.toSet())
                }
                EnglishWord.TokenKind.OTHER -> Result(emptyList(), emptySet())
                else -> Result((listOf(source) + alternatives(source)).filter { it.isNotEmpty() }.distinct(), emptySet())
            }
        }
        val out = LinkedHashSet<String>()
        val predictions = HashSet<String>()
        fun add(s: String) {
            if (out.size < MAX && s.isNotEmpty()) out += s
        }
        fun replaced(index: Int, text: String): String = buildString {
            for ((i, t) in tokens.withIndex()) append(if (i == index) text else t.text)
        }
        fun spellingOf(t: EnglishWord.Token): String =
            lookup(t.text.lowercase())?.exact?.firstOrNull()?.let { EnglishWord.styled(t.text, it) } ?: t.text
        fun wordsMapped(f: (EnglishWord.Token) -> String) = tokens.joinToString("") { if (it.kind == EnglishWord.TokenKind.LETTERS) f(it) else it.text }

        add(source)
        // 2. Dictionary spellings, then completions of the word at the caret.
        val dictionary = wordsMapped(::spellingOf)
        add(dictionary)
        val at = word.caretToken(tokens)
        tokens[at].takeIf { it.kind == EnglishWord.TokenKind.LETTERS }?.let { t ->
            lookup(t.text.lowercase())?.completions.orEmpty().take(MAX_COMPLETIONS).forEach { c ->
                val s = replaced(at, EnglishWord.styled(t.text, c))
                if (s !in out) predictions += s
                add(s)
            }
        }
        // 3. Whole case forms.
        val title = wordsMapped { CaseForm.TITLE.apply(it.text) }
        val upper = wordsMapped { CaseForm.UPPER.apply(it.text) }
        add(title)
        add(upper)
        add(wordsMapped { CaseForm.LOWER.apply(it.text) })
        // 4. Whole widths.
        if (widths) {
            for (s in listOf(source, upper, title, dictionary)) add(Supplement.Widths.full(s))
            add(Supplement.Widths.half(source))
        }
        // 5. Bracket styles over every matched pair of a kind.
        val pairs = matchedPairs(source)
        val across = HashSet<Int>()
        for ((open, close) in pairs) {
            val a = tokenAt(tokens, open)
            val b = tokenAt(tokens, close)
            if (a != b) {
                across += a
                across += b
            }
        }
        for (kind in pairs.map { source[it.first] }.distinct()) {
            val closing = PAIRS.getValue(Supplement.fold(kind.toString())[0])
            val styles = alternatives("${Supplement.fold(kind.toString())}$closing")
                .filter { it.codePointCount(0, it.length) == 2 }.take(MAX_STYLES)
            // 1: opening, 2: closing bracket of a matched pair of this kind.
            val role = IntArray(source.length)
            for ((open, close) in pairs) if (source[open] == kind) { role[open] = 1; role[close] = 2 }
            for (style in styles) {
                val first = String(Character.toChars(style.codePointAt(0)))
                val second = style.substring(first.length)
                add(buildString {
                    for (i in source.indices) when (role[i]) {
                        1 -> append(first)
                        2 -> append(second)
                        else -> append(source[i])
                    }
                })
            }
        }
        // 6. Token forms, alone and combined, cheapest first ([variants]).
        val axes = ArrayList<Axis>()
        for (i in byDistance(tokens.indices.toList(), at).take(MAX_TOKENS)) {
            val t = tokens[i]
            val forms: List<Pair<String, Int>> = when (t.kind) {
                EnglishWord.TokenKind.LETTERS -> {
                    val exact = lookup(t.text.lowercase())?.exact.orEmpty().map { EnglishWord.styled(t.text, it) }
                    val cased = listOf(CaseForm.TITLE, CaseForm.UPPER, CaseForm.LOWER).map { it.apply(t.text) }
                    exact.take(1).map { it to 1 } + cased.map { it to 1 } +
                        (if (widths) (listOf(t.text) + cased + exact.take(1)).map { Supplement.Widths.full(it) to 1 } else emptyList()) +
                        exact.drop(1).map { it to 2 }
                }
                EnglishWord.TokenKind.DIGITS -> if (widths) supplementForms(t.text, alternatives(t.text)) else emptyList()
                EnglishWord.TokenKind.PUNCTUATION -> if (widths && i !in across) supplementForms(t.text, alternatives(t.text)) else emptyList()
                EnglishWord.TokenKind.OTHER -> emptyList()
            }
            val cap = if (t.kind == EnglishWord.TokenKind.PUNCTUATION) MAX_STYLES else PER_TOKEN
            val seen = hashSetOf(t.text)
            val kept = forms.filter { it.first.isNotEmpty() && seen.add(it.first) }.sortedBy { it.second }.take(cap)
            if (kept.isNotEmpty()) axes += Axis(intArrayOf(i), listOf("") + kept.map { it.first }, intArrayOf(0) + kept.map { it.second }, kotlin.math.abs(i - at))
        }
        // Brackets paired across tokens change as a pair, one axis per kind, so a pair never gets two styles.
        val crossPairs = pairs.filter { (o, c) -> tokenAt(tokens, o) != tokenAt(tokens, c) }
        if (widths) for (kind in crossPairs.map { source[it.first] }.distinct()) {
            val folded = Supplement.fold(kind.toString())
            val typedPair = "$folded${PAIRS.getValue(folded[0])}"
            val styles = supplementForms(typedPair, alternatives(typedPair).filter { it.codePointCount(0, it.length) == 2 }).take(MAX_STYLES)
            if (styles.isEmpty()) continue
            val roles = crossPairs.filter { source[it.first] == kind }
            val touched = roles.flatMap { listOf(tokenAt(tokens, it.first), tokenAt(tokens, it.second)) }.distinct().toIntArray()
            axes += Axis(touched, listOf("") + styles.map { it.first }, intArrayOf(0) + styles.map { it.second },
                touched.minOf { kotlin.math.abs(it - at) }, roles)
        }
        val starts = IntArray(tokens.size)
        for (i in 1 until tokens.size) starts[i] = starts[i - 1] + tokens[i - 1].text.length
        // A whole-composition form (case of the words, full width) that the other parts keep around a changed one.
        class Base(val case: CaseForm, val full: Boolean)
        fun baseText(t: EnglishWord.Token, base: Base?): String {
            if (base == null || t.kind == EnglishWord.TokenKind.OTHER) return t.text
            val cased = if (t.kind == EnglishWord.TokenKind.LETTERS) base.case.apply(t.text) else t.text
            return if (base.full) Supplement.Widths.full(cased) else cased
        }
        fun build(state: IntArray, base: Base? = null): String {
            val texts = tokens.map { baseText(it, base) }.toTypedArray()
            val roleOf = HashMap<Int, String>() // source offset → bracket text of the chosen pair style
            for ((a, axis) in axes.withIndex()) {
                val f = state[a]
                if (f == 0) continue
                val form = axis.forms[f]
                val pairRoles = axis.pairs
                if (pairRoles == null) {
                    texts[axis.tokens[0]] = form
                } else {
                    val first = String(Character.toChars(form.codePointAt(0)))
                    for ((o, c) in pairRoles) { roleOf[o] = first; roleOf[c] = form.substring(first.length) }
                }
            }
            if (roleOf.isNotEmpty()) for (i in tokens.indices) {
                if (roleOf.keys.none { it >= starts[i] && it < starts[i] + tokens[i].text.length }) continue
                texts[i] = buildString {
                    val t = tokens[i].text
                    for (k in t.indices) append(roleOf[starts[i] + k] ?: t[k].toString().let { if (base?.full == true) Supplement.Widths.full(it) else it })
                }
            }
            return texts.joinToString("")
        }
        val found = variants(axes)
        // 6a. The cheapest forms of one part alone (cost 1: widths, case, common brackets, circled numbers).
        for ((s, cost) in found) if (cost <= 1) add(build(s))
        // 6b. One part's first forms inside each whole form of the rest (M-1x → M-①X, Ｍ－①Ｘ), whatever the number of parts.
        val bases = listOf(Base(CaseForm.UPPER, false), Base(CaseForm.TITLE, false)) +
            if (widths) listOf(Base(CaseForm.AS_TYPED, true), Base(CaseForm.UPPER, true), Base(CaseForm.TITLE, true)) else emptyList()
        // Rank by rank, so every whole form and every part gets its first forms before any part gets more.
        var based = 0
        for (f in 1..BASED_RANK) for ((a, axis) in axes.withIndex()) for (base in bases) {
            if (f > axis.forms.lastIndex || based >= MAX_BASED || out.size >= MAX) continue
            val before = out.size
            add(build(IntArray(axes.size).also { it[a] = f }, base))
            if (out.size > before) based++
        }
        // 6c. Everything else the search found, cheapest first.
        for ((s, cost) in found) if (cost > 1) add(build(s))
        return Result(out.toList(), predictions.filter { it in out }.toSet())
    }

    /**
     * One changeable part of the composition: a token ([tokens] one index, [forms] its replacements) or a bracket kind
     * paired across tokens ([pairs] its source offsets, [forms] two-character styles). forms[0] "" is "as typed", cost 0;
     * [costs] never decrease along [forms].
     */
    private class Axis(val tokens: IntArray, val forms: List<String>, val costs: IntArray, val distance: Int, val pairs: List<Pair<Int, Int>>? = null)

    /**
     * The supplement's forms of [typed] (itself excluded) and its full width, with costs: full width, the common Japanese
     * brackets and circled numbers 1 ([isCommon]); the others, in the supplement's order, 4 and more ([COST_STEP]), so
     * combinations of common forms come before an uncommon form of one part.
     */
    private fun supplementForms(typed: String, forms: List<String>): List<Pair<String, Int>> {
        val full = Supplement.Widths.full(typed)
        var rank = 0
        return (listOf(full) + forms).filter { it != typed }.distinct()
            .map { f -> f to if (f == full || isCommon(f)) 1 else 4 + (rank++) / COST_STEP }
            .sortedBy { it.second } // stable: the supplement's order within a cost
    }

    private const val COMMON_BRACKETS = "「」『』【】（）"

    private fun isCommon(form: String): Boolean = form.isNotEmpty() &&
        (form.all { it in COMMON_BRACKETS } || form.codePoints().allMatch { it in 0x2460..0x2473 })

    /**
     * Form choices for [axes] in order of total cost, then fewer changed parts, then nearest the caret: best-first over
     * index vectors (a priority queue and a visited set; each popped state pushes one step along every axis). One axis
     * changed may use its whole list; two or more only forms of rank ≤ [COMBO_RANK] each, at most [MAX_COMBOS] of them.
     * At most [MAX_POPS] states are taken from the queue, so the work is bounded whatever the composition (about
     * MAX_POPS × axes small string steps); this is not every combination, only the cheapest that fit.
     */
    private fun variants(axes: List<Axis>): List<Pair<IntArray, Int>> {
        if (axes.isEmpty()) return emptyList()
        class State(val idx: IntArray, val cost: Int, val changed: Int, val distance: Int, val seq: Int)
        val queue = java.util.PriorityQueue<State>(compareBy<State>({ it.cost }, { it.changed }, { it.distance }, { it.seq }))
        val seen = HashSet<List<Int>>()
        var seq = 0
        fun push(idx: IntArray) {
            if (!seen.add(idx.toList())) return
            var cost = 0
            var changed = 0
            var distance = Int.MAX_VALUE
            for ((a, f) in idx.withIndex()) if (f > 0) {
                cost += axes[a].costs[f]
                changed++
                distance = minOf(distance, axes[a].distance)
            }
            if (changed >= 2 && idx.any { it > COMBO_RANK }) return
            queue += State(idx, cost, changed, distance, seq++)
        }
        push(IntArray(axes.size))
        val out = ArrayList<Pair<IntArray, Int>>()
        var combos = 0
        var pops = 0
        while (queue.isNotEmpty() && pops++ < MAX_POPS) {
            val s = queue.poll()
            if (s.changed == 1 || s.changed >= 2 && combos++ < MAX_COMBOS) out += s.idx to s.cost
            for (a in axes.indices) if (s.idx[a] + 1 < axes[a].forms.size) push(s.idx.copyOf().also { it[a]++ })
        }
        return out
    }

    /** Offsets of matched opening and closing brackets in [s] (ASCII or full width, by [Supplement.fold]), innermost first. */
    private fun matchedPairs(s: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val open = ArrayList<Int>()
        for (i in s.indices) {
            val c = Supplement.fold(s[i].toString())[0]
            when {
                c in PAIRS.keys -> open += i
                c in PAIRS.values -> {
                    val j = open.lastOrNull() ?: continue
                    if (PAIRS[Supplement.fold(s[j].toString())[0]] == c) {
                        open.removeAt(open.lastIndex)
                        out += j to i
                    } else {
                        open.clear() // crossed brackets: none of the open ones is matched by what follows
                    }
                }
            }
        }
        return out
    }

    private fun tokenAt(tokens: List<EnglishWord.Token>, offset: Int): Int {
        var end = 0
        for ((i, t) in tokens.withIndex()) {
            end += t.text.length
            if (offset < end) return i
        }
        return tokens.lastIndex
    }

    private fun byDistance(indices: List<Int>, at: Int): List<Int> = indices.sortedWith(compareBy({ kotlin.math.abs(it - at) }, { -it }))
}

/**
 * An English part of a kana composition (0.1.35): m-1 that ぐらんぷり took over, openai-1 typed between あめ and です. As a
 * segment, at the composing caret or selected, it gets the forms an English composition of the same text gets
 * ([WholeCandidates]: case, widths, the English index's spellings, the supplement's number and symbol forms, combined),
 * after what the converter offers. Each is the whole scope and reads as typed, so a pick replaces that segment only.
 * Completions are left out: a segment's choices spell what was typed.
 */
internal object MixedSegment {
    /** Longer scopes get no forms (the converter's alone); the work per scope is bounded by [WholeCandidates] as well. */
    const val MAX_TYPED = 40

    /**
     * [typed] as an English composition, or null unless it is made of ASCII letters, ASCII digits and punctuation only
     * with at least one letter or digit (kana, kanji, full-width letters, spaces and emoji never; a mark alone is left to
     * the converter's symbol forms).
     */
    fun word(typed: String): EnglishWord? {
        if (typed.isEmpty() || typed.length > MAX_TYPED) return null
        val word = EnglishWord()
        var letterOrDigit = false
        var i = 0
        while (i < typed.length) {
            val entry = String(Character.toChars(typed.codePointAt(i)))
            when {
                EnglishWord.isLetter(entry) || EnglishWord.isDigits(entry) -> letterOrDigit = true
                !EnglishWord.isPunctuation(entry) -> return null
            }
            word.insert(entry)
            i += entry.length
        }
        return word.takeIf { letterOrDigit }
    }

    /**
     * The forms of [typed] (itself first) as candidates read as [typed], unaligned; empty when it is not English. Letters
     * alone also get the full width of each form ([widths] only), as letters among digits or marks do.
     */
    fun candidates(
        typed: String,
        lookup: (String) -> EnglishLexicon.Result?,
        alternatives: (String) -> List<String>,
        widths: Boolean,
    ): List<Candidate> {
        val word = word(typed) ?: return emptyList()
        val all = WholeCandidates.of(word, lookup, alternatives, widths)
        val forms = all.list.filter { it !in all.predictions }
        val letters = word.tokens().singleOrNull()?.kind == EnglishWord.TokenKind.LETTERS
        val list = if (widths && letters) (forms + forms.map { Supplement.Widths.full(it) }).distinct() else forms
        return list.map { Candidate(typed, it, 0.0) }
    }
}

/**
 * Loads the English index ([EnglishLexicon], about 1.3 MB) once on its own thread and answers lookups there,
 * latest request only. Results are posted to [main] with the caller's generation; the caller drops any result
 * whose generation is no longer current (another letter, a commit, a new editor). Main-thread API.
 */
internal class EnglishLookup(
    private val main: Handler,
    private val load: () -> EnglishLexicon?,
) {
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "astra-english").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    @Volatile private var lexicon: EnglishLexicon? = null
    @Volatile private var loaded = false
    @Volatile var failed = false
        private set

    private fun ensureLoaded(): EnglishLexicon? {
        if (!loaded) {
            // Word candidates are optional: without the index the letters still compose and commit.
            lexicon = try { load() } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            failed = lexicon == null
            loaded = true
        }
        return lexicon
    }

    /** Starts loading in the background (idempotent). */
    fun preload() {
        if (!loaded) executor.execute { ensureLoaded() }
    }

    /** Up to [WholeCandidates.MAX_LOOKUPS] [words] in one job, so the latest-wins queue never drops one of them. */
    fun lookup(words: List<String>, generation: Long, onResult: (Long, Map<String, EnglishLexicon.Result>) -> Unit) {
        executor.execute {
            val lexicon = ensureLoaded()
            val results = words.associateWith { lexicon?.lookup(it) ?: EnglishLexicon.Result(emptyList(), emptyList()) }
            main.post { onResult(generation, results) }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        const val ASSET = "english/english-words.tsv"

        fun fromAssets(assets: AssetManager): EnglishLexicon = EnglishLexicon(assets.open(ASSET).use { it.readBytes() })
    }
}
