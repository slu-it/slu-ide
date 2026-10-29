package dev.sluide.gitlog.commit

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommitChanges
import dev.sluide.git.GitPendingConflicts
import dev.sluide.git.GitPendingMerge
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPendingRebase
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
        assertNull(commitBlocked(null))
        assertEquals(
            "A merge is in progress; continue or abort it in the log first.",
            commitBlocked(GitPendingMerge(listOf("h1"), null, listOf("a"))),
        )
        assertEquals(
            "A rebase is in progress; continue or abort it in the log first.",
            commitBlocked(GitPendingRebase("main", null, null, null, null, emptyList())),
        )
        assertEquals(
            "Cherry-pick of 1a2b3c4d is in progress; continue or abort it in the log first.",
            commitBlocked(GitPendingPick(GitPick.CHERRY_PICK, "1a2b3c4d5e6f", listOf("a"))),
        )
        assertEquals(
            "Files have conflicts; mark them resolved in the log first.",
            commitBlocked(GitPendingConflicts(listOf("a"))),
        )
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
    fun `Stash Checked Files needs a checked file, nothing blocking, no running write, and both paths of a rename`() {
        assertTrue(canStashChecked(1, blocked = false, running = false))
        assertFalse(canStashChecked(0, blocked = false, running = false))
        assertFalse(canStashChecked(2, blocked = true, running = false), "git refuses with conflicts")
        assertFalse(canStashChecked(2, blocked = false, running = true))

        val files =
            listOf(
                GitChangedFile(GitChange.RENAMED, "new.txt", "old.txt"),
                GitChangedFile(GitChange.COPIED, "copy.txt", "source.txt"),
                GitChangedFile(GitChange.MODIFIED, "a.txt"),
            )
        assertEquals(listOf("new.txt", "old.txt", "copy.txt", "a.txt", "u.txt"), stashPaths(files, listOf("u.txt")))
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

    @Test
    fun `showing the tab again reads nothing if not stale, a read and a message in flight at close are dropped`() {
        val tasks = ArrayDeque<Runnable>()
        val reader =
            CommitReader(
                Path.of("/repo"),
                { _ -> CommitSnapshot(changes(1), "h1") },
                { "message" },
                tasks::addLast,
                Runnable::run,
            )
        var loaded = 0
        var handled = 0
        reader.loaded = { loaded++ }
        reader.visible = true
        tasks.removeFirst().run()
        assertEquals(1, loaded)

        reader.visible = false
        reader.visible = true
        assertTrue(tasks.isEmpty(), "nothing stale: no read")

        reader.reload()
        reader.headMessage { handled++ }
        reader.close()
        while (tasks.isNotEmpty()) tasks.removeFirst().run()

        assertEquals(1, loaded, "the read in flight is dropped")
        assertEquals(0, handled, "the message in flight is dropped")
    }

    private fun changes(untracked: Int) = GitCommitChanges(emptyList(), (1..untracked).map { "u$it" }, null, false)
}
