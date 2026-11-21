package dev.astra.ime

import android.os.Build
import android.view.inputmethod.EditorInfo
import dev.astra.ime.oss.zenz.ZenzRanking
import java.lang.ref.WeakReference

/**
 * A shadow of the committed text before the cursor, for the zenz judge only, for one field at a time and on the main
 * thread. It is built from [EditorInfo]'s local snapshot and from what this keyboard itself committed; it never asks the
 * field for text, so it cannot wait on the host app. It is what ranking uses when the field's own answer ([HostContext])
 * is missing, late or out of date.
 *
 * What it knows is a stretch of the field by absolute offset, at most [MAX_KNOWN] UTF-16 units, so a cursor moved back
 * finds the text before it instead of the text typed last. Only what precedes the cursor reaches the judge, at most
 * [ZenzRanking.CONTEXT_CHARS] sanitized code points. All of it stays in memory: never stored, logged, learned from or
 * written back (see docs/privacy-and-learning.md). Doubt forgets it, and text that follows is added without inventing
 * what came before.
 *
 * Offsets are checked, the text is not: a host that rewrites text and reports offsets that fit goes unnoticed, and a move
 * cannot be told from an edit that lands the cursor on a known offset. See reports/context-learning-0.1.29.md.
 */
internal class CommittedContext {
    /** Text directly before the cursor, or before the composition while one is open. */
    var text = ""
        private set

    /** Bumped whenever text changes other than by a commit: a conversion ranked with it is out of date. */
    var generation = 0L
        private set

    private var tracking = false
    /** Offset where [text] ends and the cursor belongs with nothing composing, -1 when unknown. */
    private var end = -1
    /** The field from offset [base] on, as far as it is known. With [end] known, [end] lies within it. */
    private val known = StringBuilder()
    private var base = 0
    /** Our span is open and not in [known]: if the cursor leaves it, it stays in the field with an unknown text. */
    private var spanOpen = false
    /** Our delete went out and takes an amount only the editor knows, so what follows the cursor no longer lines up. */
    private var shortening = false
    /** Reports expected from our writes. A caret inside our span stays ours: nothing before the span changed. */
    private val expected = OwnReports(caretInSpan = true)
    /** Snapshot already used. The framework repeats an old EditorInfo on a configuration change. */
    private var seeded = WeakReference<EditorInfo>(null)

    /**
     * A field starts: [info]'s snapshot if it is new and [allowed], and nothing kept from before. Answers whether [info]
     * was repeated, which makes everything in it old.
     */
    fun start(info: EditorInfo, allowed: Boolean): Boolean {
        clear()
        tracking = allowed
        val repeated = seeded.get() === info
        seeded = WeakReference(info)
        if (!allowed || repeated) return repeated
        val cursor = info.initialSelStart
        if (cursor < 0 || cursor != info.initialSelEnd) return false
        end = cursor
        expected.expect(cursor, cursor)
        known.append(seed(info, cursor))
        base = cursor - known.length
        text = before()
        return false
    }

    /** The switch came on in an open field. The field's snapshot is old by now, so only later commits count. */
    fun resume() {
        tracking = true
    }

    /** Field finished or the switch went off: nothing stays and nothing is followed. */
    fun clear() {
        tracking = false
        invalidate()
    }

    /** Text before the cursor is no longer known, for example the keyboard was hidden. Later commits still count. */
    fun invalidate() {
        forget()
        end = -1
        expected.clear()
    }

    /** Forgets everything while offsets stay known: text is there that must not reach the judge, such as a paste. */
    fun forget() {
        if (text.isNotEmpty()) generation++
        text = ""
        known.setLength(0)
        spanOpen = false
        shortening = false
    }

    /** Our arrow key went out. The field is as it was, and the report says where the cursor landed. */
    fun moving() {
        if (!tracking) return
        unplaced()
    }

    /** Our delete key went out. What is before the cursor stands, up to where the report puts it. */
    fun deleting() {
        if (!tracking) return
        if (end < 0 && !shortening) return invalidate()
        unplaced()
        shortening = true
    }

