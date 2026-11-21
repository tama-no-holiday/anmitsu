package dev.astra.ime.core

/** How a [Segment] relates to the typed text. */
enum class SegmentKind {
    /** A dictionary (or registered) word. Its reading can differ from the typed span after typo correction. */
    WORD,
    /** Text that is never converted (punctuation, ASCII, emoji): typed text, reading and surface are equal. */
    PASSTHROUGH,
    /** Kana left as typed because no word covered it. */
    LITERAL,
}

/**
 * One aligned piece of a candidate. [start] until [end] are UTF-16 offsets into the typed text the candidate
 * was converted from; they come from the converter's lattice/token spans and are never derived from reading
 * lengths (a corrected [reading] may be longer or shorter than its typed span). [surface] is its output.
 */
data class Segment(val start: Int, val end: Int, val reading: String, val surface: String, val kind: SegmentKind) {
    /** Redacted: segments carry user text. */
    override fun toString(): String = "Segment($start..$end, $kind)"
}

/**
 * A repairable range of the composition. Units are groups of [Segment]s, not linguistic bunsetsu: a word starts
 * a unit, following hiragana-only words (particles, auxiliaries, okurigana, but also kana content words) join
 * it up to [Alignment.MAX_UNIT_TYPED] typed characters, and passthrough text is a unit of its own.
 */
data class RepairUnit(
    /** Typed (raw) kana of this unit; the composition's typed text is the concatenation over all units. */
    val typed: String,
    val reading: String,
    val surface: String,
    /** Current alignment relative to [typed]; empty when unknown (one span, no word-level learning). */
    val segments: List<Segment>,
    /** Words of the default conversion before any repair; learning only reinforces these if learned before. */
    val defaults: List<Segment>,
    /** Explicitly chosen in repair mode: asynchronous results never change it. */
    val locked: Boolean = false,
    /** Reading edited in repair mode. */
    val edited: Boolean = false,
) {
    override fun toString(): String = "RepairUnit(length=${typed.length}, locked=$locked, edited=$edited)"
}

object Alignment {
    /** Longest typed span a group of words grows to by attaching hiragana words. */
    const val MAX_UNIT_TYPED = 12

    /**
     * [segments] when they tile [typed] exactly (contiguous, non-empty, no split surrogate pair), spell
     * [surface], and passthrough text is unchanged; otherwise null, and the candidate is one unaligned span.
     */
    fun checked(typed: String, surface: String, segments: List<Segment>?): List<Segment>? {
        if (segments.isNullOrEmpty()) return null
        var at = 0
        val spelled = StringBuilder()
        for (s in segments) {
            if (s.start != at || s.end <= s.start || s.end > typed.length) return null
            if (splitsPair(typed, s.start) || splitsPair(typed, s.end)) return null
            if (s.kind == SegmentKind.PASSTHROUGH && (typed.substring(s.start, s.end) != s.surface || s.reading != s.surface)) return null
            spelled.append(s.surface)
            at = s.end
        }
        return if (at == typed.length && spelled.contentEquals(surface)) segments else null
    }

    /** Groups checked segments into unit ranges (see [RepairUnit]). */
    fun group(segments: List<Segment>): List<List<Segment>> {
        val groups = ArrayList<MutableList<Segment>>()
        for (s in segments) {
            val last = groups.lastOrNull()
            val attach = last != null && s.kind == SegmentKind.WORD && last[0].kind != SegmentKind.PASSTHROUGH &&
                isHiragana(s.surface) && s.end - last[0].start <= MAX_UNIT_TYPED
            if (attach) last!!.add(s) else groups.add(mutableListOf(s))
        }
        return groups
    }

    /**
     * Repair units for the composition [typed] showing [candidate] (null: the kana). Without a reliable alignment
     * (learned phrase, prediction, `:core` identity, unchecked segments) the whole text is one unit. With [kana]
     * the boundaries of [candidate] are used but each unit still shows its typed kana (live conversion off).
     * [defaults] are the default candidate's segments, kept for learning.
     */
    fun units(typed: String, candidate: Candidate?, defaults: List<Segment> = emptyList(), kana: Boolean = false): List<RepairUnit> {
        if (typed.isEmpty()) return emptyList()
        val words = defaults.filter { it.kind == SegmentKind.WORD }
        val segments = candidate?.let { checked(typed, it.surface, it.segments) }
        if (segments == null) {
            val surface = if (kana || candidate == null) typed else candidate.surface
            val reading = if (kana || candidate == null) typed else candidate.reading
            return listOf(RepairUnit(typed, reading, surface, emptyList(), words))
        }
        return group(segments).map { g ->
            val start = g.first().start
            val end = g.last().end
            val part = typed.substring(start, end)
            if (kana) {
                RepairUnit(part, part, part, emptyList(), words)
            } else {
                RepairUnit(
                    part, g.joinToString("") { it.reading }, g.joinToString("") { it.surface },
                    g.map { it.copy(start = it.start - start, end = it.end - start) }, words,
                )
            }
        }
    }

