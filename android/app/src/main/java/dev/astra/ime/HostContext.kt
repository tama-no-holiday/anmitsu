package dev.astra.ime

import dev.astra.ime.oss.zenz.ZenzRanking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Asks the field for the text before the cursor on a reading thread while the main thread waits at most [waitNanos]
 * (100 ms as approved: well over a 16 ms frame, so a slow field still stalls the first key once). Main thread only, except
 * the read itself.
 *
 * The answer is sanitized to [ZenzRanking.promptContext] on the reading thread and handed over once; nothing else is
 * kept. A read that misses the deadline is abandoned for good: when it finishes, its text is dropped, never stored and
 * never given to a later request. It is not cancelled, because nothing would stop it: the framework's remote connection
 * waits up to 2 s for the editor and waits again after an interrupt (reports/context-read-0.1.30.md). So there is a bound
 * instead: at most one read at a time, each on its own daemon thread, and no queue. While a read is still running every
 * new ask is refused without starting a thread, and a slow read turns asking off for this field and for every field for
 * [quietNanos].
 *
 * The deadline is taken on the monotonic clock before the thread is started, so starting it counts against the 100 ms.
 * Lifetimes, all in memory: the field switch-off lasts until [newField]; the quiet period until it runs out on [clock]
 * or the service is destroyed ([close]). Neither is stored.
 */
internal class HostContext(
    private val waitNanos: Long = TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS),
    private val quietNanos: Long = TimeUnit.SECONDS.toNanos(QUIET_SECONDS),
    private val clock: () -> Long = System::nanoTime,
) {
    enum class Outcome {
        /** The field answered in time; [Answer.text] is what precedes the cursor, possibly empty. */
        TEXT,
        /** The connection gave nothing (null) or failed: no answer, but not slow. */
        NONE,
        /** Not asked: off for this field, quiet, a read still running, or closed. */
        SKIPPED,
        /** Not answered in time: asking is now off for this field and quiet for every field. */
        SLOW,
    }

    class Answer(val outcome: Outcome, val text: String = "")

    private var running: Request? = null
    private var fieldOff = false
    private var quietSince = 0L
    private var quiet = false
    private var closed = false

    /** Reading threads started so far, for tests: it grows by one per read, never while one is running. */
    var threadsStarted = 0
        private set

    /** A different field started: it gets its own chance. A quiet period still holds. */
    fun newField() {
        fieldOff = false
    }

    /** Whether [ask] would start a read now. */
    val asking: Boolean get() = !closed && !fieldOff && !quietNow() && running?.finished != false

    /** Runs [read] on a reading thread and waits for it until [waitNanos] after the call. */
    fun ask(read: () -> CharSequence?): Answer {
        val deadline = System.nanoTime() + waitNanos
        if (!asking) return Answer(Outcome.SKIPPED)
        val request = Request(read)
        running = request
        threadsStarted++
        Thread(request, THREAD_NAME).apply { isDaemon = true }.start()
        val answered = try {
            request.done.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!answered) {
            request.abandon()
            fieldOff = true
            quiet = true
            quietSince = clock()
            return Answer(Outcome.SLOW)
        }
        val text = request.take() ?: return Answer(Outcome.NONE)
        return Answer(Outcome.TEXT, text)
    }

    /** The service is going away: no more asks. A read still running ends on its own and its answer is dropped. */
    fun close() {
        closed = true
        running?.abandon()
        running = null
    }

    private fun quietNow(): Boolean {
        if (quiet && clock() - quietSince >= quietNanos) quiet = false
        return quiet
    }

    /** One read. Holds the connection only until the read starts and the text only until it is taken or dropped. */
    private class Request(read: () -> CharSequence?) : Runnable {
        val done = CountDownLatch(1)
        @Volatile var finished = false
            private set
        private var read: (() -> CharSequence?)? = read
        private var text: String? = null
        private var abandoned = false

        override fun run() {
            try {
                val r = synchronized(this) { read.also { read = null } } ?: return
                val answer = try {
                    r()?.let { ZenzRanking.promptContext(it.toString()) }
                } catch (e: RuntimeException) {
                    null // a failed connection only means no answer; nothing is logged
                }
                synchronized(this) { if (!abandoned) text = answer }
            } finally {
                finished = true
                done.countDown()
            }
        }

        fun abandon() = synchronized(this) {
            abandoned = true
            read = null
            text = null
        }

        fun take(): String? = synchronized(this) { text.also { text = null } }
    }

    companion object {
        const val WAIT_MILLIS = 100L
        const val QUIET_SECONDS = 30L
        /** UTF-16 units asked for: enough for [ZenzRanking.CONTEXT_CHARS] code points after sanitizing. */
        const val READ_UNITS = 128
        const val THREAD_NAME = "astra-host-context"
    }
}
