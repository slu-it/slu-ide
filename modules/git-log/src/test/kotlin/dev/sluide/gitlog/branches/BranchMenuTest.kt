package dev.sluide.gitlog.branches

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.data.branchNodes
import dev.sluide.gitlog.data.findNode
import dev.sluide.gitlog.operations.CheckoutBranch
import dev.sluide.gitlog.operations.RebaseBranch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BranchMenuTest {
    private val refs =
        GitRefs(
            listOf(
                GitRef("HEAD", GitRefKind.HEAD, "h1"),
                GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1"),
                GitRef("refs/heads/feature", GitRefKind.LOCAL_BRANCH, "h2"),
            ),
            "refs/heads/main",
        )

    private fun selected(
        refs: GitRefs,
        path: String,
    ): List<BranchNode> = listOfNotNull(findNode(branchNodes(refs), path))

    private fun actions(
        path: String = "Local/feature",
        refs: GitRefs = this.refs,
        writing: Boolean = false,
        busy: Boolean = false,
        remotes: List<String> = emptyList(),
    ) = menuActions(selected(refs, path), refs, remotes, writing, busy)

    @Test
    fun `offers everything that fits the selection`() {
        val actions = actions()

        assertEquals(CheckoutBranch("feature"), actions.checkout)
        assertEquals(RebaseBranch("main", "refs/heads/feature", "feature"), actions.rebase)
        assertEquals("refs/heads/feature", actions.merge?.name)
        assertNotNull(actions.compare)
        assertNotNull(actions.workingTreeDiff)
        assertEquals(1, actions.delete.size)
    }

    @Test
    fun `while writing only what reads is left`() {
        val actions = actions(writing = true)

        assertNull(actions.checkout)
        assertNull(actions.rebase)
        assertNull(actions.merge)
        assertNull(actions.newBranchFrom)
        assertNull(actions.rename)
        assertTrue(actions.delete.isEmpty())
        assertNotNull(actions.compare)
        assertNotNull(actions.workingTreeDiff)
    }

    @Test
    fun `while writing a remote branch can neither be fetched nor deleted on its remote`() {
        val withRemote =
            GitRefs(refs.refs + GitRef("refs/remotes/origin/x", GitRefKind.REMOTE_BRANCH, "h2"), "refs/heads/main")
        val idle = actions("Remote/origin/x", withRemote, remotes = listOf("origin"))
        assertEquals("origin", idle.fetch)
        assertEquals(1, idle.deleteRemote.size)

        val writing = actions("Remote/origin/x", withRemote, writing = true, remotes = listOf("origin"))
        assertNull(writing.fetch)
        assertTrue(writing.deleteRemote.isEmpty())
    }

    @Test
    fun `while a merge, rebase or pick waits only rebase, merge and pull are left out`() {
        val actions = actions(busy = true)

        assertNull(actions.rebase)
        assertNull(actions.merge)
        assertNotNull(actions.checkout)
        assertNotNull(actions.rename)
        assertNotNull(actions.compare)
    }

    @Test
    fun `without a commit at HEAD nothing is compared or rebased`() {
        val unborn = GitRefs(refs.refs.filter { it.kind != GitRefKind.HEAD }, "refs/heads/main")
        val actions = actions(refs = unborn)

        assertNull(actions.compare)
        assertNull(actions.rebase)
        assertNotNull(actions.workingTreeDiff)
    }

    @Test
    fun `a remote branch of a known remote can be pulled into the current branch, also at HEAD's commit`() {
        val remote =
            listOf(
                GitRef("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, "h1"),
                GitRef("refs/remotes/origin/HEAD", GitRefKind.REMOTE_BRANCH, "h1"),
                GitRef("refs/remotes/team/a/x", GitRefKind.REMOTE_BRANCH, "h2"),
                GitRef("refs/remotes/gone/x", GitRefKind.REMOTE_BRANCH, "h2"),
            )
        val all = GitRefs(refs.refs + remote, "refs/heads/main")
        val remotes = listOf("origin", "team", "team/a")

        fun pull(
            path: String,
            refs: GitRefs = all,
            writing: Boolean = false,
            busy: Boolean = false,
        ) = actions(path, refs, writing, busy, remotes).pull?.name

        assertEquals("refs/remotes/origin/main", pull("Remote/origin/main"))
        assertEquals("refs/remotes/team/a/x", pull("Remote/team/a/x"), "of the longest remote that matches")
        assertNull(pull("Remote/origin/HEAD"))
        assertNull(pull("Remote/gone/x"), "no such remote to fetch")
        assertNull(pull("Local/feature"), "a local branch")
        assertNull(pull("Remote/origin/main", writing = true))
        assertNull(pull("Remote/origin/main", busy = true))
        assertNull(pull("Remote/origin/main", GitRefs(all.refs, null)), "detached")
        assertNull(
            pull("Remote/origin/main", GitRefs(all.refs.filter { it.kind != GitRefKind.HEAD }, "refs/heads/main")),
        )
        assertNull(actions("Remote/origin", all, remotes = remotes).pull, "a remote, not a branch")

        val leaf = selected(all, "Remote/origin/main")
        assertEquals("Pull 'origin/main' into 'main'\u2026", pullMenuText(leaf, all))
        assertEquals("Pull into Current Branch\u2026", pullMenuText(selected(all, "Local/feature"), all))
        assertEquals("Pull into Current Branch\u2026", pullMenuText(leaf, GitRefs(all.refs, null)))
    }

    @Test
    fun `leaves of a ref know whether it is the current branch`() {
        fun ref(name: String) = refs.refs.single { it.name == name }
        assertTrue(branchLeaf(ref("refs/heads/main"), refs).current)
        assertFalse(branchLeaf(ref("refs/heads/feature"), refs).current)
        assertTrue(branchLeaf(ref("HEAD"), refs).current)
        val detached = GitRefs(refs.refs, null)
        assertFalse(branchLeaf(ref("refs/heads/main"), detached).current)
        assertFalse(branchLeaf(ref("HEAD"), detached).current)
        assertNull(
            menuActions(listOf(branchLeaf(ref("refs/heads/main"), refs)), refs, emptyList(), false, false).rebase,
        )
    }

    @Test
    fun `names the rebase and merge even where they are not possible, generic without a target`() {
        val atHead = GitRef("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, "h1")
        val all = GitRefs(refs.refs + atHead, "refs/heads/main")
        assertEquals("Rebase 'main' onto 'origin/main'", rebaseMenuText(listOf(branchLeaf(atHead, all)), all))
        assertEquals("Merge 'origin/main' into 'main'\u2026", mergeMenuText(listOf(branchLeaf(atHead, all)), all))
        assertEquals("Merge into Current Branch\u2026", mergeMenuText(emptyList(), refs))
        assertEquals("Rebase Current Branch onto\u2026", rebaseMenuText(listOf(branchLeaf(refs.refs[1], refs)), refs))
        assertEquals("Rebase Current Branch onto\u2026", rebaseMenuText(emptyList(), refs))
        assertEquals(
            "Rebase Current Branch onto\u2026",
            rebaseMenuText(selected(refs, "Local/feature"), GitRefs(refs.refs, null)),
        )
    }
}