    /**
     * Index of the unit of [units] whose surface holds the character that starts at UTF-16 [offset] of their joined
     * surface (not of the typed or reading text, whose lengths differ). A caret on a boundary between two units
     * therefore selects the following unit. Null when [offset] is outside the surface or inside a surrogate pair.
     * Units with an empty surface are never selected.
     */
    fun unitAt(units: List<RepairUnit>, offset: Int): Int? {
        val surface = units.joinToString("") { it.surface }
        if (offset !in surface.indices || splitsPair(surface, offset)) return null
        var end = 0
        for ((i, u) in units.withIndex()) {
            end += u.surface.length
            if (offset < end) return i
        }
        return null
    }

    /**
     * Caret in [unit]'s typed kana for a caret before its surface character [offset] (UTF-16, within the unit's
     * surface). Exact where the surface is the typed kana (a kana unit, a literal or passthrough piece, a word written
     * in the kana that was typed); inside a word written otherwise (区切 for くぎ) the caret goes after that word,
     * since its characters have no kana of their own. Without an alignment only a kana unit maps; otherwise its end.
     */
    fun typedOffset(unit: RepairUnit, offset: Int): Int {
        if (offset <= 0) return 0
        if (offset >= unit.surface.length) return unit.typed.length
        if (unit.segments.isEmpty()) return if (unit.surface == unit.typed) offset else unit.typed.length
        var at = 0
        for (s in unit.segments) {
            val end = at + s.surface.length
            if (offset < end) {
                val typed = unit.typed.substring(s.start, s.end)
                return if (s.surface == typed) s.start + (offset - at) else if (offset == at) s.start else s.end
            }
            at = end
        }
        return unit.typed.length
    }

    /**
     * The other direction of [typedOffset]: the surface offset of a caret at [cursor] of [unit]'s typed kana, or null when
     * the caret has no place in the surface. Exact at the unit's ends, on a segment boundary and inside a segment written
     * in the kana that was typed (a particle, a literal, passthrough text); inside a word written otherwise (区切 for くぎ)
     * there is none, since its characters have no kana of their own. Without an alignment only a kana unit maps.
     */
    fun surfaceOffset(unit: RepairUnit, cursor: Int): Int? {
        if (cursor <= 0) return 0
        if (cursor >= unit.typed.length) return unit.surface.length
        if (unit.segments.isEmpty()) return if (unit.surface == unit.typed) cursor else null
        var at = 0
        for (s in unit.segments) {
            if (cursor == s.start) return at
            if (cursor < s.end) {
                return if (s.surface == unit.typed.substring(s.start, s.end)) at + (cursor - s.start) else null
            }
            at += s.surface.length
        }
        return null
    }

    internal fun isHiragana(s: String): Boolean = s.isNotEmpty() && s.all { it.code in 0x3041..0x3096 || it == 'ー' }

    private fun splitsPair(t: String, i: Int): Boolean =
        i in 1 until t.length && Character.isHighSurrogate(t[i - 1]) && Character.isLowSurrogate(t[i])
}

/**
 * Candidates for the focused unit, requested by [RepairSession.request]. Only the latest request of the current
 * [revision] is accepted by [RepairSession.apply]. [context] is the surface before the unit (for a judge).
 */
class RepairRequest internal constructor(
    val revision: Long,
    val unit: Int,
    val text: String,
    val context: String,
    /** Issued right after a reading edit: only such a result may preview its top candidate. */
    internal val afterEdit: Boolean = false,
    /** Another unit still showing kana after a [RepairSession.resize] (-1: none); convert [neighborText] alongside. */
    val neighbor: Int = -1,
    val neighborText: String = "",
) {
    override fun toString(): String = "RepairRequest(revision=$revision, unit=$unit, length=${text.length})"
}

/**
 * Explicit repair of an uncommitted composition: the user focuses one unit, picks another surface for it or edits
 * its reading at a cursor, while every other unit stays exactly as it was. Nothing here commits, learns or reads
 * editor text; [finish] hands the units back to [InputSession.replaceComposition].
 *
 * Every change (focus, edit, choice, undo, applied result) bumps [revision], which makes outstanding requests and
 * candidate taps stale. A unit chosen explicitly is [RepairUnit.locked]: results never alter it. Results only
 * preview the top candidate in an edited, unlocked unit, and only when requested right after a reading edit: after
 * an undo, a focus change or a choice the unit keeps showing exactly what it shows. Main thread only, like [InputSession].
 */
