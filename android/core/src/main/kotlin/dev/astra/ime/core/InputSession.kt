package dev.astra.ime.core

import java.util.Collections

/**
 * Immutable snapshot of one composition state, handed to a background conversion and back.
 * Only the instance most recently returned by [InputSession.request] is accepted by
 * [InputSession.apply]; only the instance last applied is accepted by [InputSession.choose].
 * [text] is the raw composing text and is excluded from [toString].
 */
class SessionToken internal constructor(
    val editorId: Long,
    val revision: Long,
    val text: String,
    val sensitive: Boolean,
) {
    override fun toString(): String =
        "SessionToken(editorId=$editorId, revision=$revision, sensitive=$sensitive, length=${text.length})"
}

/**
 * Platform-neutral composition gate for one keyboard. Holds the current composition only;
 * never stores committed text, never learns, never commits on its own.
 *
 * Every method must be called from the thread that created the session (the Android main
 * thread); violations throw [IllegalStateException]. Only [SessionToken]s cross threads.
 *
 * Typical app flow:
 * 1. `onStartInput` -> [startInput] (sensitive = password/raw TYPE_NULL field).
 *    `onFinishInput` -> [finishInput].
 * 2. Key press -> [type] / [backspace] / [toggleLast]. Each successful edit bumps [revision],
 *    hides candidates and makes every outstanding token stale. If they return false the key
 *    was not consumed (e.g. backspace on empty composition: send it to the editor).
 * 3. After an edit, `val t = request()`; convert `t.text` off the main thread
 *    (`engine.convert(t.text)`), or skip the engine and use the raw text when `t.sensitive`;
 *    post back and call `apply(t, result)`. A false return means the result is stale; drop it.
 * 4. Show [candidates]. On an explicit tap, `choose(t, index)`; a non-null result is the text
 *    to commit, and the composition is already cleared. Null means do nothing.
 * 5. Cursor moved outside the composition (`onUpdateSelection`) -> [onSelectionMoved]; the app
 *    finishes the composing region as typed (no conversion).
 * 6. Explicit Enter/confirm without choosing -> [commitRaw].
 * 7. After a repair ([RepairSession]), [replaceComposition] keeps the repaired units as a fixed head. Typing then
 *    extends the tail ([composingText]), which alone is converted; [choose] and [commitRaw] return the head's
 *    surface followed by the tail. Backspace on an empty tail releases the last head unit back into the tail.
 */
class InputSession {
    private val owner = Thread.currentThread()
    private val composing = StringBuilder()
    private var head: List<RepairUnit> = emptyList()
    private var editor = 0L
    private var rev = 0L
    private var active = false
    private var sensitiveEditor = false
    private var pending: SessionToken? = null
    private var shownFor: SessionToken? = null
    private var shown: List<Candidate> = emptyList()

    /** True between [startInput] and [finishInput]. */
    val isActive: Boolean get() = confined { active }

    /** Whether the current editor is sensitive (raw text only). */
    val isSensitive: Boolean get() = confined { sensitiveEditor }

    /** Identifier of the current editor; increases with every [startInput]. */
    val editorId: Long get() = confined { editor }

    /** Strictly increases on every state change; never reset, even across editors. */
    val revision: Long get() = confined { rev }

    /** Current composing text after the head (raw, possibly noisy kana); this is what [request] converts. */
    val composingText: String get() = confined { composing.toString() }

    /** Repaired units before [composingText]; never changed by conversion results. Unmodifiable. */
    val headUnits: List<RepairUnit> get() = confined { head }
    val headSurface: String get() = confined { head.joinToString("") { it.surface } }
    val headTyped: String get() = confined { head.joinToString("") { it.typed } }

    /** Whether anything is being composed (head or tail). */
    val hasComposition: Boolean get() = confined { head.isNotEmpty() || composing.isNotEmpty() }

    /** Typed kana of the whole composition (head, then tail). */
    val typedText: String get() = confined { head.joinToString("") { it.typed } + composing }

    /** Candidates to display; empty unless a fresh result was applied. Unmodifiable. */
    val candidates: List<Candidate> get() = confined { shown }

    /** Begins a new editor; everything from the previous editor is dropped. */
    fun startInput(sensitive: Boolean) = confined {
        editor += 1
        active = true
        sensitiveEditor = sensitive
        invalidate(clearText = true)
    }

    /** Ends the editor; composition, candidates and tokens are dropped. */
    fun finishInput() = confined {
        active = false
        invalidate(clearText = true)
    }

    /** Appends [text] to the composition. Returns false if inactive or [text] is empty. */
    fun type(text: String): Boolean = confined {
        if (!active || text.isEmpty()) return@confined false
        composing.append(text)
        invalidate(clearText = false)
        true
    }

    /**
     * Deletes the last code point (surrogate-pair safe). With an empty tail, the last head unit's typed kana
     * becomes the tail first (it is converted again). Returns false if nothing was composed.
     */
    fun backspace(): Boolean = confined {
        if (!active || (composing.isEmpty() && head.isEmpty())) return@confined false
        if (composing.isEmpty()) releaseLastUnit()
        if (composing.isNotEmpty()) {
            val last = composing.codePointBefore(composing.length)
            composing.setLength(composing.length - Character.charCount(last))
        }
        invalidate(clearText = false)
        true
    }

