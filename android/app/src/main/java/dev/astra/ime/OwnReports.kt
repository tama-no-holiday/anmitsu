package dev.astra.ime

/**
 * The editor reports our own writes should cause, oldest first, so that a late one can be told from something else
 * moving the text. Offsets only, in memory, main thread only: no text of the field is held or asked for.
 *
 * A report carries nothing but offsets, so this can only say that a report fits a write, not that it came from one. See
 * reports/context-shadow-0.1.28.md for what that leaves open.
 *
 * @param caretInSpan a caret moved inside a span we wrote also counts as that write. For text before the span, which the
 *   caret does not touch; a composition has to see such a report as a tap.
 */
internal class OwnReports(private val caretInSpan: Boolean = false) {
    /** A span of -1 means nothing composing. */
    private class Report(val start: Int, val end: Int, val spanStart: Int, val spanEnd: Int) {
        fun exactly(start: Int, end: Int, spanStart: Int, spanEnd: Int): Boolean =
            start == this.start && end == this.end && spanStart == this.spanStart && spanEnd == this.spanEnd

        fun caretInside(start: Int, end: Int, spanStart: Int, spanEnd: Int): Boolean =
            this.spanStart >= 0 && spanStart == this.spanStart && spanEnd == this.spanEnd && start >= spanStart && end <= spanEnd
    }

    private val reports = ArrayDeque<Report>()
    /** Index of the last entry reported, which stays since reports can repeat. Those before it were passed over. */
    private var reported = 0
    private var lastStart = -1
    private var lastEnd = -1

    /** Entries the editor has not gone past. */
    val waiting: Int get() = reports.size - reported

    /** A newer write of ours is still to be reported, so offsets reported now are already out of date. */
    val writesAhead: Boolean get() = reported < reports.lastIndex

    fun expect(start: Int, end: Int, spanStart: Int = -1, spanEnd: Int = -1) {
        reports += Report(start, end, spanStart, spanEnd)
        if (reports.size > MAX_KEPT) {
            reports.removeFirst()
            if (reported > 0) reported--
        }
    }

    /** A write whose offset only the editor knows, such as a key event. Nothing matches it, but it is newer than the rest. */
    fun expectUnknown() = expect(UNKNOWN, UNKNOWN)

    /** The field starts with its cursor here, so that the first report can be seen to continue from it. */
    fun startAt(selStart: Int, selEnd: Int) {
        clear()
        lastStart = selStart
        lastEnd = selEnd
    }

    /**
     * Whether a report's old offsets are where the last report ended, as they are for anything happening in order. One
     * that does not continue either came out of order or skipped something, and is not trusted as late.
     */
    fun continues(oldStart: Int, oldEnd: Int): Boolean = lastStart < 0 || (oldStart == lastStart && oldEnd == lastEnd)

    /** Whether this report fits a write the editor has not gone past. Earlier entries are then passed over. */
    fun take(selStart: Int, selEnd: Int, spanStart: Int, spanEnd: Int): Boolean {
        lastStart = selStart
        lastEnd = selEnd
        val low = minOf(selStart, selEnd)
        val high = maxOf(selStart, selEnd)
        val composing = spanStart >= 0 && spanEnd > spanStart
        val from = if (composing) spanStart else -1
        val to = if (composing) spanEnd else -1
        for (i in reported..reports.lastIndex) {
            val report = reports[i]
            if (!report.exactly(low, high, from, to) && !(caretInSpan && report.caretInside(low, high, from, to))) continue
            reported = i
            while (reported > MAX_OVERTAKEN) {
                reports.removeFirst()
                reported--
            }
            return true
        }
        return false
    }

    /**
     * Whether a report that does not [continues] fits a write already passed over: it was delivered after a newer report.
     * A cursor really moved back to such an offset continues from the last report, so it is not taken for this. On a
     * host whose old offsets do not continue, it would be, for the last [MAX_OVERTAKEN] writes.
     */
    fun overtaken(selStart: Int, selEnd: Int, spanStart: Int, spanEnd: Int): Boolean {
        val low = minOf(selStart, selEnd)
        val high = maxOf(selStart, selEnd)
        val composing = spanStart >= 0 && spanEnd > spanStart
        return (0 until reported).any { reports[it].exactly(low, high, if (composing) spanStart else -1, if (composing) spanEnd else -1) }
    }

    /** No report of ours explains what happened. A plain cursor is where the next write continues from: true then. */
    fun adopt(selStart: Int, selEnd: Int, spanStart: Int, spanEnd: Int): Boolean {
        clear()
        if (selStart < 0 || selStart != selEnd || (spanStart >= 0 && spanEnd > spanStart)) return false
        expect(selStart, selEnd)
        return true
    }

    fun clear() {
        reports.clear()
        reported = 0
    }

    private companion object {
        const val UNKNOWN = -2

        /** Passed-over entries kept for reports delivered out of order. Few, since each is one more offset excused. */
        const val MAX_OVERTAKEN = 8

        /** A host that never reports must not grow this. Above what [CommittedContext] tolerates before giving up. */
        const val MAX_KEPT = 2 * CommittedContext.MAX_EXPECTED
    }
}
