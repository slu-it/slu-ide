package dev.sluide.languageservers.connection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The scheduler of the restarts after a crash. */
class SleepingSchedulerTest {
    @Test
    fun `a task runs after its delay, not before`() {
        val ran = CountDownLatch(1)
        val started = System.nanoTime()

        SleepingScheduler.schedule(150.milliseconds) { ran.countDown() }

        assertTrue(ran.await(5, TimeUnit.SECONDS), "the task ran")
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 150, "not before its delay")
    }

    @Test
    fun `a negative delay runs the task at once`() {
        val ran = CountDownLatch(1)

        SleepingScheduler.schedule((-5).seconds) { ran.countDown() }

        assertTrue(ran.await(5, TimeUnit.SECONDS), "the task ran")
    }

    @Test
    fun `closing the result before the delay is over cancels the task`() {
        val ran = CountDownLatch(1)
        val later = CountDownLatch(1)
        SleepingScheduler.schedule(400.milliseconds) { ran.countDown() }.close()
        SleepingScheduler.schedule(600.milliseconds) { later.countDown() }

        // A cancelled task has no signal; a later one, due after the cancelled one would have run, is the clock.
        assertTrue(later.await(5, TimeUnit.SECONDS))
        assertEquals(1, ran.count)
        assertFalse(ran.await(0, TimeUnit.MILLISECONDS))
    }
}
