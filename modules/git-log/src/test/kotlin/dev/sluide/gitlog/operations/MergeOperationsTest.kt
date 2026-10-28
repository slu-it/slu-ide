package dev.sluide.gitlog.operations

import dev.sluide.git.GitConflictReason
import dev.sluide.git.GitMergeMode
import dev.sluide.git.GitMergeResult
import dev.sluide.git.GitPendingMerge
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitResult
import dev.sluide.git.GitUncheckedConflict
import dev.sluide.gitlog.data.FakeWrites
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MergeOperationsTest {
    private val root = Path.of("/repo")
    private val ref = "refs/heads/main"
    private val head = "5e6f7a8b9c0d"

    private val writes = FakeWrites()

    private val merge = MergeBranch("feature", ref, "main", GitMergeMode.FAST_FORWARD, null, autostash = true)

    private fun done(result: GitMergeResult) =
        merge
            .also { writes.mergeResult = { result } }
            .run(writes, root)
            .done

    @Test
    fun `a merge runs as chosen and tells how it merged`() {
        val outcome =
            merge
                .copy(
                    mode = GitMergeMode.MERGE_COMMIT,
                    message = "Text",
                    autostash = false,
                ).run(writes, root)

        assertEquals(RefOutcome(GitResult(true, ""), "Merged main into feature."), outcome)
        assertEquals(listOf("merge feature $ref merge_commit -m Text"), writes.calls)
        assertEquals("Merging main into feature…", merge.progress)
        assertEquals("Merge", merge.fileChange)
        assertEquals("Merge Failed", merge.errorTitle)
        assertEquals("main could not be merged into feature.", merge.failure)

        assertEquals("Fast-forwarded feature to main.", done(GitMergeResult.Done(head, fastForward = true)))
        assertEquals(
            "Squashed main into the index of feature; commit the changes.",
            done(GitMergeResult.Done(head, squashed = true)),
        )
        assertEquals(
            "Squashed main into the index of feature with conflicts in 2 files; resolve them, then commit.",
            done(GitMergeResult.Done(head, squashed = true, conflicts = listOf("a.txt", "b.txt"))),
        )
        assertEquals(
            "'main' is already contained in 'feature'; nothing to merge.",
            done(GitMergeResult.UpToDate),
        )
    }

    @Test
    fun `local changes kept in the stash are told after what was done`() {
        assertEquals(
            "Merged main into feature. Your local changes could not be restored without conflicts; they are kept " +
                "in the stash.",
            done(GitMergeResult.Done(head, stashKept = true)),
        )
        writes.mergeResult = { GitMergeResult.Done(head, stashKept = true) }
        assertEquals("Aborted the merge. $STASH_KEPT", AbortMerge.run(writes, root).done)
        assertEquals("Merged main into feature. $STASH_KEPT", ContinueMerge("main", "feature").run(writes, root).done)
    }

    @Test
    fun `a stop leaves the merge for the user with its conflicts`() {
        val pending = GitPendingMerge(listOf(head), "Merge branch 'main' into feature", listOf("a.txt"))
        writes.mergeResult = { GitMergeResult.Stopped(pending, "CONFLICT in a.txt") }

        val outcome = merge.run(writes, root)

        assertEquals(MergeStop("main", "feature", listOf("a.txt"), "CONFLICT in a.txt"), outcome.mergeStopped)
        assertEquals("Merge of main into feature stopped with conflicts.", outcome.done)
        assertEquals(true, outcome.result.success)
        val stop = assertNotNull(outcome.mergeStopped)
        assertEquals("Merge of main into feature stopped with conflicts in:", mergeStopHeader(stop))
        assertEquals("Merge stopped.", mergeStopHeader(MergeStop(null, null, emptyList(), "hook failed")))
        assertEquals("Merge stopped.", mergeStopText(MergeStop(null, null, emptyList(), "")))
    }

    @Test
    fun `a failure shows git's message`() {
        writes.mergeResult = { GitMergeResult.Failed("Not possible to fast-forward, aborting.") }

        val outcome = merge.run(writes, root)

        assertEquals(RefOutcome(GitResult(false, "Not possible to fast-forward, aborting."), null), outcome)
    }

    @Test
    fun `continue names what was merged where it is known, and asks about files it cannot check`() {
        assertEquals("Merged main into feature.", ContinueMerge("main", "feature").run(writes, root).done)
        assertEquals("Merge committed.", ContinueMerge(null, "feature").run(writes, root).done)
        assertEquals(listOf("merge --continue", "merge --continue"), writes.calls)
        assertEquals("Continue", ContinueMerge().fileChange)

        val unchecked = GitUncheckedConflict("bin.dat", GitConflictReason.BINARY)
        writes.mergeResult = { line ->
            if (line.endsWith("as on disk")) GitMergeResult.Done(head) else GitMergeResult.Unchecked(listOf(unchecked))
        }
        val asked = ContinueMerge("main", "feature").run(writes, root)
        val confirmation = assertNotNull(asked.confirmation)
        assertNull(asked.done)
        assertEquals("Continue Merge", confirmation.title)
        assertEquals(COMMIT_AS_ON_DISK_BUTTON, confirmation.button)
        assertEquals("Merged main into feature.", confirmation.confirmed.run(writes, root).done)
        assertEquals(setOf(unchecked), writes.confirmedSets.last())

        writes.mergeResult = { GitMergeResult.MarkersLeft(listOf("a.txt")) }
        assertEquals(
            GitResult(false, "Conflict markers are left in: a.txt"),
            ContinueMerge().run(writes, root).result,
        )
    }

    @Test
    fun `abort undoes the merge`() {
        val outcome = AbortMerge.run(writes, root)

        assertEquals(RefOutcome(GitResult(true, ""), "Aborted the merge."), outcome)
        assertEquals(listOf("merge --abort"), writes.calls)
        assertEquals("Abort", AbortMerge.fileChange)
    }

    @Test
    fun `the bar tells the merge's message and its conflicts`() {
        fun text(
            message: String?,
            conflicts: Int,
            heads: List<String> = listOf(head),
        ) = pendingMergeText(GitPendingMerge(heads, message, List(conflicts) { "f$it.txt" }))

        assertEquals(
            "Merge branch 'main' into feature: 2 files have conflicts.",
            text("Merge branch 'main' into feature", 2),
        )
        assertEquals("Merge branch 'main': 1 file has conflicts.", text("Merge branch 'main'", 1))
        assertEquals("Merge branch 'main': conflicts resolved.", text("Merge branch 'main'", 0))
        assertEquals("A merge is in progress: 2 files have conflicts.", text(null, 2))
        assertEquals(
            "An octopus merge is in progress; finish or abort it in the terminal.",
            text("Merge branches 'a' and 'b'", 0, listOf("h1", "h2")),
        )
    }

    @Test
    fun `continue from the bar names the branch at the merged commit and the current one`() {
        val pending = GitPendingMerge(listOf(head), null, emptyList())
        val refs =
            GitRefs(
                listOf(
                    GitRef("HEAD", GitRefKind.HEAD, "h0"),
                    GitRef("refs/heads/feature", GitRefKind.LOCAL_BRANCH, "h0"),
                    GitRef("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, head),
                    GitRef("refs/tags/v1", GitRefKind.TAG, head),
                ),
                "refs/heads/feature",
            )

        assertEquals(ContinueMerge("origin/main", "feature"), continueMerge(pending, refs))
        assertEquals(ContinueMerge(null, "feature"), continueMerge(pending.copy(heads = listOf("other")), refs))
        assertEquals(ContinueMerge(null, null), continueMerge(pending, null))
    }
}
