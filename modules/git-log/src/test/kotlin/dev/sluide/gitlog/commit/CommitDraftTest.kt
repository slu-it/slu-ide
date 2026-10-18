package dev.sluide.gitlog.commit

import dev.sluide.git.GitCommitChanges
import dev.sluide.git.GitOperation
import dev.sluide.git.GitPendingOperation
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPick
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommitDraftTest {
    @Test
    fun `new tracked files are checked, new untracked ones not, and choices stay across updates`() {
        val checks = CommitChecks()
        checks.update(listOf("a", "b"), listOf("u"))
        assertTrue(checks.isChecked("a"))
        assertFalse(checks.isChecked("u"))

        checks.set(listOf("a"), false)
        checks.set(listOf("u"), true)
        checks.update(listOf("a", "b", "c"), listOf("u", "v"))

        assertFalse(checks.isChecked("a"), "unchecked by the user")
        assertTrue(checks.isChecked("c"), "new tracked")
        assertTrue(checks.isChecked("u"), "checked by the user")
        assertFalse(checks.isChecked("v"), "new untracked")

        // Gone for a while and back: as the user left it.
        checks.update(listOf("b"), emptyList())
        checks.update(listOf("a", "b"), emptyList())
        assertFalse(checks.isChecked("a"))
    }

    @Test
    fun `an untracked file staged in a terminal comes back checked, forgotten files count as new`() {
        val checks = CommitChecks()
        checks.update(emptyList(), listOf("u"))
        checks.update(listOf("u"), emptyList())
        assertTrue(checks.isChecked("u"))

        checks.set(listOf("u"), false)
        checks.forget(listOf("u"))
        checks.update(listOf("u"), emptyList())
        assertTrue(checks.isChecked("u"))
    }

    @Test
    fun `a folder's state is all, some or none of its files`() {
        val checks = CommitChecks()
        checks.update(listOf("a", "b"), listOf("u"))

        assertEquals(CheckState.ALL, checks.stateOf(listOf("a", "b")))
        assertEquals(CheckState.SOME, checks.stateOf(listOf("a", "u")))
        assertEquals(CheckState.NONE, checks.stateOf(listOf("u")))
        assertEquals(CheckState.NONE, checks.stateOf(emptyList()))
    }

    @Test
    fun `the summary counts the checked files`() {
        assertEquals("Nothing to commit.", commitSummary(0, 0))
        assertEquals("1 of 1 file selected", commitSummary(1, 1))
        assertEquals("2 of 5 files selected", commitSummary(2, 5))
    }

    @Test
    fun `what git is in the middle of or conflicts block committing`() {
        assertNull(commitBlocked(null, emptyList()))
        assertEquals(
            "A merge is in progress; finish or abort it in the terminal.",
            commitBlocked(GitPendingOperation(GitOperation.MERGE), listOf("a")),
        )
        assertEquals(
            "Cherry-pick of 1a2b3c4d is in progress; continue or abort it in the log first.",
            commitBlocked(GitPendingPick(GitPick.CHERRY_PICK, "1a2b3c4d5e6f", listOf("a")), listOf("a")),
        )
        assertEquals("1 file has conflicts; resolve it first.", commitBlocked(null, listOf("a")))
        assertEquals("2 files have conflicts; resolve them first.", commitBlocked(null, listOf("a", "b")))
    }

    @Test
    fun `Commit needs a message, a checked file or Amend, nothing blocking and no running operation`() {
        assertTrue(canCommit("m", 1, amend = false, blocked = false, running = false))
        assertFalse(canCommit(" \n", 1, amend = false, blocked = false, running = false))
        assertFalse(canCommit("m", 0, amend = false, blocked = false, running = false))
        assertTrue(canCommit("m", 0, amend = true, blocked = false, running = false))
        assertFalse(canCommit("m", 1, amend = false, blocked = true, running = false))
        assertFalse(canCommit("m", 1, amend = false, blocked = false, running = true))
    }

    @Test
    fun `the reader reads only while visible, remembers a reload while hidden and lets the newest read win`() {
        val tasks = ArrayDeque<Runnable>()
        val reads = mutableListOf<Int>()
        var answer = 0
        val reader =
            CommitReader(
                Path.of("/repo"),
                { _ ->
                    reads += ++answer
                    if (answer == 3) null else CommitSnapshot(changes(answer), "h1")
                },
                { "message" },
                tasks::addLast,
                Runnable::run,
            )
        val loaded = mutableListOf<CommitSnapshot>()
        var failed = 0
        reader.loaded = { loaded += it }
        reader.failed = { failed++ }

        reader.reload()
        assertTrue(tasks.isEmpty(), "hidden")
        reader.visible = true
        reader.reload()
        // The older of two queued reads is skipped.
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(listOf(1), reads)
        assertEquals(
            1,
            loaded
                .single()
                .changes.untracked.size,
        )
        assertEquals(CommitLoadState.LOADED, reader.state.get())

        reader.visible = false
        reader.reload()
        assertTrue(tasks.isEmpty())
        reader.visible = true
        tasks.removeFirst().run()
        reader.reload()
        tasks.removeFirst().run()
        assertEquals(CommitLoadState.FAILED, reader.state.get())
        assertEquals(1, failed)
        assertEquals(
            2,
            reader.snapshot
                ?.changes
                ?.untracked
                ?.size,
            "the snapshot before stays",
        )

        var message: String? = null
        reader.headMessage { message = it }
        tasks.removeFirst().run()
        assertEquals("message", message)

        reader.close()
        reader.reload()
        assertTrue(tasks.isEmpty())
    }

    private fun changes(untracked: Int) =
        GitCommitChanges(emptyList(), (1..untracked).map { "u$it" }, null, emptyList(), false)
}
