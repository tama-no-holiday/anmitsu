package dev.astra.ime

import dev.astra.ime.HostContext.Outcome
import dev.astra.ime.oss.zenz.ZenzRanking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The asking alone, with a clock for the quiet period. Real threads and real waits; synthetic text only. */
class HostContextTest {
    private var now = 0L
    private val release = CountDownLatch(1)
    private val host = HostContext(waitNanos = TimeUnit.MILLISECONDS.toNanos(50), clock = { now })

    @After fun tearDown() {
        release.countDown()
        host.close()
    }

    /** A host that answers [text] only once released, as one stuck in its own work would. */
    private fun stuck(text: String, started: CountDownLatch? = null): () -> CharSequence? = {
        started?.countDown()
        release.await(5, TimeUnit.SECONDS)
        text
    }

    private fun slow(): HostContext.Answer {
        val answer = host.ask(stuck("遅れた答え。"))
        assertEquals(Outcome.SLOW, answer.outcome)
        return answer
    }

    @Test fun aFastAnswerIsTheSanitizedTextBeforeTheCursor() {
        val answer = host.ask { "子供と遊ぶ約束をした。" }
        assertEquals(Outcome.TEXT, answer.outcome)
        assertEquals("子供と遊ぶ約束をした。", answer.text)
        val long = "あ".repeat(HostContext.READ_UNITS)
        val bounded = host.ask { long }.text
        assertEquals(ZenzRanking.promptContext(long), bounded)
        assertEquals(ZenzRanking.CONTEXT_CHARS, bounded.codePointCount(0, bounded.length))
        assertEquals("", host.ask { "" }.text)
        assertEquals(Outcome.TEXT, host.ask { "" }.outcome)
    }

    @Test fun nullOrAFailureIsNoAnswerButNotSlow() {
        assertEquals(Outcome.NONE, host.ask { null }.outcome)
        assertEquals(Outcome.NONE, host.ask { throw IllegalStateException("dead connection") }.outcome)
        assertTrue(host.asking)
        assertEquals(Outcome.TEXT, host.ask { "次。" }.outcome)
    }

    @Test fun theWaitEndsAtTheDeadlineAndTheLateAnswerIsDroppedForGood() {
        val begun = System.nanoTime()
        val answer = slow()
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun)
        assertTrue("waited $waited ms", waited in 45..1000)
        assertEquals("", answer.text)
        release.countDown()
        now += TimeUnit.SECONDS.toNanos(HostContext.QUIET_SECONDS)
        host.newField()
        // The late read has finished by now or soon will; the next ask must get its own answer, never that one.
        repeat(100) { if (host.asking) return@repeat; Thread.sleep(10) }
        val next = host.ask { "新しい答え。" }
        assertEquals(Outcome.TEXT, next.outcome)
        assertEquals("新しい答え。", next.text)
    }

    @Test fun whileAReadIsStuckNothingElseStartsAndNoThreadIsAdded() {
        val started = CountDownLatch(1)
        assertEquals(Outcome.SLOW, host.ask(stuck("遅れた答え。", started)).outcome)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        now += TimeUnit.SECONDS.toNanos(HostContext.QUIET_SECONDS)
        host.newField()
        val before = Thread.getAllStackTraces().keys.count { it.name == HostContext.THREAD_NAME }
        repeat(20) {
            var called = false
            assertEquals(Outcome.SKIPPED, host.ask { called = true; "x" }.outcome)
            assertFalse(called)
        }
        assertEquals(1, host.threadsStarted)
        assertEquals(before, Thread.getAllStackTraces().keys.count { it.name == HostContext.THREAD_NAME })
    }

    @Test fun aSlowFieldStaysUnaskedAndEveryFieldIsQuietForThirtySeconds() {
        slow()
        release.countDown()
        repeat(100) { Thread.sleep(10) } // the stuck read ends; only the field and the quiet period hold now
        assertFalse(host.asking) // this field
        host.newField()
        now += TimeUnit.SECONDS.toNanos(HostContext.QUIET_SECONDS) - 1
        assertFalse(host.asking) // another field, still quiet
        assertEquals(Outcome.SKIPPED, host.ask { "x" }.outcome)
        now += 1
        assertTrue(host.asking)
        assertEquals(Outcome.TEXT, host.ask { "x" }.outcome)
    }

    @Test fun theSlowFieldIsNotAskedAgainAfterTheQuietPeriodUntilAnotherFieldStarts() {
        slow()
        release.countDown()
        now += TimeUnit.SECONDS.toNanos(HostContext.QUIET_SECONDS * 10)
        repeat(100) { Thread.sleep(10) }
        assertFalse(host.asking)
        host.newField()
        assertTrue(host.asking)
    }

    @Test fun afterCloseNothingIsAskedAndARunningReadIsDropped() {
        val started = CountDownLatch(1)
        assertEquals(Outcome.SLOW, host.ask(stuck("遅れた答え。", started)).outcome)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        host.close()
        release.countDown()
        now += TimeUnit.SECONDS.toNanos(HostContext.QUIET_SECONDS)
        host.newField()
        assertEquals(Outcome.SKIPPED, host.ask { "x" }.outcome)
        assertEquals(1, host.threadsStarted)
    }

    @Test fun anInterruptedWaitIsNotAnAnswer() {
        Thread.currentThread().interrupt()
        try {
            assertEquals(Outcome.SLOW, host.ask(stuck("遅れた答え。")).outcome)
            assertTrue(Thread.interrupted())
        } finally {
            Thread.interrupted()
        }
    }
}
