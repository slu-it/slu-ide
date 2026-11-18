package dev.sluide.git

import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The git log's reads at the limits of their arguments, and when git fails (ADRs 0035 to 0046), with the real git, also
 * behind a wrapper that fails or records; skipped without git.
 */
internal class GitLogReadsTest : GitLogFixture() {
    // The wrapper scripts live in .git, where they are no untracked files.
    private fun failing(vararg words: String) = GitLog(failingGit(repo.resolve(".git"), *words))

    private fun recording() = GitLog(recordingGit(repo.resolve(".git"), repo.resolve(".git/calls.log")))

    private fun calls() = recordedCalls(repo.resolve(".git/calls.log"))

    @Test
    fun `skip and limit of the commits and of a file's history are checked, with the messages`() {
        commitFiles("First", "a.txt" to "a\n")

        val skip = assertFailsWith<IllegalArgumentException> { log.commits(repo, skip = -1, limit = 1) }
        assertEquals("skip must not be negative: -1", skip.message)
        val limit = assertFailsWith<IllegalArgumentException> { log.commits(repo, skip = 0, limit = 0) }
        assertEquals("limit must be positive: 0", limit.message)
        assertEquals(1, log.commits(repo, skip = 0, limit = 1)?.commits?.size)

        val fileSkip =
            assertFailsWith<IllegalArgumentException> { log.fileHistory(repo, "a.txt", skip = -1, limit = 1) }
        assertEquals("skip must not be negative: -1", fileSkip.message)
        val fileLimit =
            assertFailsWith<IllegalArgumentException> { log.fileHistory(repo, "a.txt", skip = 0, limit = 0) }
        assertEquals("limit must be positive: 0", fileLimit.message)
        assertEquals(1, log.fileHistory(repo, "a.txt", skip = 0, limit = 1)?.entries?.size)
        val start = assertFailsWith<IllegalArgumentException> { log.fileHistory(repo, "a.txt", "HEAD", 0, 1) }
        assertEquals("not a commit hash: HEAD", start.message)
        assertNull(log.fileHistory(repo, "", skip = 0, limit = 1))
    }

    @Test
    fun `a text of hexadecimal characters names a commit only as a whole hash prefix of 4 to 64 characters`() {
        val first = commitFiles("First", "a.txt" to "a\n")

        assertEquals(first, log.hashMatch(repo, GitLogFilter(text = first.take(4)))?.hash)
        assertEquals(first, log.hashMatch(repo, GitLogFilter(text = first.uppercase()))?.hash)
        assertNull(log.hashMatch(repo, GitLogFilter(text = first.take(3))))
        assertNull(log.hashMatch(repo, GitLogFilter(text = first + "0")))
        assertNull(log.hashMatch(repo, GitLogFilter(text = "${first.take(8)}g")))
    }

    @Test
    fun `a branch named like a hash prefix of another commit is not taken for that hash`() {
        val first = commitFiles("First", "a.txt" to "a\n")
        val second = commitFiles("Second", "b.txt" to "b\n")
        // A branch of the first four characters of the second commit's hash, pointing at the first commit.
        val prefix = second.take(4)
        git("branch", prefix, first)

        // git resolves the branch before the hash, so what it names does not start with the text.
        assertNull(log.hashMatch(repo, GitLogFilter(text = prefix)))
        assertEquals(second, log.hashMatch(repo, GitLogFilter(text = second.take(8)))?.hash)
    }

    @Test
    fun `the hash match is none when git fails to resolve or to read the commit`() {
        val first = commitFiles("First", "a.txt" to "a\n")
        val filter = GitLogFilter(text = first.take(8))

        assertNotNull(log.hashMatch(repo, filter))
        assertNull(failing("--verify").hashMatch(repo, filter), "resolving")
        assertNull(failing("log").hashMatch(repo, filter), "reading")
    }

    @Test
    fun `the folders and files of HEAD are none when git fails, empty only while HEAD has no commit`() {
        assertEquals(emptyList(), log.paths(repo))
        assertNull(failing("symbolic-ref", "--verify").paths(repo), "HEAD cannot be read at all")
        commitFiles("First", "a.txt" to "a\n")

        assertNull(failing("ls-tree").paths(repo))
        assertEquals(listOf(GitTreePath("a.txt", folder = false)), log.paths(repo))
        val blob = git("rev-parse", "HEAD:a.txt")
        git("update-index", "--add", "--cacheinfo", "160000,$blob,lib")
        git("commit", "-q", "-m", "Submodule")
        assertEquals(GitTreePath("lib", folder = false), log.paths(repo)?.last(), "a submodule is a file")
    }

