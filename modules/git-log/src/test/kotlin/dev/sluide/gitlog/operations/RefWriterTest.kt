package dev.sluide.gitlog.operations

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitFetchChanges
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.FakeWrites
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The background tasks of a [RefWriter] (ADR 0061). */
class RefWriterTest {
    private val root = Path.of("/repo")
    private val lines = CopyOnWriteArrayList<String>()
    private val tasks = BackgroundTasks.forTests(lines::add)
    private val background = mutableListOf<Runnable>()
    private val writes = FakeWrites()
    private val writer = RefWriter(writes, background::add, Runnable::run, tasks)
    private val outcomes = mutableListOf<RefOutcome>()

    /** The lines without the task numbers and durations. */
    private fun lines() =
        lines.map { it.replace(Regex("task#\\d+"), "task").replace(Regex(" (after|in) \\d+(\\.\\d+)? s"), " $1") }

    @Test
    fun `a write is a task named by its progress, started on its thread, and finishes`() {
        writes.remotes = listOf("origin", "fork")

        writer.run(root, Fetch(), {}, outcomes::add)
        assertEquals(emptyList(), lines, "not before its thread runs")
        background.single().run()

        assertEquals(
            listOf(
                "task started: Fetching…",
                "task: Fetching… → Fetching origin…",
                "task: Fetching origin… → Fetching fork…",
                "task finished in: Fetching fork…",
            ),
            lines(),
        )
        assertTrue(tasks.shown.isEmpty())
    }

    @Test
    fun `a failed write ends its task with git's message`() {
        writes.result = { GitResult(false, "error: pathspec 'main' did not match") }

        writer.run(root, CheckoutBranch("main"), outcomes::add)
        background.single().run()

        assertEquals(
            listOf(
                "task started: Checking out main…",
                "task failed after: Checking out main…: error: pathspec 'main' did not match",
            ),
            lines(),
        )
    }

    @Test
    fun `a write that throws ends its task as failed`() {
        writes.result = { throw IllegalStateException("broken") }

        writer.run(root, CheckoutBranch("main"), outcomes::add)
        background.single().run()

        assertEquals(listOf("task started: Checking out main…", "task failed after: Checking out main…"), lines())
        assertEquals(GitResult(false, "broken"), outcomes.single().result)
    }

    @Test
    fun `the task of a fetch can be cancelled and ends as cancelled`() {
        writes.remotes = listOf("origin", "fork")
        writes.fetchResult = {
            tasks.shown.single().cancel()
            GitFetchResult(GitResult(true, ""), changes = GitFetchChanges())
        }

        writer.run(root, Fetch(), {}, outcomes::add)
        assertTrue(writer.cancellable.get())
        background.single().run()

        assertFalse(writer.cancellable.get(), "the task's cancel cancels the writer's run")
        assertEquals(listOf("fetch origin"), writes.calls, "the next remote is skipped")
        assertEquals(
            listOf(
                "task started: Fetching…",
                "task: Fetching… → Fetching origin…",
                "task cancelled",
                "task cancelled after: Fetching origin…",
            ),
            lines(),
        )
    }

    @Test
    fun `cancelling the writer cancels the task and a checkout has no cancel`() {
        writer.run(root, CheckoutBranch("main"), outcomes::add)
        writes.result = {
            assertFalse(tasks.shown.single().cancellable, "a checkout is never stopped")
            GitResult(true, "")
        }
        background.removeFirst().run()

        writer.run(root, Fetch("origin"), {}, outcomes::add)
        assertTrue(writer.cancel())
        background.removeFirst().run()

        assertEquals("Fetch cancelled.", outcomes.last().done)
        assertEquals(
            listOf("task started: Fetching origin…", "task cancelled after: Fetching origin…"),
            lines().takeLast(2),
        )
    }

    @Test
    fun `a cancel after git was done leaves the write finished`() {
        // The fake's git has fetched when this runs: the cancel comes too late to stop anything.
        writes.fetchResult = {
            assertTrue(writer.cancel())
            GitFetchResult(GitResult(true, ""), changes = GitFetchChanges())
        }
        writer.run(root, Fetch("origin"), {}, outcomes::add)
        background.single().run()

        assertEquals(
            listOf("task started: Fetching origin…", "task cancelled", "task finished in: Fetching origin…"),
            lines(),
        )
    }
}
