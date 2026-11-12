package dev.sluide.codereview

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewCancellationTest {
    @Test
    fun `cancel runs the registered actions once`() {
        val cancellation = ReviewCancellation()
        val runs = AtomicInteger()
        cancellation.onCancel { runs.incrementAndGet() }
        assertFalse(cancellation.isCancelled)
        assertEquals(0, runs.get())

        cancellation.cancel()
        cancellation.cancel()

        assertTrue(cancellation.isCancelled)
        assertEquals(1, runs.get())
    }

    @Test
    fun `an action registered after cancel runs at once`() {
        val cancellation = ReviewCancellation()
        cancellation.cancel()
        val runs = AtomicInteger()

        cancellation.onCancel { runs.incrementAndGet() }

        assertEquals(1, runs.get())
    }

    @Test
    fun `a failing action does not keep the others from running and is thrown`() {
        val cancellation = ReviewCancellation()
        val runs = AtomicInteger()
        cancellation.onCancel { error("broken") }
        cancellation.onCancel { runs.incrementAndGet() }

        assertFailsWith<IllegalStateException> { cancellation.cancel() }

        assertEquals(1, runs.get())
    }

    @Test
    fun `actions registered while another thread cancels run exactly once`() {
        repeat(20) {
            val cancellation = ReviewCancellation()
            val runs = List(200) { AtomicInteger() }
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(4)
            try {
                runs.chunked(50).forEach { chunk ->
                    executor.execute {
                        start.await()
                        chunk.forEach { counter -> cancellation.onCancel { counter.incrementAndGet() } }
                    }
                }
                executor.execute {
                    start.await()
                    cancellation.cancel()
                }
                start.countDown()
            } finally {
                executor.shutdown()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
            assertEquals(List(200) { 1 }, runs.map { it.get() })
        }
    }
}
