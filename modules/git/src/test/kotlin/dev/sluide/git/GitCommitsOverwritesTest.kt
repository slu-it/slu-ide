package dev.sluide.git

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a hard reset or a pick would overwrite ([GitCommits.overwrittenFiles], [GitCommits.pickOverwrites]) with a
 * git that fails one command: when git cannot say, how paths are looked up without the JVM, and what is tracked.
 * Real git; skipped without it.
 */
internal class GitCommitsOverwritesTest : GitCommitsFixture() {
    private val callLog get() = folder.resolve("calls.log")

    private fun failing(
        vararg words: String,
        after: Int = 0,
    ) = GitCommits(failingGit(folder, *words, after = after))

    private fun failingNth(
        word: String,
        nth: Int,
    ) = GitCommits(failingNthGit(folder, word, nth))

    private fun recording() = GitCommits(recordingGit(folder, callLog))

    private fun calls() = recordedCalls(callLog)

    private fun commitFile(
        subject: String,
        path: String,
    ): String {
        repo.resolve(path).parent.createDirectories()
        return commit(subject, path)
    }

    /** A commit on `other` that adds `.env`, which main ignores and has its own copy of. */
    private fun addingEnv(): String {
        commit("Base", "kept.txt")
        git("switch", "-q", "-c", "other")
        val added = commit("Add .env", ".env", "FROM_COMMIT=1")
        git("switch", "-q", "main")
        repo.resolve(".gitignore").writeText(".env\n")
        git("add", ".gitignore")
        git("commit", "-q", "-m", "Ignore .env")
        repo.resolve(".env").writeText("MY_SECRET=1\n")
        return added
    }

    @Test
    fun `names git cannot take give no answer for what a reset or pick reads, without running git`() {
        commit("Base")
        val recorded = recording()

        assertNull(recorded.overwrittenFiles(repo, "-x"))
        assertNull(recorded.overwrittenFiles(repo, ""))
        assertNull(recorded.pickOverwrites(repo, GitPick.CHERRY_PICK, "-x", firstParent = false))
        assertNull(recorded.pickOverwrites(repo, GitPick.REVERT, "", firstParent = false))

        assertEquals(emptyList(), calls())
    }

