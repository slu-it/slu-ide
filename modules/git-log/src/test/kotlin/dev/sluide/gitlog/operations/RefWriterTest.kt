package dev.sluide.gitlog.operations

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitFetchChanges
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitMergeMode
import dev.sluide.git.GitMergeResult
import dev.sluide.git.GitResetMode
import dev.sluide.git.GitResult
import dev.sluide.git.ssh.HostKeyRequest
import dev.sluide.git.ssh.PassphraseReply
import dev.sluide.git.ssh.PassphraseRequest
import dev.sluide.git.ssh.SshAccess
import dev.sluide.git.ssh.SshPromptUi
import dev.sluide.gitlog.data.FakeWrites
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The background tasks of a [RefWriter] (ADR 0061) and what it tells about its writes (ADR 0062). */
class RefWriterTest {
    private val root = Path.of("/repo")
    private val lines = CopyOnWriteArrayList<String>()
    private val tasks = BackgroundTasks.forTests(lines::add)
    private val background = mutableListOf<Runnable>()
    private val writes = FakeWrites()

    /** What the writer told about its writes' start and end, with what it handled, in order. */
    private val signals = mutableListOf<String>()
    private val outcomes = mutableListOf<RefOutcome>()

    private val ui =
        object : SshPromptUi {
            override fun askPassphrase(request: PassphraseRequest): PassphraseReply? = null

            override fun confirmHost(request: HostKeyRequest) = false

            override fun notSaved(reason: String) = Unit

            override fun notAddedToAgent(message: String) = Unit
        }

    /** A session of the fake `SshPrompts`; [closed] counts its `close()`. */
    private inner class FakeSession(
        val root: Path,
        val ui: SshPromptUi,
        val onCancel: () -> Unit,
    ) : SshAccess.Prompting {
        var closed = 0
        override val environment = emptyMap<String, String>()

        override fun ended(success: Boolean) = Unit

        override fun close() {
            closed++
        }
    }

    private val sessions = mutableListOf<FakeSession>()

    private fun openSession(
        root: Path,
        ui: SshPromptUi,
        onCancel: () -> Unit,
    ): SshAccess = FakeSession(root, ui, onCancel).also(sessions::add)

    private val writer =
        RefWriter(
            writes,
            background::add,
            Runnable::run,
            tasks,
            SshSessions(ui, ::openSession),
            { signals += "started holding $it" },
        ) {
            signals += "finished"
        }

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
    fun `runs one operation at a time, which runs until its outcome is handled`() {
        val dispatched = mutableListOf<Runnable>()
        val writer = RefWriter(writes, background::add, dispatched::add, tasks)

        assertTrue(writer.run(root, CheckoutBranch("main"), outcomes::add))
        assertTrue(writer.running.get())
        assertFalse(writer.run(root, CheckoutBranch("other"), outcomes::add), "one at a time")
        assertEquals(1, background.size)

        background.removeFirst().run()
        assertTrue(writer.running.get(), "until the outcome is handled")
        dispatched.removeFirst().run()
        assertFalse(writer.running.get())
        assertEquals(listOf("Checked out main."), outcomes.map { it.done })
        assertTrue(writer.run(root, CheckoutBranch("other"), outcomes::add))
    }

    @Test
    fun `only a fetch can be cancelled, which runs until git has stopped and then reports no progress`() {
        val progress = mutableListOf<String>()
        writes.remotes = listOf("origin", "fork")
        assertFalse(writer.cancel(), "nothing runs")

        assertTrue(writer.run(root, CheckoutBranch("main"), outcomes::add))
        assertFalse(writer.cancellable.get())
        assertFalse(writer.cancel(), "a checkout is never stopped")
        background.removeFirst().run()
        assertEquals("Checked out main.", outcomes.single().done)

        assertTrue(writer.run(root, Fetch(), progress::add, outcomes::add))
        assertTrue(writer.cancellable.get())
        assertTrue(writer.cancel())
        assertFalse(writer.cancellable.get(), "cancelled once")
        assertTrue(writer.running.get(), "until git has stopped")
        background.removeFirst().run()

        assertEquals("Fetch cancelled.", outcomes.last().done)
        assertEquals(emptyList(), progress, "no remote fetched")
        assertFalse(writer.running.get())
        assertFalse(writer.cancel())
    }

