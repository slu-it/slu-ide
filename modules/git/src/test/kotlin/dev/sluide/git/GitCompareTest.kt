package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.comparisonCounts
import dev.sluide.git.cli.diffFiles
import dev.sluide.git.cli.workingTreeChanges
import dev.sluide.git.parse.GitDiffParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteExisting
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Comparing refs with `HEAD` and commits with the working tree (ADR 0044), with the real git; skipped without it. */
class GitCompareTest {
    @TempDir
    lateinit var repo: Path

    private val git = GitCli("git", testGitEnvironment())
    private val log = GitLog(git)

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
    }

    private fun git(
        vararg arguments: String,
        input: String = "",
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    /** Commits [subject] at [time] seconds after the epoch (the date order follows it); its hash. */
    private fun commit(
        subject: String,
        time: Long,
    ): String {
        val date = "@$time +0000"
        val process =
            ProcessBuilder("git", "commit", "-q", "--allow-empty", "-m", subject)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(
                        testGitEnvironment() + mapOf("GIT_AUTHOR_DATE" to date, "GIT_COMMITTER_DATE" to date),
                    )
                }.start()
        process.inputStream.readAllBytes()
        assertEquals(0, process.waitFor())
        return git("rev-parse", "HEAD")
    }

    private fun subjects(filter: GitLogFilter) = log.commits(repo, filter, 0, 100)?.commits?.map { it.subject }

    /** main: Base, Main 1, Main 2 (HEAD); feature from Base: Feature 1, Feature 2. */
    private fun branches() {
        commit("Base", 1000)
        git("switch", "-q", "-c", "feature")
        commit("Feature 1", 2000)
        commit("Feature 2", 3000)
        git("switch", "-q", "main")
        commit("Main 1", 4000)
        commit("Main 2", 5000)
    }

    @Test
    fun `excluded revisions list a range in both directions, with the other filters on top`() {
        branches()

        val inFeature = GitLogFilter(revisions = listOf("refs/heads/feature"), excluded = listOf("HEAD"))
        val inMain = GitLogFilter(revisions = listOf("HEAD"), excluded = listOf("refs/heads/feature"))
        assertEquals(listOf("Feature 2", "Feature 1"), subjects(inFeature))
        assertEquals(listOf("Main 2", "Main 1"), subjects(inMain))
        assertEquals(listOf("Feature 1"), subjects(inFeature.copy(text = "1")))
        assertEquals(listOf("Main 2"), subjects(inMain.copy(text = "2", order = GitLogOrder.TOPOLOGICAL)))
        assertEquals(emptyList(), subjects(inFeature.copy(paths = listOf("nothing.txt"))))
        assertFalse(inFeature.isDefault)
        assertFalse(GitLogFilter(excluded = listOf("HEAD")).isDefault)
    }

    @Test
    fun `counts the commits each side has that the other has not, also detached`() {
        branches()

        assertEquals(GitComparisonCounts(2, 2), log.comparisonCounts(repo, "refs/heads/feature"))
        git("switch", "-q", "--detach", "HEAD~1")
        assertEquals(GitComparisonCounts(2, 1), log.comparisonCounts(repo, "refs/heads/feature"))
        assertEquals(
            listOf("Main 1"),
            subjects(GitLogFilter(revisions = listOf("HEAD"), excluded = listOf("refs/heads/feature"))),
        )
        assertEquals(GitComparisonCounts(0, 0), log.comparisonCounts(repo, "HEAD"))
        assertNull(log.comparisonCounts(repo, "refs/heads/gone"))
        assertNull(log.comparisonCounts(repo, "-x"))
    }

    @Test
    fun `a ref with non-ASCII characters is compared through the standard input`() {
        commit("Base", 1000)
        val green = "refs/heads/grün"
        // Through git's standard input, as UTF-8: arguments of the JVM take its locale, which may be plain ASCII.
        git("update-ref", "--stdin", input = "create $green ${commit("Green", 2000)}\n")
        git("reset", "-q", "--hard", "HEAD~1")
        commit("Main", 3000)

        assertEquals(listOf("Green"), subjects(GitLogFilter(revisions = listOf(green), excluded = listOf("HEAD"))))
        assertEquals(listOf("Main"), subjects(GitLogFilter(revisions = listOf("HEAD"), excluded = listOf(green))))
        assertEquals(GitComparisonCounts(1, 1), log.comparisonCounts(repo, green))
        val changes = assertIs<GitWorkingTreeChanges.Files>(log.workingTreeChanges(repo, green))
        assertEquals(emptyList(), changes.files)
    }

    @Test
    fun `lists the tracked files that differ between a commit and the working tree, not untracked ones`() {
        val lines = (1..20).joinToString("") { "line $it\n" }
        repo.resolve("keep.txt").writeText("one\ntwo\n")
        repo.resolve("gone.txt").writeText("a\n")
        repo.resolve("old.txt").writeText(lines)
        repo.resolve("same.txt").writeText("same\n")
        git("add", "-A")
        val base = commit("Base", 1000)
        git("tag", "v1")
        repo.resolve("keep.txt").writeText("one\nTWO\nthree\n")
        repo.resolve("gone.txt").deleteExisting()
        git("mv", "old.txt", "new.txt")
        repo.resolve("new.txt").writeText(lines.replace("line 3\n", "line three\n"))
        repo.resolve("dir").createDirectories()
        repo.resolve("dir/added.txt").writeText("x\n")
        git("add", "dir/added.txt")
        repo.resolve("untracked.txt").writeText("u\n")

        val changes = assertIs<GitWorkingTreeChanges.Files>(log.workingTreeChanges(repo, "refs/tags/v1"))

        assertEquals(base, changes.commit)
        assertEquals(
            listOf(
                GitChangedFile(GitChange.ADDED, "dir/added.txt"),
                GitChangedFile(GitChange.DELETED, "gone.txt"),
                GitChangedFile(GitChange.MODIFIED, "keep.txt"),
                GitChangedFile(GitChange.RENAMED, "new.txt", "old.txt"),
            ),
            changes.files.sortedBy { it.path },
        )
        assertEquals(changes, log.workingTreeChanges(repo, base))
        assertEquals(GitWorkingTreeChanges.Missing, log.workingTreeChanges(repo, "refs/heads/gone"))
        val byPath = changes.files.associateBy { it.path }

        fun diff(path: String) = log.workingTreeFileDiff(repo, base, byPath.getValue(path))

        assertEquals(
            GitFileDiff.Text(
                "one\ntwo\n",
                "one\nTWO\nthree\n",
                listOf(GitHunk(1 until 2, 1 until 3)),
            ),
            diff("keep.txt"),
        )
        assertEquals(GitFileDiff.Text("", "x\n", listOf(GitHunk(0 until 0, 0 until 1))), diff("dir/added.txt"))
        assertEquals(GitFileDiff.Text("a\n", "", listOf(GitHunk(0 until 1, 0 until 0))), diff("gone.txt"))
        val renamed = assertIs<GitFileDiff.Text>(diff("new.txt"))
        assertEquals(lines, renamed.original)
        assertEquals(listOf(GitHunk(2 until 3, 2 until 3)), renamed.hunks)
    }

    @Test
    fun `binary and too large files are not compared`() {
        repo.resolve("data.bin").writeBytes(byteArrayOf(1, 2, 3))
        repo.resolve("big.txt").writeText("small\n")
        git("add", "-A")
        val base = commit("Base", 1000)
        repo.resolve("data.bin").writeBytes(byteArrayOf(1, 0, 3))
        repo.resolve("big.txt").writeText("small\nbut growing\n")

        val binary = GitChangedFile(GitChange.MODIFIED, "data.bin")
        val big = GitChangedFile(GitChange.MODIFIED, "big.txt")
        assertEquals(GitFileDiff.Binary, log.workingTreeFileDiff(repo, base, binary))
        assertEquals(GitFileDiff.TooLarge, log.workingTreeFileDiff(repo, base, big, maxSize = 10))
        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, big, maxSize = 20))
    }

    @Test
    fun `a path with non-ASCII characters is compared with the working tree`() {
        val path = "grün/ä.txt"
        // Written through git's standard input, as UTF-8: file names of the JVM take its locale, which may be ASCII.
        val first = git("hash-object", "-w", "--stdin", input = "one\ntwo\n")
        git("update-index", "--add", "--index-info", input = "100644 $first\t$path\n")
        val base = commit("Base", 1000)
        val second = git("hash-object", "-w", "--stdin", input = "one\nzwei\n")
        git("update-index", "--index-info", input = "100644 $second\t$path\n")
        git("checkout-index", "-f", "--stdin", input = "$path\n")

        val changes = assertIs<GitWorkingTreeChanges.Files>(log.workingTreeChanges(repo, base))

        assertEquals(listOf(GitChangedFile(GitChange.MODIFIED, path)), changes.files)
        assertEquals(
            GitFileDiff.Text("one\ntwo\n", "one\nzwei\n", listOf(GitHunk(1 until 2, 1 until 2))),
            log.workingTreeFileDiff(repo, base, changes.files.single()),
        )
    }

    @Test
    fun `fails outside a repository`() {
        val plain = repo.resolve("plain").createDirectories()
        val outside = GitLog(GitCli("git", testGitEnvironment() + ("GIT_CEILING_DIRECTORIES" to repo.toString())))
        assertNull(outside.comparisonCounts(plain, "HEAD"))
        assertNull(outside.workingTreeChanges(plain, "HEAD"))
        assertNull(log.workingTreeChanges(repo, "a\nb"))
    }

    @Test
    fun `the counts apply the filter's text, users, paths and options, not its revisions`() {
        commit("Base", 1000)
        git("switch", "-q", "-c", "feature")
        repo.resolve("a.txt").writeText("a\n")
        git("add", "a.txt")
        commit("Fix a", 2000)
        commit("Other", 3000)
        git("switch", "-q", "main")
        commit("Main fix", 4000)
        repo.resolve(".mailmap").writeText("Ann Real <test@example.com>\n")

        val feature = "refs/heads/feature"
        assertEquals(GitComparisonCounts(2, 1), log.comparisonCounts(repo, feature))
        assertEquals(
            GitComparisonCounts(1, 0),
            log.comparisonCounts(repo, feature, GitLogFilter(paths = listOf("a.txt"))),
        )
        assertEquals(GitComparisonCounts(1, 1), log.comparisonCounts(repo, feature, GitLogFilter(text = "FIX")))
        assertEquals(
            GitComparisonCounts(0, 0),
            log.comparisonCounts(repo, feature, GitLogFilter(text = "FIX", matchCase = true)),
        )
        assertEquals(
            GitComparisonCounts(2, 1),
            log.comparisonCounts(repo, feature, GitLogFilter(users = listOf("ann real"))),
            "after the mailmap",
        )
        assertEquals(
            GitComparisonCounts(0, 0),
            log.comparisonCounts(repo, feature, GitLogFilter(users = listOf("Test"))),
        )
        assertEquals(
            GitComparisonCounts(2, 1),
            log.comparisonCounts(
                repo,
                feature,
                GitLogFilter(revisions = listOf("refs/heads/main"), firstParent = true),
            ),
        )
    }

    @Test
    fun `the commit side is compared in its checkout form, whatever the attributes say about the temporary files`() {
        repo.resolve(".gitattributes").writeText("*.cs text eol=crlf\n")
        repo.resolve("a.cs").writeText("a\nb\nc\n")
        git("add", "-A")
        val base = commit("Base", 1000)
        // As a checkout writes it, with one line changed.
        repo.resolve("a.cs").writeText("a\r\nB\r\nc\r\n")

        val changes = assertIs<GitWorkingTreeChanges.Files>(log.workingTreeChanges(repo, base))

        assertEquals(listOf(GitChangedFile(GitChange.MODIFIED, "a.cs")), changes.files)
        assertEquals(
            GitFileDiff.Text("a\r\nb\r\nc\r\n", "a\r\nB\r\nc\r\n", listOf(GitHunk(1 until 2, 1 until 2))),
            log.workingTreeFileDiff(repo, base, changes.files.single()),
        )
    }

    @Test
    fun `attributes and line-ending settings do not change how the two texts compare`() {
        repo.resolve("a.txt").writeText("a\nb\nc\n")
        git("add", "-A")
        val base = commit("Base", 1000)
        repo.resolve(".gitattributes").writeText("* -diff\n* binary\n")
        git("config", "core.autocrlf", "true")
        repo.resolve("a.txt").writeText("a\nB\nc\n")

        val diff = log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.MODIFIED, "a.txt"))

        assertEquals(GitFileDiff.Text("a\nb\nc\n", "a\nB\nc\n", listOf(GitHunk(1 until 2, 1 until 2))), diff)
    }

    @Test
    fun `where the JVM cannot name a file git reads it, unless its staged size is too large already`() {
        repo.resolve("a.txt").writeText("one\ntwo\n")
        git("add", "-A")
        val base = commit("Base", 1000)
        repo.resolve("a.txt").writeText("one\nzwei\n")
        log.canNameFile = { false }
        val file = GitChangedFile(GitChange.MODIFIED, "a.txt")

        assertEquals(
            GitFileDiff.Text("one\ntwo\n", "one\nzwei\n", listOf(GitHunk(1 until 2, 1 until 2))),
            log.workingTreeFileDiff(repo, base, file),
        )
        val stored = git("hash-object", "--stdin", input = "one\nzwei\n")
        assertEquals("blob", git("cat-file", "-t", stored), "stored by git")

        // Staged larger than the limit: not stored at all.
        repo.resolve("a.txt").writeText("one\nzwei\ndrei\n")
        git("add", "a.txt")
        repo.resolve("a.txt").writeText("one\nzwei\ndrei\nvier\n")
        val unstored = git("hash-object", "--stdin", input = "one\nzwei\ndrei\nvier\n")
        assertEquals(GitFileDiff.TooLarge, log.workingTreeFileDiff(repo, base, file, maxSize = 12))
        val missing =
            ProcessBuilder("git", "cat-file", "-e", unstored)
                .directory(repo.toFile())
                .apply { environment().putAll(testGitEnvironment()) }
                .start()
                .waitFor()
        assertTrue(missing != 0, "not stored")
    }

    @Test
    fun `a file turned into a symbolic link compares with the link's target path, a pure rename has no hunks`() {
        repo.resolve("a.txt").writeText("x\n")
        repo.resolve("old.txt").writeText("same\n")
        git("add", "-A")
        val base = commit("Base", 1000)
        repo.resolve("a.txt").deleteExisting()
        repo.resolve("a.txt").createSymbolicLinkPointingTo(Path.of("target.txt"))
        git("mv", "old.txt", "new.txt")

        val changes = assertIs<GitWorkingTreeChanges.Files>(log.workingTreeChanges(repo, base))

        assertEquals(
            listOf(
                GitChangedFile(GitChange.TYPE_CHANGED, "a.txt"),
                GitChangedFile(GitChange.RENAMED, "new.txt", "old.txt"),
            ),
            changes.files.sortedBy { it.path },
        )
        val byPath = changes.files.associateBy { it.path }
        assertEquals(
            GitFileDiff.Text("x\n", "target.txt", listOf(GitHunk(0 until 1, 0 until 1))),
            log.workingTreeFileDiff(repo, base, byPath.getValue("a.txt")),
        )
        assertEquals(
            GitFileDiff.Text("same\n", "same\n", emptyList()),
            log.workingTreeFileDiff(repo, base, byPath.getValue("new.txt")),
        )
    }

    @Test
    fun `a submodule is not compared`() {
        val base = commit("Base", 1000)
        git("update-index", "--add", "--cacheinfo", "160000,${"1".repeat(40)},lib")
        // Not checked out: an empty folder.
        repo.resolve("lib").createDirectories()

        val changes = assertIs<GitWorkingTreeChanges.Files>(log.workingTreeChanges(repo, base))

        val lib = changes.files.single { it.path == "lib" }
        assertEquals(GitFileDiff.Submodule(null, null), log.workingTreeFileDiff(repo, base, lib))
    }

    @Test
    fun `the temporary files are deleted afterwards`(
        @TempDir temporary: Path,
    ) {
        repo.resolve("a.txt").writeText("a\n")
        git("add", "-A")
        val base = commit("Base", 1000)
        repo.resolve("a.txt").writeText("b\n")
        log.temporaryFolder = temporary

        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.MODIFIED, "a.txt")))
        assertEquals(emptyList(), temporary.listDirectoryEntries())
    }

    @Test
    fun `diffing two files accepts git's exit values for equal and different files only`(
        @TempDir folder: Path,
    ) {
        folder.resolve("a").writeText("one\n")
        folder.resolve("b").writeText("one\n")
        folder.resolve("c").writeText("two\n")

        assertEquals("", git.diffFiles(folder, "a", "b", null), "exit value 0")
        assertEquals(
            listOf(GitHunk(0 until 1, 0 until 1)),
            GitDiffParser.parse(assertNotNull(git.diffFiles(folder, "a", "c", "histogram"))),
        )
        assertNull(git.diffFiles(folder, "a", "missing", null), "exit value 1 without a hunk")
        assertNull(git.diffFiles(folder, "a", "c", "no-such-algorithm"))
    }

    @Test
    fun `a smudge filter's output is read only up to the limit, then git is stopped`() {
        repo.resolve(".gitattributes").writeText("*.big filter=big\n*.huge filter=huge\n")
        repo.resolve("a.big").writeText("small\n")
        repo.resolve("b.huge").writeText("small\n")
        git("add", "-A")
        val base = commit("Base", 1000)
        // As git-lfs does: a small stored blob, a large file once smudged.
        git("config", "filter.big.smudge", "cat >/dev/null; printf 'line of the large file\\n%.0s' 1 2 3 4 5")
        git("config", "filter.huge.smudge", "cat >/dev/null; yes | head -c 50000000")
        repo.resolve("a.big").writeText("small\nchanged\n")
        repo.resolve("b.huge").writeText("small\nchanged\n")

        val big = GitChangedFile(GitChange.MODIFIED, "a.big")
        assertEquals(GitFileDiff.TooLarge, log.workingTreeFileDiff(repo, base, big, maxSize = 50))
        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, big, maxSize = 200))
        // 50 MB once smudged: only the first kilobyte is read.
        assertEquals(
            GitFileDiff.TooLarge,
            log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.MODIFIED, "b.huge"), maxSize = 1000),
        )
    }
}