    private fun unplaced() {
        if (text.isNotEmpty()) generation++
        text = ""
        end = -1
        expected.clear()
    }

    /** A composing write of [length] units at [from] succeeded. It adds nothing: only a commit is committed. */
    fun composing(from: Int, length: Int) {
        if (!follow(from)) return
        spanOpen = true
        expect(from + length, from + length, from, from + length)
    }

    /** [committed] was accepted at [from], replacing any composition there. Empty: a composition was cancelled. */
    fun committed(committed: String, from: Int) {
        if (!follow(from)) return
        spanOpen = false
        insert(committed)
        expect(end, end)
    }

    /**
     * A line break was sent as a key. The editor decides what happens, so the text stands only if the report comes back
     * at the next offset, and an editor that sends or clears instead reports something else. Sanitizing removes the break.
     */
    fun newline() {
        if (!tracking) return
        if (end < 0) return invalidate()
        insert("\n")
        expect(end, end)
    }

    /** The editor's onUpdateSelection. A report matching none of our writes means something else moved the cursor or the text. */
    fun report(selStart: Int, selEnd: Int, spanStart: Int, spanEnd: Int) {
        if (!tracking || expected.take(selStart, selEnd, spanStart, spanEnd)) return
        val plain = expected.adopt(selStart, selEnd, spanStart, spanEnd)
        // Offset 0 says nothing precedes it, and is also where a cleared field reports: nothing after it can be trusted.
        if (!plain || spanOpen || selStart == 0) {
            forget()
            end = if (plain) selStart else -1
            return
        }
        moveTo(selStart)
    }

    /** A plain cursor nothing of ours explains. Inside what is known it is a move; beyond that nothing is known about it. */
    private fun moveTo(cursor: Int) {
        if (known.isEmpty() || cursor < base || cursor > base + known.length) {
            forget()
            end = cursor
            return
        }
        if (shortening) known.setLength(cursor - base)
        end = cursor
        val before = before()
        if (before != text) generation++
        text = before
    }

    /** Every write starts at the end of [text], or [text] is not what precedes it. False: nothing to follow. */
    private fun follow(from: Int): Boolean {
        if (!tracking) return false
        if (from < 0) {
            invalidate()
            return false
        }
        if (from != end) {
            forget()
            end = from
        }
        shortening = false
        return true
    }

    /** [added] went in at [end]: what follows it moves along, and the far end goes first when too much is held. */
    private fun insert(added: String) {
        if (known.isEmpty()) base = end
        known.insert(end - base, added)
        end += added.length
        val over = known.length - MAX_KNOWN
        if (over > 0) {
            val after = known.length - (end - base)
            known.setLength(known.length - minOf(over, after))
            val cut = maxOf(0, over - after)
            known.delete(0, cut)
            base += cut
        }
        text = before()
    }

    private fun before() = ZenzRanking.promptContext(known.substring(0, end - base))

    private fun expect(start: Int, end: Int, spanStart: Int = -1, spanEnd: Int = -1) {
        expected.expect(start, end, spanStart, spanEnd)
        // Nothing reported for this long: this host cannot show that the text is still what it was.
        if (expected.waiting > MAX_EXPECTED) invalidate()
    }

    private fun seed(info: EditorInfo, cursor: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val before = try {
            info.getInitialTextBeforeCursor(SEED_CHARS, 0)?.toString()
        } catch (e: RuntimeException) {
            null // the snapshot crossed from another process; a bad one only means no context, not logged
        } ?: return ""
        // Longer than the offset allows: the snapshot and the cursor disagree.
        return if (before.length > cursor) "" else before
    }

    companion object {
        /** UTF-16 units asked of the snapshot: enough for [ZenzRanking.CONTEXT_CHARS] code points after sanitizing. */
        const val SEED_CHARS = 128

        /** Writes in flight before a silent host stops being believed. Same bound as the service's unconfirmed list. */
        const val MAX_EXPECTED = 64

        /** UTF-16 units of the field kept around the cursor, so that a move back can find what precedes it. */
        const val MAX_KNOWN = 512
    }
}