class RepairSession(initial: List<RepairUnit>, focus: Int = initial.lastIndex) {
    /** [growing]: in [caretMode], the unit that typing at the caret is building (-1: none); it is edited as a whole. */
    private class State(val units: List<RepairUnit>, val focus: Int, val cursor: Int, val growing: Int = -1)

    private val owner = Thread.currentThread()
    private val start: State
    private var state: State
    private val history = ArrayDeque<State>()
    private var pending: RepairRequest? = null
    private var candidatesFor: String? = null
    /** The last change was a reading edit (cursor moves inside the reading keep it). */
    private var editedLast = false

    init {
        require(initial.isNotEmpty() && focus in initial.indices)
        start = State(initial.toList(), focus, initial[focus].typed.length)
        state = start
    }

    var revision = 0L
        private set

    /** Candidates for the focused unit (empty until a result is applied); converted from its current typed text. */
    var candidates: List<Candidate> = emptyList()
        private set

    /** Index in [candidates] the focused unit currently shows, -1 if none. */
    var candidateIndex = -1
        private set

    val units: List<RepairUnit> get() = confined { state.units }
    val focus: Int get() = confined { state.focus }

    /** Cursor in the focused unit's typed reading (UTF-16 offset, always on a code point boundary). */
    val cursor: Int get() = confined { state.cursor }
    val typed: String get() = confined { state.units.joinToString("") { it.typed } }
    val surface: String get() = confined { state.units.joinToString("") { it.surface } }
    val canUndo: Boolean get() = confined { history.isNotEmpty() }

    /** Surface offsets of the focused unit within [surface]. */
    val focusRange: IntRange get() = confined {
        val before = state.units.take(state.focus).sumOf { it.surface.length }
        before until before + state.units[state.focus].surface.length
    }

    fun focus(index: Int): Boolean = confined {
        if (index !in state.units.indices || index == state.focus) return@confined false
        state = State(state.units, index, state.units[index].typed.length)
        changed(clearCandidates = true)
        true
    }

    /**
     * Focuses unit [index] with the caret at [cursor] of its typed kana (clamped, moved off a surrogate pair's middle).
     * Inside the focused unit only the caret moves: its candidates and pending results stay valid. False if nothing changed.
     */
    fun place(index: Int, cursor: Int): Boolean = confined {
        if (index !in state.units.indices) return@confined false
        val t = state.units[index].typed
        var c = cursor.coerceIn(0, t.length)
        if (c in 1 until t.length && Character.isLowSurrogate(t[c]) && Character.isHighSurrogate(t[c - 1])) c--
        if (index == state.focus) {
            if (c == state.cursor) return@confined false
            state = State(state.units, index, c)
            return@confined true
        }
        state = State(state.units, index, c)
        changed(clearCandidates = true)
        true
    }

    /**
     * ⌫ on the selected unit: deletes the code point before the caret in its typed kana; every other unit stays as it
     * is. A unit left without kana is removed and the previous unit is selected with the caret at its end (the first
     * unit's removal selects the next one, caret at its start), so one press never deletes from two units. With the
     * caret at the start of a later unit only the selection moves back; at the start of the first unit nothing happens
     * (what precedes the composition is never touched). The last kana of the last unit leaves one empty unit
     * ([typed] empty): the composition is gone. Undoable. False when nothing changed.
     */
    fun deleteBackward(): Boolean = confined {
        val s = state
        val f = s.focus
        if (splitsOnEdit() && s.cursor > 0) {
            caretReplace(Graphemes.previous(s.units[f].typed, s.cursor), s.cursor, "")
            return@confined true
        }
        if (s.cursor == 0) {
            if (f == 0) return@confined false
            state = State(s.units, f - 1, s.units[f - 1].typed.length)
            changed(clearCandidates = true)
            return@confined true
        }
        val t = s.units[f].typed
        // One character, never half an emoji sequence or a letter without its mark.
        val n = s.cursor - Graphemes.previous(t, s.cursor)
        val rest = t.substring(0, s.cursor - n) + t.substring(s.cursor)
        if (rest.isNotEmpty() || s.units.size == 1) {
            edit(rest, s.cursor - n)
            return@confined true
        }
        history.addLast(state)
        if (history.size > MAX_UNDO) history.removeFirst()
        val units = s.units.toMutableList().also { it.removeAt(f) }
        state = if (f > 0) State(units, f - 1, units[f - 1].typed.length) else State(units, 0, 0)
        changed(clearCandidates = true)
        true
    }