    @Test
    fun `a pick that changes a tracked file and adds an ignored one finds the ignored one`() {
        commit("Base", "kept.txt")
        git("switch", "-q", "-c", "other")
        repo.resolve("kept.txt").writeText("changed\n")
        repo.resolve(".env").writeText("FROM_COMMIT=1\n")
        git("add", "-f", "kept.txt", ".env")
        git("commit", "-q", "-m", "Change and add")
        val picked = head()
        git("switch", "-q", "main")
        repo.resolve(".gitignore").writeText(".env\n")
        git("add", ".gitignore")
        git("commit", "-q", "-m", "Ignore .env")
        repo.resolve(".env").writeText("MY_SECRET=1\n")

        assertEquals(listOf(".env"), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, picked, firstParent = false))
        assertNull(failing("merge-tree").pickOverwrites(repo, GitPick.CHERRY_PICK, picked, firstParent = false))
    }

    @Test
    fun `what a hard reset would overwrite is null when git cannot say`() {
        commit("Base")
        val target = commit("Target", "t.txt")
        git("rm", "-q", "--cached", "t.txt")
        git("commit", "-q", "-m", "Untrack")
        repo.resolve("t.txt").writeText("mine\n")

        assertEquals(listOf("t.txt"), commits.overwrittenFiles(repo, target))
        assertNull(failing("diff-index").overwrittenFiles(repo, target))
        assertNull(failing("config").overwrittenFiles(repo, target), "core.ignorecase")
        val unnamed = failing("ls-files")
        unnamed.canNameFile = { false }
        assertNull(unnamed.overwrittenFiles(repo, target), "the files git lists, for a file")
    }

    @Test
    fun `a file the JVM cannot name is looked for through git, also its folders and the folder it replaces`() {
        commit("Base")
        val target = commitFile("Target", "dir/t.txt")
        git("rm", "-q", "--cached", "dir/t.txt")
        git("commit", "-q", "-m", "Untrack")
        val unnamed = failing("ls-files")
        unnamed.canNameFile = { false }
        repo.resolve("dir").createDirectories()

        assertNull(unnamed.overwrittenFiles(repo, target), "the folder dir")
        // git lists the files that are not in the index: the folder is there as soon as one of them is below it.
        repo.resolve("dir/t.txt").writeText("mine\n")
        val named = commits
        named.canNameFile = { false }
        assertEquals(listOf("dir/t.txt"), named.overwrittenFiles(repo, target))
        repo.resolve("dir/t.txt").toFile().delete()
        assertEquals(emptyList(), named.overwrittenFiles(repo, target), "an empty folder is no file")
    }

    @Test
    fun `a folder the JVM cannot name lists only what is below it`() {
        commit("Base")
        val target = commit("Target", "dir")
        git("rm", "-q", "--cached", "dir")
        git("commit", "-q", "-m", "Untrack")
        repo.resolve("dir").toFile().delete()
        repo.resolve("dir").createDirectories()
        repo.resolve("dir/x.txt").writeText("x\n")
        repo.resolve("unrelated.txt").writeText("unrelated\n")
        commits.canNameFile = { false }

        assertEquals(listOf("dir/x.txt"), commits.overwrittenFiles(repo, target))
    }

    @Test
    fun `a file that replaced a folder in the index is tracked, so the folder's files overwrite nothing`() {
        commit("Base")
        val target = commitFile("Target", "dir/f.txt")
        git("rm", "-q", "dir/f.txt")
        git("commit", "-q", "-m", "Remove")
        repo.resolve("dir").writeText("a file now\n")
        git("add", "dir")

        assertEquals(emptyList(), commits.overwrittenFiles(repo, target))
    }

    @Test
    fun `a file that replaced a folder in a conflicted index is tracked as well`() {
        commit("Base")
        val target = commitFile("Target", "dir/f.txt")
        git("rm", "-q", "dir/f.txt")
        git("commit", "-q", "-m", "Remove")
        git("switch", "-q", "-c", "other")
        commit("Other", "dir", "theirs")
        git("switch", "-q", "main")
        commit("Ours", "dir", "ours")
        assertTrue(runCatching { git("merge", "other") }.isFailure, "add/add conflict")
        assertEquals(listOf("dir"), git("ls-files", "--unmerged").lines().map { it.substringAfter('\t') }.distinct())

        assertEquals(emptyList(), commits.overwrittenFiles(repo, target))
    }

    @Test
    fun `the folder of a submodule is kept, the folder of a file is not`() {
        val base = commit("Base")
        git("update-index", "--add", "--cacheinfo", "160000,$base,sub")
        git("update-index", "--add", "--cacheinfo", "100644,${git("hash-object", "-w", "file.txt")},plain")
        git("commit", "-q", "-m", "Gitlink and file")
        val target = head()
        git("rm", "-q", "--cached", "sub", "plain")
        git("commit", "-q", "-m", "Untrack")
        repo.resolve("sub").createDirectories()
        repo.resolve("sub/x.txt").writeText("x\n")
        repo.resolve("plain").createDirectories()
        repo.resolve("plain/y.txt").writeText("y\n")

        assertEquals(listOf("plain/y.txt"), commits.overwrittenFiles(repo, target))
    }

    @Test
    fun `what a pick would overwrite is null when git cannot say`() {
        val added = addingEnv()

        assertEquals(listOf(".env"), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false))
        assertNull(failing("rev-list").pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false), "parents")
        assertNull(failing("rev-parse").pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false), "unborn")
        assertNull(
            failing("diff-index", after = 1).pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false),
            "what the merged tree would overwrite",
        )
        assertNull(
            failing("ls-files").pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false),
            "the untracked files",
        )
    }

    @Test
    fun `nothing found needs no list of untracked files`() {
        val added = addingEnv()
        repo.resolve(".env").toFile().delete()

        val result = failing("ls-files").pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `a root commit and a branch without commits need the empty tree, which git must give`() {
        val root = commit("Root", ".env", "FROM_ROOT=1")
        val second = commit("Second", "second.txt")
        assertNull(failing("hash-object").pickOverwrites(repo, GitPick.CHERRY_PICK, root, firstParent = false))

        git("switch", "-q", "--orphan", "fresh")
        assertNull(failing("hash-object").pickOverwrites(repo, GitPick.CHERRY_PICK, second, firstParent = false))
        assertEquals(emptyList(), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, second, firstParent = false))
    }

    @Test
    fun `a merge commit is looked at only relative to its first parent`() {
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

        assertEquals(emptyList(), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, merge, firstParent = false))
        assertEquals(listOf(".env"), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, merge, firstParent = true))
    }

    @Test
    fun `a nested repository where a file was is listed as its folder, once`() {
        commit("Base")
        git("update-index", "--add", "--cacheinfo", "100644,${git("hash-object", "-w", "file.txt")},plain")
        git("commit", "-q", "-m", "A file")
        val target = head()
        git("rm", "-q", "--cached", "plain")
        git("commit", "-q", "-m", "Untrack")
        repo.resolve("plain").createDirectories()
        git("init", "-q", directory = repo.resolve("plain"))
        repo.resolve("plain/y.txt").writeText("y\n")

        assertEquals(listOf("plain/"), commits.overwrittenFiles(repo, target))
    }

    @Test
    fun `a folder that cannot be read is no answer, where git could have listed what it could`() {
        commit("Base")
        git("update-index", "--add", "--cacheinfo", "100644,${git("hash-object", "-w", "file.txt")},plain")
        git("commit", "-q", "-m", "A file")
        val target = head()
        git("rm", "-q", "--cached", "plain")
        git("commit", "-q", "-m", "Untrack")
        val inner = repo.resolve("plain/sub").createDirectories()
        inner.resolve("z.txt").writeText("z\n")
        Files.setPosixFilePermissions(inner, PosixFilePermissions.fromString("---------"))
        try {
            assumeTrue(!Files.isReadable(inner), "the permissions do not apply (running as root?)")

            assertNull(commits.overwrittenFiles(repo, target))
        } finally {
            Files.setPosixFilePermissions(inner, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `a pick of a file where HEAD has a folder-less file of that name is merged onto HEAD`() {
        commit("Base", "kept.txt")
        git("switch", "-q", "-c", "other")
        val added = commitFile("Add d/x", "d/x")
        git("switch", "-q", "main")
        repo.resolve(".gitignore").writeText("d~HEAD\n")
        git("add", ".gitignore")
        commit("Add d", "d")
        // Merged onto HEAD, the file d and the folder d/ clash: the file is kept as d~HEAD, which is not tracked.
        repo.resolve("d~HEAD").writeText("mine\n")

        assertEquals(listOf("d~HEAD"), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false))
    }
}
