package dev.sluide.databases.session

import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionWorkerTest {
    private val closed = AtomicBoolean()
    private val worker = ConnectionWorker("test-connection-worker") { closed.get() }

    @AfterEach
    fun endThread() {
        worker.finish {}
    }

    private fun waitUntil(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "never: $message" }
            Thread.onSpinWait()
        }
    }

    @Test
    fun `finish runs after the close`() {
        closed.set(true)
        var ran = false

        worker.finish { ran = true }.await()

        assertTrue(ran)
    }

    @Test
    fun `finish ends the thread, so later work fails as closed although the worker is not closed`() {
        val open = ConnectionWorker("test-connection-worker-open") { false }
        open.finish { }.await()

        val late = open.submit { 1 }

        assertIs<DbSessionClosedException>(assertFailsWith<ExecutionException> { late.await() }.cause)
    }

    @Test
    fun `work that has not started when the session closes fails as closed and does not run`() {
        val hang = CountDownLatch(1)
        val started = CountDownLatch(1)
        var ran = false
        val running =
            worker.submit {
                started.countDown()
                hang.await(10, TimeUnit.SECONDS)
            }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val queued = worker.submit { ran = true }

        closed.set(true)
        hang.countDown()

        running.await()
        assertIs<DbSessionClosedException>(assertFailsWith<ExecutionException> { queued.await() }.cause)
        assertEquals(false, ran)
    }

    @Test
    fun `a future that completed is not unfinished any more, whether it ended in a result, an error or a refusal`() {
        val hang = CountDownLatch(1)
        val started = CountDownLatch(1)
        val running =
            worker.submit {
                started.countDown()
                hang.await(10, TimeUnit.SECONDS)
            }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val queued = worker.submit { 1 }
        assertEquals(2, worker.unfinishedCount)

        hang.countDown()
        running.await()
        queued.await()
        waitUntil("both futures left the unfinished ones") { worker.unfinishedCount == 0 }

        assertFailsWith<ExecutionException> { worker.submit { error("boom") }.await() }
        closed.set(true)
        assertFailsWith<ExecutionException> { worker.submit { 2 }.await() }
        waitUntil("the failed and refused futures left the unfinished ones") { worker.unfinishedCount == 0 }
    }

    @Test
    fun `abandon fails what is still unfinished and drops the late result`() {
        val hang = CountDownLatch(1)
        val started = CountDownLatch(1)
        val running =
            worker.submit {
                started.countDown()
                hang.await(10, TimeUnit.SECONDS)
                "late"
            }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val queued = worker.submit { "queued" }

        worker.abandon()

        assertIs<DbSessionClosedException>(assertFailsWith<ExecutionException> { running.await() }.cause)
        assertIs<DbSessionClosedException>(assertFailsWith<ExecutionException> { queued.await() }.cause)
        assertEquals(0, worker.unfinishedCount)
        hang.countDown()
    }
}
