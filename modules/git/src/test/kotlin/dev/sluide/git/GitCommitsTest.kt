package dev.sluide.git

import dev.sluide.git.cli.headCommit
import dev.sluide.git.cli.inProgress
import dev.sluide.git.cli.unreachableCount
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cherry-pick, revert, reset and the counts and ignored files around them; skipped if git is not installed. */
internal class GitCommitsTest : GitCommitsFixture() {
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
    fun `counts the files whose staged changes differ from the file on disk`() {
        commit("First")
        commit("Other", "other.txt")
        assertEquals(0, commits.stagedOnlyCount(repo))

        repo.resolve("file.txt").writeText("staged\n")
        git("add", "file.txt")
        repo.resolve("file.txt").writeText("staged, then changed again\n")
        repo.resolve("other.txt").writeText("staged as it is\n")
        git("add", "other.txt")
        repo.resolve("new.txt").writeText("intent to add only\n")
        git("add", "-N", "new.txt")

        assertEquals(1, commits.stagedOnlyCount(repo), "only file.txt's staged version is nowhere else")
    }

    @Test
    fun `a file renamed only in case is tracked where core_ignorecase is set`() {
        commit("First", "Readme.md")
        val target = head()
        git("mv", "Readme.md", "README.md")
        git("commit", "-q", "-m", "Rename")
        // On a case-insensitive file system (macOS, Windows) both names are the tracked file, and git init set
        // core.ignorecase; on a case-sensitive one a second file stands in for it.
        repo.resolve("Readme.md").writeText("the tracked file under the other case\n")

        if (!Files.isSameFile(repo.resolve("Readme.md"), repo.resolve("README.md"))) {
            assertEquals(listOf("Readme.md"), commits.overwrittenFiles(repo, target), "a different file on this system")
        }
        git("config", "core.ignorecase", "true")
        assertEquals(emptyList(), commits.overwrittenFiles(repo, target))
    }

    @Test
    fun `conflicted files are no staged changes, and a soft reset is refused with them`() {
        val theirs = conflicting()
        assertEquals(false, commits.softResetRefused(repo))
        assertIs<GitPickResult.Stopped>(commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false))