    /** ◀: one code point left in the reading, or to the end of the previous unit. */
    fun moveLeft(): Boolean = confined {
        val s = state
        val t = s.units[s.focus].typed
        when {
            // Moving inside the reading changes neither the unit nor its candidates: pending results stay valid.
            s.cursor > 0 -> {
                state = State(s.units, s.focus, s.cursor - Character.charCount(t.codePointBefore(s.cursor)))
                true
            }
            s.focus > 0 -> {
                state = State(s.units, s.focus - 1, s.units[s.focus - 1].typed.length)
                changed(clearCandidates = true)
                true
            }
            else -> false
        }
    }

    /** ▶: one code point right in the reading, or to the start of the next unit. */
    fun moveRight(): Boolean = confined {
        val s = state
        val t = s.units[s.focus].typed
        when {
            s.cursor < t.length -> {
                state = State(s.units, s.focus, s.cursor + Character.charCount(t.codePointAt(s.cursor)))
                true
            }
            s.focus < s.units.lastIndex -> {
                state = State(s.units, s.focus + 1, 0)
                changed(clearCandidates = true)
                true
            }
            else -> false
        }
    }

    /**
     * Composing caret (0.1.34): the arrow keys move [cursor] through the typed kana of the whole composition, one
     * character ([Graphemes]) a press, crossing units; the unit holding the caret is focused. A caret on a boundary
     * belongs to the end of the unit before it (only the very start is the first unit's 0), so every press moves the
     * caret visibly. False when the caret is already at that end.
     *
     * Boundary at the caret (review follow-up): a caret strictly inside a unit splits it there, provisionally — only
     * [shownUnits], [scopeTyped] and [shownCaret] see the split ([split]); [units] stay as they are, so moving on (or
     * back) shows the unit whole again and [finish] keeps what it was (moving never turns a word into kana). The
     * candidates are for the [scope]: the part of the unit before the caret. An edit at the caret ([insert],
     * [deleteBackward], [toggleBefore]) or a choice makes that one boundary real ([caretReplace]); every other unit stays
     * as it was. The unit typing there builds ([State.growing]) is edited as a whole, without further splits.
     */
    var caretMode = false

    fun caretLeft(): Boolean = confined {
        val abs = absoluteCaret()
        if (abs == 0) return@confined false
        moveCaretTo(Graphemes.previous(typed, abs))
    }

    fun caretRight(): Boolean = confined {
        val abs = absoluteCaret()
        val all = typed
        if (abs >= all.length) return@confined false
        moveCaretTo(Graphemes.next(all, abs))
    }

    fun caretToStart(): Boolean = confined { if (absoluteCaret() == 0) false else moveCaretTo(0) }

    fun caretToEnd(): Boolean = confined {
        val all = typed
        if (absoluteCaret() >= all.length) false else moveCaretTo(all.length)
    }

    /** The caret is after the last kana of the composition. */
    val caretAtEnd: Boolean get() = confined { absoluteCaret() >= typed.length }

    /**
     * What the composition shows. In [caretMode] a unit with the caret strictly inside is shown as its two parts at the
     * caret ([split]): words keep their surface where the alignment gives the caret a place ([Alignment.surfaceOffset]);
     * a word written otherwise than its kana (明日 for あした) shows that kana, so the reading is never mapped by surface
     * length. The unit typing builds shows its kana while the caret is inside a word of it. Otherwise [units].
     */
    val shownUnits: List<RepairUnit> get() = confined {
        val s = state
        val u = s.units[s.focus]
        val parts = split()
        when {
            parts != null -> s.units.toMutableList().also { it[s.focus] = parts.first; it.add(s.focus + 1, parts.second) }
            !caretMode || Alignment.surfaceOffset(u, s.cursor) != null -> s.units
            else -> s.units.toMutableList().also { it[s.focus] = asKana(u) }
        }
    }

    val shownSurface: String get() = confined { shownUnits.joinToString("") { it.surface } }

    /** Surface offset in [shownSurface] where the caret is (the end of the focused unit outside [caretMode]). */
    val shownCaret: Int get() = confined {
        val shown = shownUnits
        val before = shown.take(state.focus).sumOf { it.surface.length }
        val u = shown[state.focus]
        before + when {
            split() != null -> u.surface.length // the caret is the boundary after the first part
            caretMode -> Alignment.surfaceOffset(u, state.cursor) ?: u.surface.length
            else -> u.surface.length
        }
    }

