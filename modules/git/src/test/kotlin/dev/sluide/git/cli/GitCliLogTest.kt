package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogOrder
import dev.sluide.git.failingGit
import dev.sluide.git.recordedCalls
import dev.sluide.git.recordingGit
import dev.sluide.git.testGitEnvironment
import dev.sluide.git.unavailableGit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GitCliLog.kt` with the real git: the commits each reader gives for a filter, the arguments it passes (a recording
 * wrapper of git), what it refuses, and that a git that fails, or is not available, gives no answer.
 */
class GitCliLogTest {
    @TempDir
    lateinit var folder: Path

    private val git = GitCli("git", testGitEnvironment())
    private lateinit var repo: TestRepository
    private val root get() = repo.root
    private val log get() = folder.resolve("calls")

    private val output =
        "--use-mailmap -z --no-color --no-show-signature --encoding=UTF-8 " +
            "--format=%H%x1f%P%x1f%aN%x1f%aE%x1f%at%x1f%s"

    /** main: first (a.txt), second (b.txt), third (a.txt); old: first; side: first, merged into main as merge. */
    private lateinit var first: String
    private lateinit var second: String
    private lateinit var third: String

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo = TestRepository(folder.resolve("repo"))
        first = repo.commit("first", "a.txt")
        repo.git("branch", "old")
        second = repo.commit("second", "b.txt")
        third = repo.commit("third", "a.txt")
    }

    private val unavailable get() = unavailableGit(folder)

    private fun failing(word: String) = failingGit(folder, word)

    /** The hashes of the records of a log output, in order. */
    private fun hashes(output: String?): List<String> =
        assertNotNull(output).split('\u0000').filter(String::isNotEmpty).map { it.substringBefore('\u001f') }

    private fun log(
        filter: GitLogFilter = GitLogFilter(),
        skip: Int = 0,
        limit: Int = 100,
        globs: List<String> = emptyList(),
        git: GitCli = this.git,
    ) = git.log(root, filter, skip, limit, globs)

    @Test
    fun `log of the default filter is every commit of the branches, remote branches, tags and HEAD`() {
        repo.git("update-ref", "refs/remotes/origin/main", second)
        repo.git("tag", "t", first)
        val recording = recordingGit(folder, log)

        assertEquals(listOf(third, second, first), hashes(log(git = recording)))
        assertEquals(
            listOf(
                "--no-optional-locks -c log.follow=false log --branches --remotes --tags --ignore-missing HEAD " +
                    "--date-order $output --skip=0 --max-count=100 --",
            ),
            recordedCalls(log),
        )
    }

    @Test
    fun `log with revisions, excluded revisions and paths reads them from the standard input`() {
        val recording = recordingGit(folder, log)
        val filter =
            GitLogFilter(
                revisions = listOf("refs/heads/main", "refs/heads/missing"),
                excluded = listOf("refs/heads/old"),
                paths = listOf("a.txt"),
                order = GitLogOrder.TOPOLOGICAL,
            )

        assertEquals(listOf(third), hashes(log(filter, git = recording)))
        assertEquals(
            listOf(
                "--no-optional-locks -c log.follow=false --literal-pathspecs log --ignore-missing --topo-order " +
                    "--parents --stdin $output --skip=0 --max-count=100 --",
            ),
            recordedCalls(log),
        )
    }

    @Test
    fun `log leaves out what the excluded globs match, skips and limits`() {
        val recording = recordingGit(folder, log)

        assertEquals(listOf(third, second), hashes(log(globs = listOf("refs/heads/old*"), git = recording)))
        assertTrue(" --not --glob=refs/heads/old* $output" in recordedCalls(log).single(), recordedCalls(log).single())
        assertEquals(listOf(second), hashes(log(skip = 1, limit = 1)))
        assertEquals(emptyList(), hashes(log(skip = 3)))
    }

    @Test
    fun `log of an unknown revision is empty, not a failure`() {
        assertEquals("", log(GitLogFilter(revisions = listOf("refs/heads/missing"))))
        assertEquals("", TestRepository(folder.resolve("empty")).let { git.log(it.root, GitLogFilter(), 0, 10) })
    }

    @Test
    fun `log limits by first parents, merges, text and users`() {
        repo.git("checkout", "-q", "-b", "side", first)
        val side = repo.commit("side Fix.Me", "c.txt")
        repo.git("checkout", "-q", "main")
        repo.git("commit", "-q", "--allow-empty", "--author=Alice Example <alice@example.org>", "-m", "by alice")
        val alice = repo.head()
        repo.git("merge", "-q", "--no-ff", "-m", "merge side", "side")
        val merge = repo.head()

        val main = GitLogFilter(revisions = listOf("refs/heads/main"))
        assertEquals(listOf(merge, alice, side, third, second, first).sorted(), hashes(log()).sorted())
        // The branches are all walked: side is a tip of its own.
        assertEquals(listOf(merge, alice, third, second, first), hashes(log(main.copy(firstParent = true))))
        assertEquals(listOf(merge, alice, side, third, second, first).sorted(), hashes(log(main)).sorted())
        assertEquals(
            listOf(alice, side, third, second, first).sorted(),
            hashes(log(GitLogFilter(noMerges = true))).sorted(),
        )
        assertEquals(listOf(side), hashes(log(GitLogFilter(text = "  FIX.me  "))), "ignoring case, trimmed, literal")
        assertEquals(emptyList(), hashes(log(GitLogFilter(text = "fix.me", matchCase = true))))
        assertEquals(listOf(side), hashes(log(GitLogFilter(text = "side Fix.Me", matchCase = true))))
        assertEquals(emptyList(), hashes(log(GitLogFilter(text = "side FixXMe"))), "a dot is a dot")
        assertEquals(listOf(side), hashes(log(GitLogFilter(text = "side Fix.M[e]", regex = true))))
        assertEquals(emptyList(), hashes(log(GitLogFilter(text = "side Fix.M[e]"))), "no regular expression")
        assertEquals(listOf(alice), hashes(log(GitLogFilter(users = listOf("ALICE example")))))
        assertEquals(listOf(alice), hashes(log(GitLogFilter(users = listOf("Alice@Example.org")))))
        assertEquals(listOf(alice), hashes(log(GitLogFilter(users = listOf(" alice example ", "", "  ")))))
        assertEquals(emptyList(), hashes(log(GitLogFilter(users = listOf("Alice")))), "the whole name")
        assertEquals(hashes(log()).sorted(), hashes(log(GitLogFilter(users = listOf("", "  ")))).sorted())
    }

    @Test
    fun `log of paths follows only the commits that change them, with the parents shown`() {
        assertEquals(listOf(third, first), hashes(log(GitLogFilter(paths = listOf("a.txt")))))
        val parents =
            assertNotNull(
                log(GitLogFilter(paths = listOf("a.txt"))),
            ).split('\u0000').first().split('\u001f')[1]
        assertEquals(first, parents, "the nearest commit shown, not the parent")
    }

    @Test
    fun `log refuses what git would read as something else, and a standard input that is too large`() {
        assertNull(log(GitLogFilter(revisions = listOf("main\nother"))))
        assertNull(log(GitLogFilter(excluded = listOf("a\nb"))))
        assertNull(log(GitLogFilter(paths = listOf("a\nb"))))
        assertNull(log(GitLogFilter(revisions = listOf("--all"))))
        assertNull(log(GitLogFilter(excluded = listOf("--all"))))
        assertNull(unavailable.log(root, GitLogFilter(), 0, 10))
        assertNull(failing("log").log(root, GitLogFilter(), 0, 10))
        assertNull(git.log(root, GitLogFilter(), 0, 10, cancellation = GitCancellation().apply { cancel() }))
    }

    @Test
    fun `log takes standard input up to the limit, not more`() {
        // "--\n" and the path with its line break: 3 + 15357 = 15360 bytes, exactly GitCli.MAX_INPUT.
        assertEquals("", log(GitLogFilter(paths = listOf("p".repeat(GitCli.MAX_INPUT - 4)))))
        assertNull(log(GitLogFilter(paths = listOf("p".repeat(GitCli.MAX_INPUT - 3)))))
    }

    @Test
    fun `logCommit is the commit alone if it passes the users, paths and merges of the filter, else empty`() {
        repo.git("commit", "-q", "--allow-empty", "--author=Alice Example <alice@example.org>", "-m", "by alice")
        val alice = repo.head()
        repo.git("checkout", "-q", "-b", "side", first)
        repo.commit("side", "c.txt")
        repo.git("checkout", "-q", "main")
        repo.git("merge", "-q", "--no-ff", "-m", "merge side", "side")
        val merge = repo.head()
        val recording = recordingGit(folder, log)

        assertEquals(listOf(third), hashes(git.logCommit(root, third, GitLogFilter())))
        assertEquals(listOf(alice), hashes(git.logCommit(root, alice, GitLogFilter(users = listOf("alice example")))))
        assertEquals("", git.logCommit(root, third, GitLogFilter(users = listOf("alice example"))))
        assertEquals(listOf(third), hashes(git.logCommit(root, third, GitLogFilter(paths = listOf("a.txt")))))
        assertEquals("", git.logCommit(root, second, GitLogFilter(paths = listOf("a.txt"))))
        assertEquals(listOf(merge), hashes(git.logCommit(root, merge, GitLogFilter())))
        assertEquals("", git.logCommit(root, merge, GitLogFilter(noMerges = true)))
        // The text and the revisions of the filter do not count.
        assertEquals(
            listOf(third),
            hashes(git.logCommit(root, third, GitLogFilter(text = "nothing", revisions = listOf("x")))),
        )
        assertNotNull(recording.logCommit(root, third, GitLogFilter(users = listOf("Al")), null))
        assertNull(git.logCommit(root, "f".repeat(40), GitLogFilter()))
        assertNull(git.logCommit(root, third, GitLogFilter(paths = listOf("a\nb"))))
        assertNull(unavailable.logCommit(root, third, GitLogFilter()))
        assertNull(failing("log").logCommit(root, third, GitLogFilter()))
        assertEquals(
            "--no-optional-locks -c log.follow=false log --extended-regexp --author=^[Aa][Ll] < --author=<[Aa][Ll]> $output $third ^$third^@ --",
            recordedCalls(log).single(),
        )
    }

    @Test
    fun `logCommit with paths reads them literally from the standard input`() {
        val recording = recordingGit(folder, log)

        assertNotNull(recording.logCommit(root, third, GitLogFilter(paths = listOf("a.txt"), noMerges = true)))

        assertEquals(
            "--no-optional-locks -c log.follow=false --literal-pathspecs log --no-merges --stdin $output " +
                "$third ^$third^@ --",
            recordedCalls(log).single(),
        )
    }

    @Test
    fun `fileHistory is the commits of a file with its status and paths, renames followed`() {
        repo.git("mv", "a.txt", "renamed.txt")
        repo.git("commit", "-q", "-m", "rename")
        val renamed = repo.head()
        val recording = recordingGit(folder, log)

        val history = assertNotNull(recording.fileHistory(root, "renamed.txt", "HEAD", 10))

        val records = history.split('\u001e').filter(String::isNotEmpty)
        assertEquals(listOf(renamed, third, first), records.map { it.substringBefore('\u001f') })
        assertTrue(records[0].contains("\nR100\u0000a.txt\u0000renamed.txt\u0000"), records[0])
        assertEquals(
            "--no-optional-locks --literal-pathspecs log --follow -M --name-status --ignore-missing --stdin --root " +
                "--use-mailmap -z --no-color --no-show-signature --encoding=UTF-8 " +
                "--format=%x1e%H%x1f%P%x1f%aN%x1f%aE%x1f%at%x1f%s --max-count=10 --",
            recordedCalls(log).single(),
        )
        assertEquals(1, git.fileHistory(root, "renamed.txt", "HEAD", 1)?.split('\u001e')?.count { it.isNotEmpty() })
        assertEquals("", git.fileHistory(root, "renamed.txt", "refs/heads/missing", 10))
        assertNull(git.fileHistory(root, "a\nb", "HEAD", 10))
        assertNull(git.fileHistory(root, "a.txt", "-x", 10))
        assertNull(unavailable.fileHistory(root, "a.txt", "HEAD", 10))
        assertNull(failing("log").fileHistory(root, "a.txt", "HEAD", 10))
        assertNull(git.fileHistory(root, "a.txt", "HEAD", 10, GitCancellation().apply { cancel() }))
    }

    @Test
    fun `comparisonCounts are the commits each side has that the other does not`() {
        repo.git("checkout", "-q", "-b", "side", first)
        repo.commit("side", "c.txt")
        repo.commit("side two", "c.txt")
        repo.git("checkout", "-q", "main")
        val recording = recordingGit(folder, log)

        assertEquals(2 to 2, git.comparisonCounts(root, "main", "side"))
        assertEquals(2 to 0, git.comparisonCounts(root, "main", "old"))
        assertEquals(0 to 0, git.comparisonCounts(root, "main", "main"))
        assertEquals(1 to 0, git.comparisonCounts(root, "main", "side", GitLogFilter(paths = listOf("a.txt"))))
        assertEquals(0 to 2, git.comparisonCounts(root, "main", "side", GitLogFilter(paths = listOf("c.txt"))))
        assertEquals(1 to 0, git.comparisonCounts(root, "main", "side", GitLogFilter(text = "third")))
        assertEquals(0 to 1, git.comparisonCounts(root, "main", "side", GitLogFilter(text = "side two")))
        assertEquals(2 to 2, recording.comparisonCounts(root, "main", "side", GitLogFilter(firstParent = true)))
        assertEquals(
            "--no-optional-locks -c log.follow=false log --left-right --first-parent --use-mailmap --no-color " +
                "--format=%m --stdin --",
            recordedCalls(log).single(),
        )
        assertNull(git.comparisonCounts(root, "main", "no-such-revision"))
        assertNull(git.comparisonCounts(root, "-x", "side"))
        assertNull(git.comparisonCounts(root, "main", "side", GitLogFilter(paths = listOf("a\nb"))))
        assertNull(unavailable.comparisonCounts(root, "main", "side"))
        assertNull(failing("log").comparisonCounts(root, "main", "side"))
    }

    @Test
    fun `log takes revisions on the standard input up to the limit, with no separator counted`() {
        // The revision and its line break: exactly GitCli.MAX_INPUT bytes; paths would add "--" and their own.
        assertEquals("", log(GitLogFilter(revisions = listOf("r".repeat(GitCli.MAX_INPUT - 1)))))
        assertNull(log(GitLogFilter(revisions = listOf("r".repeat(GitCli.MAX_INPUT)))))
    }
}