    @Test
    fun `a pull can be cancelled while it fetches only`() {
        val pull = PullBranch("feature", "origin", "refs/remotes/origin/main", GitMergeMode.FAST_FORWARD, null, true)
        writes.ancestry = { _, _ -> false }
        writes.currentBranch = "feature"
        val whileMerging = mutableListOf<Boolean>()
        writes.mergeResult = {
            val task = tasks.shown.single()
            whileMerging += writer.cancellable.get()
            whileMerging += task.cancellable
            // The task's ✕, too late: nothing is cancelled.
            task.cancel()
            GitMergeResult.Done("h2")
        }

        assertTrue(writer.run(root, pull, outcomes::add))
        assertTrue(writer.cancellable.get())
        background.removeFirst().run()

        assertEquals(listOf(false, false), whileMerging, "Cancel and ✕ are gone once fetched")
        assertEquals("Pulled origin/main into feature (merge commit).", outcomes.single().done)
        assertFalse(writer.cancel())
        assertTrue(lines().none { it.endsWith("cancelled") || "cancelled after" in it }, lines().toString())
        assertTrue(lines().last().startsWith("task finished"), lines().toString())
    }

    @Test
    fun `a fetch's progress is passed on, and dropped with its outcome`() {
        writes.remotes = listOf("origin", "fork")
        val progress = mutableListOf<String>()

        writer.run(root, Fetch(), progress::add, outcomes::add)
        background.removeFirst().run()
        assertEquals(listOf("Fetching origin…", "Fetching fork…"), progress)

        progress.clear()
        writer.run(root, Fetch(), progress::add, outcomes::add)
        writer.forget()
        background.removeFirst().run()
        assertEquals(emptyList(), progress, "dropped with the outcome")
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

    @Test
    fun `a write's start is told before git runs and its end before its outcome`() {
        writer.run(root, CheckoutBranch("main")) { signals += "outcome" }
        assertEquals(listOf("started holding true"), signals, "before its thread runs")
        background.single().run()

        assertEquals(listOf("started holding true", "finished", "outcome"), signals)
    }

    @Test
    fun `a fetch, a push and a change of refs only do not hold the status, a commit does`() {
        writer.run(root, Fetch("origin"), {}, outcomes::add)
        background.removeFirst().run()
        writer.run(root, CreateTag("v1", "abc", ""), outcomes::add)
        background.removeFirst().run()

        assertEquals(listOf("started holding false", "finished", "started holding false", "finished"), signals)
        assertTrue(CommitChanges("Message", emptyList(), emptyList(), amend = false).holdsStatus)
        assertTrue(ResetBranch("abc", GitResetMode.HARD, "main").holdsStatus)
        assertFalse(ResetBranch("abc", GitResetMode.SOFT, "main").holdsStatus)
    }

    @Test
    fun `a write that throws still tells its end`() {
        writes.result = { throw IllegalStateException("broken") }
        writer.run(root, CheckoutBranch("main"), outcomes::add)
        background.removeFirst().run()

        writes.result = { throw OutOfMemoryError("gone") }
        writer.run(root, CheckoutBranch("main"), outcomes::add)
        assertFailsWith<OutOfMemoryError> { background.removeFirst().run() }

        assertEquals(listOf("started holding true", "finished", "started holding true", "finished"), signals)
        assertFalse(writer.running.get(), "a later write may run")
        assertEquals(1, outcomes.size, "no outcome of the write that threw")
    }

    @Test
    fun `a write whose outcome is dropped still tells its end`() {
        writer.run(root, CheckoutBranch("main"), outcomes::add)
        writer.forget()
        background.single().run()

        assertEquals(listOf("started holding true", "finished"), signals)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `a write that cannot start tells its end at once`() {
        val closed =
            RefWriter(
                writes,
                { throw java.util.concurrent.RejectedExecutionException() },
                Runnable::run,
                tasks,
                writeStarted = { signals += "started holding $it" },
            ) { signals += "finished" }

        assertFailsWith<java.util.concurrent.RejectedExecutionException> {
            closed.run(root, CheckoutBranch("main"), outcomes::add)
        }

        assertEquals(listOf("started holding true", "finished"), signals)
    }

    @Test
    fun `an operation that contacts a remote gets a session of the window's prompts, closed once it ended`() {
        writes.fetchResult = {
            assertEquals(0, sessions.single().closed, "open while git runs")
            GitFetchResult(GitResult(true, ""), changes = GitFetchChanges())
        }

        writer.run(root, Fetch("origin"), {}, outcomes::add)
        background.single().run()

        val session = sessions.single()
        assertEquals(root, session.root)
        assertSame(ui, session.ui)
        assertEquals(listOf<SshAccess>(session), writes.sshSeen, "the operation got it in its context")
        assertEquals(1, session.closed)
    }

    @Test
    fun `an operation that does not contact a remote runs without a session`() {
        writer.run(root, CheckoutBranch("main"), outcomes::add)
        background.single().run()

        assertEquals(emptyList(), sessions)
    }

    @Test
    fun `a session is closed also when its operation throws`() {
        writes.fetchResult = { throw IllegalStateException("broken") }

        writer.run(root, Fetch("origin"), {}, outcomes::add)
        background.single().run()

        assertEquals(1, sessions.single().closed)
        assertEquals(GitResult(false, "broken"), outcomes.single().result)
    }

    @Test
    fun `a session is closed also when the operation fails with an error`() {
        writes.fetchResult = { throw OutOfMemoryError("gone") }

        writer.run(root, Fetch("origin"), {}, outcomes::add)
        assertFailsWith<OutOfMemoryError> { background.single().run() }

        assertEquals(1, sessions.single().closed)
    }

    @Test
    fun `a cancelled prompt cancels the run, which ends as cancelled even if git failed first`() {
        writes.remotes = listOf("origin", "fork")
        writes.fetchResult = {
            sessions.single().onCancel()
            GitFetchResult(GitResult(false, "Permission denied (publickey)."))
        }

        writer.run(root, Fetch(), {}, outcomes::add)
        background.single().run()

        assertEquals(listOf("fetch origin"), writes.calls, "the other remote is skipped")
        assertEquals("Fetch cancelled.", outcomes.single().done)
        assertTrue(outcomes.single().result.success, "no error dialog")
        assertEquals(1, sessions.single().closed)
    }

    @Test
    fun `each run opens a session of its own`() {
        writer.run(root, Fetch("origin"), {}, outcomes::add)
        background.removeFirst().run()
        writer.run(root, Fetch("origin"), {}, outcomes::add)
        background.removeFirst().run()

        assertEquals(2, sessions.size)
        assertEquals(listOf(1, 1), sessions.map { it.closed })
        assertFalse(sessions[0] === sessions[1])
    }

    @Test
    fun `a cancelled prompt cancels the run's token and sets its flag, which no other run sees`() {
        writes.fetchResult = {
            sessions.last().onCancel()
            GitFetchResult(GitResult(false, "Permission denied (publickey)."))
        }
        writer.run(root, Fetch("origin"), {}, outcomes::add)
        background.removeFirst().run()
        assertTrue(writes.cancellationsSeen.single().cancelled, "the token git sees")
        assertEquals("Fetch cancelled.", outcomes.last().done)

        writes.fetchResult = { GitFetchResult(GitResult(false, "Permission denied (publickey).")) }
        writer.run(root, Fetch("origin"), {}, outcomes::add)
        background.removeFirst().run()
        assertFalse(writes.cancellationsSeen.last().cancelled, "a token of its own")
        assertFalse(outcomes.last().cancelled, "a flag of its own")
    }

    @Test
    fun `without sessions a remote operation runs in batch mode`() {
        val plain = RefWriter(writes, background::add, Runnable::run, tasks)

        plain.run(root, Fetch("origin"), {}, outcomes::add)
        background.removeFirst().run()

        assertEquals(listOf<SshAccess>(SshAccess.Batch), writes.sshSeen)
        assertEquals("Fetched origin: nothing new.", outcomes.single().done)
    }

    @Test
    fun `a session that cannot be opened with no message fails the operation with the exception's name`() {
        val broken =
            RefWriter(
                writes,
                background::add,
                Runnable::run,
                tasks,
                SshSessions(ui) { _, _, _ -> throw IllegalStateException() },
            )

        broken.run(root, Fetch("origin"), {}, outcomes::add)
        background.removeFirst().run()

        assertEquals(GitResult(false, "java.lang.IllegalStateException"), outcomes.single().result)
    }

    @Test
    fun `a session that cannot be opened fails the operation like any other failure`() {
        val broken =
            RefWriter(writes, background::add, Runnable::run, tasks, SshSessions(ui) { _, _, _ -> error("no socket") })

        broken.run(root, Fetch("origin"), {}, outcomes::add)
        background.removeFirst().run()

        assertEquals(emptyList(), writes.calls, "git never ran")
        assertEquals(GitResult(false, "no socket"), outcomes.single().result)
        assertEquals(listOf("task started: Fetching origin…", "task failed after: Fetching origin…"), lines())
        assertFalse(broken.running.get())
    }
}
