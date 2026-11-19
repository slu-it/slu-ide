package dev.sluide.languageservers.workspace

import dev.sluide.languageservers.connection.Scheduler
import dev.sluide.languageservers.files.FakeDirectoryWatcher
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The watches of the folders a spec names per workspace folder, and the debounce of their reports. */
class FolderWatchesTest {
    private val watcher = FakeDirectoryWatcher()
    private val reported = mutableListOf<Path>()
    private val watches = FolderWatches(watcher) { reported.add(it) }

    private val workspace = Path.of("/work/ws").toAbsolutePath()
    private val venv = Path.of("/work/ws/.venv").toAbsolutePath()
    private val lib = Path.of("/work/ws/lib").toAbsolutePath()

    @Test
    fun `a report in a watched folder calls back with the workspace folder`() {
        watches.update(workspace, listOf(venv, lib)) { true }

        watcher.report(lib, lib.resolve("a.py"))

        assertEquals(listOf(workspace), reported)
        assertEquals(listOf(venv, lib), watches.paths(workspace))
        assertNull(watches.paths(Path.of("/work/other")))
    }

    @Test
    fun `an update watches every folder again and then ends the old watches`() {
        watches.update(workspace, listOf(venv, lib)) { true }

        watches.update(workspace, listOf(lib)) { true }

        assertEquals(listOf(venv, lib, lib), watcher.watches)
        assertEquals(listOf(venv, lib), watcher.closes)
        assertEquals(setOf(lib), watcher.listeners.keys)
        assertEquals(listOf(lib), watches.paths(workspace))
    }

    @Test
    fun `an update that is no longer wanted watches nothing and keeps what was`() {
        watches.update(workspace, listOf(venv)) { true }

        watches.update(workspace, listOf(lib)) { false }

        assertEquals(listOf(venv), watcher.watches)
        assertEquals(emptyList(), watcher.closes)
        assertEquals(listOf(venv), watches.paths(workspace))
    }

    @Test
    fun `an update is asked whether it is wanted once the watches are being made`() {
        var asked = 0

        watches.update(workspace, listOf(venv)) { (++asked > 0) }

        assertEquals(1, asked)
        assertEquals(listOf(venv), watcher.watches)
    }

    @Test
    fun `removing a workspace folder ends its watches and forgets its folders`() {
        watches.update(workspace, listOf(venv, lib)) { true }

        watches.remove(workspace)
        watches.remove(Path.of("/work/unknown"))

        assertEquals(listOf(venv, lib), watcher.closes)
        assertNull(watches.paths(workspace))
        assertEquals(emptyMap(), watcher.listeners)
    }

    @Test
    fun `closing all ends every watch and forgets the folders, later updates still watch unless it was for good`() {
        watches.update(workspace, listOf(venv)) { true }
        watches.update(Path.of("/work/b").toAbsolutePath(), listOf(lib)) { true }

        watches.closeAll()

        assertEquals(setOf(venv, lib), watcher.closes.toSet())
        assertNull(watches.paths(workspace))
        watches.closeAll()
        assertEquals(2, watcher.closes.size, "nothing is ended twice")

        watches.update(workspace, listOf(venv)) { true }
        assertEquals(listOf(venv), watches.paths(workspace))
    }

    @Test
    fun `closing all for good makes later updates watch nothing`() {
        watches.update(workspace, listOf(venv)) { true }

        watches.closeAll(forGood = true)
        watches.update(workspace, listOf(lib)) { true }

        assertEquals(listOf(venv), watcher.watches)
        assertNull(watches.paths(workspace))
    }

    /** Runs scheduled tasks when told; a closed one is cancelled. */
    private class ManualScheduler : Scheduler {
        class Task(
            val delay: Duration,
            val run: Runnable,
        ) : AutoCloseable {
            var cancelled = false

            override fun close() {
                cancelled = true
            }
        }

        val tasks = mutableListOf<Task>()

        override fun schedule(
            delay: Duration,
            task: Runnable,
        ): AutoCloseable = Task(delay, task).also { tasks += it }
    }

    private val scheduler = ManualScheduler()
    private val debouncer = Debouncer<String>(scheduler, 2.seconds)
    private val ran = mutableListOf<String>()

    @Test
    fun `a trigger runs its action once after the delay`() {
        debouncer.trigger("k") { ran += "a" }

        assertEquals(2.seconds, scheduler.tasks.single().delay)
        assertEquals(emptyList(), ran)
        scheduler.tasks
            .single()
            .run
            .run()

        assertEquals(listOf("a"), ran)
        scheduler.tasks
            .single()
            .run
            .run()
        assertEquals(listOf("a"), ran, "a task that ran does not run again")
    }

    @Test
    fun `a trigger of the same key cancels the wait before and replaces its action, other keys are independent`() {
        debouncer.trigger("k") { ran += "first" }
        debouncer.trigger("other") { ran += "other" }
        debouncer.trigger("k") { ran += "second" }
        val (first, other, second) = scheduler.tasks

        assertEquals(listOf(true, false, false), listOf(first, other, second).map { it.cancelled })
        // A cancelled task that runs anyway (it was already due) is stale: it must not run its action.
        first.run.run()
        assertEquals(emptyList(), ran)
        second.run.run()
        other.run.run()

        assertEquals(listOf("second", "other"), ran)
    }

    @Test
    fun `closing cancels what waits, later triggers and tasks already due do nothing`() {
        debouncer.trigger("k") { ran += "a" }
        val waiting = scheduler.tasks.single()

        debouncer.close()
        waiting.run.run()
        debouncer.trigger("k") { ran += "b" }

        assertEquals(true, waiting.cancelled)
        assertEquals(1, scheduler.tasks.size)
        assertEquals(emptyList(), ran)
    }

    @Test
    fun `closing leaves a task that already ran alone`() {
        debouncer.trigger("k") { ran += "a" }
        val done = scheduler.tasks.single()
        done.run.run()

        debouncer.close()

        assertFalse(done.cancelled)
    }
}