    @Test
    fun `what git is in the middle of is read, none if nothing or if git fails`() {
        commitFiles("Base", "file.txt" to "base\n")
        git("switch", "-q", "-c", "other")
        commitFiles("Other", "file.txt" to "other\n")
        git("switch", "-q", "main")
        commitFiles("Main", "file.txt" to "main\n")
        assertNull(log.inProgress(repo))

        val merge = ProcessBuilder("git", "merge", "other")
        merge.directory(repo.toFile()).redirectErrorStream(true)
        merge.environment().clear()
        merge.environment().putAll(testGitEnvironment())
        val process = merge.start()
        process.outputStream.close()
        process.inputStream.readAllBytes()
        process.waitFor()

        assertIs<GitPendingMerge>(log.inProgress(repo))
        assertNull(failing("--git-path").inProgress(repo))
    }

    @Test
    fun `revisions are resolved without git for none, and not at all for a blank one`() {
        val first = commitFiles("First", "a.txt" to "a\n")
        val log = recording()

        assertEquals(emptyList(), log.resolveCommits(repo, emptyList()))
        assertEquals(emptyList(), calls(), "no revision, no git")
        assertNull(log.resolveCommits(repo, listOf("HEAD", " ")))
        assertEquals(emptyList(), calls(), "a blank revision, no git")
        assertEquals(1, log.resolveCommits(repo, listOf(first))?.size)
        assertNull(failing("--batch-check").resolveCommits(repo, listOf(first)))
    }

    @Test
    fun `the branches that contain a commit leave out symbolic refs, and none if git fails`() {
        val first = commitFiles("First", "a.txt" to "a\n")
        git("update-ref", "refs/remotes/origin/main", first)
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")

        assertEquals(
            listOf("refs/heads/main", "refs/remotes/origin/main"),
            log.branchesContaining(repo, first),
        )
        assertNull(failing("for-each-ref").branchesContaining(repo, first))
    }

    @Test
    fun `a file diff of a root commit compares with nothing also for a file listed as modified`() {
        val root = commitFiles("First", "a.txt" to "a\nb\n")
        val second = commitFiles("Second", "c.txt" to "c\n")

        val diff = log.fileDiff(repo, root, null, GitChangedFile(GitChange.MODIFIED, "a.txt"))

        assertEquals(GitFileDiff.Text("", "a\nb\n", listOf(GitHunk(0 until 0, 0 until 2))), diff)
        // An added file has no old side, a deleted one no new side, whatever its parent has.
        val added = log.fileDiff(repo, second, root, GitChangedFile(GitChange.ADDED, "a.txt"))
        assertEquals(GitFileDiff.Text("", "a\nb\n", listOf(GitHunk(0 until 0, 0 until 2))), added)
        val removed = commitFiles("Third", "a.txt" to null)
        val deleted = log.fileDiff(repo, removed, second, GitChangedFile(GitChange.DELETED, "a.txt"))
        assertEquals(GitFileDiff.Text("a\nb\n", "", listOf(GitHunk(0 until 2, 0 until 0))), deleted)
        assertNull(log.fileDiff(repo, second, root, GitChangedFile(GitChange.MODIFIED, "no-such.txt")))
        assertNull(failing("--batch-check").fileDiff(repo, second, root, GitChangedFile(GitChange.ADDED, "c.txt")))
        assertNull(log.fileDiff(repo, second, root, GitChangedFile(GitChange.ADDED, "c\nd.txt")))
    }

    @Test
    fun `the changes against the working tree name a missing revision, and are none for a line break or a failure`() {
        val base = commitFiles("First", "a.txt" to "a\n")
        repo.resolve("a.txt").writeText("b\n")

        assertEquals(GitWorkingTreeChanges.Missing, log.workingTreeChanges(repo, "no-such-branch"))
        assertNull(log.workingTreeChanges(repo, "main\nmain"))
        assertNull(failing("--batch-check").workingTreeChanges(repo, base))
        assertNull(failing("--name-status").workingTreeChanges(repo, base))
        val changes = assertIs<GitWorkingTreeChanges.Files>(log.workingTreeChanges(repo, "main"))
        assertEquals(base, changes.commit)
        assertEquals(listOf(GitChangedFile(GitChange.MODIFIED, "a.txt")), changes.files)
    }

    @Test
    fun `a working tree diff names files by the JVM unless the hook says it cannot`() {
        repo.resolve("a.txt").writeText("one\n")
        git("add", "-A")
        val base = commit("Base", 1000)
        repo.resolve("a.txt").writeText("two\n")
        val file = GitChangedFile(GitChange.MODIFIED, "a.txt")
        val log = recording()

        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file))
        assertFalse(calls().any { "hash-object" in it }, "the JVM names the file")

        log.canNameFile = { false }
        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file))
        assertTrue(calls().any { "hash-object" in it }, "git stores the file")
    }
}
