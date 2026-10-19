package dev.sluide.backgroundtasks

import java.lang.System.Logger.Level
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackgroundTasksTest {
    private data class Line(
        val level: Level,
        val message: String,
        val error: Throwable?,
    )

    private class Timer(
        val delay: Duration,
        val due: Long,
        val action: Runnable,
    ) {
        var cancelled = false
    }

    private val lines = CopyOnWriteArrayList<Line>()
    private val fx = ConcurrentLinkedQueue<Runnable>()
    private val timers = mutableListOf<Timer>()
    private var now = 0L

    private val tasks =
        BackgroundTasks(
            dispatch = { fx += it },
            clock = { now },
            later = { delay, action ->
                val timer = Timer(delay, now + delay.toNanos(), action).also { timers += it }
                Pending { timer.cancelled = true }
            },
            delay = Duration.ofMillis(500),
        ).apply { report = { level, message, error -> lines += Line(level, message, error) } }

    /** Runs what was dispatched to the FX thread. */
    private fun runFx() {
        while (true) fx.poll()?.run() ?: break
    }

    /** Lets [millis] pass and runs the timers that are due, as the FX thread would. */
    private fun advance(millis: Long) {
        now += millis * 1_000_000
        runFx()
        timers.filter { !it.cancelled && it.due <= now }.forEach {
            timers -= it
            it.action.run()
        }
        runFx()
    }

    private fun messages() = lines.map { it.message }

    @Test
    fun `logs start and finish with the duration and numbers the tasks`() {
        val first = tasks.start("Fetching origin…")
        val second = tasks.start("Refreshing Git status")
        now += 1_234_000_000
        first.close()
        now += 11_100_000_000
        second.close()

        assertEquals(second.number, first.number + 1)
        assertEquals(
            listOf(
                "task#${first.number} started: Fetching origin…",
                "task#${second.number} started: Refreshing Git status",
                "task#${first.number} finished in 1.23 s: Fetching origin…",
                "task#${second.number} finished in 12.3 s: Refreshing Git status",
            ),
            messages(),
        )
        assertTrue(lines.all { it.level == Level.INFO && it.error == null })
    }

    @Test
    fun `a finish with a detail is its one end line`() {
        val task = tasks.start("GET example.com")
        advance(1230)
        task.close("200, 12 bytes")
        task.close("again")
        task.close()
        task.failed("late")
        runFx()

        assertEquals(
            listOf(
                "task#${task.number} started: GET example.com",
                "task#${task.number} finished in 1.23 s: GET example.com (200, 12 bytes)",
            ),
            messages(),
        )
        assertTrue(lines.all { it.level == Level.INFO })
        assertTrue(tasks.shown.isEmpty())
    }

    @Test
    fun `formats durations with three significant digits`() {
        assertEquals("0.123 s", seconds(Duration.ofMillis(123)))
        assertEquals("1.23 s", seconds(Duration.ofMillis(1234)))
        assertEquals("12.3 s", seconds(Duration.ofMillis(12_340)))
        assertEquals("123 s", seconds(Duration.ofMillis(123_400)))
    }

    @Test
    fun `a task is shown only after the delay`() {
        val task = tasks.start("Loading Git log")
        runFx()
        assertTrue(tasks.shown.isEmpty())
        assertEquals(Duration.ofMillis(500), timers.single().delay)

        advance(499)
        assertTrue(tasks.shown.isEmpty())
        advance(1)
        assertEquals(listOf(task), tasks.shown)
    }

    @Test
    fun `the delay counts from the start, not from when the FX thread gets to it`() {
        tasks.start("Loading Git log")
        now += 200_000_000
        runFx()
        assertEquals(Duration.ofMillis(300), timers.single().delay)

        val late = tasks.start("Late")
        now += 600_000_000
        runFx()
        assertEquals(listOf(late), tasks.shown, "shown at once, the delay has passed")
    }

    @Test
    fun `a task that ends before the delay is never shown`() {
        val task = tasks.start("Quick")
        runFx()
        task.close()
        runFx()
        assertTrue(timers.single().cancelled)
        advance(1000)
        assertTrue(tasks.shown.isEmpty())
    }

    @Test
    fun `a task closed before the FX thread schedules it is never shown`() {
        tasks.start("Quick").close()
        advance(1000)
        assertTrue(timers.isEmpty())
        assertTrue(tasks.shown.isEmpty())
    }

    @Test
    fun `a shown task leaves the list when it ends`() {
        val task = tasks.start("Long")
        advance(500)
        task.close()
        assertEquals(listOf(task), tasks.shown, "only on the FX thread")
        runFx()
        assertTrue(tasks.shown.isEmpty())
    }

    @Test
    fun `shown tasks are oldest first`() {
        val first = tasks.start("First")
        runFx()
        now += 100_000_000
        val second = tasks.start("Second")
        runFx()
        // The second one's timer runs first, as if the FX thread was late with the first one.
        timers[1].action.run()
        timers[0].action.run()
        assertEquals(listOf(first, second), tasks.shown)
    }

    @Test
    fun `rename logs only real changes and updates the name on the FX thread`() {
        val task = tasks.start("Checking out main…")
        task.rename("Checking out main…")
        task.rename("Updating files…")
        assertEquals("Checking out main…", task.name.get(), "not before the FX thread")
        runFx()
        assertEquals("Updating files…", task.name.get())
        now += 2_000_000_000
        task.close()
        task.rename("After")
        runFx()

        assertEquals(
            listOf(
                "task#${task.number} started: Checking out main…",
                "task#${task.number}: Checking out main… → Updating files…",
                "task#${task.number} finished in 2.00 s: Updating files…",
            ),
            messages(),
        )
        assertEquals("Updating files…", task.name.get())
    }

    @Test
    fun `cancel calls the function once and only if the task is cancellable`() {
        var calls = 0
        val cancellable = tasks.start("Pushing to origin…") { calls++ }
        val plain = tasks.start("Committing…")
        assertTrue(cancellable.cancellable)
        assertTrue(!plain.cancellable)

        cancellable.cancel()
        cancellable.cancel()
        plain.cancel()

        assertEquals(1, calls)
        assertEquals(1, messages().count { it == "task#${cancellable.number} cancelled" })
        assertTrue(messages().none { it == "task#${plain.number} cancelled" })
    }

    @Test
    fun `an ended task is not cancelled`() {
        var calls = 0
        val task = tasks.start("Fetching origin…") { calls++ }
        task.close()
        task.cancel()
        assertEquals(0, calls)
    }

    @Test
    fun `a failure with a message is a warning and ends the task`() {
        val task = tasks.start("Pushing to origin…")
        now += 1_500_000_000
        task.failed("rejected")
        task.close()

        val end = lines.drop(1).single()
        assertEquals(Level.WARNING, end.level)
        assertEquals("task#${task.number} failed after 1.50 s: Pushing to origin…: rejected", end.message)
        assertNull(end.error)
    }

    @Test
    fun `a failure with an exception is an error with it`() {
        val boom = IllegalStateException("boom")
        val task = tasks.start("Loading Git log")
        now += 50_000_000
        task.failed(boom)
        task.failed("again")
        task.close()

        val end = lines.drop(1).single()
        assertEquals(Level.ERROR, end.level)
        assertEquals("task#${task.number} failed after 0.050 s: Loading Git log", end.message)
        assertSame(boom, end.error)
    }

    @Test
    fun `closing twice logs one end line`() {
        val task = tasks.start("Exporting a.md as HTML")
        task.close()
        task.close()
        assertEquals(2, lines.size)
    }

    @Test
    fun `track runs the work as a task`() {
        val result = tasks.track("Refreshing Git status") { "done" }
        assertEquals("done", result)
        assertEquals(2, lines.size)
        assertTrue(lines[1].message.contains("finished in"))
    }

    @Test
    fun `track reports and rethrows a failure`() {
        val boom = IllegalStateException("boom")
        val thrown = assertFailsWith<IllegalStateException> { tasks.track("Loading Git log") { throw boom } }
        assertSame(boom, thrown)
        assertEquals(Level.ERROR, lines[1].level)
        assertSame(boom, lines[1].error)
        assertEquals(2, lines.size)
    }

    @Test
    fun `a cancelled task that fails ends as cancelled`() {
        var calls = 0
        val byMessage = tasks.start("Fetching origin…") { calls++ }
        val byError = tasks.start("Pushing to origin…") { calls++ }
        now += 2_000_000_000
        byMessage.cancel()
        byError.cancel()
        byMessage.failed("stopped")
        byError.failed(IllegalStateException("exit 143"))
        byError.close()

        assertEquals(2, calls)
        assertEquals(
            listOf(
                "task#${byMessage.number} cancelled after 2.00 s: Fetching origin…",
                "task#${byError.number} cancelled after 2.00 s: Pushing to origin…",
            ),
            messages().filter { "after" in it },
        )
        assertTrue(lines.all { it.level == Level.INFO && it.error == null })
    }

    @Test
    fun `track ends a cancellation or an interruption as cancelled and rethrows it`() {
        val cancellation = CancellationException("stopped")
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> { tasks.track("Loading Git log") { throw cancellation } },
        )
        val interruption = InterruptedException()
        assertSame(
            interruption,
            assertFailsWith<InterruptedException> { tasks.track("Searching Git log for main") { throw interruption } },
        )

        assertEquals(4, lines.size)
        assertTrue(lines[1].message.matches(Regex("""task#\d+ cancelled after 0.000 s: Loading Git log""")))
        assertTrue(lines[3].message.matches(Regex("""task#\d+ cancelled after 0.000 s: Searching Git log for main""")))
        assertTrue(lines.all { it.level == Level.INFO && it.error == null })
    }

    @Test
    fun `the end line is the last line of a task, also with renames and cancels racing it`() {
        repeat(200) {
            val task = tasks.start("Task") {}
            val renamer = Thread { repeat(50) { i -> task.rename("Task $i") } }
            val canceller = Thread { task.cancel() }
            renamer.start()
            canceller.start()
            task.close()
            renamer.join()
            canceller.join()
            val own =
                messages().filter {
                    it.startsWith(
                        "task#${task.number} ",
                    ) || it.startsWith("task#${task.number}:")
                }
            assertTrue(own.last().contains("finished in"), own.joinToString("\n"))
            assertEquals(1, own.count { "finished in" in it })
        }
    }

    @Test
    fun `closing the registry abandons running tasks and later starts are never shown`() {
        val shown = tasks.start("Fetching origin…")
        advance(500)
        val pending = tasks.start("Loading Git log")
        runFx()
        val showTimer = timers.single { !it.cancelled }
        val done = tasks.start("Quick")
        done.close()
        lines.clear()
        now += 3_000_000_000

        tasks.close()
        runFx()
        assertTrue(showTimer.cancelled, "the pending show timer is cancelled")
        shown.close()
        pending.failed("too late")
        assertEquals(
            listOf(
                "task#${shown.number} abandoned after 3.50 s: Fetching origin…",
                "task#${pending.number} abandoned after 3.00 s: Loading Git log",
            ),
            messages(),
        )
        assertTrue(lines.all { it.level == Level.INFO })
        assertTrue(tasks.shown.isEmpty())

        lines.clear()
        val later = tasks.start("After close")
        advance(1000)
        assertTrue(tasks.shown.isEmpty())
        later.close()
        assertEquals(
            listOf("task#${later.number} started: After close", "task#${later.number} finished in 1.00 s: After close"),
            messages(),
        )
    }

    @Test
    fun `a registry for tests shows its tasks at once and records their lines`() {
        val recorded = CopyOnWriteArrayList<String>()
        val forTests = BackgroundTasks.forTests(recorded::add)
        var cancelled = false

        val task = forTests.start("Fetching origin…") { cancelled = true }
        assertEquals(listOf(task), forTests.shown)
        task.rename("Fetching upstream…")
        assertEquals(
            "Fetching upstream…",
            forTests.shown
                .single()
                .name
                .get(),
        )
        forTests.shown.single().cancel()
        task.failed("rejected")

        assertTrue(cancelled)
        assertTrue(forTests.shown.isEmpty())
        val number = task.number
        assertEquals(
            listOf(
                "task#$number started: Fetching origin…",
                "task#$number: Fetching origin… → Fetching upstream…",
                "task#$number cancelled",
            ),
            recorded.take(3),
        )
        assertTrue(recorded[3].startsWith("task#$number cancelled after "), recorded[3])
        assertTrue(recorded[3].endsWith(": Fetching upstream…"), recorded[3])
    }
}