    /** Applies the 小゛゜ key ([FlickLayout.toggle]) to the last character. False if not applicable. */
    fun toggleLast(): Boolean = confined {
        if (!active || (composing.isEmpty() && head.isEmpty())) return@confined false
        val source = if (composing.isNotEmpty()) composing.toString() else head.last().typed
        if (source.isEmpty()) return@confined false
        val count = Character.charCount(source.codePointBefore(source.length))
        val replaced = FlickLayout.toggle(source.substring(source.length - count)) ?: return@confined false
        if (composing.isEmpty()) releaseLastUnit()
        composing.setLength(composing.length - count)
        composing.append(replaced)
        invalidate(clearText = false)
        true
    }

    /**
     * Replaces the whole composition with repaired [units] (from [RepairSession.finish]); the tail becomes empty.
     * Outstanding tokens become stale. False if inactive or sensitive.
     */
    fun replaceComposition(units: List<RepairUnit>): Boolean = confined {
        if (!active || sensitiveEditor) return@confined false
        invalidate(clearText = true)
        head = units.filter { it.typed.isNotEmpty() }.toList()
        true
    }

    /** Cursor/selection moved away: drops the composition and candidates without returning text. */
    fun onSelectionMoved() {
        confined { if (active) invalidate(clearText = true) }
    }

    /**
     * Snapshot for conversion; supersedes earlier tokens of the same revision.
     * Null when inactive or nothing is composed.
     */
    fun request(): SessionToken? = confined {
        if (!active || composing.isEmpty()) return@confined null
        SessionToken(editor, rev, composing.toString(), sensitiveEditor).also { pending = it }
    }

    /**
     * Shows [result] if [token] is the current pending request for the unchanged composition and
     * [result] contains the raw text within [MAX_LIMIT] entries. In a sensitive editor only the raw
     * text is shown. Never commits. Returns whether the candidates were accepted.
     */
    fun apply(token: SessionToken, result: List<Candidate>): Boolean = confined {
        if (token !== pending || !isFresh(token)) return@confined false
        if (result.isEmpty() || result.size > MAX_LIMIT || result.none { it.surface == token.text }) return@confined false
        pending = null
        shownFor = token
        shown = if (token.sensitive) Collections.singletonList(Candidate(token.text, token.text, 0.0))
        else Collections.unmodifiableList(ArrayList(result))
        true
    }

    /**
     * Explicit user selection of [candidates]`[index]`. Returns the text to commit (head surface, then the
     * candidate) and clears the composition, or null (state unchanged) if [token] is not the shown,
     * still-fresh result or [index] is out of range.
     */
    fun choose(token: SessionToken, index: Int): String? = confined {
        if (token !== shownFor || !isFresh(token) || index !in shown.indices) return@confined null
        val chosen = shown[index].surface
        if (token.sensitive && chosen != token.text) return@confined null
        val text = head.joinToString("") { it.surface } + chosen
        invalidate(clearText = true)
        text
    }

    /**
     * Explicit selection of a [candidate] outside [candidates], e.g. one of the expanded panel's exact words past the
     * capped list. Same rules as [choose]: null, with nothing changed, unless [token] is the shown, still-fresh result.
     */
    fun choose(token: SessionToken, candidate: Candidate): String? = confined {
        if (token !== shownFor || !isFresh(token)) return@confined null
        if (token.sensitive && candidate.surface != token.text) return@confined null
        val text = head.joinToString("") { it.surface } + candidate.surface
        invalidate(clearText = true)
        text
    }

    /**
     * Explicit confirm without a candidate (e.g. Enter): the head surface followed by the raw tail. Returns it and
     * clears, or null if nothing is composed.
     */
    fun commitRaw(): String? = confined {
        if (!active || (composing.isEmpty() && head.isEmpty())) return@confined null
        val raw = head.joinToString("") { it.surface } + composing
        invalidate(clearText = true)
        raw
    }

    override fun toString(): String =
        "InputSession(editorId=$editor, revision=$rev, active=$active, sensitive=$sensitiveEditor, length=${composing.length})"

    private fun isFresh(token: SessionToken): Boolean =
        active && token.editorId == editor && token.revision == rev &&
            token.sensitive == sensitiveEditor && composing.contentEquals(token.text)

    private fun invalidate(clearText: Boolean) {
        rev += 1
        pending = null
        shownFor = null
        shown = emptyList()
        if (clearText) {
            composing.setLength(0)
            head = emptyList()
        }
    }

    /** Moves the last head unit's typed kana into the (empty) tail; its repair is dropped. */
    private fun releaseLastUnit() {
        val last = head.lastOrNull() ?: return
        head = head.dropLast(1)
        composing.append(last.typed)
    }

    private inline fun <T> confined(block: () -> T): T {
        check(Thread.currentThread() === owner) { "InputSession must be used from its creating thread" }
        return block()
    }
}
