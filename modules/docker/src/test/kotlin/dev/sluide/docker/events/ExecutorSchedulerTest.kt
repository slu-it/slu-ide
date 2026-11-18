package dev.sluide.docker.events

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration.ofSeconds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class ExecutorSchedulerTest {
    private val executor = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val scheduler = ExecutorScheduler(executor)

    @AfterEach
    fun shutDown() {
        executor.shutdownNow()
    }

    @Test
    fun `a task runs after its delay`() {
        val ran = CountDownLatch(1)

        scheduler.schedule(10.milliseconds) { ran.countDown() }

        assertTimeoutPreemptively(ofSeconds(30)) { ran.await() }
    }

    @Test
    fun `the delay is taken in milliseconds and closing the result cancels the task`() {
        val ran = CountDownLatch(1)

        val handle = scheduler.schedule(1.hours) { ran.countDown() }

        val queued = executor.queue.single() as ScheduledFuture<*>
        assertTrue(queued.getDelay(TimeUnit.MILLISECONDS) > 59 * 60 * 1000L, "an hour, not a moment")
        handle.close()
        assertEquals(0, executor.queue.size, "the task is gone from the queue")
        assertEquals(1, ran.count)
    }
}
