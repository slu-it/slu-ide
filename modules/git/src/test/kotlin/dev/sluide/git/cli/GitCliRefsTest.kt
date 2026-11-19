package dev.sluide.git.cli

import dev.sluide.git.FAKE_GIT_START
import dev.sluide.git.GitBranchTip
import dev.sluide.git.failingGit
import dev.sluide.git.testGitEnvironment
import dev.sluide.git.unavailableGit
import dev.sluide.git.vanishedGit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The ref readers of [GitCli] (`GitCliRefs.kt`) with the real git: what they answer for a repository, and that a git
 * that fails, or is not available, gives no answer even where the real one would.
 */
class GitCliRefsTest {
    @TempDir
    lateinit var folder: Path

    private val git = GitCli("git", testGitEnvironment())
    private lateinit var repo: TestRepository
    private val root get() = repo.root

    private lateinit var first: String
    private lateinit var second: String

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo = TestRepository(folder.resolve("repo"))
        first = repo.commit("first")
        second = repo.commit("second")
    }

    /** Refs of every kind: branches (one in a folder), an annotated and a lightweight tag, a remote branch with HEAD. */
    private fun addRefs() {
        repo.git("branch", "feature/x", first)
        repo.git("tag", "-a", "v1", "-m", "tag", first)
        repo.git("tag", "light", second)
        repo.git("update-ref", "refs/remotes/origin/main", first)
        repo.git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        repo.git("config", "remote.origin.url", "https://example.invalid/repo.git")
        repo.git("config", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*")
        repo.git("config", "branch.main.remote", "origin")
        repo.git("config", "branch.main.merge", "refs/heads/main")
    }

    private val unavailable get() = unavailableGit(folder)

    private fun failing(word: String) = failingGit(folder, word)

    /** A stand-in for git that runs [body] for anything but `--version`; a body that holds its output open says so. */
    private fun fakeGit(body: String): GitCli {
        val script =
            folder.resolve("fake-git-${System.nanoTime()}").apply {
                writeText(FAKE_GIT_START + body + "\n")
                toFile().setExecutable(true)
            }
        return GitCli(script.toString(), testGitEnvironment(), 2.seconds, 300.milliseconds).also {
            assumeTrue(it.available, "no /bin/sh to stand in for git")
        }
    }

    @Test
    fun `refs lists the branches, remote branches and tags with their targets`() {
        addRefs()
        val tag = repo.git("rev-parse", "v1")
        val us = "\u001f"

        val expected =
            listOf(
                "$first$us$us" + "refs/heads/feature/x$us",
                "$second$us$us" + "refs/heads/main$us",
                "$first$us$us" + "refs/remotes/origin/HEAD${us}refs/remotes/origin/main",
                "$first$us$us" + "refs/remotes/origin/main$us",
                "$second$us$us" + "refs/tags/light$us",
                "$tag$us$first${us}refs/tags/v1$us",
            ).joinToString("\n", postfix = "\n")
        assertEquals(expected, git.refs(root))
        assertNull(unavailable.refs(root))
        assertNull(failing("for-each-ref").refs(root))
    }

    @Test
    fun `branchRefs gives the local and remote branches with their symbolic target and upstream`() {
        addRefs()

        assertEquals(
            listOf(
                BranchRef("refs/heads/feature/x", "", ""),
                BranchRef("refs/heads/main", "", "refs/remotes/origin/main"),
                BranchRef("refs/remotes/origin/HEAD", "refs/remotes/origin/main", ""),
                BranchRef("refs/remotes/origin/main", "", ""),
            ),
            git.branchRefs(root),
        )
        assertNull(unavailable.branchRefs(root))
        assertNull(failing("for-each-ref").branchRefs(root))
    }

    @Test
    fun `branchRefs of a repository without commits is empty, not unknown`() {
        val empty = TestRepository(folder.resolve("empty"))

        assertEquals(emptyList(), git.branchRefs(empty.root))
        assertEquals("", git.localBranches(empty.root))
        assertEquals(emptyMap(), git.localBranchTips(empty.root))
    }

    @Test
    fun `localBranches lists the full names, one per line`() {
        addRefs()

        assertEquals("refs/heads/feature/x\nrefs/heads/main\n", git.localBranches(root))
        assertNull(unavailable.localBranches(root))
        assertNull(failing("for-each-ref").localBranches(root))
    }

    @Test
    fun `localBranchTips gives the commit of each branch by short name`() {
        addRefs()

        assertEquals(mapOf("feature/x" to first, "main" to second), git.localBranchTips(root))
        assertNull(unavailable.localBranchTips(root))
        assertNull(failing("for-each-ref").localBranchTips(root))
    }

    @Test
    fun `refTips gives the objects of the refs below the prefixes, without symbolic refs`() {
        addRefs()
        val tag = repo.git("rev-parse", "v1")

        assertEquals(
            mapOf("refs/remotes/origin/main" to first, "refs/tags/light" to second, "refs/tags/v1" to tag),
            git.refTips(root, listOf("refs/remotes/", "refs/tags/")),
        )
        assertEquals(emptyMap(), git.refTips(root, listOf("refs/nothing/")))
        assertNull(unavailable.refTips(root, listOf("refs/tags/")))
        assertNull(failing("for-each-ref").refTips(root, listOf("refs/tags/")))
    }

    @Test
    fun `branchTip is the commit of exactly that branch, missing or unknown`() {
        addRefs()

        assertEquals(GitBranchTip.At(second), git.branchTip(root, "main"))
        assertEquals(GitBranchTip.At(first), git.branchTip(root, "feature/x"))
        // The pattern refs/heads/feature also matches refs/heads/feature/x: only the exact name counts.
        assertEquals(GitBranchTip.Missing, git.branchTip(root, "feature"))
        assertEquals(GitBranchTip.Missing, git.branchTip(root, "nope"))
        assertEquals(GitBranchTip.Unknown, unavailable.branchTip(root, "main"))
        assertEquals(GitBranchTip.Unknown, failing("for-each-ref").branchTip(root, "main"))
    }

    @Test
    fun `headCommit and commitOfRevision give the commit a revision names`() {
        repo.git("tag", "-a", "v1", "-m", "tag", first)

        assertEquals(second, git.headCommit(root))
        assertEquals(first, git.commitOfRevision(root, "v1"), "an annotated tag names its commit")
        assertEquals(first, git.commitOfRevision(root, "HEAD~1"))
        assertNull(git.commitOfRevision(root, "no-such-revision"))
        assertNull(git.commitOfRevision(root, "-x"))
        assertNull(unavailable.commitOfRevision(root, "HEAD"))
        assertNull(failing("rev-parse").commitOfRevision(root, "HEAD"))
        assertNull(TestRepository(folder.resolve("empty")).let { git.headCommit(it.root) }, "unborn")
    }

    @Test
    fun `a revision that git answers with nothing is no commit`() {
        // Exit 0 without output: not a hash.
        assertNull(fakeGit("case \" \$* \" in *' rev-parse '*) exit 0;; esac").commitOfRevision(root, "HEAD"))
        assertNull(fakeGit("case \" \$* \" in *' symbolic-ref '*) exit 0;; esac").currentBranch(root))
        assertEquals(
            second,
            fakeGit("case \" \$* \" in *' rev-parse '*) echo ' $second ';; esac").commitOfRevision(root, "HEAD"),
        )
    }

    @Test
    fun `currentBranch is the full name of the branch HEAD is on, null when detached`() {
        assertEquals("refs/heads/main", git.currentBranch(root))
        assertEquals("refs/heads/main", git.currentBranch(TestRepository(folder.resolve("empty")).root), "unborn")
        assertNull(unavailable.currentBranch(root))
        assertNull(failing("symbolic-ref").currentBranch(root))

        repo.git("checkout", "-q", "--detach")

        assertNull(git.currentBranch(root))
    }

    @Test
    fun `parents are the full hashes in git's order, none for a root commit`() {
        repo.git("checkout", "-q", "-b", "side", first)
        val side = repo.commit("side", "other.txt")
        repo.git("merge", "-q", "--no-ff", "-m", "merge", "main")
        val merge = repo.head()

        assertEquals(listOf(first), git.parents(root, second))
        assertEquals(emptyList(), git.parents(root, first))
        assertEquals(listOf(side, second), git.parents(root, merge))
        assertNull(git.parents(root, "f".repeat(40)), "no such commit")
        assertNull(git.parents(root, "HEAD"), "git names the commit, not the revision it was asked with")
        assertNull(unavailable.parents(root, second))
        assertNull(failing("rev-list").parents(root, second))
    }

    @Test
    fun `commitMessage is the full message in UTF-8 whatever the revision's encoding says`() {
        // On standard input: an argument would go through the JVM's locale.
        repo.git("commit", "-q", "--allow-empty", "-F", "-", input = "Köln\n\nbody line\n")

        // git show adds a line break to the message's own.
        assertEquals("Köln\n\nbody line\n\n", git.headMessage(root))
        assertEquals("second\n\n", git.commitMessage(root, second))
        assertNull(git.commitMessage(root, "f".repeat(40)))
        assertNull(unavailable.headMessage(root))
        assertNull(failing("show").headMessage(root))
    }

    @Test
    fun `a revision that starts with a dash is not given to show as an option`() {
        // git show would take --format=%an as an option that replaces the format and print the author.
        assertEquals("Test\n", repo.git("show", "-s", "--format=%B", "--format=%an", "HEAD", "--") + "\n")

        assertNull(git.commitMessage(root, "--format=%an"))
    }

    @Test
    fun `isHeadUnborn tells a branch without commits from one with, unknown if git fails`() {
        assertEquals(false, git.isHeadUnborn(root))
        val empty = TestRepository(folder.resolve("empty"))
        assertEquals(true, git.isHeadUnborn(empty.root))
        assertNull(unavailable.isHeadUnborn(root))
        assertNull(failing("rev-parse").isHeadUnborn(root))
    }

    @Test
    fun `a detached HEAD without a commit is neither unborn nor on a branch`() {
        val empty = TestRepository(folder.resolve("empty"))
        // Git exits with 1 for both an unborn branch and a HEAD naming no object; only the branch tells them apart.
        empty.root.resolve(".git/HEAD").writeText("0123456789012345678901234567890123456789\n")

        assertNull(git.isHeadUnborn(empty.root))
        assertEquals("", git.headBranch(empty.root))
    }

    @Test
    fun `headBranch is the full name, empty when detached, null when unknown`() {
        assertEquals("refs/heads/main", git.headBranch(root))
        assertEquals("refs/heads/main", git.headBranch(TestRepository(folder.resolve("empty")).root))
        assertNull(unavailable.headBranch(root))
        assertNull(failing("symbolic-ref").headBranch(root))

        repo.git("checkout", "-q", "--detach")

        assertEquals("", git.headBranch(root))
    }

    @Test
    fun `headBranch has no answer for an output that is not complete or empty`() {
        // The script ends at once; a process it left behind holds the output open (docs/testing.md "Portability").
        val held = "case \" \$* \" in *' symbolic-ref '*) echo refs/heads/x; (sleep 3 &); sleep 0.3; exit 0;; esac"
        assertNull(fakeGit(held).headBranch(root))
        assertNull(fakeGit("case \" \$* \" in *' symbolic-ref '*) exit 0;; esac").headBranch(root))
        assertEquals(
            "refs/heads/x",
            fakeGit("case \" \$* \" in *' symbolic-ref '*) echo ' refs/heads/x ';; esac").headBranch(root),
        )
    }

    /** `main` has first and second; `topic` has third on top of first; the remote branch is at first. */
    private fun diverge(): String {
        repo.git("checkout", "-q", "-b", "topic", first)
        val third = repo.commit("third", "other.txt")
        repo.git("checkout", "-q", "main")
        return third
    }

    @Test
    fun `unreachableCount counts the commits of HEAD that no other branch, tag or remote branch has`() {
        val third = diverge()
        repo.git("tag", "t1", first)

        // main has first and second, topic first and third: with main out of the others, only second is main's alone.
        assertEquals(1, git.unreachableCount(root, first, branch = "main"))
        assertEquals(0, git.unreachableCount(root, second, branch = "main"))
        // Without a current branch, main is one of the others and keeps second.
        assertEquals(0, git.unreachableCount(root, first, branch = null))
        repo.git("checkout", "-q", third)
        assertEquals(0, git.unreachableCount(root, first, branch = null), "topic has HEAD")
        assertNull(unavailable.unreachableCount(root, first, "main"))
        assertNull(failing("rev-list").unreachableCount(root, first, "main"))
    }

    @Test
    fun `unreachableCount and leftBehindCount do not take a target for an option`() {
        // git rev-list --count HEAD --not --max-count=1 … prints 0 (checked with git 2.56): only the guard says null.
        assertEquals("0", repo.git("rev-list", "--count", "HEAD", "--not", "--max-count=1", "--branches", "--"))

        assertNull(git.unreachableCount(root, "--max-count=1", "main"))
        assertNull(git.leftBehindCount(root, "--max-count=1"))
    }

    @Test
    fun `leftBehindCount counts the commits of HEAD that neither the target nor any ref has`() {
        repo.git("checkout", "-q", "--detach")
        val loose = repo.commit("loose", "other.txt")

        assertEquals(1, git.leftBehindCount(root, "main"), "loose is on no ref")
        assertEquals(0, git.leftBehindCount(root, loose))
        repo.git("tag", "keep", loose)
        assertEquals(0, git.leftBehindCount(root, "main"), "a tag keeps it")
        assertNull(unavailable.leftBehindCount(root, "main"))
        assertNull(failing("rev-list").leftBehindCount(root, "main"))
    }

    @Test
    fun `lastOutgoing is the last commit HEAD has and the excluded refs do not, with its parents`() {
        val third = repo.commit("third")
        repo.git("update-ref", "refs/remotes/origin/main", first)

        // Topological order, newest first: third, second; the last line is second, whose parent is first.
        assertEquals(listOf(second, first), git.lastOutgoing(root, listOf("refs/remotes/*")))
        assertEquals(emptyList(), git.lastOutgoing(root, listOf("refs/heads/*")), "nothing outgoing")
        repo.git("update-ref", "refs/remotes/origin/main", third)
        assertEquals(emptyList(), git.lastOutgoing(root, listOf("refs/remotes/*")))
        assertNull(unavailable.lastOutgoing(root, listOf("refs/remotes/*")))
        assertNull(failing("rev-list").lastOutgoing(root, listOf("refs/remotes/*")))
    }

    @Test
    fun `mergeBase is the best common ancestor, null for none`() {
        repo.git("checkout", "-q", "-b", "topic", first)
        val third = repo.commit("third", "other.txt")
        repo.git("checkout", "-q", "--orphan", "unrelated")
        val orphan = repo.commit("orphan", "third.txt")

        assertEquals(first, git.mergeBase(root, "main", third))
        assertEquals(first, git.mergeBase(root, second, "topic"))
        assertNull(git.mergeBase(root, "main", orphan))
        assertNull(git.mergeBase(root, "main", "no-such-revision"))
        assertNull(unavailable.mergeBase(root, "main", third))
        assertNull(failing("merge-base").mergeBase(root, "main", third))
    }

    @Test
    fun `isAncestor is true, false, or null when git cannot tell`() {
        assertEquals(true, git.isAncestor(root, first, second))
        assertEquals(true, git.isAncestor(root, second, second))
        assertEquals(false, git.isAncestor(root, second, first))
        assertNull(git.isAncestor(root, "no-such-revision", second))
        assertNull(unavailable.isAncestor(root, first, second))
        assertNull(failing("merge-base").isAncestor(root, first, second))
    }

    @Test
    fun `a git that vanishes or hangs gives no answer where an exit value is read`() {
        val vanished = vanishedGit(folder)
        assertNull(vanished.isHeadUnborn(root))
        assertNull(vanished.headBranch(root))
        assertNull(vanished.isAncestor(root, first, second))

        val slow = 300.milliseconds
        assertNull(fakeGit("case \" \$* \" in *' rev-parse '*) sleep 30;; esac").isHeadUnborn(root, slow))
        assertNull(fakeGit("case \" \$* \" in *' symbolic-ref '*) sleep 30;; esac").headBranch(root, slow))
        assertNull(fakeGit("case \" \$* \" in *' merge-base '*) sleep 30;; esac").isAncestor(root, first, second, slow))
    }

    @Test
    fun `exit values 0 and 1 of the fakes are what git gives, others are unknown`() {
        // `git merge-base --is-ancestor` exits with 0, 1, and 128 for an unknown revision (checked with git 2.56).
        fun ancestor(exit: Int) =
            fakeGit("case \" \$* \" in *' merge-base '*) exit $exit;; esac").isAncestor(root, "a", "b")
        assertEquals(true, ancestor(0))
        assertEquals(false, ancestor(1))
        assertNull(ancestor(2))
        assertNull(ancestor(128))
    }

    @Test
    fun `without a current branch the branch called null is still one of the others`() {
        repo.git("branch", "null", second)
        repo.git("checkout", "-q", "--detach", second)
        repo.git("update-ref", "refs/heads/main", first)

        // Only the branch called null has second. With no current branch it is one of the others; as the current
        // branch it is left out, as git would be told with --exclude=null.
        assertEquals(0, git.unreachableCount(root, first, branch = null))
        assertEquals(1, git.unreachableCount(root, first, branch = "null"))
    }

    @Test
    fun `mergeBase has no answer for an output that is empty, and trims what git adds around a hash`() {
        assertNull(fakeGit("case \" \$* \" in *' merge-base '*) exit 0;; esac").mergeBase(root, "a", "b"))
        assertEquals(
            first,
            fakeGit("case \" \$* \" in *' merge-base '*) echo ' $first ';; esac").mergeBase(root, "a", "b"),
        )
    }

    @Test
    fun `parents does not give an option to git, which would write a file before it fails`() {
        val victim = folder.resolve("victim.txt").apply { writeText("keep me\n") }

        // git rev-list --output=<file> empties the file while it reads its options, then exits with 129.
        assertNull(git.parents(root, "--output=$victim"))

        assertEquals("keep me\n", victim.readText())
    }
}