    /** [focusRange] over [shownSurface]: the [scope] (with a split, its first part). */
    val shownFocusRange: IntRange get() = confined {
        val shown = shownUnits
        val before = shown.take(state.focus).sumOf { it.surface.length }
        before until before + shown[state.focus].surface.length
    }

    /** The kana the candidates are for: the focused unit, or with a split at the caret the part before it. */
    val scopeTyped: String get() = confined { scope().typed }

    /** What the [scope] shows. */
    val scopeSurface: String get() = confined { scope().surface }

    /** The unit of [units] that [shownUnits]`[index]` is (or is a part of). */
    fun unitOfShown(index: Int): Int = confined {
        if (split() == null || index <= state.focus) index else index - 1
    }

    /**
     * The provisional split of the focused unit at the caret, or null: outside [caretMode], with the caret at an end of
     * the unit, or in the unit typing builds.
     */
    private fun split(): Pair<RepairUnit, RepairUnit>? {
        val s = state
        if (!caretMode || s.focus == s.growing) return null
        val u = s.units[s.focus]
        if (s.cursor <= 0 || s.cursor >= u.typed.length) return null
        val (a, b) = splitAt(u, s.cursor)
        return if (a != null && b != null) a to b else null
    }

    private fun scope(): RepairUnit = split()?.first ?: state.units[state.focus]

    /**
     * [u] cut at [c] of its typed kana: the parts before and after (null when empty). Where the alignment gives the cut a
     * place in the surface, each part keeps its words, a kana-written segment cut in two keeps its kana as literals;
     * otherwise (inside a word written otherwise) both parts are their kana, to be converted again.
     */
    private fun splitAt(u: RepairUnit, c: Int): Pair<RepairUnit?, RepairUnit?> {
        if (c <= 0) return null to u
        if (c >= u.typed.length) return u to null
        val t = u.typed
        val off = Alignment.surfaceOffset(u, c)
            ?: return kanaPiece(t.substring(0, c), u) to kanaPiece(t.substring(c), u)
        if (u.segments.isEmpty()) {
            fun part(typed: String, surface: String) = u.copy(typed = typed, reading = surface, surface = surface)
            return part(t.substring(0, c), u.surface.substring(0, off)) to part(t.substring(c), u.surface.substring(off))
        }
        val left = ArrayList<Segment>()
        val right = ArrayList<Segment>()
        for (s in u.segments) when {
            s.end <= c -> left += s
            s.start >= c -> right += s.copy(start = s.start - c, end = s.end - c)
            else -> { // a segment written in its typed kana (surfaceOffset found a place in it)
                val kind = if (s.kind == SegmentKind.PASSTHROUGH) s.kind else SegmentKind.LITERAL
                val k = c - s.start
                left += Segment(s.start, c, s.surface.substring(0, k), s.surface.substring(0, k), kind)
                right += Segment(0, s.end - c, s.surface.substring(k), s.surface.substring(k), kind)
            }
        }
        fun part(typed: String, segments: List<Segment>) = u.copy(
            typed = typed, reading = segments.joinToString("") { it.reading }, surface = segments.joinToString("") { it.surface },
            segments = segments,
        )
        return part(t.substring(0, c), left) to part(t.substring(c), right)
    }

    private fun kanaPiece(typed: String, from: RepairUnit) =
        RepairUnit(typed, typed, typed, emptyList(), from.defaults, locked = false, edited = true)

    /**
     * Replaces typed kana [p] until [c] of the focused unit with [text] at the caret, making the boundaries at [p] and
     * [c] real for this unit only: the part before [p] and the part after [c] keep their words ([splitAt]), [text] joins
     * a part before it that shows kana or is a new unit of its own, and the caret goes after it. A part left as kana by a
     * cut through a word converts again (the focused one through [request], the other as its neighbor).
     */
    private fun caretReplace(p: Int, c: Int, text: String) {
        val s = state
        val f = s.focus
        val u = s.units[f]
        val left = splitAt(u, p).first
        val right = splitAt(u, c).second
        val parts = ArrayList<RepairUnit>()
        if (left != null) parts += left
        var at = parts.lastIndex
        if (text.isNotEmpty()) {
            if (left != null && !left.locked && left.surface == left.typed) parts[parts.lastIndex] = kanaPiece(left.typed + text, left)
            else parts += RepairUnit(text, text, text, emptyList(), emptyList(), edited = true)
            at = parts.lastIndex
        }
        if (right != null) parts += right
        var units: List<RepairUnit> = s.units.subList(0, f) + parts + s.units.subList(f + 1, s.units.size)
        val focus: Int
        val cursor: Int
        when {
            units.isEmpty() -> {
                units = listOf(RepairUnit("", "", "", emptyList(), emptyList(), edited = true))
                focus = 0; cursor = 0
            }
            at >= 0 -> { focus = f + at; cursor = units[focus].typed.length }
            f > 0 -> { focus = f - 1; cursor = units[focus].typed.length }
            else -> { focus = 0; cursor = 0 }
        }
        history.addLast(state)
        if (history.size > MAX_UNDO) history.removeFirst()
        val focused = units[focus]
        val building = focused.edited && !focused.locked && focused.surface == focused.typed && focused.typed.isNotEmpty()
        state = State(units, focus, cursor, if (building) focus else -1)
        changed(clearCandidates = true)
        editedLast = building
    }

