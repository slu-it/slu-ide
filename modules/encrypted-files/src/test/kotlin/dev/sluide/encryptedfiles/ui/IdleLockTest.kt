package dev.sluide.encryptedfiles.ui

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A wall clock ([now], milliseconds) and a scheduler the test drives: [advance] moves both and runs what became due;
 * [sleep] moves only the wall clock, as system sleep does, during which the timers stand still.
 */
internal class FakeIdleTime {
    var now = 0L
        private set
    private var running = 0L
    private val tasks = mutableListOf<Task>()

    val scheduler: IdleScheduler = { delay, run ->
        val task = Task(running + delay.toMillis(), run)
        tasks += task
        Pending { tasks -= task }
    }

    val pending: Int get() = tasks.size

    /** Runs the tasks that become due in [by], each at its time. */
    fun advance(by: Duration) {
        val end = running + by.toMillis()
        while (true) {
            val due = tasks.filter { it.at <= end }.minByOrNull { it.at } ?: break
            tasks -= due
            moveTo(maxOf(due.at, running))
            due.run.run()
        }
        moveTo(end)
    }

    private fun moveTo(at: Long) {
        now += at - running
        running = at
    }

    fun sleep(by: Duration) {
        now += by.toMillis()
    }

    class Task(
        val at: Long,
        val run: Runnable,
    )
}

class IdleLockTest {
    private val time = FakeIdleTime()
    private var minutes = 2
    private var fired = 0
    private val lock = IdleLock({ minutes }, { fired++ }, Duration.ofMinutes(1), { time.now }, time.scheduler)

    private fun minutes(n: Long) = Duration.ofMinutes(n)

    @Test
    fun `fires after the minutes without activity, then counts a full period again`() {
        lock.start()
        time.advance(minutes(1))
        assertEquals(0, fired)
        time.advance(minutes(1))
        assertEquals(1, fired)
        time.advance(minutes(2))
        assertEquals(2, fired)
    }

    @Test
    fun `activity moves the deadline`() {
        lock.start()
        time.advance(minutes(1))
        lock.activity()
        time.advance(minutes(1))
        assertEquals(0, fired)
        time.advance(minutes(1))
        assertEquals(1, fired)
    }

    @Test
    fun `zero is off`() {
        minutes = 0
        lock.start()
        assertFalse(lock.armed)
        time.advance(minutes(1000))
        assertEquals(0, fired)
    }

    @Test
    fun `a changed setting counts live from the last activity, and 0 stops the timer`() {
        lock.start()
        time.advance(minutes(1))
        minutes = 5
        lock.settingChanged()
        time.advance(minutes(3))
        assertEquals(0, fired)
        time.advance(minutes(1))
        assertEquals(1, fired)

        minutes = 0
        lock.settingChanged()
        assertFalse(lock.armed)
        minutes = 1
        lock.settingChanged()
        assertTrue(lock.armed)
        time.advance(minutes(1))
        assertEquals(2, fired)
    }

    @Test
    fun `a lowered setting fires at once when the idle time already passed`() {
        minutes = 10
        lock.start()
        time.advance(minutes(3))
        minutes = 2
        lock.settingChanged()
        time.advance(Duration.ZERO)
        assertEquals(1, fired)
    }

    @Test
    fun `stop cancels the timer and ignores activity until the next start`() {
        lock.start()
        lock.stop()
        assertEquals(0, time.pending)
        lock.activity()
        time.advance(minutes(5))
        assertEquals(0, fired)

        lock.start()
        time.advance(minutes(2))
        assertEquals(1, fired)
    }

    @Test
    fun `time the computer slept counts, so the next tick fires, and so does activity or a check`() {
        lock.start()
        time.advance(Duration.ofSeconds(10))
        time.sleep(minutes(5))
        assertEquals(0, fired)

        // The timers stood still during the sleep; the next one is at most 15 s away.
        time.advance(Duration.ofSeconds(5))
        assertEquals(1, fired)

        time.sleep(minutes(3))
        lock.activity()
        assertEquals(2, fired)

        time.sleep(minutes(3))
        lock.check()
        assertEquals(3, fired)
        lock.check()
        assertEquals(3, fired, "a full period counts again after firing")
    }

    @Test
    fun `activity before the period ends only moves the deadline, and a clock set back counts from its new time`() {
        lock.start()
        time.advance(minutes(1))
        lock.activity()
        lock.check()
        assertEquals(0, fired)

        // Set back between two ticks: the next tick, 15 s later, counts from the clock's new time.
        time.sleep(minutes(-10))
        time.advance(minutes(2))
        assertEquals(0, fired)
        time.advance(minutes(1))
        assertEquals(1, fired)
    }

    @Test
    fun `stopping inside the callback leaves no timer`() {
        var holder: IdleLock? = null
        val self = IdleLock({ 1 }, { holder?.stop() }, Duration.ofMinutes(1), { time.now }, time.scheduler)
        holder = self
        self.start()
        time.advance(minutes(1))
        assertEquals(0, time.pending)
    }
}
