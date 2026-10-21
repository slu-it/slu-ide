package dev.sluide.gitlog.branches

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.data.BranchParent
import dev.sluide.gitlog.data.branchNodes
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
    ): List<BranchNode> {
        fun find(nodes: List<BranchNode>): BranchNode? =
            nodes.firstNotNullOfOrNull { if (it.path == path) it else (it as? BranchParent)?.children?.let(::find) }
        return listOfNotNull(find(branchNodes(refs)))
    }

    private fun actions(
        path: String = "Local/feature",
        refs: GitRefs = this.refs,
        writing: Boolean = false,
        busy: Boolean = false,
    ) = menuActions(selected(refs, path), refs, emptyList(), writing, busy)

    @Test
    fun `offers everything that fits the selection`() {
        val actions = actions()

        assertEquals(CheckoutBranch("feature"), actions.checkout)
        assertEquals(RebaseBranch("main", "refs/heads/feature", "feature"), actions.rebase)
        assertNotNull(actions.compare)
        assertNotNull(actions.workingTreeDiff)
        assertEquals(1, actions.delete.size)
    }

    @Test
    fun `while writing only what reads is left`() {
        val actions = actions(writing = true)

        assertNull(actions.checkout)
        assertNull(actions.rebase)
        assertNull(actions.newBranchFrom)
        assertNull(actions.rename)
        assertTrue(actions.delete.isEmpty())
        assertNotNull(actions.compare)
        assertNotNull(actions.workingTreeDiff)
    }

    @Test
    fun `while a rebase or pick waits only the rebase is left out`() {
        val actions = actions(busy = true)

        assertNull(actions.rebase)
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
    fun `names the rebase even where it is not possible, generic without a target`() {
        val atHead = GitRef("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, "h1")
        val all = GitRefs(refs.refs + atHead, "refs/heads/main")
        assertEquals("Rebase 'main' onto 'origin/main'", rebaseMenuText(listOf(branchLeaf(atHead, all)), all))
        assertEquals("Rebase Current Branch onto\u2026", rebaseMenuText(listOf(branchLeaf(refs.refs[1], refs)), refs))
        assertEquals("Rebase Current Branch onto\u2026", rebaseMenuText(emptyList(), refs))
        assertEquals(
            "Rebase Current Branch onto\u2026",
            rebaseMenuText(selected(refs, "Local/feature"), GitRefs(refs.refs, null)),
        )
    }
}
