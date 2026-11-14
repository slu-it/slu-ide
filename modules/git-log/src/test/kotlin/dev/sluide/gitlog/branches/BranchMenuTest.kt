package dev.sluide.gitlog.branches

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitStash
import dev.sluide.gitlog.data.BranchGroup
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.data.branchNodes
import dev.sluide.gitlog.data.findNode
import dev.sluide.gitlog.operations.BranchActions
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
    fun `only a remote can be fetched, not a branch, and while writing neither it nor a remote delete`() {
        val withRemote =
            GitRefs(refs.refs + GitRef("refs/remotes/origin/x", GitRefKind.REMOTE_BRANCH, "h2"), "refs/heads/main")
        val idle = actions("Remote/origin/x", withRemote, remotes = listOf("origin"))
        assertNull(idle.fetch, "a branch is not fetched, its remote is")
        assertEquals(1, idle.deleteRemote.size)
        assertEquals("origin", actions("Remote/origin", withRemote, remotes = listOf("origin")).fetch)

        val writing = actions("Remote/origin/x", withRemote, writing = true, remotes = listOf("origin"))
        assertTrue(writing.deleteRemote.isEmpty())
        assertNull(actions("Remote/origin", withRemote, writing = true, remotes = listOf("origin")).fetch)
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
    fun `a stash is popped, applied and dropped alone, not while writing, and only dropped while busy`() {
        val stash = GitStash(0, "c0", 0, "On main: message")
        val nodes = branchNodes(refs, listOf(stash, stash.copy(index = 1, commit = "c1")))
        val leaf = listOf(assertNotNull(findNode(nodes, "Stashes/c0")))
        val group = listOf(assertNotNull(findNode(nodes, "Stashes")))

        assertEquals(StashActions(stash, stash, stash, stash, stashChanges = true), stashActions(leaf, false, false))
        assertEquals(StashActions(drop = stash, copy = stash), stashActions(leaf, writing = false, busy = true))
        assertEquals(StashActions(copy = stash), stashActions(leaf, writing = true, busy = false))
        assertEquals(StashActions(stashChanges = true), stashActions(group, false, false))
        val both = (group.single() as BranchGroup).children
        assertEquals(StashActions(stashChanges = true), stashActions(both, false, false), "not several")
        assertEquals(StashActions(), stashActions(selected(refs, "Local/feature"), false, false))
        assertEquals(StashActions(), stashActions(leaf + selected(refs, "Local/feature"), false, false))
        assertTrue(isStashSelection(leaf + group))
        assertFalse(isStashSelection(emptyList()))

        // No ref action for a stash: as for a group.
        val refActions = menuActions(leaf, refs, emptyList(), writing = false, busy = false)
        assertEquals(BranchActions(null, null, null, emptyList()), refActions)
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

    @Test
    fun `a local or remote branch can be reviewed, also the current one and while writing, a tag or a group not`() {
        val all =
            GitRefs(
                refs.refs +
                    GitRef("refs/remotes/origin/x", GitRefKind.REMOTE_BRANCH, "h2") +
                    GitRef("refs/tags/v1", GitRefKind.TAG, "h1"),
                "refs/heads/main",
            )

        assertEquals("refs/heads/feature", actions("Local/feature", all).review?.name)
        assertEquals("refs/heads/main", actions("Local/main", all).review?.name, "the current branch")
        assertEquals("refs/remotes/origin/x", actions("Remote/origin/x", all).review?.name)
        assertEquals("refs/heads/feature", actions("Local/feature", all, writing = true).review?.name)
        assertEquals("refs/heads/feature", actions("Local/feature", all, busy = true).review?.name)
        assertNull(actions("Tags/v1", all).review)
        assertNull(actions("Remote/origin", all).review, "a remote, not a branch")
    }

    private val withRemoteAndTag =
        GitRefs(
            refs.refs +
                GitRef("refs/heads/other", GitRefKind.LOCAL_BRANCH, "h3") +
                GitRef("refs/remotes/origin/x", GitRefKind.REMOTE_BRANCH, "h2") +
                GitRef("refs/remotes/origin/feature/y", GitRefKind.REMOTE_BRANCH, "h2") +
                GitRef("refs/tags/v1", GitRefKind.TAG, "h1") +
                GitRef("refs/tags/v2", GitRefKind.TAG, "h3"),
            "refs/heads/main",
        )

    private fun entries(
        vararg paths: String,
        refs: GitRefs = withRemoteAndTag,
        writing: Boolean = false,
        busy: Boolean = false,
    ) = branchMenuEntries(paths.flatMap { selected(refs, it) }, refs, listOf("origin"), writing, busy)

    private fun List<List<ShownEntry>>.entries() = map { section -> section.map { it.entry } }

    private fun List<List<ShownEntry>>.disabled() = flatten().filter { !it.enabled }.map { it.entry }

    @Test
    fun `a local branch that is not current shows its sections in order`() {
        val shown = entries("Local/feature")

        assertEquals(
            listOf(
                listOf(BranchMenuEntry.NEW_BRANCH, BranchMenuEntry.REVIEW),
                listOf(BranchMenuEntry.CHECKOUT, BranchMenuEntry.RENAME, BranchMenuEntry.DELETE),
                listOf(BranchMenuEntry.REBASE, BranchMenuEntry.MERGE),
                listOf(BranchMenuEntry.COMPARE, BranchMenuEntry.WORKING_TREE_DIFF),
            ),
            shown.entries(),
        )
        assertEquals("New Branch from 'feature'\u2026", shown[0][0].text)
        assertEquals("Rebase 'main' onto 'feature'", shown[2][0].text)
        assertTrue(shown.disabled().isEmpty())
    }

    @Test
    fun `the current branch has no checkout, rebase, merge, delete or compare`() {
        assertEquals(
            listOf(
                listOf(BranchMenuEntry.NEW_BRANCH, BranchMenuEntry.REVIEW),
                listOf(BranchMenuEntry.RENAME),
                listOf(BranchMenuEntry.WORKING_TREE_DIFF),
            ),
            entries("Local/main").entries(),
        )
    }

    @Test
    fun `a remote branch has no rename and no fetch but deletes on its remote and pulls`() {
        val shown = entries("Remote/origin/x")

        assertEquals(
            listOf(
                listOf(BranchMenuEntry.NEW_BRANCH, BranchMenuEntry.REVIEW),
                listOf(BranchMenuEntry.CHECKOUT, BranchMenuEntry.DELETE),
                listOf(BranchMenuEntry.REBASE, BranchMenuEntry.MERGE, BranchMenuEntry.PULL),
                listOf(BranchMenuEntry.COMPARE, BranchMenuEntry.WORKING_TREE_DIFF),
            ),
            shown.entries(),
        )
        assertEquals("Pull 'origin/x' into 'main'\u2026", shown[2][2].text)
    }

    @Test
    fun `a tag can start a branch, be checked out, deleted, pushed, compared and diffed`() {
        val shown = entries("Tags/v1")
        assertEquals(
            listOf(
                listOf(BranchMenuEntry.NEW_BRANCH),
                listOf(BranchMenuEntry.CHECKOUT, BranchMenuEntry.DELETE),
                listOf(BranchMenuEntry.PUSH_TAG),
                listOf(BranchMenuEntry.COMPARE, BranchMenuEntry.WORKING_TREE_DIFF),
            ),
            shown.entries(),
        )
        assertEquals("Push Tag", shown[2].single().text)
    }

    @Test
    fun `several tags are pushed together, a branch or a mix with one is not, nor is a tag without a remote`() {
        val several = entries("Tags/v1", "Tags/v2")
        assertEquals(listOf(listOf(BranchMenuEntry.DELETE), listOf(BranchMenuEntry.PUSH_TAG)), several.entries())
        assertEquals("Push Tags", several[1].single().text)
        assertEquals(1, actions("Tags/v1", withRemoteAndTag, remotes = listOf("origin")).pushTags.size)

        assertTrue(BranchMenuEntry.PUSH_TAG !in entries("Local/feature").entries().flatten())
        assertTrue(BranchMenuEntry.PUSH_TAG !in entries("Tags/v1", "Local/feature").entries().flatten())
        val noRemote =
            branchMenuEntries(selected(withRemoteAndTag, "Tags/v1"), withRemoteAndTag, emptyList(), false, false)
        assertTrue(BranchMenuEntry.PUSH_TAG !in noRemote.entries().flatten())
    }

    @Test
    fun `pushing tags is disabled while writing but not while busy, as it changes no files`() {
        assertEquals(
            listOf(BranchMenuEntry.PUSH_TAG),
            entries("Tags/v1", writing = true)
                .flatten()
                .filter { it.entry == BranchMenuEntry.PUSH_TAG && !it.enabled }
                .map { it.entry },
        )
        assertTrue(actions("Tags/v1", withRemoteAndTag, writing = true, remotes = listOf("origin")).pushTags.isEmpty())
        assertFalse(BranchMenuEntry.PUSH_TAG in entries("Tags/v1", busy = true).disabled())
        assertEquals(1, actions("Tags/v1", withRemoteAndTag, busy = true, remotes = listOf("origin")).pushTags.size)
    }

    @Test
    fun `a remote and a folder of its branches only fetch`() {
        assertEquals(listOf(listOf(BranchMenuEntry.FETCH)), entries("Remote/origin").entries())
        assertEquals(listOf(listOf(BranchMenuEntry.FETCH)), entries("Remote/origin/feature").entries())
        assertEquals("Fetch", entries("Remote/origin").single().single().text)
    }

    @Test
    fun `several branches only delete, a group or a mixed selection shows nothing`() {
        assertEquals(
            listOf(listOf(BranchMenuEntry.DELETE)),
            entries("Local/feature", "Local/other", "Tags/v1").entries(),
        )
        assertEquals(
            listOf(listOf(BranchMenuEntry.DELETE)),
            entries("Remote/origin/x", "Remote/origin/feature/y").entries(),
        )
        assertTrue(entries("Local").isEmpty())
        assertTrue(entries("Remote").isEmpty())
        assertTrue(entries("Tags").isEmpty())
        assertTrue(entries("Local/feature", "Remote/origin/x").isEmpty(), "local and remote together")
        assertTrue(entries("Local/feature", "Remote/origin").isEmpty())
        assertTrue(entries().isEmpty())
    }

    @Test
    fun `while writing the same entries stay and only what reads is enabled`() {
        val idle = entries("Local/feature").entries()
        val writing = entries("Local/feature", writing = true)

        assertEquals(idle, writing.entries())
        assertEquals(
            listOf(
                BranchMenuEntry.CHECKOUT,
                BranchMenuEntry.RENAME,
                BranchMenuEntry.DELETE,
                BranchMenuEntry.REBASE,
                BranchMenuEntry.MERGE,
                BranchMenuEntry.NEW_BRANCH,
            ).toSet(),
            writing.disabled().toSet(),
        )
        assertEquals(listOf(listOf(BranchMenuEntry.FETCH)), entries("Remote/origin", writing = true).entries())
        assertEquals(listOf(BranchMenuEntry.FETCH), entries("Remote/origin", writing = true).disabled())
    }

    @Test
    fun `while busy only rebase, merge and pull are disabled`() {
        val busy = entries("Remote/origin/x", busy = true)

        assertEquals(entries("Remote/origin/x").entries(), busy.entries())
        assertEquals(
            setOf(BranchMenuEntry.REBASE, BranchMenuEntry.MERGE, BranchMenuEntry.PULL),
            busy.disabled().toSet(),
        )
    }

    @Test
    fun `without a commit at HEAD there is no compare`() {
        val unborn = GitRefs(withRemoteAndTag.refs.filter { it.kind != GitRefKind.HEAD }, "refs/heads/main")
        val shown = entries("Local/feature", refs = unborn).entries().flatten()

        assertFalse(BranchMenuEntry.COMPARE in shown)
        assertFalse(BranchMenuEntry.REBASE in shown)
        assertTrue(BranchMenuEntry.WORKING_TREE_DIFF in shown)
    }
}
