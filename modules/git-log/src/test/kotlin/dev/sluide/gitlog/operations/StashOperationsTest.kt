package dev.sluide.gitlog.operations

import dev.sluide.git.GitConflictReason
import dev.sluide.git.GitPendingConflicts
import dev.sluide.git.GitResolveResult
import dev.sluide.git.GitResult
import dev.sluide.git.GitStash
import dev.sluide.git.GitStashResult
import dev.sluide.git.GitStashSources
import dev.sluide.git.GitUncheckedConflict
import dev.sluide.gitlog.actions.errorHeader
import dev.sluide.gitlog.data.FakeWrites
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StashOperationsTest {
    private val root = Path.of("/repo")
    private val writes = FakeWrites()
    private val stash = GitStash(0, "c0ffee", 0, "On feature: message")

    @Test
    fun `the dialog's header names the branch, HEAD when detached, and the checked files`() {
        assertEquals("Stash local changes of 'feature'", stashHeader(StashPrompt("feature")))
        assertEquals("Stash local changes of HEAD", stashHeader(StashPrompt(null)))
        val renamed = CheckedFiles(listOf("new", "old", "c", "d"), includeUntracked = false, count = 3)
        assertEquals("Stash 3 checked files of 'feature'", stashHeader(StashPrompt("feature", renamed)))
        assertEquals("Stash 1 checked file of HEAD", stashHeader(StashPrompt(null, CheckedFiles(listOf("a"), true))))
    }

    @Test
    fun `preparing reads the branch and asks with the dialog, or the header says why not`() {
        val checked = CheckedFiles(listOf("a.txt"), includeUntracked = true)
        val prompt = PrepareStash(checked).run(writes, root)

        assertEquals(StashPrompt("main", checked), prompt.stashPrompt)
        assertNull(prompt.done)
        assertNull(PrepareStash().fileChange, "it only reads")

        writes.stash.sources = GitStashSources("main", "A merge is in progress; finish or abort it first.")
        val busy = PrepareStash().run(writes, root)
        assertEquals(RefOutcome(GitResult(true, ""), "A merge is in progress; finish or abort it first."), busy)
    }

    @Test
    fun `stashing tells where the changes went, or that there were none`() {
        val changes = StashChanges("msg", includeUntracked = true, paths = listOf("a.txt", "b.txt"))

        assertEquals(RefOutcome(GitResult(true, ""), "Stashed local changes as stash@{0}."), changes.run(writes, root))
        assertEquals(listOf("stash push -u -m msg a.txt b.txt"), writes.stash.calls)
        assertEquals("Stash", changes.fileChange)
        assertEquals("Stashing local changes…", changes.progress)
        assertEquals("Stash Failed", changes.errorTitle)

        writes.stash.result = { GitStashResult.NothingToStash }
        assertEquals("No local changes to stash.", StashChanges(null, false).run(writes, root).done)
        writes.stash.result = { GitStashResult.Failed("You do not have the initial commit yet") }
        assertEquals(
            RefOutcome(GitResult(false, "You do not have the initial commit yet"), null),
            StashChanges(null, false).run(writes, root),
        )
    }

    @Test
    fun `popping and applying tell what was done, conflicts with the stash kept, a changed list refused`() {
        val pop = PopStash(stash)
        val apply = PopStash(stash, pop = false)

        assertEquals("Popped stash@{0} (On feature: message).", pop.run(writes, root).done)
        assertEquals("Applied stash@{0} (On feature: message).", apply.run(writes, root).done)
        assertEquals(listOf("stash pop stash@{0} c0ffee", "stash apply stash@{0} c0ffee"), writes.stash.calls)
        assertEquals(listOf("Pop", "Apply"), listOf(pop.fileChange, apply.fileChange))
        assertEquals(listOf("Pop Failed", "Apply Failed"), listOf(pop.errorTitle, apply.errorTitle))
        assertEquals("Popping stash@{0}…", pop.progress)

        writes.stash.result = { GitStashResult.Conflicts(listOf("a.txt", "b.txt"), kept = true) }
        val popped = pop.run(writes, root)
        assertEquals("Popping stash@{0} gave conflicts in 2 files; the stash is kept.", popped.done)
        assertEquals(StashConflict(stash, true, listOf("a.txt", "b.txt"), true), popped.stashConflicts)
        val applied = apply.run(writes, root)
        assertEquals("Applying stash@{0} gave conflicts in 2 files; the stash stays in the list.", applied.done)

        val message = "u.txt already exists, no checkout"
        writes.stash.result = { GitStashResult.Partly(true, listOf("v.txt"), listOf("u.txt"), true, message) }
        val partly = pop.run(writes, root)
        val text =
            "Popped stash@{0} only partly: its changes to tracked files were applied; untracked v.txt was restored; " +
                "untracked u.txt could not be restored as it already exists. The stash is kept."
        assertEquals(RefOutcome(GitResult(false, message, changed = true), text), partly)
        assertEquals(text, errorHeader(pop, partly), "the error dialog says what was done above git's message")
        writes.stash.result = { GitStashResult.Partly(false, listOf("u1", "u2"), emptyList(), true, "") }
        assertEquals(
            "Applied stash@{0} only partly: its changes to tracked files were not applied; untracked u1, u2 were " +
                "restored. The stash stays in the list.",
            apply.run(writes, root).done,
        )
        writes.stash.result = { GitStashResult.Partly(null, emptyList(), listOf("u", "v"), false, "") }
        assertEquals(
            "Popped stash@{0} only partly: untracked u, v could not be restored as they already exist. The stash is " +
                "no longer in the list.",
            pop.run(writes, root).done,
        )
        writes.stash.result = { GitStashResult.Partly(true, emptyList(), emptyList(), true, "") }
        assertEquals(
            "Popped stash@{0} only partly: its changes to tracked files were applied; its untracked files could not " +
                "all be restored. The stash is kept.",
            pop.run(writes, root).done,
        )

        writes.stash.result = { GitStashResult.Changed }
        assertEquals(
            RefOutcome(GitResult(false, "The stash list changed; check it again."), null),
            pop.run(writes, root),
        )
        writes.stash.result = { GitStashResult.Failed("error: Your local changes would be overwritten") }
        assertEquals(GitResult(false, "error: Your local changes would be overwritten"), apply.run(writes, root).result)
    }

    @Test
    fun `the conflicts dialog says where they are and that the stash is kept`() {
        val pop = StashConflict(stash, pop = true, paths = listOf("a.txt"), kept = true)
        val apply = pop.copy(pop = false)

        assertEquals("Popping stash@{0} gave conflicts in:", stashConflictHeader(pop))
        assertEquals("Applying stash@{0} gave conflicts in:", stashConflictHeader(apply))
        assertEquals("The stash is kept; drop it once they are resolved.", stashConflictNote(pop))
        assertEquals("The stash stays in the list; drop it once they are resolved.", stashConflictNote(apply))
        assertEquals("Popping stash@{0} gave conflicts in 1 file; the stash is kept.", stashConflictText(pop))
    }

    @Test
    fun `Pop Latest Stash reads the stashes and pops the newest, asking about unsaved editors then`() {
        assertEquals(RefOutcome(GitResult(true, ""), "No stashes to pop."), PopLatestStash.run(writes, root))
        assertNull(PopLatestStash.fileChange)

        writes.stash.stashes = listOf(stash, stash.copy(index = 1, commit = "older"))
        assertEquals(PopStash(stash), PopLatestStash.run(writes, root).next)
        assertEquals(listOf("stash list", "stash list"), writes.stash.calls)

        writes.stash.stashes = null
        assertEquals(GitResult(false, "The stashes could not be read."), PopLatestStash.run(writes, root).result)
    }

    @Test
    fun `dropping asks first, Cancel the default, and changes no files`() {
        val confirmation = dropConfirmation(stash)

        assertEquals("Drop Stash", confirmation.title)
        assertEquals("Drop stash@{0} (On feature: message)? Its changes are lost.", confirmation.question)
        assertEquals("Drop", confirmation.button.text)
        assertEquals(DropStash(stash), confirmation.confirmed)
        assertNull(DropStash(stash).fileChange)

        assertEquals("Dropped stash@{0}.", DropStash(stash).run(writes, root).done)
        writes.stash.result = { GitStashResult.Changed }
        assertEquals(GitResult(false, STASH_LIST_CHANGED), DropStash(stash).run(writes, root).result)
    }

    @Test
    fun `Mark Resolved stages the files, refuses markers left and asks about files that cannot be checked`() {
        val mark = MarkResolved()
        assertEquals("Mark Resolved", mark.fileChange)

        writes.stash.resolveResult = { GitResolveResult.Done(listOf("a.txt", "b.txt")) }
        assertEquals(RefOutcome(GitResult(true, ""), "Marked 2 files resolved."), mark.run(writes, root))
        writes.stash.resolveResult = { GitResolveResult.Done(listOf("a.txt")) }
        assertEquals("Marked 1 file resolved.", mark.run(writes, root).done)

        writes.stash.resolveResult = { GitResolveResult.MarkersLeft(listOf("a.txt", "b.txt")) }
        assertEquals(GitResult(false, "Conflict markers are left in: a.txt, b.txt"), mark.run(writes, root).result)

        val unchecked = GitUncheckedConflict("bin.dat", GitConflictReason.BINARY)
        writes.stash.resolveResult = { confirmed ->
            if (unchecked in confirmed) {
                GitResolveResult.Done(listOf("bin.dat"))
            } else {
                GitResolveResult.Unchecked(listOf(unchecked))
            }
        }
        val confirmation = assertNotNull(mark.run(writes, root).confirmation)
        assertEquals("Mark Resolved", confirmation.title)
        assertEquals(
            "These conflicted files cannot be checked for conflict markers. Mark them resolved as they are on disk?",
            confirmation.question,
        )
        assertEquals("bin.dat — binary, the version on disk will be staged", confirmation.details)
        assertEquals("Mark Resolved", confirmation.button.text)
        assertEquals("Marked 1 file resolved.", confirmation.confirmed.run(writes, root).done)
        assertEquals("mark resolved as on disk", writes.stash.calls.last())
    }

    @Test
    fun `the bar counts the files with conflicts`() {
        assertEquals("1 file has conflicts.", pendingConflictsText(GitPendingConflicts(listOf("a"))))
        assertEquals("2 files have conflicts.", pendingConflictsText(GitPendingConflicts(listOf("a", "b"))))
    }
}
