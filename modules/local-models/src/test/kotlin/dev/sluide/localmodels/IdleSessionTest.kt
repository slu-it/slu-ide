package dev.sluide.localmodels

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class IdleSessionTest {
    private class Model(
        val failing: Boolean,
    ) : AutoCloseable {
        @Volatile var closed = false

        override fun close() {
            closed = true
            if (failing) throw IllegalStateException("cannot close")
        }
    }

    private val models = CopyOnWriteArrayList<Model>()
    private val sessions = CopyOnWriteArrayList<IdleSession<Model>>()
    private val pool = Executors.newCachedThreadPool()

    private fun session(
        idle: Duration = 1.minutes,
        failing: Boolean = false,
        scheduler: ScheduledExecutorService? = null,
    ): IdleSession<Model> {
        val load = { Model(failing).also { models += it } }
        val session =
            if (scheduler ==
                null
            ) {
                IdleSession("test-idle", idle, load)
            } else {
                IdleSession("test-idle", idle, load, scheduler)
            }
        return session.also { sessions += it }
    }

    @AfterEach
    fun tearDown() {
        // Bounded: a use that never released the lock would make close() wait forever.
        try {
            pool.submit { sessions.forEach { it.close() } }.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun awaitClosed(model: Model) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!model.closed && System.nanoTime() < deadline) Thread.sleep(10)
    }

    @Test
    fun `the value loads on the first use and is kept for the next`() {
        val session = session()
        assertFalse(session.loaded())
        assertTrue(models.isEmpty())

        val first = session.use { it }
        val second = session.use { it }

        assertTrue(session.loaded())
        assertEquals(1, models.size)
        assertTrue(first === second)
        assertFalse(first.closed)
    }

    @Test
    fun `uses run one after the other`() {
        val session = session()
        val running = AtomicInteger()
        val most = AtomicInteger()
        val futures =
            List(4) {
                pool.submit {
                    session.use {
                        most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        Thread.sleep(5) // keeps the use open so that an unserialized second one would overlap
                        running.decrementAndGet()
                    }
                }
            }
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        assertEquals(1, most.get())
        assertEquals(1, models.size)
    }

    @Test
    fun `the value is closed after the idle time and loaded again on demand`() {
        val session = session(idle = 50.milliseconds)
        session.use {}
        awaitClosed(models[0])
        assertTrue(models[0].closed)
        assertFalse(session.loaded())

        session.use {}
        assertEquals(2, models.size)
    }

    @Test
    fun `close closes the value and refuses later uses without loading`() {
        val session = session()
        session.use {}
        session.close()
        assertTrue(models[0].closed)

        assertFailsWith<IllegalStateException> { session.use {} }
        assertEquals(1, models.size)
    }

    @ParameterizedTest(name = "closing the value fails: {0}")
    @ValueSource(booleans = [false, true])
    fun `close during a use waits for it and closes the value after it, also when closing the value fails`(
        failing: Boolean,
    ) {
        // close() shuts the scheduler down before it waits for the lock: the sign that it has started.
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val session = session(failing = failing, scheduler = scheduler)
        val inUse = CountDownLatch(1)
        val release = CountDownLatch(1)
        val openAtTheEnd = AtomicReference<Boolean>()
        val use =
            pool.submit {
                session.use { model ->
                    inUse.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    openAtTheEnd.set(!model.closed)
                }
            }
        assertTrue(inUse.await(10, TimeUnit.SECONDS))
        val close = pool.submit { session.close() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!scheduler.isShutdown && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(scheduler.isShutdown)
        // No signal for "close() is blocked on the lock": give a close() that would not wait the time to finish.
        Thread.sleep(50)

        assertFalse(close.isDone, "close waits for the use")
        release.countDown()
        use.get(10, TimeUnit.SECONDS)
        close.get(10, TimeUnit.SECONDS)

        assertEquals(true, openAtTheEnd.get(), "the value stays open while it is in use")
        assertTrue(models.single().closed)
        assertFailsWith<IllegalStateException> { session.use {} }
    }

    @Test
    fun `a use that waits can be interrupted`() {
        val session = session()
        val inUse = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            pool.submit {
                session.use {
                    inUse.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        assertTrue(inUse.await(10, TimeUnit.SECONDS))
        val thrown = AtomicReference<Throwable>()
        val waiting = Thread { thrown.set(runCatching { session.use {} }.exceptionOrNull()) }
        waiting.start()

        waiting.interrupt()
        waiting.join(10_000)

        assertIs<InterruptedException>(thrown.get())
        release.countDown()
        holder.get(10, TimeUnit.SECONDS)
    }
}
