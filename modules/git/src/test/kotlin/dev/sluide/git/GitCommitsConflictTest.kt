package dev.sluide.git

import dev.sluide.git.cli.inProgress
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
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

/** Conflicts of picks and reverts: state, abort, continue, confirming files; skipped if git is not installed. */
internal class GitCommitsConflictTest : GitCommitsFixture() {
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
        assertIs<GitPendingRebase>(commits.inProgress(repo))
        val rebasing = "A rebase is in progress; finish or abort it first."
        assertEquals(GitPickResult.Failed(rebasing), commits.pick(repo, GitPick.CHERRY_PICK, ours, firstParent = false))
        assertEquals(GitPickResult.Failed(rebasing), commits.continuePick(repo, GitPick.CHERRY_PICK))
        assertEquals(GitResult(false, rebasing), commits.abortPick(repo, GitPick.CHERRY_PICK))
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

    @Test
    fun `a conflict writes the file too, a change to a file deleted here`() {
        commit("Base", "cfg.local", "shared")
        git("switch", "-q", "-c", "other")
        val changed = commit("Change", "cfg.local", "theirs")
        git("switch", "-q", "main")
        git("rm", "-q", "cfg.local")
        repo.resolve(".gitignore").writeText("cfg.local\n")
        git("add", ".gitignore")
        git("commit", "-q", "-m", "Keep it local")
        repo.resolve("cfg.local").writeText("mine\n")

        assertEquals(
            listOf("cfg.local"),
            commits.pickOverwrites(repo, GitPick.CHERRY_PICK, changed, firstParent = false),
        )
        assertIs<GitPickResult.Stopped>(commits.pick(repo, GitPick.CHERRY_PICK, changed, firstParent = false))
        assertEquals("theirs\n", repo.resolve("cfg.local").readText(), "as git does it")
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
}
