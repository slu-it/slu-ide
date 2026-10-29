package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.inProgress
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real git in temporary repositories; skipped if git is not installed. */
class GitStashesTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")

    private val git = GitCli("git", testGitEnvironment())
    private val stashes = GitStashes(git)

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo.createDirectories()
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        write("a.txt", "a")
        write("b.txt", "b")
        git("add", ".")
        git("commit", "-q", "-m", "Base")
    }

    private fun git(
        vararg arguments: String,
        succeeds: Boolean = true,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                    environment()["GIT_EDITOR"] = ":"
                }.start()
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().decodeToString()
        val exit = process.waitFor()
        if (succeeds) assertEquals(0, exit, "git ${arguments.joinToString(" ")}: $output")
        return output.trimEnd()
    }

    /** Runs [script] in the repository: the shell names files the JVM cannot name in a POSIX locale. */
    private fun shell(script: String) {
        val process =
            ProcessBuilder("sh", "-c", script)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "$script: $output")
    }

    private fun write(
        name: String,
        content: String,
    ) = repo.resolve(name).writeText("$content\n")

    private fun file(name: String) = repo.resolve(name).readText()

    private fun status() = git("status", "--porcelain")

    private fun list() = stashes.list(repo).orEmpty()

    private fun pending() = git.inProgress(repo).getOrThrow()

    /** Stashes a change of a.txt to [content] with git itself, then commits another one: popping it conflicts. */
    private fun conflictingStash(content: String = "stashed"): GitStash {
        write("a.txt", content)
        git("stash", "push", "-q")
        write("a.txt", "committed")
        git("commit", "-q", "-am", "Other")
        return list().single()
    }

    @Test
    fun `lists the stashes newest first with git's messages, also non-ASCII and one made on a detached HEAD`() {
        assertEquals(emptyList(), list())
        write("a.txt", "one")
        // From an ASCII command: a JVM in a POSIX locale cannot pass the message itself.
        shell("git stash push -q -m \"Gr\$(printf '\\303\\274')\$(printf '\\303\\237')e\"")
        val first = git("rev-parse", "stash@{0}")
        git("switch", "-q", "--detach")
        write("a.txt", "two")
        git("stash", "push", "-q")
        val head = git("rev-parse", "--short", "HEAD")

        val listed = list()

        assertEquals(listOf(0, 1), listed.map { it.index })
        assertEquals(listOf("stash@{0}", "stash@{1}"), listed.map { it.name })
        assertEquals(listOf("WIP on (no branch): $head Base", "On main: Grüße"), listed.map { it.message })
        assertEquals(listOf(git("rev-parse", "stash@{0}"), first), listed.map { it.commit })
        assertEquals(git("log", "-1", "--format=%ct", "stash@{1}").toLong(), listed[1].time)
    }

    @Test
    fun `stashes tracked changes with a message, untracked files only when asked, ignored files never`() {
        git("config", "core.excludesFile", "/dev/null")
        repo.resolve(".git/info/exclude").writeText("ignored.txt\n")
        write("a.txt", "changed")
        write("new.txt", "new")
        write("ignored.txt", "ignored")

        assertEquals(GitStashResult.Done, stashes.push(repo, "Work in progress ", includeUntracked = false))
        assertEquals("On main: Work in progress", list().single().message)
        assertEquals("?? new.txt", status())

        assertEquals(GitStashResult.Done, stashes.push(repo, " ", includeUntracked = true))
        assertTrue(list()[0].message.startsWith("WIP on main: "), list()[0].message)
        assertEquals("", status())
        assertTrue(repo.resolve("ignored.txt").exists())

        assertEquals(GitStashResult.NothingToStash, stashes.push(repo, null, includeUntracked = true))
        assertEquals(2, list().size)
    }

    @Test
    fun `stashes only the paths given, an untracked one included, other changes stay`() {
        write("a.txt", "changed")
        write("b.txt", "changed")
        shell("echo new > \"n\$(printf '\\303\\253')w.txt\"")
        write("other.txt", "other")

        val result = stashes.push(repo, "Some", includeUntracked = true, paths = listOf("a.txt", "nëw.txt"))

        assertEquals(GitStashResult.Done, result)
        assertEquals(listOf(" M b.txt", "?? other.txt"), status().lines())
        assertEquals(
            listOf("a.txt", "nëw.txt"),
            git("-c", "core.quotePath=false", "stash", "show", "--include-untracked", "--name-only").lines(),
        )
        assertEquals(
            GitStashResult.Failed("Nothing is selected to stash."),
            stashes.push(repo, null, false, emptyList()),
        )
    }

    @Test
    fun `a message that cannot be passed in the JVM's locale is refused, not mangled`() {
        stashes.canPass = { it.all { char -> char.code < 128 } }
        write("a.txt", "changed")

        assertEquals(
            GitStashResult.Failed("'Grüße' cannot be passed to git in this locale."),
            stashes.push(repo, "Grüße", includeUntracked = false),
        )
        assertEquals(emptyList(), list())
    }

    @Test
    fun `pops and applies a stash without its index, applying keeps it`() {
        write("a.txt", "staged")
        git("add", "a.txt")
        git("stash", "push", "-q")
        val stash = list().single()

        assertEquals(GitStashResult.Done, stashes.apply(repo, stash))
        assertEquals(" M a.txt", status(), "not staged again")
        assertEquals(listOf(stash), list())

        git("checkout", "--", "a.txt")
        assertEquals(GitStashResult.Done, stashes.pop(repo, stash))
        assertEquals("staged\n", file("a.txt"))
        assertEquals(emptyList(), list())
    }

    @Test
    fun `a pop with conflicts keeps the stash and leaves the files unmerged, nothing else in progress`() {
        write("b.txt", "stashed")
        val stash = conflictingStash()

        assertEquals(GitStashResult.Conflicts(listOf("a.txt"), kept = true), stashes.pop(repo, stash))
        assertEquals(listOf(stash), list())
        assertEquals(listOf("M  b.txt", "UU a.txt"), status().lines().sorted(), "what merged cleanly is staged")
        assertTrue(file("a.txt").startsWith("<<<<<<< "), file("a.txt"))
        assertEquals(GitPendingConflicts(listOf("a.txt")), pending())
    }

    @Test
    fun `an apply with conflicts keeps the stash too`() {
        val stash = conflictingStash()

        assertEquals(GitStashResult.Conflicts(listOf("a.txt"), kept = true), stashes.apply(repo, stash))
        assertEquals(listOf(stash), list())
    }

    @Test
    fun `untracked files in the way stop a pop after its tracked changes, keeping the stash`() {
        write("a.txt", "stashed")
        write("u.txt", "stashed")
        write("v.txt", "stashed")
        git("stash", "push", "-q", "--include-untracked")
        val stash = list().single()
        write("u.txt", "local")

        val result = stashes.pop(repo, stash)

        assertIs<GitStashResult.Partly>(result)
        assertEquals(GitStashResult.Partly(true, listOf("v.txt"), listOf("u.txt"), true, result.message), result)
        assertTrue("already exists" in result.message, result.message)
        assertEquals("stashed\n", file("a.txt"), "the tracked changes are applied")
        assertEquals("local\n", file("u.txt"))
        assertEquals(listOf(stash), list())
        assertNull(pending())

        git("checkout", "--", "a.txt")
        repo.resolve("v.txt").toFile().delete()
        assertIs<GitStashResult.Partly>(stashes.apply(repo, stash))
    }

    @Test
    fun `local changes in the way of a stash with untracked files leave those restored, the tracked changes not`() {
        write("a.txt", "stashed")
        write("u1.txt", "stashed")
        git("stash", "push", "-q", "--include-untracked")
        val stash = list().single()
        // Also the stash's own content: git refuses all the same.
        write("a.txt", "stashed")

        val result = stashes.pop(repo, stash)

        assertIs<GitStashResult.Partly>(result)
        assertEquals(GitStashResult.Partly(false, listOf("u1.txt"), emptyList(), true, result.message), result)
        assertTrue("overwritten" in result.message, result.message)
        assertEquals("stashed\n", file("u1.txt"))
        assertEquals(listOf(stash), list())
    }

    @Test
    fun `a stash of untracked files only restores those that are not on disk, also named through git`() {
        // Through git ls-files, as for paths a JVM in a POSIX locale cannot name.
        stashes.canNameFile = { false }
        write("u1.txt", "stashed")
        write("u2.txt", "stashed")
        git("stash", "push", "-q", "--include-untracked")
        val stash = list().single()
        write("u1.txt", "mine")

        val result = stashes.pop(repo, stash)

        assertIs<GitStashResult.Partly>(result)
        assertEquals(GitStashResult.Partly(null, listOf("u2.txt"), listOf("u1.txt"), true, result.message), result)
        assertEquals("mine\n", file("u1.txt"))
        assertEquals("stashed\n", file("u2.txt"))
    }

    @Test
    fun `local changes in the way refuse a pop, changing nothing`() {
        val stash = conflictingStash()
        write("a.txt", "local")

        val result = stashes.pop(repo, stash)

        assertIs<GitStashResult.Failed>(result)
        assertTrue("overwritten" in result.message, result.message)
        assertEquals("local\n", file("a.txt"))
        assertEquals(listOf(stash), list())
        assertNull(pending())
    }

    @Test
    fun `nothing is run when stash@{n} is no longer the stash listed`() {
        write("a.txt", "first")
        git("stash", "push", "-q")
        val listed = list().single()
        write("b.txt", "second")
        git("stash", "push", "-q")

        assertEquals(GitStashResult.Changed, stashes.pop(repo, listed))
        assertEquals(GitStashResult.Changed, stashes.apply(repo, listed))
        assertEquals(GitStashResult.Changed, stashes.drop(repo, listed))
        assertEquals(GitStashResult.Changed, stashes.drop(repo, listed.copy(index = 5)))
        assertEquals(2, list().size)
        assertEquals("", status())

        assertEquals(GitStashResult.Done, stashes.drop(repo, list()[1]))
        assertEquals(listOf("second"), list().map { git("show", "${it.commit}:b.txt") })
    }

    @Test
    fun `nothing that changes files starts while git is in the middle of something, a drop does`() {
        write("a.txt", "stashed")
        git("stash", "push", "-q")
        val stash = list().single()
        git("switch", "-q", "-c", "feature")
        write("a.txt", "feature")
        git("commit", "-q", "-am", "Feature")
        git("switch", "-q", "main")
        write("a.txt", "main")
        git("commit", "-q", "-am", "Main")
        git("merge", "feature", succeeds = false)
        assertIs<GitPendingMerge>(pending(), "a merge in progress is no GitPendingConflicts")
        write("b.txt", "local")
        val merging = GitStashResult.Failed("A merge is in progress; finish or abort it first.")

        assertEquals(merging, stashes.push(repo, null, includeUntracked = false))
        assertEquals(merging, stashes.pop(repo, stash))
        assertEquals(merging, stashes.apply(repo, stash))
        assertEquals("A merge is in progress; finish or abort it first.", stashes.sources(repo).busy)
        assertEquals(
            GitResolveResult.Failed("A merge is in progress; finish or abort it first."),
            stashes.markResolved(repo),
        )
        assertEquals(GitStashResult.Done, stashes.drop(repo, stash))
        assertEquals(emptyList(), list())
    }

    @Test
    fun `the sources name the current branch, none when detached, and what keeps a stash from starting`() {
        assertEquals(GitStashSources("main", null), stashes.sources(repo))
        git("switch", "-q", "--detach")
        assertEquals(GitStashSources(null, null), stashes.sources(repo))
        git("switch", "-q", "main")

        val stash = conflictingStash()
        stashes.pop(repo, stash)

        val busy = "1 file has conflicts; resolve it and mark it resolved first."
        assertEquals(GitStashSources("main", busy), stashes.sources(repo))
        assertEquals(GitStashResult.Failed(busy), stashes.push(repo, null, includeUntracked = false))
        assertEquals(GitStashResult.Failed(busy), stashes.pop(repo, stash))
    }

    @Test
    fun `Mark Resolved refuses conflict markers left, then stages exactly the unmerged files`() {
        assertEquals(GitResolveResult.Failed("No files have conflicts."), stashes.markResolved(repo))
        val stash = conflictingStash()
        stashes.pop(repo, stash)
        write("b.txt", "unrelated")

        assertEquals(GitResolveResult.MarkersLeft(listOf("a.txt")), stashes.markResolved(repo))
        assertEquals(listOf("UU a.txt", " M b.txt"), status().lines())

        write("a.txt", "resolved")
        assertEquals(GitResolveResult.Done(listOf("a.txt")), stashes.markResolved(repo))
        assertEquals(listOf("M  a.txt", " M b.txt"), status().lines())
        assertNull(pending())
        assertEquals(listOf(stash), list(), "the stash stays until dropped")
    }

    @Test
    fun `Mark Resolved stages files that cannot be checked only once confirmed`() {
        val stash = conflictingStash()
        stashes.pop(repo, stash)
        repo.resolve("a.txt").toFile().delete()
        val unchecked = GitUncheckedConflict("a.txt", GitConflictReason.DELETED_ON_DISK)

        assertEquals(GitResolveResult.Unchecked(listOf(unchecked)), stashes.markResolved(repo))
        assertEquals(GitPendingConflicts(listOf("a.txt")), pending(), "nothing staged")

        assertEquals(GitResolveResult.Done(listOf("a.txt")), stashes.markResolved(repo, setOf(unchecked)))
        assertEquals("D  a.txt", status())
    }

    @Test
    fun `a stash and a pop from a terminal change what the watch of the git folder stamps`() {
        val before = GitFolderStamps.state(repo)
        write("a.txt", "changed")
        git("stash", "push", "-q")
        val stashed = GitFolderStamps.state(repo)
        git("stash", "pop", "-q")

        assertFalse(before == stashed)
        assertFalse(stashed == GitFolderStamps.state(repo))
    }
}
