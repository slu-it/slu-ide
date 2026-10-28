package dev.sluide.gitlog.operations

import dev.sluide.git.GitFetchChanges
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitMergeMode
import dev.sluide.git.GitMergeResult
import dev.sluide.git.GitPendingRebase
import dev.sluide.git.GitPullSources
import dev.sluide.git.GitRebaseResult
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.FakeWrites
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PullOperationsTest {
    private val root = Path.of("/repo")
    private val ref = "refs/remotes/origin/main"

    /** `HEAD` behind [ref] (a rebase only fast-forwards), else diverged; never up to date. */
    private var behind = false

    private val writes =
        FakeWrites().also { writes ->
            writes.ancestry = { ancestor, descendant -> ancestor == "HEAD" && descendant == ref && behind }
            writes.currentBranch = "feature"
        }

    private val merge = PullBranch("feature", "origin", ref, GitMergeMode.FAST_FORWARD, null, autostash = true)
    private val rebase = merge.copy(mode = null)

    private val progress = mutableListOf<String>()
    private var uncancellable = 0
    private val context = RunContext(progress = progress::add, uncancellable = { uncancellable++ })

    private fun sources(
        branches: List<String> = listOf(ref),
        branch: String? = "feature",
        hasCommit: Boolean = true,
        upstream: String? = null,
        busy: String? = null,
        remotes: List<String> = listOf("origin"),
    ) = GitPullSources(branch, hasCommit, branches, remotes, upstream, busy)

    private fun fetched(changes: GitFetchChanges?) {
        writes.fetchResult = { GitFetchResult(GitResult(true, ""), changes = changes) }
    }

    @Test
    fun `a pull with merge fetches the branch's remote, then merges and tells how`() {
        val outcome = merge.run(writes, root, context)

        assertEquals(RefOutcome(GitResult(true, ""), "Pulled origin/main into feature (merge commit)."), outcome)
        assertEquals(listOf("fetch origin", "merge feature $ref fast_forward autostash"), writes.calls)
        assertEquals(listOf("Merging origin/main into feature…"), progress)
        assertEquals(1, uncancellable, "no longer cancellable once fetched")
        assertEquals("Fetching origin…", merge.progress)
        assertEquals("Pull", merge.fileChange)
        assertEquals("Pull Failed", merge.errorTitle)
        assertEquals("origin/main could not be pulled into feature.", merge.failure)
        assertTrue(merge.cancellable)
        assertTrue(merge.holdsStatus)

        fun done(result: GitMergeResult): String? {
            writes.mergeResult = { result }
            return merge.run(writes, root).done
        }
        assertEquals(
            "Pulled origin/main into feature (fast-forward).",
            done(GitMergeResult.Done("h", fastForward = true)),
        )
        assertEquals(
            "Squashed origin/main into the index of feature; commit the changes.",
            done(GitMergeResult.Done("h", squashed = true)),
        )
        assertEquals(
            "Pulled origin/main into feature (merge commit). $STASH_KEPT",
            done(GitMergeResult.Done("h", stashKept = true)),
        )
        writes.mergeResult = { GitMergeResult.Failed("Not possible to fast-forward, aborting.") }
        assertEquals(GitResult(false, "Not possible to fast-forward, aborting."), merge.run(writes, root).result)
    }

    @Test
    fun `what the fetch brought is told first, also when the branch is up to date`() {
        fetched(GitFetchChanges(updatedBranches = 2))
        assertEquals(
            "Fetched origin: 2 branches updated. Pulled origin/main into feature (merge commit).",
            merge.run(writes, root).done,
        )

        writes.calls.clear()
        writes.ancestry = { ancestor, descendant -> ancestor == ref && descendant == "HEAD" }
        assertEquals(
            "Fetched origin: 2 branches updated. feature is already up to date with origin/main.",
            merge.run(writes, root).done,
        )
        assertEquals(listOf("fetch origin"), writes.calls, "nothing merged")

        fetched(GitFetchChanges())
        assertEquals("feature is already up to date with origin/main.", merge.run(writes, root).done, "nothing new")
        fetched(null)
        assertEquals("feature is already up to date with origin/main.", merge.run(writes, root).done, "not read")
    }

    @Test
    fun `a fetch that fails, is cancelled or prunes the branch pulls nothing`() {
        writes.fetchResult = { GitFetchResult(GitResult(false, "fatal: unable to access")) }
        val failed = merge.run(writes, root, context)
        assertEquals(GitResult(false, "fatal: unable to access"), failed.result)
        assertEquals("origin could not be fetched; nothing was pulled.", failed.failure)
        assertNull(failed.done)

        writes.fetchResult = { GitFetchResult(GitResult(false, "Cancelled."), cancelled = true) }
        val cancelled = merge.run(writes, root, context)
        assertEquals(RefOutcome(GitResult(true, ""), PULL_CANCELLED, cancelled = true), cancelled)
        assertEquals(0, uncancellable)

        fetched(GitFetchChanges(prunedBranches = 1))
        writes.goneRemoteBranches = setOf(ref)
        val gone = merge.run(writes, root, context)
        assertEquals(GitResult(false, "origin/main no longer exists on origin."), gone.result)
        assertEquals("Fetched origin: 1 branch pruned.", gone.done)
        assertEquals(listOf("fetch origin", "fetch origin", "fetch origin"), writes.calls, "nothing merged")
    }

    @Test
    fun `a pull with rebase uses the fork point and counts the rebased commits, a fast-forward when only behind`() {
        writes.commitCounts = { from, to -> if (from == ref && to == "HEAD") 3 else null }

        val outcome = rebase.run(writes, root, context)

        assertEquals("Pulled origin/main into feature (rebased 3 commits).", outcome.done)
        assertEquals(
            listOf("fetch origin", "rebase merges $ref", "rebase --fork-point feature $ref autostash"),
            writes.calls,
        )
        assertEquals(listOf("Rebasing feature onto origin/main…"), progress)

        behind = true
        assertEquals("Pulled origin/main into feature (fast-forward).", rebase.run(writes, root).done)
        behind = false
        writes.commitCounts = { _, _ -> 1 }
        assertEquals("Pulled origin/main into feature (rebased 1 commit).", rebase.run(writes, root).done)
        writes.rebaseResult = { GitRebaseResult.Done("h", stashKept = true) }
        assertEquals(
            "Pulled origin/main into feature (rebased 1 commit). $STASH_KEPT",
            rebase.copy(autostash = false).run(writes, root).done,
        )
        assertEquals("rebase --fork-point feature $ref", writes.calls.last())
    }

    @Test
    fun `merge commits are asked about after the fetch, and the confirmed rebase does not fetch again`() {
        fetched(GitFetchChanges(newBranches = 1))
        writes.rebaseMerges = 2

        val asked = rebase.run(writes, root, context)

        val confirmation = assertNotNull(asked.confirmation)
        assertEquals(
            "feature has 2 merge commits since origin/main; the rebase drops them and replays their changes as plain " +
                "commits.",
            confirmation.question,
        )
        assertEquals("Fetched origin: 1 new branch.", asked.done)
        assertEquals(listOf("fetch origin", "rebase merges $ref"), writes.calls)
        val confirmed = confirmation.confirmed as PullBranch
        assertFalse(confirmed.cancellable)
        assertEquals("Rebasing feature onto origin/main…", confirmed.progress)

        writes.calls.clear()
        val done = confirmed.run(writes, root, context)
        assertEquals(listOf("rebase --fork-point feature $ref autostash"), writes.calls)
        assertEquals("Fetched origin: 1 new branch. Pulled origin/main into feature (rebased 1 commit).", done.done)
        assertEquals(1, uncancellable, "only the fetch told it")

        writes.rebaseMerges = null
        assertNotNull(rebase.run(writes, root).confirmation, "maybe some")
    }

    @Test
    fun `commits the fork point leaves out are told, and merges are counted from the fork point`() {
        writes.forkPoint = "f0"
        writes.commitCounts = { from, to -> if (from == ref && to == "f0") 2 else 1 }

        val outcome = rebase.run(writes, root)

        assertEquals(
            "Pulled origin/main into feature (rebased 1 commit). 2 commits that origin/main no longer has were left " +
                "out, as git pull --rebase does; ORIG_HEAD still points to them.",
            outcome.done,
        )
        assertEquals("rebase merges f0", writes.calls[1])
        assertTrue("count $ref..f0" in writes.reads)

        writes.commitCounts = { from, _ -> if (from == ref) 1 else 0 }
        val pending = GitPendingRebase("feature", "1a2b3c4d", "5e6f7a8b", 1, 2, listOf("a.txt"))
        writes.rebaseResult = { GitRebaseResult.Stopped(pending, "CONFLICT") }
        assertEquals(
            "Rebase of feature stopped at 5e6f7a8b with conflicts. 1 commit that origin/main no longer has was left " +
                "out, as git pull --rebase does; ORIG_HEAD still points to it.",
            rebase.run(writes, root).done,
        )
        writes.rebaseResult = { GitRebaseResult.Failed("cannot rebase") }
        assertNull(rebase.run(writes, root).done, "nothing was rebased")

        writes.calls.clear()
        writes.forkPoint = null
        writes.rebaseResult = { GitRebaseResult.Done("h") }
        assertEquals("Pulled origin/main into feature (rebased 1 commit).", rebase.run(writes, root).done)
        assertEquals("rebase merges $ref", writes.calls[1], "without a fork point from the merge base")
    }

    @Test
    fun `a branch that is no longer current is neither merged, rebased nor told up to date`() {
        writes.currentBranch = "other"
        writes.ancestry = { _, _ -> true }

        listOf(merge, rebase).forEach { pull ->
            val outcome = pull.run(writes, root)
            assertEquals(GitResult(false, "'feature' is no longer the current branch."), outcome.result)
            assertNull(outcome.confirmation)
        }
        assertEquals(listOf("fetch origin", "fetch origin"), writes.calls)
        writes.currentBranch = null
        assertFalse(
            rebase
                .copy(fetched = true)
                .run(writes, root)
                .result.success,
            "detached",
        )
    }

    @Test
    fun `a rebase that stops leaves it to the bar`() {
        val pending = GitPendingRebase("feature", "1a2b3c4d", "5e6f7a8b", 1, 2, listOf("a.txt"))
        writes.rebaseResult = { GitRebaseResult.Stopped(pending, "CONFLICT") }

        val outcome = rebase.run(writes, root)

        assertEquals("Rebase of feature stopped at 5e6f7a8b with conflicts.", outcome.done)
        assertEquals(RebaseStop("feature", "5e6f7a8b", listOf("a.txt"), "CONFLICT"), outcome.rebaseStopped)
    }

    @Test
    fun `the dialog is asked for with the branches of known remotes, the given one or the upstream chosen`() {
        val branches =
            listOf(
                "refs/remotes/origin/main",
                "refs/remotes/origin/HEAD",
                "refs/remotes/gone/x",
                "refs/remotes/team/a/x",
                "refs/remotes/origin/a",
            )
        writes.pullSources = sources(branches, upstream = ref, remotes = listOf("origin", "team/a"))

        val prompt = PreparePull().run(writes, root).pull

        assertEquals(
            PullPrompt(
                "feature",
                listOf("refs/remotes/origin/a", "refs/remotes/origin/main", "refs/remotes/team/a/x"),
                listOf("origin", "team/a"),
                preselected = ref,
            ),
            prompt,
        )
        assertEquals(
            "refs/remotes/team/a/x",
            PreparePull("refs/remotes/team/a/x").run(writes, root).pull?.preselected,
        )
        assertEquals(ref, PreparePull("refs/remotes/gone/x").run(writes, root).pull?.preselected, "not offered")
        writes.pullSources = sources(branches, remotes = listOf("origin"))
        assertNull(PreparePull().run(writes, root).pull?.preselected, "no upstream: nothing")
        assertEquals(true, PreparePull(fromPush = true).run(writes, root).pull?.fromPush)
        assertEquals(null, PreparePull().fileChange)
        assertFalse(PreparePull().cancellable)
    }

    @Test
    fun `the header says why nothing can be pulled, without a dialog`() {
        fun done(sources: GitPullSources?): RefOutcome {
            writes.pullSources = sources
            return PreparePull().run(writes, root)
        }
        val busy = "A rebase is in progress; finish or abort it first."
        assertEquals(RefOutcome(GitResult(true, ""), busy), done(sources(branch = null, busy = busy)))
        assertEquals(RefOutcome(GitResult(true, ""), PULL_NEEDS_BRANCH), done(sources(branch = null)))
        assertEquals(RefOutcome(GitResult(true, ""), PULL_NEEDS_COMMIT), done(sources(hasCommit = false)))
        assertEquals(
            RefOutcome(GitResult(true, ""), NO_REMOTES_TO_PULL),
            done(sources(emptyList(), remotes = emptyList())),
        )
        assertEquals(
            RefOutcome(GitResult(true, ""), NO_REMOTE_BRANCHES),
            done(sources(listOf("refs/remotes/origin/HEAD", "refs/remotes/gone/main"))),
        )
        assertEquals("Pull needs a current branch.", PULL_NEEDS_BRANCH)
        assertEquals("Pull needs a commit to pull into.", PULL_NEEDS_COMMIT)
        assertEquals("No remote branches to pull from; fetch first.", NO_REMOTE_BRANCHES)
        assertEquals("The repository has no remotes to pull from.", NO_REMOTES_TO_PULL)
        assertFalse(done(null).result.success)
    }

    @Test
    fun `the remote of a remote branch is the longest that starts it`() {
        val remotes = listOf("team", "team/a", "origin")
        assertEquals("team/a", remoteOfBranch("team/a/main", remotes))
        assertEquals("team", remoteOfBranch("team/b/main", remotes))
        assertEquals("origin", remoteOfBranch("origin/main", remotes))
        assertNull(remoteOfBranch("gone/main", remotes))
        assertNull(remoteOfBranch("origin", remotes))
    }

    @ParameterizedTest
    @EnumSource(GitMergeMode::class)
    fun `the stash is offered for a rebase, and for a merge unless it squashes`(mode: GitMergeMode) {
        assertTrue(pullStashes(merge = false, mode))
        assertEquals(mode != GitMergeMode.SQUASH, pullStashes(merge = true, mode))
    }

    @Test
    fun `the message follows the branch chosen until the user changes it`() {
        val main = "Merge remote-tracking branch 'origin/main' into feature"
        val dev = "Merge remote-tracking branch 'origin/dev' into feature"
        assertEquals(dev, updatedMessage(main, dev, main))
        assertEquals(dev, updatedMessage(main, dev, "$main  \n\n"), "unchanged but for trailing spaces and lines")
        assertEquals(dev, updatedMessage("", dev, ""), "nothing chosen before")
        assertEquals(dev, updatedMessage(main, dev, " "), "blank")
        assertEquals("Mine", updatedMessage(main, dev, "Mine"))
    }
}