    /** A unit other than the focused one still shows kana it should be converted from (after a cut or a resize). */
    val hasUnconverted: Boolean get() = confined {
        state.units.indices.any { i ->
            val n = state.units[i]
            i != state.focus && n.edited && !n.locked && n.typed.isNotEmpty() && n.surface == n.typed
        }
    }

    /** Edits at the caret make boundaries ([caretReplace]) unless they are in the unit typing builds. */
    private fun splitsOnEdit(): Boolean = caretMode && state.focus != state.growing

    private fun absoluteCaret(): Int = state.units.take(state.focus).sumOf { it.typed.length } + state.cursor

    /**
     * Moves the caret. Inside one unit and one [scope] its candidates and pending result stay valid; a new scope (another
     * unit, or another split of this one) drops them and bumps [revision], as [focus] does.
     */
    private fun moveCaretTo(abs: Int): Boolean {
        val units = state.units
        var unit = 0
        var cursor = 0
        if (abs > 0) {
            var before = 0
            for ((i, u) in units.withIndex()) {
                if (abs <= before + u.typed.length) {
                    unit = i
                    cursor = abs - before
                    break
                }
                before += u.typed.length
            }
        }
        if (unit == state.focus && cursor == state.cursor) return false
        val scopeBefore = if (unit == state.focus) scope().typed else null
        state = State(units, unit, cursor, state.growing)
        if (scopeBefore == null || scopeBefore != scope().typed) changed(clearCandidates = true)
        return true
    }

    private fun asKana(u: RepairUnit) =
        u.copy(reading = u.typed, surface = u.typed, segments = emptyList(), locked = false, edited = true)

    /** Inserts [text] into the focused unit's reading at the cursor; the unit shows the new kana until a result. */
    fun insert(text: String): Boolean = confined {
        if (text.isEmpty()) return@confined false
        if (splitsOnEdit()) {
            caretReplace(state.cursor, state.cursor, text)
            return@confined true
        }
        val t = state.units[state.focus].typed
        edit(t.substring(0, state.cursor) + text + t.substring(state.cursor), state.cursor + text.length)
        true
    }

    /** Deletes the code point before the cursor in the focused unit. False at the start of the unit. */
    fun deleteBefore(): Boolean = confined {
        val c = state.cursor
        if (c == 0) return@confined false
        val t = state.units[state.focus].typed
        val n = Character.charCount(t.codePointBefore(c))
        edit(t.substring(0, c - n) + t.substring(c), c - n)
        true
    }

    /** Applies [toggle] (the 小゛゜ key) to the character before the cursor. */
    fun toggleBefore(toggle: (String) -> String?): Boolean = confined {
        val c = state.cursor
        if (c == 0) return@confined false
        val t = state.units[state.focus].typed
        val n = Character.charCount(t.codePointBefore(c))
        val replaced = toggle(t.substring(c - n, c)) ?: return@confined false
        if (splitsOnEdit()) {
            caretReplace(c - n, c, replaced)
            return@confined true
        }
        edit(t.substring(0, c - n) + replaced + t.substring(c), c - n + replaced.length)
        true
    }

    /** Request for the [scope]'s candidates; supersedes earlier ones. Null for an empty unit. */
    fun request(): RepairRequest? = confined {
        val u = scope()
        if (u.typed.isEmpty()) return@confined null
        val context = state.units.take(state.focus).joinToString("") { it.surface }.takeLast(CONTEXT_CHARS)
        // A unit a resize left as kana is converted with the next request, whichever unit is focused by then.
        val neighbor = state.units.indices.firstOrNull { i ->
            val n = state.units[i]
            i != state.focus && n.edited && !n.locked && n.typed.isNotEmpty() && n.surface == n.typed
        } ?: -1
        RepairRequest(revision, state.focus, u.typed, context, editedLast, neighbor, state.units.getOrNull(neighbor)?.typed.orEmpty())
            .also { pending = it }
    }