        assertEquals(0, commits.stagedOnlyCount(repo))
        assertEquals(true, commits.softResetRefused(repo))
    }

    /**
     * A target commit with the files `.env`, `build/out.txt`, `kept.txt`, `data/a.txt`, `foo`, `bar`, `empty`, `d/f.txt`
     * and the submodules `sub` and `lib/sub2`; then `HEAD` without them (`.env` and `build/` ignored) and with the
     * tracked `foo/tracked.txt` and `bar/tracked.txt`. Returns the target.
     */
    private fun untrackedSinceTarget(): String {
        val first = commit("First")
        for (file in listOf(".env", "build/out.txt", "kept.txt", "data/a.txt", "foo", "bar", "empty", "d/f.txt")) {
            repo.resolve(file).parent.createDirectories()
            repo.resolve(file).writeText("tracked $file\n")
        }
        git("add", ".")
        for (submodule in listOf(
            "sub",
            "lib/sub2",
        )) {
            git("update-index", "--add", "--cacheinfo", "160000,$first,$submodule")
        }
        git("commit", "-q", "-m", "Target")
        val target = head()
        git("rm", "-q", "-r", "--cached", ".env", "build", "kept.txt", "data", "foo", "bar", "empty", "d", "sub", "lib")
        for (file in listOf(".env", "build", "kept.txt", "data", "foo", "bar", "empty", "d")) {
            repo.resolve(file).toFile().deleteRecursively()
        }
        repo.resolve(".gitignore").writeText(".env\nbuild/\n*.log\n")
        for (file in listOf("foo/tracked.txt", "bar/tracked.txt")) {
            repo.resolve(file).parent.createDirectories()
            repo.resolve(file).writeText("tracked now\n")
        }
        git("add", ".gitignore", "foo", "bar")
        git("commit", "-q", "-m", "Untrack")
        return target
    }

    @Test
    fun `lists the untracked and ignored files a hard reset would overwrite or delete, as the reset does`() {
        val target = untrackedSinceTarget()
        repo.resolve(".env").writeText("LOCAL SECRET\n")
        repo.resolve("build").createDirectories()
        repo.resolve("build/out.txt").writeText("local output\n")
        // A file where the target has a folder.
        repo.resolve("data").writeText("a file in place of the folder\n")
        // Folders where the target has a file: with untracked and ignored files, with tracked ones only, empty.
        repo.resolve("foo/untracked.txt").writeText("untracked\n")
        repo.resolve("foo/debug.log").writeText("ignored\n")
        // A repository of its own in it, lost with its history: one entry.
        val nested = repo.resolve("foo/nested")
        nested.createDirectories()
        git("init", "-q", directory = nested)
        nested.resolve("n.txt").writeText("nested\n")
        git("add", "n.txt", directory = nested)
        git("-c", "user.name=T", "-c", "user.email=t@example.com", "commit", "-q", "-m", "Nested", directory = nested)
        repo.resolve("empty").createDirectories()
        // A link in place of a folder: git replaces the link, not what is in the folder it points to.
        folder.resolve("outside").createDirectories()
        folder.resolve("outside/f.txt").writeText("outside\n")
        Files.createSymbolicLink(repo.resolve("d"), folder.resolve("outside"))
        // A file at a submodule's path, a folder at another's.
        repo.resolve("sub").writeText("a file where the submodule goes\n")
        repo.resolve("lib/sub2").createDirectories()

        val overwritten = commits.overwrittenFiles(repo, target)

        val expected =
            listOf(".env", "build/out.txt", "d", "data", "foo/debug.log", "foo/nested/", "foo/untracked.txt", "sub")
        assertEquals(expected, overwritten?.sorted())
        commits.canNameFile = { false }
        assertEquals(expected, commits.overwrittenFiles(repo, target)?.sorted(), "listed by git, the link not followed")
        commits.canNameFile = { true }
        assertEquals(emptyList(), commits.overwrittenFiles(repo, head()), "HEAD's own files are tracked")
        assertNull(commits.overwrittenFiles(repo, "-x"))

        assertTrue(commits.reset(repo, target, GitResetMode.HARD).success)
        assertEquals("tracked .env\n", repo.resolve(".env").readText(), "git overwrote it as told")
        assertEquals("tracked data/a.txt\n", repo.resolve("data/a.txt").readText())
        assertEquals("tracked foo\n", repo.resolve("foo").readText(), "the folder and its files are gone")
        assertEquals("outside\n", folder.resolve("outside/f.txt").readText(), "only the link was replaced")
        assertTrue(Files.isDirectory(repo.resolve("sub")))
    }

    @Test
    fun `nothing is in progress in a fresh repository`() {
        commit("First")
        assertNull(commits.inProgress(repo))
        assertEquals(head(), commits.headCommit(repo))
    }

    /** On a branch `other`: a commit adding `.env`, `build` and `out/x.txt`, all ignored on main, and `notes.txt`. */
    private fun addingIgnoredPaths(): String {
        repo.resolve(".gitignore").writeText(".env\nbuild/\nout\n")
        git("add", ".gitignore")
        git("commit", "-q", "-m", "Ignore")
        git("switch", "-q", "-c", "other")
        repo.resolve(".env").writeText("FROM_COMMIT=1\n")
        repo.resolve("build").writeText("a file\n")
        repo
            .resolve("out")
            .createDirectories()
            .resolve("x.txt")
            .writeText("x\n")
        repo.resolve("notes.txt").writeText("notes\n")
        git("add", "-f", ".env", "build", "out/x.txt", "notes.txt")
        git("commit", "-q", "-m", "Add")
        val added = head()
        git("switch", "-q", "main")
        return added
    }

    @Test
    fun `a cherry-pick would overwrite and delete ignored files without a word, as found before`() {
        val added = addingIgnoredPaths()
        repo.resolve(".env").writeText("MY_SECRET=1\n")
        repo
            .resolve("build/cache")
            .createDirectories()
            .resolve("data")
            .writeText("cache\n")
        repo.resolve("out").writeText("mine\n")

        val overwritten = commits.pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false)

        assertEquals(listOf(".env", "build/cache/data", "out"), overwritten)
        // As git does it.
        val result = commits.pick(repo, GitPick.CHERRY_PICK, added, firstParent = false)
        assertEquals(GitPickResult.Done(head()), result)
        assertEquals("FROM_COMMIT=1\n", repo.resolve(".env").readText())
        assertTrue(repo.resolve("out/x.txt").exists(), "the ignored file out is a folder now")
        assertFalse(repo.resolve("build/cache").exists())
    }

    @Test
    fun `untracked files that are not ignored are left to git, which refuses for them`() {
        val added = addingIgnoredPaths()
        repo.resolve("notes.txt").writeText("my notes\n")

        assertEquals(emptyList(), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false))
        assertIs<GitPickResult.Failed>(commits.pick(repo, GitPick.CHERRY_PICK, added, firstParent = false))
        assertEquals("my notes\n", repo.resolve("notes.txt").readText())
    }

    @Test
    fun `with staged changes git refuses, so nothing is found`() {
        val added = addingIgnoredPaths()
        repo.resolve(".env").writeText("MY_SECRET=1\n")
        repo.resolve("staged.txt").writeText("staged\n")
        git("add", "staged.txt")

        assertEquals(emptyList(), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false))
    }

    @Test
    fun `a merge commit relative to its first parent, cherry-picked brings its branch, reverted takes it back`() {
        commit("Base", "kept.txt")
        git("switch", "-q", "-c", "other")
        repo.resolve(".env").writeText("FROM_MERGE=1\n")
        git("add", "-f", ".env")
        git("commit", "-q", "-m", "Add .env")
        git("switch", "-q", "-c", "feature", "main")
        commit("Feature", "feature.txt")
        git("merge", "-q", "--no-ff", "--no-edit", "other")
        val merge = head()
        git("switch", "-q", "main")
        repo.resolve(".gitignore").writeText(".env\n")
        git("add", ".gitignore")
        git("commit", "-q", "-m", "Ignore .env")
        repo.resolve(".env").writeText("MY_SECRET=1\n")

        assertEquals(listOf(".env"), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, merge, firstParent = true))
        // Reverting it here takes away what main never had: nothing is written.
        assertEquals(emptyList(), commits.pickOverwrites(repo, GitPick.REVERT, merge, firstParent = true))
    }

    @Test
    fun `a revert that brings a file back would overwrite an ignored one`() {
        val added = addingIgnoredPaths()
        git("merge", "-q", "--ff-only", "other")
        git("rm", "-q", ".env")
        git("commit", "-q", "-m", "Remove .env")
        val removed = head()
        repo.resolve(".env").writeText("MY_SECRET=1\n")

        assertEquals(listOf(".env"), commits.pickOverwrites(repo, GitPick.REVERT, removed, firstParent = false))
    }

    @Test
    fun `a root commit is picked onto the empty tree, a merge commit without its first parent is refused by git`() {
        val root = commit("Root", ".env", "FROM_ROOT=1")
        // Without the files of main.
        git("switch", "-q", "--orphan", "fresh")
        repo.resolve(".env").writeText("MY_SECRET=1\n")
        repo.resolve(".gitignore").writeText(".env\n")
        git("add", ".gitignore")
        git("commit", "-q", "-m", "Fresh")

        assertEquals(listOf(".env"), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, root, firstParent = false))

        git("merge", "-q", "--allow-unrelated-histories", "--no-edit", "main")
        assertEquals(emptyList(), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, head(), firstParent = false))
        // Reverting a root commit merges with the empty tree as the other side: it only takes files away.
        assertEquals(emptyList(), commits.pickOverwrites(repo, GitPick.REVERT, root, firstParent = false))
    }

    @Test
    fun `onto a branch without commits the commit is picked onto nothing`() {
        val root = commit("Root", ".env", "FROM_ROOT=1")
        git("switch", "-q", "--orphan", "fresh")
        repo.resolve(".gitignore").writeText(".env\n")
        repo.resolve(".env").writeText("MY_SECRET=1\n")

        assertEquals(listOf(".env"), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, root, firstParent = false))
    }
}
