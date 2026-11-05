package dev.sluide.translation.opusmt

import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class PairSessionsTest {
    private class Loaded(
        val model: PairModel,
        val failsToClose: Boolean,
    ) : AutoCloseable {
        @Volatile var closed = false

        override fun close() {
            closed = true
            if (failsToClose) throw IllegalStateException("cannot close")
        }
    }

    private val a = PairModel("de", "en", Quality.MEDIUM)
    private val b = PairModel("en", "sv", Quality.MEDIUM)
    private val c = PairModel("de", "en", Quality.HIGH)

    private val opened = CopyOnWriteArrayList<Loaded>()
    private var failing: PairModel? = null
    private var failingToClose = emptySet<PairModel>()
    private val pools = CopyOnWriteArrayList<PairSessions<Loaded>>()

    private fun sessions(idle: Duration = 1.minutes) =
        PairSessions(idle) { model ->
            if (model == failing) throw IllegalArgumentException("broken")
            Loaded(model, model in failingToClose).also { opened += it }
        }.also { pools += it }

    @AfterEach
    fun tearDown() {
        pools.forEach { it.close() }
    }

    private fun PairSessions<Loaded>.load(model: PairModel): Loaded = use(model) { it }

    @Test
    fun `a third model unloads the least recently used one, which loads again when used`() {
        val sessions = sessions()
        val first = sessions.load(a)
        sessions.load(b)
        sessions.load(c)
        assertTrue(first.closed)
        assertEquals(listOf(b, c), sessions.loaded())

        val again = sessions.load(a)
        assertNotSame(first, again)
        assertEquals(listOf(c, a), sessions.loaded())
        assertTrue(opened.single { it.model == b }.closed)
    }

    @Test
    fun `using a model makes it the most recent one`() {
        val sessions = sessions()
        val first = sessions.load(a)
        val second = sessions.load(b)
        sessions.load(a)
        sessions.load(c)
        assertFalse(first.closed)
        assertTrue(second.closed)
        assertEquals(listOf(a, c), sessions.loaded())
    }

    @Test
    fun `a model in use is unloaded only after its call, and the evicting call waits for it`() {
        val sessions = sessions()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closedDuringCall = AtomicReference<Boolean>()
        val running =
            Thread {
                sessions.use(a) { loaded ->
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    closedDuringCall.set(loaded.closed)
                }
            }.apply { start() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        sessions.load(b)
        val evicting = Thread { sessions.load(c) }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (evicting.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
        assertEquals(Thread.State.WAITING, evicting.state)

        release.countDown()
        running.join(10_000)
        evicting.join(10_000)
        assertEquals(false, closedDuringCall.get())
        assertTrue(opened.first { it.model == a }.closed)
        assertEquals(listOf(b, c), sessions.loaded())
    }

    @Test
    fun `an idle model is unloaded`() {
        val sessions = sessions(idle = 50.milliseconds)
        val loaded = sessions.load(a)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!loaded.closed && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(loaded.closed)
        assertEquals(emptyList(), sessions.loaded())
    }

    @Test
    fun `a model that fails to load is not counted and is tried again`() {
        val sessions = sessions()
        failing = a
        assertFailsWith<IllegalArgumentException> { sessions.load(a) }
        assertEquals(emptyList(), sessions.loaded())
        failing = null
        sessions.load(a)
        assertEquals(listOf(a), sessions.loaded())
    }

    @Test
    fun `close unloads every model and refuses later use`() {
        val sessions = sessions()
        val first = sessions.load(a)
        val second = sessions.load(b)
        sessions.close()
        assertTrue(first.closed && second.closed)
        assertFailsWith<IllegalStateException> { sessions.load(a) }
        assertFailsWith<IllegalStateException> { sessions.load(c) }
    }

    @Test
    fun `a model that fails to close neither breaks the evicting call nor stops close`() {
        failingToClose = setOf(a, b)
        val sessions = sessions()
        val first = sessions.load(a)
        val second = sessions.load(b)
        val third = sessions.load(c)
        assertTrue(first.closed)
        assertEquals(listOf(b, c), sessions.loaded())

        sessions.close()
        assertTrue(second.closed && third.closed)
    }
}