    /**
     * ◀ ▶ on a focused segment: moves the boundary after it by one code point. [extend] takes the next unit's first
     * kana (that unit disappears when it had only one); otherwise the focused unit's last kana moves to the start of
     * the next unit (a new one at the end). Both readings show as kana until [apply]: the focused one through the
     * normal request, the other as its [RepairRequest.neighbor]. False when there is nothing to move. Undoable.
     */
    fun resize(extend: Boolean): Boolean = confined {
        val s = state
        val units = s.units.toMutableList()
        val f = s.focus
        val u = units[f]
        fun kana(unit: RepairUnit, typed: String) =
            unit.copy(typed = typed, reading = typed, surface = typed, segments = emptyList(), locked = false, edited = true)
        if (extend) {
            val next = units.getOrNull(f + 1) ?: return@confined false
            val n = Character.charCount(next.typed.codePointAt(0))
            units[f] = kana(u, u.typed + next.typed.substring(0, n))
            val rest = next.typed.substring(n)
            if (rest.isEmpty()) units.removeAt(f + 1) else units[f + 1] = kana(next, rest)
        } else {
            if (u.typed.codePointCount(0, u.typed.length) < 2) return@confined false
            val n = Character.charCount(u.typed.codePointBefore(u.typed.length))
            val moved = u.typed.substring(u.typed.length - n)
            units[f] = kana(u, u.typed.substring(0, u.typed.length - n))
            val next = units.getOrNull(f + 1)
            if (next == null) units.add(f + 1, RepairUnit(moved, moved, moved, emptyList(), emptyList(), edited = true))
            else units[f + 1] = kana(next, moved + next.typed)
        }
        history.addLast(state)
        if (history.size > MAX_UNDO) history.removeFirst()
        state = State(units, f, units[f].typed.length)
        changed(clearCandidates = true)
        editedLast = true
        true
    }

    /**
     * Shows [result] for the focused unit if [request] is the latest one and nothing changed since. With [preview]
     * (live conversion) an edited, unlocked unit shows the first candidate if [request] followed a reading edit;
     * otherwise no unit changes (an undone state stays as restored). The unit's current surface is kept as a
     * choice. An edited, unlocked unit that still shows its typed kana has no selected candidate
     * ([candidateIndex] -1), even though the raw kana is always listed ([LiteralChoices]): that kana is a
     * placeholder awaiting conversion, so the first 変換 picks the first real alternative. Returns whether the
     * result was accepted.
     */
    fun apply(request: RepairRequest, result: List<Candidate>, preview: Boolean, neighborResult: List<Candidate> = emptyList()): Boolean = confined {
        // With a provisional split the result is for its first part, which nothing previews (it is not edited).
        val provisional = split() != null
        val u = scope()
        if (request !== pending || request.revision != revision || request.unit != state.focus || request.text != u.typed) {
            return@confined false
        }
        if (result.isEmpty()) return@confined false
        // The neighbor of a resize shows its first candidate (live conversion), as the focused unit does.
        val n = state.units.getOrNull(request.neighbor)
        if (preview && n != null && n.typed == request.neighborText && n.edited && !n.locked && neighborResult.isNotEmpty()) {
            val units = state.units.toMutableList()
            units[request.neighbor] = unitFor(n, neighborResult[0], locked = false)
            state = State(units, state.focus, state.cursor, state.growing)
        }
        val merged = ArrayList<Candidate>(result.size + 1)
        if (!u.edited && result.none { it.surface == u.surface }) {
            merged += Candidate(u.reading, u.surface, -1.0, u.segments.takeIf { it.isNotEmpty() })
        }
        merged += result
        // The unit's raw kana and katakana stay choosable even when the current surface takes a slot.
        val list = LiteralChoices.ensure(u.typed, merged, MAX_LIMIT).candidates
        pending = null
        candidates = list
        candidatesFor = u.typed
        if (!provisional && preview && request.afterEdit && u.edited && !u.locked) {
            state = State(replaced(unitFor(u, list[0], locked = false)), state.focus, state.cursor, state.growing)
            candidateIndex = 0
        } else {
            val placeholder = u.edited && !u.locked && u.surface == u.typed
            candidateIndex = if (placeholder) -1 else list.indexOfFirst { it.surface == u.surface }
        }
        revision++
        true
    }

    /**
     * Adds [extra] choices for the [scope] after its candidates (0.1.35: the forms of an English part, M-1 for m-1), each
     * surface once, uncapped. Only while the candidates are for [typed], the scope's current text, and only entries read
     * as [typed]. Nothing listed moves or goes, so a tap on an earlier chip and [candidateIndex] keep their meaning; no
     * revision bump either, so a pending request stays valid. False when nothing was added.
     */
    fun supplement(typed: String, extra: List<Candidate>): Boolean = confined {
        if (candidatesFor != typed || scope().typed != typed) return@confined false
        val seen = candidates.mapTo(HashSet()) { it.surface }
        val added = extra.filter { it.reading == typed && seen.add(it.surface) }
        if (added.isEmpty()) return@confined false
        candidates = candidates + added
        true
    }

