package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.TestRepository
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
        TestRepository(repo, environment = environment)
        write("a.txt", "a")
        write("b.txt", "b")
        git("add", ".")
        git("commit", "-q", "-m", "Base")
    }

    // A stash apply or merge that concludes would wait for an editor.
    private val environment = testGitEnvironment() + ("GIT_EDITOR" to ":")

    /** Runs git in the repository; its output cut at the end only, because a status line starts with a space. */
    private fun git(
        vararg arguments: String,
        succeeds: Boolean = true,
    ): String {
        val run = runGitRaw(repo, *arguments, environment = environment)
        if (succeeds) assertEquals(0, run.exit, "git ${arguments.joinToString(" ")}: ${run.output}")
        return run.output.trimEnd()
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

    private val callLog get() = repo.resolve(".git/calls.log")

    /** The stash of two untracked files, u1 and u2, with u1 on disk again: popping stops at it and restores u2. */
    private fun untrackedStashWithOneInTheWay(): GitStash {
        write("u1.txt", "stashed")
        write("u2.txt", "stashed")
        git("stash", "push", "-q", "--include-untracked")
        val stash = list().single()
        write("u1.txt", "mine")
        return stash
    }

    /** A stash of a.txt and the untracked u1, u2, with u1 on disk again: a pop applies a.txt, restores u2, stops at u1. */
    private fun mixedStashWithOneInTheWay(): GitStash {
        write("a.txt", "stashed")
        write("u1.txt", "stashed")
        write("u2.txt", "stashed")
        git("stash", "push", "-q", "--include-untracked")
        write("u1.txt", "mine")
        return list().single()
    }

    /** A script that prints [output] for `git stash list` and runs the real git for the rest. */
    private fun listingGit(output: String): GitCli {
        val records = folder.resolve("listing.out")
        records.writeText(output)
        val script = folder.resolve("listing-git")
        script.toFile().writeText(
            FAKE_GIT_START + "case \" \$* \" in *' stash list '*) cat '$records'; exit 0;; esac\nexec git \"\$@\"\n",
        )
        script.toFile().setExecutable(true)
        return GitCli(script.toString(), testGitEnvironment())
    }

    @Test
    fun `the hooks answer by the JVM's argument encoding, whatever the locale`() {
        assertTrue(stashes.canNameFile("a.txt"))
        assertFalse(stashes.canNameFile("lone-surrogate-\uD800"))
        assertTrue(stashes.canPass("a message"))
        assertFalse(stashes.canPass("lone-surrogate-\uD800"))
    }

    @Test
    fun `no list without a git, or when git fails`() {
        val missing = GitStashes(GitCli(folder.resolve("no-such-git").toString(), System.getenv()))

        assertNull(missing.list(repo))
        assertNull(GitStashes(failingGit(folder, "stash")).list(repo))
    }

    @Test
    fun `no list while git is not available, whatever a command would answer`() {
        // The version fails and the rest runs the real git, as a git that was removed after the check never could.
        val gone = unavailableGit(folder)
        write("a.txt", "changed")
        git("stash", "push", "-q")

        assertFalse(gone.available)
        assertNull(GitStashes(gone).list(repo))
        assertEquals(1, list().size)
    }

    @Test
    fun `the list skips records without four fields and falls back to the position for a name it cannot read`() {
        val hash = "a".repeat(40)
        // The shape of git's `stash list -z --format=%gd%x1f%H%x1f%ct%x1f%gs` (checked with git 2.56): records end in NUL.
        val output =
            listOf(
                "stash@{0}\u001f$hash\u001f1700000000", // three fields: skipped
                "stash@{4}x\u001f$hash\u001f1700000001\u001fnot the whole name",
                "", // an empty record is no record and takes no position
                "x stash@{5}\u001f$hash\u001f1700000002\u001fnor this",
                "stash@{99999999999}\u001f$hash\u001f1700000003\u001ftoo big",
                "stash@{7}\u001f$hash\u001fsoon\u001ftime that is no number",
                "stash@{8}\u001f$hash\u001f1700000005\u001fa\u001fb: kept whole",
            ).joinToString("") { "$it\u0000" } + "\u0000"

        val listed = GitStashes(listingGit(output)).list(repo)

        assertEquals(listOf(1, 2, 3, 7, 8), listed?.map { it.index })
        assertEquals(listOf(1700000001L, 1700000002L, 1700000003L, 0L, 1700000005L), listed?.map { it.time })
        assertEquals(
            listOf("not the whole name", "nor this", "too big", "time that is no number", "a\u001fb: kept whole"),
            listed?.map { it.message },
        )
    }

    @Test
    fun `a name git shows by date still lists, in the order git gave`() {
        git("config", "log.date", "iso")
        write("a.txt", "one")
        git("stash", "push", "-q")
        write("a.txt", "two")
        git("stash", "push", "-q")

        val listed = list()

        assertEquals(listOf(0, 1), listed.map { it.index })
        assertEquals(listOf(git("rev-parse", "stash@{0}"), git("rev-parse", "stash@{1}")), listed.map { it.commit })
    }

    @Test
    fun `what is in progress that cannot be read refuses every start and is the busy reason of the sources`() {
        val plain = folder.resolve("plain").also { it.createDirectories() }
        val refused = GitStashResult.Failed(IN_PROGRESS_UNREADABLE)
        val stash = GitStash(0, "a".repeat(40), 0, "On main: x")

        assertEquals(IN_PROGRESS_UNREADABLE, stashes.sources(plain).busy)
        assertEquals(refused, stashes.push(plain, null, includeUntracked = false))
        assertEquals(refused, stashes.pop(plain, stash))
        assertEquals(refused, stashes.apply(plain, stash))
        assertEquals(GitResolveResult.Failed(IN_PROGRESS_UNREADABLE), stashes.markResolved(plain))
    }

    @Test
    fun `a push gives git the options asked for and nothing else`() {
        val recording = GitStashes(recordingGit(folder, callLog))
        write("a.txt", "changed")
        write("u1.txt", "new")

        recording.push(repo, null, includeUntracked = false)
        git("stash", "drop", "-q")
        write("a.txt", "changed")
        recording.push(repo, " Named ", includeUntracked = true, paths = listOf("a.txt", "u1.txt"))
        git("stash", "drop", "-q")
        write("a.txt", "changed")
        recording.push(repo, " \t ", includeUntracked = false)

        val calls = recordedCalls(callLog).map { it.split(' ') }.filter { "push" in it }
        assertEquals(listOf("stash", "push"), calls[0].dropWhile { it != "stash" })
        assertEquals(
            listOf(
                "--literal-pathspecs",
                "stash",
                "push",
                "--include-untracked",
                "--message=Named",
                "--pathspec-from-file=-",
                "--pathspec-file-nul",
            ),
            calls[1].dropWhile { it != "--literal-pathspecs" },
        )
        assertEquals(listOf("stash", "push"), calls[2].dropWhile { it != "stash" }, "a blank message is none")
    }

    @Test
    fun `a push whose git fails says why, and one that cannot read the stash tips still reports what git did`() {
        write("a.txt", "changed")

        assertEquals(
            GitStashResult.Failed("fatal: refused by test"),
            GitStashes(failingGit(folder, "push")).push(repo, null, includeUntracked = false),
        )
        assertEquals(emptyList(), list())

        // No changes: git succeeds and nothing moves, which only two readable tips can tell.
        git("stash", "push", "-q")
        val tipsUnread = GitStashes(failingGit(folder, "for-each-ref"))
        assertEquals(GitStashResult.Done, tipsUnread.push(repo, null, includeUntracked = false))
        assertEquals(GitStashResult.Done, GitStashes(failingNthGit(folder, "for-each-ref", 1)).push(repo, null, false))
        assertEquals(GitStashResult.Done, GitStashes(failingNthGit(folder, "for-each-ref", 2)).push(repo, null, false))
        assertEquals(GitStashResult.NothingToStash, stashes.push(repo, null, includeUntracked = false))
    }

    @Test
    fun `a failed drop says why`() {
        write("a.txt", "changed")
        git("stash", "push", "-q")
        val stash = list().single()

        assertEquals(
            GitStashResult.Failed("fatal: refused by test"),
            GitStashes(failingGit(folder, "drop")).drop(repo, stash),
        )
        assertEquals(listOf(stash), list())
    }

    @Test
    fun `a pop whose reads fail after git failed still reports the files git changed`() {
        // The unmerged paths unreadable count as none; the first read of ls-files is the one of inProgress.
        val stash = conflictingStash()
        write("a.txt", "local")
        val unmergedUnread = GitStashes(failingAfterGit(folder, "pop", "ls-files"))

        val result = unmergedUnread.pop(repo, stash)

        assertIs<GitStashResult.Failed>(result)
        assertTrue("overwritten" in result.message, result.message)
    }

    @Test
    fun `the tracked paths and states of the stash decide Partly only when they could be read`() {
        val stash = mixedStashWithOneInTheWay()
        val expected = GitStashResult.Partly(true, listOf("u2.txt"), listOf("u1.txt"), true, "")

        // diff: the stash's tracked paths (only call before the write); status: before, then after.
        val trackedUnread = GitStashes(failingNthGit(folder, "diff", 1)).pop(repo, stash)
        assertIs<GitStashResult.Partly>(trackedUnread)
        assertEquals(expected.copy(tracked = null, message = trackedUnread.message), trackedUnread)
        git("checkout", "--", "a.txt")
        repo.resolve("u2.txt").toFile().delete()

        val beforeUnread = GitStashes(failingNthGit(folder, "status", 1)).pop(repo, stash)
        assertIs<GitStashResult.Partly>(beforeUnread)
        assertEquals(expected.copy(tracked = false, message = beforeUnread.message), beforeUnread)
        git("checkout", "--", "a.txt")
        repo.resolve("u2.txt").toFile().delete()

        val afterUnread = GitStashes(failingNthGit(folder, "status", 2)).pop(repo, stash)
        assertIs<GitStashResult.Partly>(afterUnread)
        assertEquals(expected.copy(tracked = false, message = afterUnread.message), afterUnread)
    }

    @Test
    fun `untracked files that cannot be told apart through git leave only what is known, whichever read fails`() {
        stashes.canNameFile = { false }
        val stash = untrackedStashWithOneInTheWay()
        // ls-files runs: 1 in progress, 2 the untracked files before, 3 the unmerged paths, 4 the untracked after.
        val beforeUnread = GitStashes(failingNthGit(folder, "ls-files", 2)).also { it.canNameFile = { false } }

        val result = beforeUnread.pop(repo, stash)

        assertIs<GitStashResult.Failed>(result)
        assertTrue("already exists" in result.message, result.message)
        repo.resolve("u2.txt").toFile().delete()

        val afterUnread = GitStashes(failingNthGit(folder, "ls-files", 4)).also { it.canNameFile = { false } }
        assertIs<GitStashResult.Failed>(afterUnread.pop(repo, stash))
        repo.resolve("u2.txt").toFile().delete()

        val bothRead = GitStashes(failingNthGit(folder, "ls-files", 9)).also { it.canNameFile = { false } }
        assertIs<GitStashResult.Partly>(bothRead.pop(repo, stash))
    }

    @Test
    fun `tracked changes applied and untracked files unread before are Partly without restored or in the way`() {
        val stash = mixedStashWithOneInTheWay()
        val unread = GitStashes(failingNthGit(folder, "ls-files", 2)).also { it.canNameFile = { false } }

        val result = unread.pop(repo, stash)

        assertIs<GitStashResult.Partly>(result)
        assertEquals(GitStashResult.Partly(true, emptyList(), emptyList(), true, result.message), result)
    }

    @Test
    fun `files the JVM can name are read by the JVM, so a git that cannot list files changes nothing`() {
        val stash = untrackedStashWithOneInTheWay()

        val result = GitStashes(failingAfterGit(folder, "pop", "ls-files")).pop(repo, stash)

        assertIs<GitStashResult.Partly>(result)
        assertEquals(GitStashResult.Partly(null, listOf("u2.txt"), listOf("u1.txt"), true, result.message), result)
    }

    @Test
    fun `an untracked file git could not write because a file stands where its folder would be is not restored`() {
        write("d.txt", "restored")
        repo.resolve("d").createDirectories()
        write("d/x.txt", "stashed")
        git("stash", "push", "-q", "--include-untracked")
        val stash = list().single()
        write("d", "a file")

        val result = stashes.pop(repo, stash)

        assertIs<GitStashResult.Partly>(result)
        assertEquals(GitStashResult.Partly(null, listOf("d.txt"), emptyList(), true, result.message), result)
        assertTrue("cannot create directory" in result.message, result.message)
        assertEquals(listOf(stash), list())
    }

    @Test
    fun `Mark Resolved names the files by the hook, git stores those the JVM cannot name`() {
        val recording = GitStashes(recordingGit(folder, callLog))
        val stash = conflictingStash()
        recording.pop(repo, stash)
        write("a.txt", "<<<<<<< ours\n")

        assertEquals(GitResolveResult.MarkersLeft(listOf("a.txt")), recording.markResolved(repo))
        assertFalse(recordedCalls(callLog).any { "hash-object" in it }, "the JVM names the file")

        recording.canNameFile = { false }
        assertEquals(GitResolveResult.MarkersLeft(listOf("a.txt")), recording.markResolved(repo))
        assertTrue(recordedCalls(callLog).any { "hash-object" in it }, "git stores the file")
    }

    @Test
    fun `Mark Resolved stops at what it cannot read or stage, staging nothing`() {
        val stash = conflictingStash()
        stashes.pop(repo, stash)
        write("a.txt", "resolved")
        // ls-files runs: 1 in progress, 2 the unmerged entries, 3 the deleted files of the check.
        val unreadable = GitResolveResult.Failed(CONFLICTS_UNREADABLE)

        assertEquals(unreadable, GitStashes(failingNthGit(folder, "ls-files", 2)).markResolved(repo))
        assertEquals(unreadable, GitStashes(failingNthGit(folder, "ls-files", 3)).markResolved(repo))
        assertEquals(
            GitResolveResult.Failed("fatal: refused by test"),
            GitStashes(failingGit(folder, "update-index")).markResolved(repo),
        )
        assertEquals("UU a.txt", status())
    }

    @Test
    fun `Mark Resolved refuses a conflicted path that is no file, with the reason`() {
        val stash = conflictingStash()
        stashes.pop(repo, stash)
        shell("rm a.txt && mkdir a.txt && echo x > a.txt/inside")

        assertEquals(
            GitResolveResult.Failed(
                "a.txt is not a regular file (a folder or a special file), which git cannot stage. Resolve it in the terminal.",
            ),
            stashes.markResolved(repo),
        )
        assertEquals(GitPendingConflicts(listOf("a.txt")), pending(), "nothing staged")
    }
}
