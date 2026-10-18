package dev.sluide.gitlog

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefOperationsTest {
    private val root = Path.of("/repo")

    private val keep = "To keep them, cancel and create a branch at HEAD first."

    private val refs =
        GitRefs(
            listOf(
                GitRef("HEAD", GitRefKind.HEAD, "h1"),
                GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1"),
                GitRef("refs/heads/feature/a", GitRefKind.LOCAL_BRANCH, "h2"),
                GitRef("refs/heads/feature/b", GitRefKind.LOCAL_BRANCH, "h3"),
                GitRef("refs/remotes/origin/fix", GitRefKind.REMOTE_BRANCH, "h4"),
                GitRef("refs/tags/v1", GitRefKind.TAG, "h1"),
            ),
            "refs/heads/main",
        )

    private val nodes = branchNodes(refs)

    private fun node(path: String): BranchNode {
        fun find(nodes: List<BranchNode>): BranchNode? =
            nodes.firstNotNullOfOrNull { node ->
                if (node.path == path) node else (node as? BranchParent)?.children?.let(::find)
            }
        return checkNotNull(find(nodes)) { path }
    }

    private fun actions(vararg paths: String) = branchActions(paths.map(::node))

    private fun ref(name: String) = refs.refs.single { it.name == name }

    @Test
    fun `checks out other local branches, remote branches and tags, not HEAD, the current branch or folders`() {
        assertEquals(CheckoutBranch("feature/a"), actions("Local/feature/a").checkout)
        assertEquals(CheckoutRemote(ref("refs/remotes/origin/fix")), actions("Remote/origin/fix").checkout)
        assertNull(
            branchActions(listOf(node("Remote/origin/fix")), "refs/heads/fix").checkout,
            "its local branch is the current one",
        )
        assertEquals(CheckoutRevision("refs/tags/v1", "v1"), actions("Tags/v1").checkout)
        assertNull(actions("HEAD").checkout)
        assertNull(actions("Local/main").checkout)
        assertNull(actions("Local/feature").checkout)
        assertNull(actions("Local").checkout)
        assertNull(actions("Local/feature/a", "Local/feature/b").checkout)
    }

    @Test
    fun `starts new branches from any one ref and renames only local branches, the current one too`() {
        assertEquals(ref("HEAD"), actions("HEAD").newBranchFrom)
        assertEquals(ref("refs/remotes/origin/fix"), actions("Remote/origin/fix").newBranchFrom)
        assertNull(actions("Local/feature").newBranchFrom)
        assertNull(actions("Local/feature/a", "Tags/v1").newBranchFrom)

        assertEquals(ref("refs/heads/main"), actions("Local/main").rename)
        assertEquals(ref("refs/heads/feature/a"), actions("Local/feature/a").rename)
        assertNull(actions("Remote/origin/fix").rename)
        assertNull(actions("Tags/v1").rename)
        assertNull(actions("HEAD").rename)
        assertNull(actions("Local/feature/a", "Local/feature/b").rename)
    }

    @Test
    fun `compares other single refs with HEAD, and any single ref with the working tree`() {
        assertEquals(ref("refs/heads/feature/a"), actions("Local/feature/a").compare)
        assertEquals(ref("refs/remotes/origin/fix"), actions("Remote/origin/fix").compare)
        assertEquals(ref("refs/tags/v1"), actions("Tags/v1").compare)
        assertNull(actions("Local/main").compare, "the current branch")
        assertNull(actions("HEAD").compare)
        assertNull(branchActions(listOf(branchNodes(GitRefs(refs.refs, null)).first())).compare, "a detached HEAD")
        assertNull(actions("Local/feature").compare)
        assertNull(actions("Local/feature/a", "Tags/v1").compare)

        assertEquals(ref("HEAD"), actions("HEAD").workingTreeDiff)
        assertEquals(ref("refs/heads/main"), actions("Local/main").workingTreeDiff)
        assertEquals(ref("refs/tags/v1"), actions("Tags/v1").workingTreeDiff)
        assertNull(actions("Local").workingTreeDiff)
        assertNull(actions("Local/feature/a", "Tags/v1").workingTreeDiff)
    }

    @Test
    fun `deletes local branches and tags, not the current branch, remote branches, HEAD or folders`() {
        assertEquals(
            listOf(ref("refs/heads/feature/a"), ref("refs/heads/feature/b"), ref("refs/tags/v1")),
            actions("Local/feature/a", "Local/feature/b", "Tags/v1").delete,
        )
        assertEquals(emptyList(), actions("Local/main").delete)
        assertEquals(emptyList(), actions("Local/feature/a", "Local/main").delete)
        assertEquals(emptyList(), actions("Local/feature/a", "Remote/origin/fix").delete)
        assertEquals(emptyList(), actions("HEAD").delete)
        assertEquals(emptyList(), actions("Local/feature").delete)
        assertEquals(emptyList(), actions("Local/feature/a", "Local/feature").delete)
        assertEquals(emptyList(), branchActions(emptyList()).delete)
    }

    @Test
    fun `a detached HEAD is not current, but HEAD is still not checked out or deleted`() {
        val detached = GitRefs(refs.refs, currentBranch = null)
        val head = branchNodes(detached).first()
        val actions = branchActions(listOf(head))

        assertNull(actions.checkout)
        assertEquals(emptyList(), actions.delete)
        val main = (branchNodes(detached)[1] as BranchGroup).children.single { it.name == "main" }
        assertEquals(CheckoutBranch("main"), branchActions(listOf(main)).checkout, "no branch is current")
    }

    @Test
    fun `names must be valid, passable and new unless overwritten`() {
        val existing = setOf("main", "feature/a")
        val canPass = { name: String -> name != "Gr\u00fc\u00dfe" }

        fun problem(
            name: String,
            kind: RefNameKind = RefNameKind.BRANCH,
            overwrite: Boolean = false,
            unchanged: String? = null,
        ) = nameProblem(name, kind, existing, canPass, overwrite, unchanged)

        assertNull(problem("feature/b"))
        assertEquals("Enter a name.", problem(""))
        assertEquals("'a..b' is not a valid branch name.", problem("a..b"))
        assertEquals("'a b' is not a valid tag name.", problem("a b", RefNameKind.TAG))
        assertEquals("'HEAD' is not a valid branch name.", problem("HEAD"))
        assertEquals("A name must not start with '-'.", problem("-x"))
        assertEquals("A name must not start with '-'.", problem("-x", RefNameKind.TAG))
        assertEquals("This name cannot be passed to git in this locale.", problem("Gr\u00fc\u00dfe"))
        assertEquals("Branch 'main' already exists", problem("main"))
        assertEquals("Tag 'main' already exists", problem("main", RefNameKind.TAG))
        assertNull(problem("main", overwrite = true))
        assertEquals(
            "Branch 'main' is checked out.",
            nameProblem("main", RefNameKind.BRANCH, existing, canPass, overwrite = true, current = "main"),
        )
        assertEquals(
            "Branch 'main' already exists",
            nameProblem("main", RefNameKind.BRANCH, existing, canPass, overwrite = false, current = "main"),
        )
        assertEquals("", problem("feature/a", unchanged = "feature/a"))
        assertEquals("Branch 'main' already exists", problem("main", unchanged = "feature/a"))
    }

    @Test
    fun `asks before deleting with the names and counts`() {
        assertEquals("Delete branch 'x'?", deleteQuestion(listOf("x"), emptyList()))
        assertEquals("Delete tag 'v1'?", deleteQuestion(emptyList(), listOf("v1")))
        assertEquals("Delete 2 branches?", deleteQuestion(listOf("a", "b"), emptyList()))
        assertEquals("Delete 2 tags?", deleteQuestion(emptyList(), listOf("a", "b")))
        assertEquals("Delete 1 branch and 1 tag?", deleteQuestion(listOf("a"), listOf("v1")))
        assertEquals("Delete 2 branches and 1 tag?", deleteQuestion(listOf("a", "b"), listOf("v1")))
        assertEquals("Force delete branch 'a'?", forceDeleteQuestion(listOf("a")))
        assertEquals("Force delete 2 branches?", forceDeleteQuestion(listOf("a", "b")))
    }

    @Test
    fun `operations say what they do and did`() {
        val writes = FakeWrites()

        assertEquals("Checking out main\u2026", CheckoutBranch("main").progress)
        assertEquals(RefOutcome(GitResult(true, ""), "Checked out main."), CheckoutBranch("main").run(writes, root))
        assertEquals("Creating branch x\u2026", CreateBranch("x", "h1", checkout = true, overwrite = false).progress)
        assertEquals(
            "Created branch x.",
            CreateBranch("x", "h1", checkout = false, overwrite = false).run(writes, root).done,
        )
        assertEquals(
            "Created and checked out branch y.",
            CreateBranch("y", "h1", checkout = true, overwrite = false).run(writes, root).done,
        )
        assertEquals("Created tag v1.", CreateTag("v1", "h1", "").run(writes, root).done)
        assertEquals("Renamed branch a to b.", RenameBranch("a", "b").run(writes, root).done)
        assertEquals("Deleting branch x\u2026", DeleteRefs(listOf("x"), emptyList()).progress)
        assertEquals("Deleting 1 branch and 2 tags\u2026", DeleteRefs(listOf("x"), listOf("a", "b")).progress)
        assertEquals("Deleted branch x.", DeleteRefs(listOf("x"), emptyList()).run(writes, root).done)
        assertEquals("Deleted tag v1.", DeleteRefs(emptyList(), listOf("v1")).run(writes, root).done)
        assertEquals("Deleted 3 refs.", DeleteRefs(listOf("x"), listOf("a", "b")).run(writes, root).done)
        assertEquals(
            listOf(
                "switch main",
                "branch x h1",
                "switch -c y h1",
                "tag v1 h1",
                "branch -m a b",
                "branch -d x",
                "tag -d v1",
                "branch -d x",
                "tag -d a b",
            ),
            writes.calls,
        )

        writes.result = { GitResult(false, "error: nope") }
        val failed = CheckoutRevision("h1", "h1").run(writes, root)
        assertEquals(RefOutcome(GitResult(false, "error: nope"), null), failed)
        assertEquals("Check Out", CheckoutBranch("main").fileChange)
        assertEquals("Check Out", CreateBranch("x", "h1", checkout = true, overwrite = false).fileChange)
        assertNull(CreateBranch("x", "h1", checkout = false, overwrite = false).fileChange)
        assertNull(DeleteRefs(listOf("x"), emptyList()).fileChange)
    }

    @Test
    fun `a remote branch is checked out as its local branch if there is one, else as a new tracking branch`() {
        val writes = FakeWrites()
        val remote = CheckoutRemote(ref("refs/remotes/origin/fix"))

        assertEquals("Checked out new branch fix tracking origin/fix.", remote.run(writes, root).done)
        writes.localBranches = setOf("fix")
        assertEquals("Checked out local branch fix.", remote.run(writes, root).done)
        assertEquals(listOf("switch --track refs/remotes/origin/fix", "switch fix"), writes.calls)
    }

    @Test
    fun `branches git refuses without force are handed back, other failures are shown`() {
        val writes = FakeWrites()
        writes.result =
            { line -> if (line.startsWith("branch -d")) GitResult(false, "error: not merged") else GitResult(true, "") }
        writes.notDeleted = { listOf("b") }

        val partly = DeleteRefs(listOf("a", "b"), listOf("v1")).run(writes, root)

        assertTrue(partly.result.success)
        assertEquals(listOf("b"), partly.refused)
        assertEquals("error: not merged", partly.refusedMessage)
        assertEquals("Deleted 2 refs.", partly.done)

        val single = DeleteRefs(listOf("a", "b"), emptyList()).run(writes, root)
        assertEquals("Deleted branch a.", single.done)

        writes.notDeleted = { emptyList() }
        val gone = DeleteRefs(listOf("a"), emptyList()).run(writes, root)
        assertEquals(RefOutcome(GitResult(false, "error: not merged"), null, emptyList(), "error: not merged"), gone)

        writes.result =
            { line ->
                if (line.startsWith("tag -d")) GitResult(false, "error: tag 'v1' not found.") else GitResult(true, "")
            }
        val tagFailed = DeleteRefs(listOf("a"), listOf("v1")).run(writes, root)
        assertEquals(GitResult(false, "error: tag 'v1' not found."), tagFailed.result)
        assertNull(tagFailed.done)

        writes.result = { GitResult(false, "error: worktree") }
        writes.notDeleted = { it }
        val forced = DeleteRefs(listOf("a"), emptyList(), force = true).run(writes, root)
        assertEquals(emptyList(), forced.refused)
        assertEquals(GitResult(false, "error: worktree"), forced.result)
    }

    @Test
    fun `runs one operation at a time on the background and drops outcomes after forget`() {
        val background = mutableListOf<Runnable>()
        val dispatched = mutableListOf<Runnable>()
        val writer = RefWriter(FakeWrites(), background::add, dispatched::add)
        val outcomes = mutableListOf<RefOutcome>()

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
        writer.forget()
        background.removeFirst().run()
        dispatched.removeFirst().run()
        assertFalse(writer.running.get())
        assertEquals(1, outcomes.size, "dropped")
    }

    @Test
    fun `a failure of the operation itself is an outcome`() {
        val background = mutableListOf<Runnable>()
        val writes =
            object : GitLogWrites by FakeWrites() {
                override fun switchBranch(
                    root: Path,
                    branch: String,
                ): GitResult = throw IllegalStateException("broken")
            }
        val outcomes = mutableListOf<RefOutcome>()
        val writer = RefWriter(writes, background::add) { it.run() }

        writer.run(root, CheckoutBranch("main"), outcomes::add)
        background.single().run()

        assertEquals(listOf(RefOutcome(GitResult(false, "broken"), null)), outcomes)
    }

    @Test
    fun `overwriting a branch with commits that are not on the start point asks first`() {
        val writes = FakeWrites()
        writes.tips = mapOf("x" to "old")
        writes.ancestry = { ancestor, descendant -> ancestor == "old" && descendant == "h1" }
        val overwrite = CreateBranch("x", "h2", checkout = false, overwrite = true, startLabel = "h2 label")

        val asked = overwrite.run(writes, root)

        assertEquals(
            RefConfirmation(
                "Overwrite Branch",
                "'x' has commits that are not on h2 label; they will only be reachable through the reflog. Overwrite?",
                OVERWRITE_BUTTON,
                overwrite.copy(confirmed = true),
            ),
            asked.confirmation,
        )
        assertNull(asked.done)
        assertEquals(emptyList(), writes.calls, "nothing done")
        assertEquals("Created branch x.", overwrite.copy(confirmed = true).run(writes, root).done)
        assertNull(CreateBranch("x", "h1", checkout = false, overwrite = true).run(writes, root).confirmation, "ahead")
        assertNull(CreateBranch("new", "h2", checkout = false, overwrite = true).run(writes, root).confirmation)
        writes.ancestry = { _, _ -> null }
        assertTrue(CreateBranch("x", "h1", checkout = false, overwrite = true).run(writes, root).confirmation != null)
        assertEquals(listOf("branch -f x h2", "branch -f x h1", "branch -f new h2"), writes.calls)

        assertEquals("Moves 'x' from old.", overwriteNote("x", overwrite = true, mapOf("x" to "old")))
        assertEquals("Moves 'x' from 01234567.", overwriteNote("x", overwrite = true, mapOf("x" to "0123456789abcdef")))
        assertNull(overwriteNote("x", overwrite = false, mapOf("x" to "old")))
        assertNull(overwriteNote("y", overwrite = true, mapOf("x" to "old")))
    }

    @Test
    fun `an overwrite is refused when where the branch points cannot be read`() {
        val writes = FakeWrites()
        writes.unreadable = setOf("x")

        val outcome = CreateBranch("x", "h1", checkout = true, overwrite = true).run(writes, root)

        assertEquals(RefOutcome(GitResult(false, "The commit of branch 'x' could not be read."), null), outcome)
        assertEquals(emptyList(), writes.calls)
    }

    @Test
    fun `a checkout that would leave commits of a detached HEAD on no branch or tag asks first`() {
        val writes = FakeWrites()
        writes.leftBehind = 2
        val checkout = CheckoutBranch("main")

        val asked = checkout.run(writes, root)

        assertEquals(
            RefConfirmation(
                "Check Out",
                "2 commits of the detached HEAD will no longer be on any branch or tag; they stay reachable through " +
                    "the reflog for a while.\n$keep Check out main anyway?",
                CHECKOUT_BUTTON,
                checkout.copy(confirmed = true),
            ),
            asked.confirmation,
        )
        assertNull(asked.done)
        assertEquals(emptyList(), writes.calls, "nothing done")
        assertEquals(listOf("left behind refs/heads/main"), writes.reads)
        assertEquals("Checked out main.", checkout.copy(confirmed = true).run(writes, root).done)
        assertEquals(listOf("switch main"), writes.calls)
    }

    @Test
    fun `every checkout asks with what it checks out, also when that cannot be read`() {
        val writes = FakeWrites()
        writes.leftBehind = 1
        val remote = GitRef("refs/remotes/origin/fix", GitRefKind.REMOTE_BRANCH, "h4")

        val question = CheckoutRevision("h2", "h2 label").run(writes, root).confirmation
        assertEquals(
            "1 commit of the detached HEAD will no longer be on any branch or tag; it stays reachable through the " +
                "reflog for a while.\n$keep Check out h2 label anyway?",
            question?.question,
        )
        assertEquals(CheckoutRevision("h2", "h2 label", confirmed = true), question?.confirmed)
        val newTracking = CheckoutRemote(remote).run(writes, root).confirmation
        assertEquals(CheckoutRemote(remote, confirmed = true), newTracking?.confirmed)
        assertTrue(newTracking?.question.orEmpty().endsWith("Check out origin/fix anyway?"))
        writes.localBranches = setOf("fix")
        val local = CheckoutRemote(remote).run(writes, root).confirmation
        assertTrue(local?.question.orEmpty().endsWith("Check out fix anyway?"), "the local branch it checks out")
        val create = CreateBranch("x", "h2", checkout = true, overwrite = false)
        assertEquals(create.copy(leavingConfirmed = true), create.run(writes, root).confirmation?.confirmed)
        assertNull(CreateBranch("y", "h2", checkout = false, overwrite = false).run(writes, root).confirmation)
        assertEquals(
            listOf(
                "left behind h2",
                "left behind refs/remotes/origin/fix",
                "left behind refs/heads/fix",
                "left behind h2",
            ),
            writes.reads,
            "no question for a branch that is not checked out",
        )
        assertEquals(listOf("branch y h2"), writes.calls)

        writes.leftBehind = null
        assertEquals(
            "Whether commits of the detached HEAD will no longer be on any branch or tag could not be read.\n" +
                "$keep Check out main anyway?",
            CheckoutBranch("main").run(writes, root).confirmation?.question,
        )
    }

    @Test
    fun `a new branch that overwrites and leaves a detached HEAD asks both, the overwrite first`() {
        val writes = FakeWrites()
        writes.leftBehind = 1
        writes.tips = mapOf("x" to "old")
        writes.ancestry = { _, _ -> false }
        val create = CreateBranch("x", "h2", checkout = true, overwrite = true)

        val overwrite = create.run(writes, root).confirmation
        assertEquals(OVERWRITE_BUTTON, overwrite?.button)
        val leaving = checkNotNull(overwrite).confirmed.run(writes, root).confirmation
        assertEquals(CHECKOUT_BUTTON, leaving?.button)
        assertEquals(create.copy(confirmed = true, leavingConfirmed = true), leaving?.confirmed)
        assertEquals(emptyList(), writes.calls, "nothing done before both are answered")

        assertEquals("Created and checked out branch x.", checkNotNull(leaving).confirmed.run(writes, root).done)
        assertEquals(listOf("switch -C x h2"), writes.calls)
    }

    @Test
    fun `a checkout confirmed only for leaving a detached HEAD still checks the branch it overwrites`() {
        val writes = FakeWrites()
        writes.leftBehind = 1
        val create = CreateBranch("x", "h2", checkout = true, overwrite = true)
        // The branch did not exist when asked, and appears with other commits before the answer.
        val leaving = checkNotNull(create.run(writes, root).confirmation).confirmed
        writes.tips = mapOf("x" to "old")
        writes.ancestry = { _, _ -> false }

        val outcome = leaving.run(writes, root)

        assertEquals(OVERWRITE_BUTTON, outcome.confirmation?.button)
        assertEquals(emptyList(), writes.calls)
    }

    @Test
    fun `a checkout git reports an error for after it was done says both`() {
        val writes = FakeWrites()
        writes.result = { GitResult(false, "hook failed", changed = true) }

        val outcome = CheckoutBranch("main").run(writes, root)

        assertEquals("Checked out main, but git reported an error.", outcome.done)
        assertFalse(outcome.result.success)
        assertEquals(
            "Created and checked out branch x, but git reported an error.",
            CreateBranch("x", "h1", checkout = true, overwrite = false).run(writes, root).done,
        )
    }
}
