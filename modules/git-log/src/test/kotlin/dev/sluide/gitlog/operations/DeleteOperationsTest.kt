package dev.sluide.gitlog.operations

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitRemoteDeletion
import dev.sluide.git.GitRemoteDeletionOutcome
import dev.sluide.git.GitRemoteDeletionResult
import dev.sluide.git.GitRemoteRefResult
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.data.BranchParent
import dev.sluide.gitlog.data.FakeWrites
import dev.sluide.gitlog.data.branchNodes
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeleteOperationsTest {
    private val root = Path.of("/repo")

    private fun remoteBranch(
        remote: String,
        name: String,
        commit: String = "c-$name",
    ) = RemoteBranch(remote, GitRef("refs/remotes/$remote/$name", GitRefKind.REMOTE_BRANCH, commit))

    private val fix = remoteBranch("origin", "fix")
    private val other = remoteBranch("origin", "other")
    private val up = remoteBranch("upstream", "up")

    /** A failed deletion that reports [outcomes] by full refname, with git's [message]. */
    private fun failed(
        outcomes: Map<String, GitRemoteRefResult>,
        message: String = "error: failed to push some refs to 'server'",
    ) = GitRemoteDeletionResult(GitResult(false, message), outcomes = outcomes)

    private fun outcome(
        outcome: GitRemoteDeletionOutcome,
        summary: String = "",
    ) = GitRemoteRefResult(outcome, summary)

    @Test
    fun `remote branches are deleted with their lease, one push per remote, and the header counts them`() {
        val writes = FakeWrites()

        assertEquals("Deleting remote branch origin/fix…", DeleteRemoteBranches(listOf(fix)).progress)
        assertEquals("Deleting 3 remote branches…", DeleteRemoteBranches(listOf(fix, other, up)).progress)
        assertTrue(DeleteRemoteBranches(listOf(fix)).cancellable)
        assertNull(DeleteRemoteBranches(listOf(fix)).fileChange)
        assertFalse(DeleteRemoteBranches(listOf(fix)).holdsStatus)

        val one = DeleteRemoteBranches(listOf(fix)).run(writes, root)
        assertEquals(RefOutcome(GitResult(true, ""), "Deleted remote branch origin/fix."), one)

        writes.calls.clear()
        val progress = mutableListOf<String>()
        val three =
            DeleteRemoteBranches(listOf(fix, up, other)).run(writes, root, RunContext(progress = { progress += it }))
        assertEquals("Deleted 3 remote branches.", three.done)
        assertEquals(
            listOf(
                "push --delete origin refs/heads/fix:c-fix refs/heads/other:c-other",
                "push --delete upstream refs/heads/up:c-up",
            ),
            writes.calls,
        )
        assertEquals(listOf("Deleting remote branches on origin…", "Deleting remote branches on upstream…"), progress)
    }

    @Test
    fun `the branch on the remote is the one the refspecs map, and one they do not map is not deleted`() {
        val writes = FakeWrites()
        writes.remoteBranch = { _, ref -> if (ref.endsWith("/fix")) "refs/heads/team/fix" else null }

        val outcome = DeleteRemoteBranches(listOf(fix, other)).run(writes, root)

        assertEquals(listOf("push --delete origin refs/heads/team/fix:c-fix"), writes.calls)
        assertEquals("Deleted remote branch origin/fix.", outcome.done)
        assertFalse(outcome.result.success)
        assertEquals(
            "origin/other: no fetch refspec of origin maps a branch to it, so the branch on origin is not known.",
            outcome.result.message,
        )

        writes.calls.clear()
        val none = DeleteRemoteBranches(listOf(other)).run(writes, root)
        assertEquals(emptyList(), writes.calls, "git does not run")
        assertNull(none.done)
        assertFalse(none.result.success)
    }

    @Test
    fun `a stale, missing or unknown lease is told in the header, without an error`() {
        val writes = FakeWrites()
        writes.deleteRemoteResult = { _, _ ->
            failed(
                mapOf(
                    "refs/heads/fix" to outcome(GitRemoteDeletionOutcome.STALE, "[rejected] (stale info)"),
                    "refs/heads/other" to outcome(GitRemoteDeletionOutcome.MISSING, "[rejected] (stale info)"),
                    "refs/heads/up" to outcome(GitRemoteDeletionOutcome.STALE_OR_MISSING, "[rejected] (stale info)"),
                ),
            )
        }

        assertEquals(
            RefOutcome(
                GitResult(true, ""),
                "origin/fix changed since your last fetch. Fetch and check it before deleting.",
            ),
            DeleteRemoteBranches(listOf(fix)).run(writes, root),
        )
        assertEquals(
            "origin/other no longer exists on origin. Fetch to update.",
            DeleteRemoteBranches(listOf(other)).run(writes, root).done,
        )
        assertEquals(
            "upstream/up changed or was deleted since your last fetch. Fetch and check it before deleting.",
            DeleteRemoteBranches(listOf(up)).run(writes, root).done,
        )
    }

    @Test
    fun `a partly refused deletion names what was deleted and lists the others with their reasons`() {
        val writes = FakeWrites()
        writes.deleteRemoteResult = { _, _ ->
            failed(
                mapOf(
                    "refs/heads/fix" to outcome(GitRemoteDeletionOutcome.DELETED),
                    "refs/heads/other" to outcome(GitRemoteDeletionOutcome.REJECTED, "[remote rejected] (protected)"),
                ),
                "remote: protected branch",
            )
        }

        val outcome = DeleteRemoteBranches(listOf(fix, other)).run(writes, root)

        assertEquals("Deleted remote branch origin/fix.", outcome.done)
        assertEquals(
            GitResult(
                false,
                "origin/other: [remote rejected] (protected)\n\nremote: protected branch",
                changed = false,
            ),
            outcome.result,
        )
        assertEquals("Not every remote branch could be deleted.", DeleteRemoteBranches(listOf(fix, other)).failure)
        assertEquals("origin/other could not be deleted.", DeleteRemoteBranches(listOf(other)).failure)
        assertEquals("Delete Failed", DeleteRemoteBranches(listOf(other)).errorTitle)

        // Git failed before it reported on the refs, e.g. the remote could not be reached.
        writes.deleteRemoteResult = { _, _ -> failed(emptyMap(), "fatal: could not read from remote") }
        val unreachable = DeleteRemoteBranches(listOf(fix)).run(writes, root)
        assertNull(unreachable.done)
        assertEquals("origin/fix: not deleted\n\nfatal: could not read from remote", unreachable.result.message)
    }

    @Test
    fun `only an interrupted push may have reached its remote, remotes skipped after a cancel are only cancelled`() {
        val writes = FakeWrites()
        val cancellation = GitCancellation()
        writes.deleteRemoteResult = { _, refs ->
            cancellation.cancel()
            GitRemoteDeletionResult(
                GitResult(true, ""),
                outcomes = refs.associate { it.ref to outcome(GitRemoteDeletionOutcome.DELETED) },
            )
        }

        val skipped = DeleteRemoteBranches(listOf(fix, up)).run(writes, root, RunContext(cancellation))

        assertEquals(listOf("push --delete origin refs/heads/fix:c-fix"), writes.calls)
        assertEquals("Deleted remote branch origin/fix. Delete cancelled.", skipped.done)
        assertTrue(skipped.cancelled)
        assertTrue(skipped.result.success)

        writes.calls.clear()
        writes.deleteRemoteResult =
            { _, _ -> GitRemoteDeletionResult(GitResult(false, "Cancelled."), cancelled = true) }
        val interrupted = DeleteRemoteBranches(listOf(up)).run(writes, root, RunContext(GitCancellation()))
        assertEquals("Delete cancelled; it may have reached upstream. Fetch to check.", interrupted.done)
        assertTrue(interrupted.cancelled)

        writes.calls.clear()
        val before =
            DeleteRemoteBranches(
                listOf(up),
            ).run(writes, root, RunContext(GitCancellation().apply { cancel() }))
        assertEquals(emptyList(), writes.calls)
        assertEquals("Delete cancelled.", before.done)
        assertTrue(before.cancelled)
    }

    @Test
    fun `a cancel while ls-remote classifies keeps what the push deleted`() {
        val writes = FakeWrites()
        val cancellation = GitCancellation()
        writes.deleteRemoteResult = { _, _ ->
            // The push ran to its end; ls-remote after it was cancelled.
            cancellation.cancel()
            failed(
                mapOf(
                    "refs/heads/fix" to outcome(GitRemoteDeletionOutcome.DELETED),
                    "refs/heads/other" to outcome(GitRemoteDeletionOutcome.STALE_OR_MISSING, "[rejected] (stale info)"),
                ),
            )
        }

        val outcome = DeleteRemoteBranches(listOf(fix, other)).run(writes, root, RunContext(cancellation))

        assertEquals(
            "Deleted remote branch origin/fix. " +
                "origin/other changed or was deleted since your last fetch. Fetch and check it before deleting.",
            outcome.done,
        )
        assertFalse(outcome.cancelled)
        assertTrue(outcome.result.success)
    }

    @Test
    fun `tags are deleted on the checked remotes first, then locally`() {
        val writes = FakeWrites()
        val progress = mutableListOf<String>()

        val outcome =
            DeleteRefs(listOf("a"), listOf("v1", "v2"), remotes = listOf("origin", "upstream"))
                .run(writes, root, RunContext(progress = { progress += it }))

        assertEquals(
            listOf(
                "push --delete origin refs/tags/v1 refs/tags/v2",
                "push --delete upstream refs/tags/v1 refs/tags/v2",
                "branch -d a",
                "tag -d v1 v2",
            ),
            writes.calls,
        )
        assertEquals(listOf("Deleting 2 tags on origin…", "Deleting 2 tags on upstream…"), progress)
        assertEquals("Deleted 3 refs, the tags also on origin and upstream.", outcome.done)
        assertTrue(DeleteRefs(emptyList(), listOf("v1"), remotes = listOf("origin")).cancellable)
        assertFalse(DeleteRefs(emptyList(), listOf("v1")).cancellable)
        assertFalse(DeleteRefs(listOf("a"), emptyList(), remotes = listOf("origin")).cancellable)

        writes.calls.clear()
        assertEquals(
            "Deleted tag v1. Also deleted on origin.",
            DeleteRefs(emptyList(), listOf("v1"), remotes = listOf("origin")).run(writes, root).done,
        )
        assertEquals(
            "Deleted 2 refs. Also deleted on origin and upstream.",
            DeleteRefs(emptyList(), listOf("v1", "v2"), remotes = listOf("origin", "upstream")).run(writes, root).done,
        )
        assertEquals(
            "Deleted 2 refs, tag v1 also on origin.",
            DeleteRefs(listOf("a"), listOf("v1"), remotes = listOf("origin")).run(writes, root).done,
        )
        writes.calls.clear()
        assertEquals(
            "Deleted branch a.",
            DeleteRefs(listOf("a"), emptyList(), remotes = listOf("origin")).run(writes, root).done,
        )
        assertEquals(listOf("branch -d a"), writes.calls, "no remote without tags")
    }

    @Test
    fun `nothing is deleted locally after a remote failed or was cancelled`() {
        val writes = FakeWrites()
        writes.deleteRemoteResult = { line, _ ->
            if (line.contains("origin")) {
                GitRemoteDeletionResult(
                    GitResult(true, ""),
                    outcomes = mapOf("refs/tags/v1" to outcome(GitRemoteDeletionOutcome.DELETED)),
                )
            } else {
                failed(
                    mapOf(
                        "refs/tags/v1" to outcome(GitRemoteDeletionOutcome.DELETED),
                        "refs/tags/v2" to
                            outcome(GitRemoteDeletionOutcome.REJECTED, "[remote rejected] (hook declined)"),
                    ),
                    "remote: no",
                )
            }
        }

        val failedOutcome =
            DeleteRefs(emptyList(), listOf("v1", "v2"), remotes = listOf("origin", "upstream")).run(writes, root)

        assertEquals(
            listOf(
                "push --delete origin refs/tags/v1 refs/tags/v2",
                "push --delete upstream refs/tags/v1 refs/tags/v2",
            ),
            writes.calls,
        )
        assertNull(failedOutcome.done)
        assertEquals("The tags could not be deleted on upstream; nothing was deleted locally.", failedOutcome.failure)
        assertEquals(
            "v2: [remote rejected] (hook declined)\n" +
                "Deleted on origin before that: v1, v2.\nDeleted on upstream before that: v1.\n\nremote: no",
            failedOutcome.result.message,
        )

        writes.calls.clear()
        val cancellation = GitCancellation().apply { cancel() }
        val cancelled =
            DeleteRefs(
                emptyList(),
                listOf("v1"),
                remotes = listOf("origin"),
            ).run(writes, root, RunContext(cancellation))
        assertEquals(emptyList(), writes.calls)
        assertTrue(cancelled.cancelled)
        assertEquals("Delete cancelled.", cancelled.done)

        writes.calls.clear()
        writes.deleteRemoteResult = { line, refs ->
            if (line.contains("origin")) {
                GitRemoteDeletionResult(
                    GitResult(true, ""),
                    outcomes = refs.associate { it.ref to outcome(GitRemoteDeletionOutcome.DELETED) },
                )
            } else {
                GitRemoteDeletionResult(GitResult(false, "Cancelled."), cancelled = true)
            }
        }
        val interrupted =
            DeleteRefs(emptyList(), listOf("v1"), remotes = listOf("origin", "upstream")).run(writes, root)
        assertEquals(listOf("push --delete origin refs/tags/v1", "push --delete upstream refs/tags/v1"), writes.calls)
        assertEquals(
            "Deleted on origin before that: v1. Delete cancelled; it may have reached upstream. Fetch to check.",
            interrupted.done,
        )
        assertTrue(interrupted.cancelled)
    }

    @Test
    fun `a branch refused as not fully merged does not count as deleted next to the remote tags`() {
        val writes = FakeWrites()
        writes.result =
            { line -> if (line.startsWith("branch -d")) GitResult(false, "error: not merged") else GitResult(true, "") }
        writes.notDeleted = { it }

        val outcome = DeleteRefs(listOf("a"), listOf("v1"), remotes = listOf("origin")).run(writes, root)

        assertEquals(listOf("push --delete origin refs/tags/v1", "branch -d a", "tag -d v1"), writes.calls)
        assertEquals("Deleted tag v1. Also deleted on origin.", outcome.done)
        assertEquals(listOf("a"), outcome.refused)
    }

    @Test
    fun `when the local deletion fails after the remotes, the header says what the remotes deleted`() {
        val writes = FakeWrites()
        writes.result =
            { line ->
                if (line.startsWith("tag -d")) GitResult(false, "error: tag 'v1' not found.") else GitResult(true, "")
            }

        val one = DeleteRefs(emptyList(), listOf("v1"), remotes = listOf("origin")).run(writes, root)
        assertEquals("Deleted tag v1 on origin.", one.done)
        assertEquals(GitResult(false, "error: tag 'v1' not found."), one.result)

        val several =
            DeleteRefs(
                emptyList(),
                listOf("v1", "v2"),
                remotes = listOf("origin", "upstream", "fork"),
            ).run(writes, root)
        assertEquals("Deleted the tags on origin, upstream and fork.", several.done)
        assertFalse(several.result.success)
    }

    @Test
    fun `remotes are joined with and`() {
        assertEquals("origin", joinedWithAnd(listOf("origin")))
        assertEquals("origin and upstream", joinedWithAnd(listOf("origin", "upstream")))
        assertEquals("origin, upstream and fork", joinedWithAnd(listOf("origin", "upstream", "fork")))
    }

    @Test
    fun `a protected branch is refused without running git, by its short name or as the refspecs map it`() {
        val writes = FakeWrites()
        val main = remoteBranch("origin", "main")

        val refused = DeleteRemoteBranches(listOf(fix, main)).run(writes, root)

        assertEquals(emptyList(), writes.calls)
        assertEquals(emptyList(), writes.threads, "not even the refspecs are read")
        assertNull(refused.done)
        assertEquals(
            GitResult(false, "Deleting origin/main is not allowed (protected branch).", changed = false),
            refused.result,
        )
        assertEquals("origin/main is protected; nothing was deleted.", refused.failure)
        val several = DeleteRemoteBranches(listOf(main, remoteBranch("upstream", "master"))).run(writes, root)
        assertEquals("No remote branch was deleted.", several.failure)
        assertEquals(
            "Deleting origin/main is not allowed (protected branch).\n" +
                "Deleting upstream/master is not allowed (protected branch).",
            several.result.message,
        )

        writes.remoteBranch = { _, ref -> if (ref.endsWith("/trunk")) "refs/heads/master" else "refs/heads/fix" }
        val mapped = DeleteRemoteBranches(listOf(fix, remoteBranch("origin", "trunk"))).run(writes, root)
        assertEquals(emptyList(), writes.calls, "nothing pushed")
        assertEquals("Deleting origin/trunk is not allowed (protected branch).", mapped.result.message)
        assertFalse(mapped.result.success)
    }

    @Test
    fun `the question names the remote branch or counts them`() {
        assertEquals("Delete remote branch 'origin/fix'?", deleteRemoteQuestion(listOf(fix)))
        assertEquals("Delete 3 remote branches?", deleteRemoteQuestion(listOf(fix, other, up)))
    }

    @Test
    fun `remote branches are deletable when the selection holds only remote branches of known remotes`() {
        val refs =
            GitRefs(
                listOf(
                    GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1"),
                    GitRef("refs/remotes/origin/HEAD", GitRefKind.REMOTE_BRANCH, "h1"),
                    GitRef("refs/remotes/origin/fix", GitRefKind.REMOTE_BRANCH, "h2"),
                    GitRef("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, "h1"),
                    GitRef("refs/remotes/upstream/Master", GitRefKind.REMOTE_BRANCH, "h1"),
                    GitRef("refs/remotes/upstream/up", GitRefKind.REMOTE_BRANCH, "h3"),
                    GitRef("refs/remotes/old/gone", GitRefKind.REMOTE_BRANCH, "h4"),
                    GitRef("refs/tags/v1", GitRefKind.TAG, "h1"),
                ),
                "refs/heads/main",
            )
        val nodes = branchNodes(refs)
        val remotes = listOf("origin", "upstream")

        fun node(path: String): BranchNode {
            fun find(nodes: List<BranchNode>): BranchNode? =
                nodes.firstNotNullOfOrNull { node ->
                    if (node.path == path) node else (node as? BranchParent)?.children?.let(::find)
                }
            return checkNotNull(find(nodes)) { path }
        }

        fun actions(vararg paths: String) = branchActions(paths.map(::node), "refs/heads/main", remotes)

        val both = actions("Remote/origin/fix", "Remote/upstream/up")
        assertEquals(
            listOf(
                RemoteBranch("origin", GitRef("refs/remotes/origin/fix", GitRefKind.REMOTE_BRANCH, "h2")),
                RemoteBranch("upstream", GitRef("refs/remotes/upstream/up", GitRefKind.REMOTE_BRANCH, "h3")),
            ),
            both.deleteRemote,
        )
        assertEquals(emptyList(), both.delete)
        assertEquals(emptyList(), actions("Remote/origin/fix", "Local/main").deleteRemote, "mixed")
        assertEquals(emptyList(), actions("Remote/origin/fix", "Tags/v1").deleteRemote, "mixed")
        assertEquals(emptyList(), actions("Tags/v1", "Remote/origin/fix").delete, "mixed")
        assertEquals(emptyList(), actions("Remote/origin/HEAD").deleteRemote, "origin/HEAD")
        assertEquals(emptyList(), actions("Remote/origin/main").deleteRemote, "protected")
        assertEquals(emptyList(), actions("Remote/upstream/Master").deleteRemote, "protected in any case")
        assertEquals(
            emptyList(),
            actions("Remote/origin/fix", "Remote/origin/main").deleteRemote,
            "with a protected one",
        )
        assertEquals(emptyList(), actions("Remote/origin/fix", "Remote/origin/HEAD").deleteRemote)
        assertEquals(emptyList(), actions("Remote/old/gone").deleteRemote, "a removed remote")
        assertEquals(emptyList(), actions("Remote/origin").deleteRemote, "a remote")
        assertEquals(emptyList(), actions("Tags/v1").deleteRemote)
    }
}