    /**
     * Explicit choice of [candidates]`[index]` for the [scope]; locks it. With a provisional split the boundary at the
     * caret becomes real: the first part takes the choice, the second keeps its words (or converts again when the cut
     * went through a word, [hasUnconverted]). Undoable.
     */
    fun choose(index: Int): Boolean = confined {
        val parts = split()
        val u = parts?.first ?: state.units[state.focus]
        if (index !in candidates.indices || candidatesFor != u.typed) return@confined false
        history.addLast(state)
        if (history.size > MAX_UNDO) history.removeFirst()
        val chosen = unitFor(u, candidates[index], locked = true)
        // A caret inside the reading would hide the choice behind its kana: it goes after the chosen unit.
        state = if (parts != null) {
            val units = state.units.toMutableList().also { it[state.focus] = chosen; it.add(state.focus + 1, parts.second) }
            State(units, state.focus, u.typed.length)
        } else {
            State(replaced(chosen), state.focus, if (caretMode) u.typed.length else state.cursor)
        }
        candidateIndex = index
        pending = null
        editedLast = false
        revision++
        true
    }

    /**
     * Explicit choice of a [candidate] that may lie outside the capped [candidates] (the expanded panel's exact list).
     * It must have been drawn for the focused unit's current text; it joins [candidates] so 変換 can come back to it.
     */
    fun choose(candidate: Candidate): Boolean = confined {
        if (candidatesFor != scope().typed) return@confined false
        var index = candidates.indexOfFirst { it.surface == candidate.surface }
        if (index < 0) {
            candidates = candidates + candidate
            index = candidates.lastIndex
        }
        choose(index)
    }

    /** 変換 in repair mode: chooses the next candidate that differs from what the unit shows. */
    fun next(): Boolean = confined {
        if (candidates.isEmpty() || candidatesFor != scope().typed) return@confined false
        val shown = scope().surface
        var i = (candidateIndex + 1) % candidates.size
        if (candidates.size > 1 && candidates[i].surface == shown) i = (i + 1) % candidates.size
        choose(i)
    }

    /** Reverts the last choice or reading edit. */
    fun undo(): Boolean = confined {
        val previous = history.removeLastOrNull() ?: return@confined false
        state = previous
        changed(clearCandidates = true)
        true
    }

    /** The state on entry: everything as before repair mode. */
    fun initialUnits(): List<RepairUnit> = confined { start.units }

    /**
     * Units to keep composing with: [units], not what a passing caret shows ([shownUnits] shows a word holding it as its
     * kana; the word is kept, so moving alone never turns it into kana). Units whose reading was deleted entirely are dropped.
     */
    fun finish(): List<RepairUnit> = confined { state.units.filter { it.typed.isNotEmpty() } }

    override fun toString(): String = "RepairSession(units=${state.units.size}, focus=${state.focus}, revision=$revision)"

    private fun edit(typed: String, cursor: Int) {
        history.addLast(state)
        if (history.size > MAX_UNDO) history.removeFirst()
        val u = state.units[state.focus]
        // The new reading is shown as typed; its alignment is unknown until a candidate is chosen or previewed.
        val edited = u.copy(typed = typed, reading = typed, surface = typed, segments = emptyList(), locked = false, edited = true)
        state = State(replaced(edited), state.focus, cursor, state.growing)
        changed(clearCandidates = true)
        editedLast = true
    }

    private fun unitFor(u: RepairUnit, c: Candidate, locked: Boolean): RepairUnit {
        val segments = Alignment.checked(u.typed, c.surface, c.segments).orEmpty()
        return u.copy(reading = c.reading, surface = c.surface, segments = segments, locked = locked)
    }

    private fun replaced(unit: RepairUnit): List<RepairUnit> = state.units.toMutableList().also { it[state.focus] = unit }

    private fun changed(clearCandidates: Boolean) {
        revision++
        pending = null
        editedLast = false
        if (clearCandidates) {
            candidates = emptyList()
            candidatesFor = null
            candidateIndex = -1
        }
    }

    private inline fun <T> confined(block: () -> T): T {
        check(Thread.currentThread() === owner) { "RepairSession must be used from its creating thread" }
        return block()
    }

    private companion object {
        const val MAX_UNDO = 64
        const val CONTEXT_CHARS = 40
    }
}
