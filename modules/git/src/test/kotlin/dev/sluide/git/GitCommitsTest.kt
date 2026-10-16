package dev.sluide.git

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real git in temporary repositories; skipped if git is not installed. */
class GitCommitsTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")

    private val git = GitCli("git", testGitEnvironment())
    private val commits = GitCommits(git)

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo.createDirectories()
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
    }

    private fun git(
        vararg arguments: String,
        input: String? = null,
        directory: Path = repo,
    ): String = gitRaw(*arguments, input = input, directory = directory).trim()

    /** The output of git with [arguments], untrimmed. */
    private fun gitRaw(
        vararg arguments: String,
        input: String? = null,
        directory: Path = repo,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        process.outputStream.use { stdin -> input?.let { stdin.write(it.toByteArray(Charsets.UTF_8)) } }
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output
    }

    /** Commits [file] with [content] (a line); returns the commit's hash. */
    private fun commit(
        subject: String,
        file: String = "file.txt",
        content: String = subject,
    ): String {
        repo.resolve(file).writeText("$content\n")
        git("add", file)
        git("commit", "-q", "-m", subject)
        return head()
    }

    private fun head() = git("rev-parse", "HEAD")

    private fun status() =
        gitRaw("status", "--porcelain", "-z", "--untracked-files=all").split('\u0000').filter(String::isNotEmpty)

    private fun gitFile(name: String) = repo.resolve(".git").resolve(name)

    /** A commit on a branch `other` that changes file.txt, and one on main that changes it too: they conflict. */
    private fun conflicting(): String {
        commit("Base")
        git("switch", "-q", "-c", "other")
        val theirs = commit("Theirs")
        git("switch", "-q", "main")
        commit("Ours")
        return theirs
    }

    @Test
    fun `cherry-picks a commit and gives the new HEAD`() {
        commit("Base")
        git("switch", "-q", "-c", "other")
        val picked = commit("Feature", "feature.txt")
        git("switch", "-q", "main")

        val result = commits.pick(repo, GitPick.CHERRY_PICK, picked, firstParent = false)

        assertEquals(GitPickResult.Done(head()), result)
        assertEquals("Feature", git("log", "-1", "--format=%s"))
        assertEquals("Feature\n", repo.resolve("feature.txt").readText())
        assertNull(commits.inProgress(repo))
    }

    @Test
    fun `cherry-picks and reverts a merge commit relative to its first parent`() {
        commit("Base")
        git("switch", "-q", "-c", "side")
        commit("Side", "side.txt")
        git("switch", "-q", "main")
        commit("Main", "main.txt")
        git("merge", "-q", "--no-ff", "-m", "Merge side", "side")
        val merge = head()

        assertIs<GitPickResult.Failed>(commits.pick(repo, GitPick.REVERT, merge, firstParent = false))
        val reverted = commits.pick(repo, GitPick.REVERT, merge, firstParent = true)

        assertEquals(GitPickResult.Done(head()), reverted)
        assertFalse(repo.resolve("side.txt").exists())
        assertTrue(repo.resolve("main.txt").exists())

        git("switch", "-q", "-c", "fresh", "HEAD~2")
        val picked = commits.pick(repo, GitPick.CHERRY_PICK, merge, firstParent = true)
        assertEquals(GitPickResult.Done(head()), picked)
        assertTrue(repo.resolve("side.txt").exists())
    }

    @Test
    fun `a conflict leaves the state and reports the conflicted paths`() {
        val theirs = conflicting()
        val before = head()

        val result = commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)

        assertIs<GitPickResult.Stopped>(result)
        assertEquals(listOf("file.txt"), result.conflicts)
        assertTrue(result.message.isNotEmpty())
        assertFalse(result.message.contains("hint:"))
        assertEquals(before, head())
        assertEquals(GitPendingPick(GitPick.CHERRY_PICK, theirs, listOf("file.txt")), commits.inProgress(repo))
        assertTrue(repo.resolve("file.txt").readText().contains("<<<<<<< "))
        assertEquals(
            GitPickResult.Failed("A cherry-pick of ${theirs.take(8)} is in progress; continue or abort it first."),
            commits.pick(repo, GitPick.REVERT, before, firstParent = false),
        )
    }

    @Test
    fun `a cherry-pick with nothing to commit is skipped, local changes stay`() {
        commit("Base")
        commit("Other file", "kept.txt", "kept")
        git("switch", "-q", "-c", "other")
        val picked = commit("Change")
        git("switch", "-q", "main")
        git("merge", "-q", "--ff-only", "other")
        val before = head()
        repo.resolve("kept.txt").writeText("edited\n")
        repo.resolve("untracked.txt").writeText("new\n")

        val result = commits.pick(repo, GitPick.CHERRY_PICK, picked, firstParent = false)

        assertEquals(GitPickResult.Empty("main"), result)
        assertEquals(before, head())
        assertNull(commits.inProgress(repo))
        assertFalse(gitFile("CHERRY_PICK_HEAD").exists())
        assertFalse(gitFile("MERGE_MSG").exists())
        assertEquals(listOf(" M kept.txt", "?? untracked.txt"), status())
        assertEquals("edited\n", repo.resolve("kept.txt").readText())

        git("switch", "-q", "--detach")
        assertEquals(GitPickResult.Empty(null), commits.pick(repo, GitPick.CHERRY_PICK, picked, firstParent = false))
    }

    @Test
    fun `reverts a commit, stops at a conflict, and clears a revert with nothing to commit`() {
        commit("Base")
        val added = commit("Add", "added.txt", "added")
        val reverted = commits.pick(repo, GitPick.REVERT, added, firstParent = false)
        assertEquals(GitPickResult.Done(head()), reverted)
        assertEquals("Revert \"Add\"", git("log", "-1", "--format=%s"))
        assertFalse(repo.resolve("added.txt").exists())

        // Its changes are undone already.
        repo.resolve("kept.txt").writeText("local\n")
        val before = head()
        assertEquals(GitPickResult.Empty("main"), commits.pick(repo, GitPick.REVERT, added, firstParent = false))
        assertEquals(before, head())
        assertFalse(gitFile("MERGE_MSG").exists(), "no message left for the next commit")
        assertFalse(gitFile("REVERT_HEAD").exists())
        assertFalse(gitFile("AUTO_MERGE").exists())
        assertFalse(git("status").contains("revert"), git("status"))
        assertEquals(listOf("?? kept.txt"), status())
        repo.resolve("kept.txt").deleteExisting()

        val changed = commit("Change")
        commit("Change again", content = "again")
        val conflict = commits.pick(repo, GitPick.REVERT, changed, firstParent = false)
        assertIs<GitPickResult.Stopped>(conflict)
        assertEquals(listOf("file.txt"), conflict.conflicts)
        assertEquals(GitPendingPick(GitPick.REVERT, changed, listOf("file.txt")), commits.inProgress(repo))
    }

    @Test
    fun `a commit git refuses to apply is a failure that leaves nothing behind`() {
        val theirs = conflicting()
        repo.resolve("file.txt").writeText("local\n")

        val result = commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)

        assertIs<GitPickResult.Failed>(result)
        assertNull(commits.inProgress(repo))
        assertEquals("local\n", repo.resolve("file.txt").readText())
        assertIs<GitPickResult.Failed>(commits.pick(repo, GitPick.REVERT, theirs, firstParent = false))
    }

    @Test
    fun `aborting restores the state before the cherry-pick`() {
        val theirs = conflicting()
        val before = head()
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)

        assertTrue(commits.abortPick(repo, GitPick.CHERRY_PICK).success)

        assertNull(commits.inProgress(repo))
        assertEquals(before, head())
        assertEquals("Ours\n", repo.resolve("file.txt").readText())
        assertFalse(commits.abortPick(repo, GitPick.CHERRY_PICK).success, "nothing to abort")
    }

    @Test
    fun `continuing is refused while conflict markers are left`() {
        val theirs = conflicting()
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        val before = head()

        assertEquals(GitPickResult.MarkersLeft(listOf("file.txt")), commits.continuePick(repo, GitPick.CHERRY_PICK))
        // Only lines starting with a marker and a space count; ======= alone is too common.
        repo.resolve("file.txt").writeText("=======\n<<<<<<<x\n")
        assertIs<GitPickResult.Done>(commits.continuePick(repo, GitPick.CHERRY_PICK))
        assertTrue(head() != before)
        assertEquals(
            GitPickResult.Failed("No cherry-pick is in progress."),
            commits.continuePick(repo, GitPick.CHERRY_PICK),
        )
    }

    @Test
    fun `continuing stages the resolved files only and commits with git's message`() {
        commit("Base")
        commit("Other", "unrelated.txt", "unrelated")
        git("switch", "-q", "-c", "other")
        val theirs = commit("Theirs")
        git("switch", "-q", "main")
        commit("Ours")
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        repo.resolve("file.txt").writeText("resolved\n")
        repo.resolve("unrelated.txt").writeText("edited, not to commit\n")

        val result = commits.continuePick(repo, GitPick.CHERRY_PICK)

        assertEquals(GitPickResult.Done(head()), result)
        assertNull(commits.inProgress(repo))
        assertEquals("Theirs", git("log", "-1", "--format=%B"), "no conflict comments in the message")
        assertEquals("resolved", git("show", "HEAD:file.txt"))
        assertEquals("unrelated", git("show", "HEAD:unrelated.txt"))
        assertEquals(listOf(" M unrelated.txt"), status())
    }

    @Test
    fun `a revert continues with git's message`() {
        commit("Base")
        val changed = commit("Change")
        commit("Change again", content = "again")
        commits.pick(repo, GitPick.REVERT, changed, firstParent = false)
        repo.resolve("file.txt").writeText("resolved\n")

        val continued = commits.continuePick(repo, GitPick.REVERT)
        assertEquals(GitPickResult.Done(head()), continued)
        assertEquals("Revert \"Change\"\n\nThis reverts commit $changed.", git("log", "-1", "--format=%B"))
    }

    @Test
    fun `continuing stages a file deleted to resolve a conflict and a non-ASCII path`() {
        val green = "grün.txt"
        commit("Base", "gone.txt", "base")
        // Written by git, as the JVM may not name the file in a POSIX locale.
        val blob = git("hash-object", "-w", "--stdin", input = "base\n")
        git("update-index", "--add", "--index-info", input = "100644 $blob\t$green\n")
        git("commit", "-q", "-m", "Green")
        git("checkout", "-q", "--", ".")
        git("switch", "-q", "-c", "other")
        repo.resolve("gone.txt").writeText("changed\n")
        val theirsBlob = git("hash-object", "-w", "--stdin", input = "theirs\n")
        git("update-index", "--index-info", input = "100644 $theirsBlob\t$green\n")
        git("add", "gone.txt")
        git("commit", "-q", "-m", "Theirs")
        git("checkout", "-q", "--", ".")
        val theirs = head()
        git("switch", "-q", "main")
        git("rm", "-q", "gone.txt")
        val oursBlob = git("hash-object", "-w", "--stdin", input = "ours\n")
        git("update-index", "--index-info", input = "100644 $oursBlob\t$green\n")
        git("commit", "-q", "-m", "Ours")
        git("checkout", "-q", "--", ".")

        val stopped = commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        assertIs<GitPickResult.Stopped>(stopped)
        assertEquals(setOf("gone.txt", green), stopped.conflicts.toSet())
        assertEquals(GitPickResult.MarkersLeft(listOf(green)), commits.continuePick(repo, GitPick.CHERRY_PICK))

        // Resolved: the deleted file stays deleted, the other one is overwritten by a shell that reads its name.
        repo.resolve("gone.txt").deleteExisting()
        shell("IFS= read -r name; printf 'resolved\\n' > \"\$name\"", "$green\n")

        // The deletion cannot be checked for markers: committed only once confirmed.
        assertEquals(
            GitPickResult.Unchecked(listOf(GitUncheckedConflict("gone.txt", GitConflictReason.DELETED_ON_DISK))),
            commits.continuePick(repo, GitPick.CHERRY_PICK),
        )
        val confirmed = setOf(GitUncheckedConflict("gone.txt", GitConflictReason.DELETED_ON_DISK))
        val continued = commits.continuePick(repo, GitPick.CHERRY_PICK, confirmed)
        assertEquals(GitPickResult.Done(head()), continued)
        assertEquals("", git("ls-tree", "--name-only", "HEAD", "--", "gone.txt"))
        // The path on git's standard input too.
        assertTrue(git("cat-file", "--batch", input = "HEAD:$green\n").endsWith("\nresolved"))
        assertEquals(emptyList(), status())
    }

    private fun shell(
        script: String,
        input: String,
    ) {
        val process =
            ProcessBuilder("sh", "-c", script)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start()
        process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), output)
    }

    @Test
    fun `resets soft, mixed and hard`() {
        val first = commit("First")
        val second = commit("Second")

        assertTrue(commits.reset(repo, first, GitResetMode.SOFT).success)
        assertEquals(first, head())
        assertEquals(listOf("M  file.txt"), status())

        git("reset", "-q", "--hard", second)
        assertTrue(commits.reset(repo, first, GitResetMode.MIXED).success)
        assertEquals(listOf(" M file.txt"), status())
        assertEquals("Second\n", repo.resolve("file.txt").readText())

        git("reset", "-q", "--hard", second)
        repo.resolve("untracked.txt").writeText("stays\n")
        assertTrue(commits.reset(repo, first, GitResetMode.HARD).success)
        assertEquals(listOf("?? untracked.txt"), status())
        assertEquals("First\n", repo.resolve("file.txt").readText())

        assertFalse(commits.reset(repo, "0".repeat(40), GitResetMode.HARD).success)
        assertEquals(first, head())
    }

    @Test
    fun `counts the commits only the current branch has, not those on other branches, tags or remote branches`() {
        val base = commit("Base")
        commit("One")
        commit("Two")
        val three = commit("Three")

        assertEquals(3, commits.unreachableCount(repo, base))
        assertEquals(0, commits.unreachableCount(repo, three))
        git("branch", "side", "HEAD~2")
        assertEquals(2, commits.unreachableCount(repo, base))
        git("tag", "t", "HEAD~1")
        assertEquals(1, commits.unreachableCount(repo, base))
        git("update-ref", "refs/remotes/origin/main", three)
        assertEquals(0, commits.unreachableCount(repo, base))
        git("update-ref", "-d", "refs/remotes/origin/main")

        // Detached, every branch counts, also main.
        git("switch", "-q", "--detach")
        assertEquals(0, commits.unreachableCount(repo, base))
        val detached = commit("Detached")
        assertEquals(1, commits.unreachableCount(repo, three))
        assertEquals(0, commits.unreachableCount(repo, detached))
    }

    @Test
    fun `counts the tracked files with local changes, staged or not`() {
        commit("First")
        commit("Other", "other.txt")
        commit("Third", "third.txt")
        assertEquals(0, commits.localChangeCount(repo))

        repo.resolve("file.txt").writeText("changed\n")
        repo.resolve("other.txt").writeText("staged\n")
        git("add", "other.txt")
        repo.resolve("third.txt").deleteExisting()
        repo.resolve("untracked.txt").writeText("not counted\n")

        assertEquals(3, commits.localChangeCount(repo))
    }

    @Test
    fun `nothing is in progress in a fresh repository`() {
        commit("First")
        assertNull(commits.inProgress(repo))
        assertEquals(head(), commits.headCommit(repo))
    }

    @Test
    fun `conflicts without markers to check are committed as on disk only once confirmed`() {
        repo.resolve(".gitattributes").writeText("*.cfg -merge\n")
        repo.resolve("bin.dat").writeBytes(byteArrayOf(1, 0, 2))
        commit("Base", "m.txt", "base")
        commit("Config", "keep.cfg", "base")
        git("add", "bin.dat", ".gitattributes")
        git("commit", "-q", "-m", "Binary")
        git("switch", "-q", "-c", "other")
        repo.resolve("bin.dat").writeBytes(byteArrayOf(3, 0, 4))
        repo.resolve("m.txt").writeText("theirs\n")
        repo.resolve("keep.cfg").writeText("theirs\n")
        git("add", "-A")
        git("commit", "-q", "-m", "Theirs")
        val theirs = head()
        git("switch", "-q", "main")
        repo.resolve("bin.dat").writeBytes(byteArrayOf(5, 0, 6))
        repo.resolve("keep.cfg").writeText("ours\n")
        git("rm", "-q", "m.txt")
        git("add", "-A")
        git("commit", "-q", "-m", "Ours")

        val stopped = commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        assertIs<GitPickResult.Stopped>(stopped)
        val unchecked = commits.continuePick(repo, GitPick.CHERRY_PICK)

        assertEquals(
            GitPickResult.Unchecked(
                listOf(
                    GitUncheckedConflict("bin.dat", GitConflictReason.BINARY),
                    GitUncheckedConflict("keep.cfg", GitConflictReason.BINARY),
                    GitUncheckedConflict("m.txt", GitConflictReason.DELETED_ON_ONE_SIDE),
                ),
            ),
            unchecked,
        )
        assertIs<GitPendingPick>(commits.inProgress(repo), "nothing done")
        assertEquals(3, (commits.inProgress(repo) as GitPendingPick).conflicts.size)

        val confirmed = (unchecked as GitPickResult.Unchecked).files.toSet()
        assertIs<GitPickResult.Done>(commits.continuePick(repo, GitPick.CHERRY_PICK, confirmed))
        assertEquals("theirs", git("show", "HEAD:m.txt"), "the file on disk")
        assertEquals("ours", git("show", "HEAD:keep.cfg"))
    }

    /** Three commits that set the gitlink `sub` to [base], then to [theirs] on `other` and to [ours] on main. */
    private fun submoduleConflict(
        base: String,
        theirs: String,
        ours: String,
    ): String {
        git("update-index", "--add", "--cacheinfo", "160000,$base,sub")
        git("commit", "-q", "-m", "Sub at base")
        git("switch", "-q", "-c", "other")
        git("update-index", "--cacheinfo", "160000,$theirs,sub")
        git("commit", "-q", "-m", "Sub at theirs")
        val commit = head()
        git("switch", "-q", "main")
        git("update-index", "--cacheinfo", "160000,$ours,sub")
        git("commit", "-q", "-m", "Sub at ours")
        return commit
    }

    @Test
    fun `a checked-out submodule's commit is committed once confirmed`() {
        commit("Base")
        val sub = repo.resolve("sub").createDirectories()
        git("init", "-q", "-b", "main", directory = sub)

        fun subCommit(message: String): String {
            git(
                "-c",
                "user.name=T",
                "-c",
                "user.email=t@x",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                message,
                directory = sub,
            )
            return git("rev-parse", "HEAD", directory = sub)
        }
        // b and c both on a, so that git cannot take either for the merge.
        val a = subCommit("a")
        val b = subCommit("b")
        git("checkout", "-q", "--detach", a, directory = sub)
        val c = subCommit("c")
        val theirs = submoduleConflict(a, b, c)
        assertIs<GitPickResult.Stopped>(commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false))

        val asked = commits.continuePick(repo, GitPick.CHERRY_PICK)

        assertEquals(GitPickResult.Unchecked(listOf(GitUncheckedConflict("sub", GitConflictReason.SUBMODULE))), asked)
        // Resolved by checking out theirs in it.
        git("checkout", "-q", "--detach", b, directory = sub)
        val confirmed = (asked as GitPickResult.Unchecked).files.toSet()
        assertIs<GitPickResult.Done>(commits.continuePick(repo, GitPick.CHERRY_PICK, confirmed))
        assertTrue(git("ls-tree", "HEAD", "--", "sub").contains(b), "the commit checked out in it")
    }

    @Test
    fun `a submodule that is not checked out is refused, a deleted one is a deletion`() {
        val a = commit("A")
        val b = commit("B")
        val c = commit("C")
        val theirs = submoduleConflict(a, b, c)
        assertIs<GitPickResult.Stopped>(commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false))
        repo.resolve("sub").createDirectories()

        assertEquals(
            GitPickResult.Failed(
                "Submodule sub is not checked out: the commit to keep cannot be taken from disk. Resolve it in the " +
                    "terminal.",
            ),
            commits.continuePick(repo, GitPick.CHERRY_PICK),
        )
        assertIs<GitPendingPick>(commits.inProgress(repo), "nothing done")

        repo.resolve("sub").deleteExisting()
        assertEquals(
            GitPickResult.Unchecked(listOf(GitUncheckedConflict("sub", GitConflictReason.DELETED_ON_DISK))),
            commits.continuePick(repo, GitPick.CHERRY_PICK),
        )
    }

    @Test
    fun `only the files confirmed as they were shown are committed as on disk`() {
        fun both(
            subject: String,
            binary: ByteArray,
        ): String {
            repo.resolve("bin.dat").writeBytes(binary)
            repo.resolve("file.txt").writeText("$subject\n")
            git("add", "bin.dat", "file.txt")
            git("commit", "-q", "-m", subject)
            return head()
        }
        both("Base", byteArrayOf(1, 0, 2))
        git("switch", "-q", "-c", "other")
        val theirs = both("Theirs", byteArrayOf(3, 0, 4))
        git("switch", "-q", "main")
        both("Ours", byteArrayOf(5, 0, 6))
        assertIs<GitPickResult.Stopped>(commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false))
        repo.resolve("file.txt").writeText("resolved\n")
        val shown = setOf(GitUncheckedConflict("bin.dat", GitConflictReason.BINARY))
        assertEquals(GitPickResult.Unchecked(shown.toList()), commits.continuePick(repo, GitPick.CHERRY_PICK))

        // Meanwhile, in a terminal.
        repo.resolve("file.txt").deleteExisting()
        val again = commits.continuePick(repo, GitPick.CHERRY_PICK, shown)

        val now = shown + GitUncheckedConflict("file.txt", GitConflictReason.DELETED_ON_DISK)
        assertEquals(GitPickResult.Unchecked(now.toList()), again)
        assertIs<GitPendingPick>(commits.inProgress(repo), "nothing done")
        assertIs<GitPickResult.Done>(commits.continuePick(repo, GitPick.CHERRY_PICK, now))
        assertEquals("", git("ls-tree", "--name-only", "HEAD", "--", "file.txt"))
    }

    @Test
    fun `a conflicted file too large on disk is not stored to be checked`() {
        val theirs = conflicting()
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        // 6,000,000 bytes, above the 5 MB that are read.
        repo.resolve("file.txt").writeText(("y".repeat(99) + "\n").repeat(60_000))
        val objects = git("count-objects", "-v")

        assertEquals(
            GitPickResult.Unchecked(listOf(GitUncheckedConflict("file.txt", GitConflictReason.TOO_LARGE))),
            commits.continuePick(repo, GitPick.CHERRY_PICK),
        )
        assertEquals(objects, git("count-objects", "-v"), "nothing stored")
    }

    @Test
    fun `markers are as long as the conflict-marker-size attribute says`() {
        repo.resolve(".gitattributes").writeText("*.txt conflict-marker-size=10\n")
        git("add", ".gitattributes")
        val theirs = conflicting()
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        assertTrue(repo.resolve("file.txt").readText().startsWith("<<<<<<<<<< "))

        assertEquals(GitPickResult.MarkersLeft(listOf("file.txt")), commits.continuePick(repo, GitPick.CHERRY_PICK))
        // Shorter ones are text.
        repo.resolve("file.txt").writeText("<<<<<<< kept\n>>>>>>> kept\n")
        assertIs<GitPickResult.Done>(commits.continuePick(repo, GitPick.CHERRY_PICK))
    }

    @Test
    fun `a continue with nothing left to commit is skipped`() {
        val theirs = conflicting()
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        val before = head()
        repo.resolve("file.txt").writeText("Ours\n")

        assertEquals(GitPickResult.Empty("main"), commits.continuePick(repo, GitPick.CHERRY_PICK))
        assertEquals(before, head())
        assertNull(commits.inProgress(repo))
        assertFalse(gitFile("CHERRY_PICK_HEAD").exists())
        assertEquals(emptyList(), status())
    }

    @Test
    fun `reads a cherry-pick in progress in a linked worktree`() {
        val theirs = conflicting()
        val worktree = folder.resolve("wt")
        git("worktree", "add", "-q", "-b", "wt", worktree.toString(), "main")
        val inWorktree = GitCommits(git)

        assertIs<GitPickResult.Stopped>(inWorktree.pick(worktree, GitPick.CHERRY_PICK, theirs, firstParent = false))

        assertEquals(GitPendingPick(GitPick.CHERRY_PICK, theirs, listOf("file.txt")), commits.inProgress(worktree))
        assertNull(commits.inProgress(repo), "not in the main worktree")
        assertTrue(commits.abortPick(worktree, GitPick.CHERRY_PICK).success)
        assertNull(commits.inProgress(worktree))
    }

    @Test
    fun `nothing starts while a merge, rebase or sequence is in progress, and nothing of theirs is touched`() {
        val theirs = conflicting()
        val ours = head()

        git("merge", "-q", "--no-commit", "--no-ff", "-s", "ours", "other")
        assertEquals(GitPendingOperation(GitOperation.MERGE), commits.inProgress(repo))
        assertEquals(
            GitPickResult.Failed("A merge is in progress; finish or abort it first."),
            commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false),
        )
        assertTrue(gitFile("MERGE_HEAD").exists(), "not concluded")
        git("merge", "--abort")

        // A sequence stopped at its first commit, which was then committed by hand: no CHERRY_PICK_HEAD any more.
        git("switch", "-q", "other")
        val second = commit("Second", "second.txt")
        git("switch", "-q", "main")
        val failing = runCatching { git("cherry-pick", theirs, second) }
        assertTrue(failing.isFailure)
        repo.resolve("file.txt").writeText("resolved\n")
        git("add", "file.txt")
        git("commit", "-q", "--no-edit")
        assertTrue(gitFile("sequencer").exists())
        assertEquals(GitPendingOperation(GitOperation.CHERRY_PICK_SEQUENCE), commits.inProgress(repo))
        val message = "A cherry-pick sequence is in progress; continue or abort it in the terminal."
        assertEquals(GitPickResult.Failed(message), commits.pick(repo, GitPick.REVERT, ours, firstParent = false))
        assertEquals(GitPickResult.Failed(message), commits.continuePick(repo, GitPick.CHERRY_PICK))
        assertEquals(GitResult(false, message), commits.abortPick(repo, GitPick.CHERRY_PICK))
        assertTrue(gitFile("sequencer").exists(), "the sequence survives")
        git("cherry-pick", "--quit")

        git("switch", "-q", "-c", "rebased", "other~1")
        val rebase = runCatching { git("rebase", "-q", "main") }
        assertTrue(rebase.isFailure)
        assertEquals(GitPendingOperation(GitOperation.REBASE), commits.inProgress(repo))
        assertEquals(
            GitPickResult.Failed("A rebase is in progress; finish or abort it first."),
            commits.pick(repo, GitPick.CHERRY_PICK, ours, firstParent = false),
        )
    }

    @Test
    fun `a conflict marker starts a line, is as long as asked and is followed by a space or the line's end`() {
        fun marked(
            text: String,
            size: Int = 7,
        ) = hasConflictMarker(text.toByteArray(), size)

        assertTrue(marked("a\n<<<<<<< HEAD\nb\n"))
        assertTrue(marked(">>>>>>> 1a2b (x)"))
        assertTrue(marked("x\r\n<<<<<<<\r\n"))
        assertTrue(marked("<<<<<<<"))
        assertFalse(marked("=======\n"))
        assertFalse(marked(" <<<<<<< indented\n"))
        assertFalse(marked("<<<<<<<< eight\n"))
        assertFalse(marked("<<<<<<<x\n"))
        assertFalse(marked("<<<<<<< seven\n", size = 10))
        assertTrue(marked("<<<<<<<<<< ten\n", size = 10))
    }

    @Test
    fun `a symbolic link in place of a text conflict is not followed but confirmed`() {
        val theirs = conflicting()
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        val target = repo.resolve("target.txt").apply { writeText("<<<<<<< not checked\n") }
        repo.resolve("file.txt").deleteExisting()
        Files.createSymbolicLink(repo.resolve("file.txt"), target.fileName)

        assertEquals(
            GitPickResult.Unchecked(listOf(GitUncheckedConflict("file.txt", GitConflictReason.SYMLINK))),
            commits.continuePick(repo, GitPick.CHERRY_PICK),
        )
    }

    @Test
    fun `a folder or a FIFO in place of a text conflict is refused without reading it`() {
        val theirs = conflicting()
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        repo.resolve("file.txt").deleteExisting()
        repo.resolve("file.txt").createDirectories()
        val refusal =
            "file.txt is not a regular file (a folder or a special file), which git cannot stage. Resolve it in the " +
                "terminal."

        assertEquals(GitPickResult.Failed(refusal), commits.continuePick(repo, GitPick.CHERRY_PICK))
        assertIs<GitPendingPick>(commits.inProgress(repo), "nothing done")

        repo.resolve("file.txt").deleteExisting()
        val fifo = runCatching { ProcessBuilder("mkfifo", repo.resolve("file.txt").toString()).start().waitFor() }
        assumeTrue(fifo.getOrNull() == 0, "mkfifo is not available")
        assertEquals(GitPickResult.Failed(refusal), commits.continuePick(repo, GitPick.CHERRY_PICK))
    }
}
