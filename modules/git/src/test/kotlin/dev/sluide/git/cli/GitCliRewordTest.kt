package dev.sluide.git.cli

import dev.sluide.git.GitResult
import dev.sluide.git.failingGit
import dev.sluide.git.fakeGit
import dev.sluide.git.recordedCalls
import dev.sluide.git.recordingGit
import dev.sluide.git.testGitEnvironment
import dev.sluide.git.unavailableGit
import dev.sluide.git.vanishedGit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The wrappers for rewording commits (`GitCliReword.kt`) with the real git, and with a stand-in for what it prints. */
class GitCliRewordTest {
    @TempDir
    lateinit var folder: Path

    private val git = GitCli("git", testGitEnvironment())
    private lateinit var repo: TestRepository
    private val root get() = repo.root
    private val calls get() = folder.resolve("calls.log")
    private lateinit var commits: List<String>

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo = TestRepository(folder.resolve("repo"))
        commits = (1..4).map { repo.commit("commit $it", "f$it.txt") }
    }

    @Test
    fun `ancestryPath lists the commits after commit up to head, parents first`() {
        assertEquals(commits.drop(1), git.ancestryPath(root, commits[0], commits[3]))
        assertEquals(commits.subList(2, 3), git.ancestryPath(root, commits[1], commits[2]))
        assertEquals(emptyList(), git.ancestryPath(root, commits[3], commits[3]))
        assertNull(git.ancestryPath(root, "f".repeat(40), commits[3]))
        assertNull(unavailableGit(folder).ancestryPath(root, commits[0], commits[3]))
    }

    @Test
    fun `ancestryPath takes a dash-leading commit for an option that writes a file`() {
        // "--output=" and "/victim.txt" make the argument "--output=../victim.txt", which git (2.56) takes as the
        // option that truncates that file, then exits 129.
        val victim = folder.resolve("victim.txt").apply { writeText("keep\n") }

        assertNull(git.ancestryPath(root, "--output=", "/victim.txt"))
        assertEquals("keep\n", victim.readText())
    }

    @Test
    fun `ancestryPath takes a dash-leading head for no option, a branch of that name is no revision here`() {
        repo.git("update-ref", "refs/heads/-x", commits[3])
        // `git rev-list <commit>..-x` does list the commits for that branch: the guard keeps it from running.
        assertEquals(
            commits.drop(1),
            repo.git("rev-list", "--reverse", "--ancestry-path", "${commits[0]}..-x", "--").lines(),
        )

        assertNull(git.ancestryPath(root, commits[0], "-x"))
    }

    @Test
    fun `rawCommits reads each commit as stored, whole, by hash`() {
        val raw = git.rawCommits(root, commits)

        assertEquals(commits.toSet(), raw?.keys)
        for (commit in commits) {
            assertContentEquals(
                repo.git("cat-file", "commit", commit).toByteArray() + '\n'.code.toByte(),
                raw?.get(commit),
            )
        }
        assertEquals(emptyMap(), git.rawCommits(root, emptyList()))
        assertNull(git.rawCommits(root, listOf(commits[0], "${commits[1]}\n")))
        assertNull(git.rawCommits(root, listOf(commits[0], "f".repeat(40))), "no such object")
        assertNull(unavailableGit(folder).rawCommits(root, commits))
        assertNull(failingGit(folder, "cat-file").rawCommits(root, commits))
    }

    @Test
    fun `rawCommits is null for a tree among the hashes, and for a hash too long for one run, but does not throw`() {
        val tree = repo.git("rev-parse", "${commits[0]}^{tree}")

        assertNull(git.rawCommits(root, listOf(tree)))
        assertNull(git.rawCommits(root, listOf("x".repeat(GitCli.MAX_INPUT + 10))))
    }

    @Test
    fun `rawCommits asks git again for hashes beyond its input limit`() {
        val recording = recordingGit(folder, calls)
        // A hash and its line break are 41 bytes: 374 of them fit below the limit of 15360.
        val hashes = List(380) { commits[0] }

        val raw = recording.rawCommits(root, hashes)

        assertEquals(setOf(commits[0]), raw?.keys)
        assertEquals(2, recordedCalls(calls).count { it.endsWith("cat-file --batch") })
    }

    @Test
    fun `rawCommits is null for an output that does not match the hashes, one broken shape at a time`() {
        val body = "abc"
        val shapes =
            mapOf(
                "no line break" to "printf 'h commit 3'",
                "two fields" to "printf 'h commit\\n$body\\n'",
                "four fields" to "printf 'h commit 3 3\\n$body\\n'",
                "another hash" to "printf 'g commit 3\\n$body\\n'",
                "a blob" to "printf 'h blob 3\\n$body\\n'",
                "a size that is no number" to "printf 'h commit x\\n$body\\n'",
                "a negative size" to "printf 'h commit -1\\n$body\\n'",
                "a size beyond the output" to "printf 'h commit 9\\n$body\\n'",
                "content to the end, no line break" to "printf 'h commit 3\\n$body'",
                "content not followed by a line break" to "printf 'h commit 3\\nabcd\\n'",
                "missing" to "printf 'h missing\\n'",
            )

        for ((shape, command) in shapes) {
            assertNull(fakeGit(folder, "cat > /dev/null; $command").rawCommits(root, listOf("h")), shape)
        }
        assertEquals(
            "abc",
            fakeGit(
                folder,
                "cat > /dev/null; printf 'h commit 3\\n$body\\n'",
            ).rawCommits(root, listOf("h"))?.get("h")?.let(::String),
        )
        assertEquals(
            mapOf("h" to "", "i" to "x\ny"),
            fakeGit(folder, "cat > /dev/null; printf 'h commit 0\\n\\ni commit 3\\nx\\ny\\n'")
                .rawCommits(root, listOf("h", "i"))
                ?.mapValues { String(it.value) },
        )
    }

    @Test
    fun `stripSpace cleans a message up as commit does without stripping comments`() {
        assertEquals("a\n\nb # c\n# comment\n", git.stripSpace(root, "\n\na   \n\n\n\nb # c\n# comment\n\n\n"))
        assertEquals("héllo ✓\n", git.stripSpace(root, "héllo ✓  "))
        assertEquals("", git.stripSpace(root, "  \n\n"))
        assertNull(unavailableGit(folder).stripSpace(root, "a"))
        assertNull(failingGit(folder, "stripspace").stripSpace(root, "a"))
    }

    @Test
    fun `committerIdent is the identity and time git would commit with`() {
        val ident = git.committerIdent(root).getOrThrow().toString(Charsets.UTF_8)

        assertTrue(Regex("Test <test@example.com> \\d+ [+-]\\d{4}").matches(ident), ident)
        assertEquals(repo.git("var", "GIT_COMMITTER_IDENT").substringBefore(" <"), ident.substringBefore(" <"))
    }

    @Test
    fun `committerIdent says why it cannot tell`() {
        fun message(git: GitCli) = git.committerIdent(root).exceptionOrNull()?.message

        assertEquals("git is not available.", message(unavailableGit(folder)))
        assertEquals("git could not be run.", message(vanishedGit(folder)))
        assertEquals("fatal: refused by test", message(failingGit(folder, "var")))
        assertEquals(
            "fatal: no name",
            message(fakeGit(folder, "echo 'hint: x' >&2; echo 'fatal: no name' >&2; exit 128")),
        )
        assertEquals("git failed with exit code 128.", message(fakeGit(folder, "exit 128")))
        assertEquals("git failed with exit code 0.", message(fakeGit(folder, "exit 0")), "nothing printed")
        assertEquals(
            "git failed with exit code 1.",
            message(fakeGit(folder, "echo 'A <a> 1 +0000'; exit 1")),
            "a failure",
        )
        assertEquals(
            "git failed with exit code 0.",
            message(fakeGit(folder, "(sleep 3 &); echo 'A <a> 1 +0000'; sleep 0.3")),
            "output a process git started holds open is not complete",
        )
        assertEquals(
            "git did not finish within 1 s.",
            fakeGit(folder, "sleep 5").committerIdent(root, 1.seconds).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `committerIdent gives the bytes without the final line breaks`() {
        val ident = fakeGit(folder, "echo 'A <a> 1 +0000'; echo").committerIdent(root).getOrThrow()

        assertEquals("A <a> 1 +0000", ident.toString(Charsets.UTF_8))
    }

    @Test
    fun `writeCommit stores a commit and gives its hash, or git's message`() {
        val content = repo.git("cat-file", "commit", commits[3]).toByteArray() + '\n'.code.toByte()

        val result = git.writeCommit(root, content)

        assertEquals(GitResult(true, commits[3]), result)
        val broken = git.writeCommit(root, "not a commit".toByteArray())
        assertFalse(broken.success)
        assertTrue(broken.message.startsWith("error:") || broken.message.startsWith("fatal:"), broken.message)
        assertEquals(
            GitResult(false, "git gave no commit hash."),
            fakeGit(folder, "cat > /dev/null; echo 'not a hash'").writeCommit(root, content),
        )
        assertEquals(
            GitResult(false, "git gave no commit hash."),
            fakeGit(folder, "cat > /dev/null; echo '${"a".repeat(41)}'").writeCommit(root, content),
            "a hash with one more digit",
        )
        assertEquals(
            GitResult(false, "git gave no commit hash."),
            fakeGit(folder, "cat > /dev/null; echo 'x${"a".repeat(40)}'").writeCommit(root, content),
        )
        assertEquals(
            GitResult(true, "b".repeat(64)),
            fakeGit(folder, "cat > /dev/null; echo ${"b".repeat(64)}").writeCommit(root, content),
        )
        assertEquals(
            GitResult(true, "b".repeat(40)),
            fakeGit(folder, "cat > /dev/null; echo ' ${"b".repeat(40)} '").writeCommit(root, content),
        )
        assertFalse(
            fakeGit(folder, "cat > /dev/null; echo 'fatal: x' >&2; exit 128").writeCommit(root, content).success,
        )
    }

    @Test
    fun `moveBranch moves a branch from its old hash to a new one with the reason in its reflog`() {
        val result = git.moveBranch(root, "main", commits[1], commits[3], "reword: test")

        assertEquals(true, result.success, result.message)
        assertEquals(commits[1], repo.git("rev-parse", "main"))
        assertTrue("reword: test" in repo.git("reflog", "show", "main", "-1"))

        val stale = git.moveBranch(root, "main", commits[2], commits[3], "again")
        assertFalse(stale.success)
        assertEquals(commits[1], repo.git("rev-parse", "main"), "the branch is no longer at the old hash")
    }

    @Test
    fun `moveBranch refuses a branch name with a line break, which would start another command`() {
        val recording = recordingGit(folder, calls)

        val result = recording.moveBranch(root, "main\nupdate refs/heads/other x", commits[0], commits[3], "r")

        assertEquals(GitResult(false, "'main\nupdate refs/heads/other x' cannot be passed to git."), result)
        assertFalse(calls.exists(), "git did not run")
    }

    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun reworded(
        raw: String,
        message: String? = null,
        rewritten: Map<String, String> = mapOf("P1" to "N1"),
    ) = String(
        rewordedCommit(bytes(raw), rewritten, bytes("NEW <n> 9 +0000"), message?.toByteArray(Charsets.UTF_8)),
        Charsets.ISO_8859_1,
    )

    private val signed =
        "tree T\nparent P1\nparent P2\nauthor A <a> 1 +0000\ncommitter C <c> 2 +0000\n" +
            "gpgsig -----BEGIN\n line two\n \n -----END\ngpgsig-sha256 sig\n more\nencoding ISO-8859-1\n"

    @Test
    fun `a reworded commit has new parents and committer, no signature, and keeps its message as bytes`() {
        assertEquals(
            "tree T\nparent N1\nparent P2\nauthor A <a> 1 +0000\ncommitter NEW <n> 9 +0000\n" +
                "encoding ISO-8859-1\n\nméssage\n\n",
            reworded(signed + "\nméssage\n\n"),
        )
    }

    @Test
    fun `a reworded commit with a message has it as the message, and no encoding header`() {
        assertEquals(
            "tree T\nparent N1\nparent P2\nauthor A <a> 1 +0000\ncommitter NEW <n> 9 +0000\n\nnew Ã©\n",
            reworded(signed + "\nold\n", "new é\n"),
        )
    }

    @Test
    fun `a continuation line belongs to the header before it, kept or dropped with it`() {
        val raw = "tree T\nmergetag object x\n tag y\n \n body\ncommitter C <c> 2 +0000\n\nm\n"

        assertEquals("tree T\nmergetag object x\n tag y\n \n body\ncommitter NEW <n> 9 +0000\n\nm\n", reworded(raw))
        // A header that merely starts like a dropped one, or like parent, stays as it is.
        assertEquals(
            "tree T\nparents P1\ngpgsigx a\nencodings b\ncommitter NEW <n> 9 +0000\n\nm\n",
            reworded("tree T\nparents P1\ngpgsigx a\nencodings b\ncommitter C <c> 2 +0000\n\nm\n", "m\n"),
        )
    }

    @Test
    fun `a commit without a message separator keeps its headers, and gets a separator only for a message`() {
        val headers = "tree T\nparent P1\ncommitter C <c> 2 +0000\n"

        assertEquals("tree T\nparent N1\ncommitter NEW <n> 9 +0000\n", reworded(headers))
        assertEquals("tree T\nparent N1\ncommitter NEW <n> 9 +0000\n\nmsg", reworded(headers, "msg"))
        assertEquals("tree T\nparent N1\ncommitter NEW <n> 9 +0000\n\n", reworded(headers + "\n"), "empty message")
        assertEquals("tree T\nauthor A\nparent N1\n", reworded("tree T\nauthor A\nparent P1"), "no final line break")
        assertEquals("", reworded(""))
    }

    @Test
    fun `a reworded commit does not fail for a message longer than the commit`() {
        assertEquals("tree T\n\n" + "m".repeat(5000), reworded("tree T\n\nx", "m".repeat(5000)))
    }

    @Test
    fun `a message starting with a line break after headers is not read as the separator twice`() {
        // The first empty line ends the headers: what follows, headers-like lines included, is the message.
        assertEquals(
            "tree T\ncommitter NEW <n> 9 +0000\n\nparent P1\n\ncommitter x\n",
            reworded("tree T\ncommitter C <c> 2 +0000\n\nparent P1\n\ncommitter x\n"),
        )
        assertNotNull(rewordedCommit(bytes("\nx"), emptyMap(), bytes("c"), null))
    }

    @Test
    fun `rawCommits sizes its runs by the longest hash, wherever it is`() {
        // A git that answers every name as an empty commit, and counts its runs. 300 names of 40 characters fit one
        // run (12300 bytes); with one of 64 characters the runs are 236 names long.
        val runs = folder.resolve("runs.log")
        val counting =
            fakeGit(
                folder,
                "echo run >> '$runs'; while IFS= read -r name; do printf '%s commit 0\\n\\n' \"\$name\"; done",
            )
        val short = List(300) { "a".repeat(40) + it.toString().padStart(4, '0') }.map { it.take(40) }
        val distinct = List(300) { "%040d".format(it) }
        val long = "b".repeat(64)

        fun runs(hashes: List<String>): Int {
            java.nio.file.Files
                .deleteIfExists(runs)
            assertEquals(hashes.size, counting.rawCommits(root, hashes)?.size)
            return java.nio.file.Files
                .readAllLines(runs)
                .size
        }

        assertEquals(1, runs(distinct))
        assertEquals(2, runs(distinct + long))
        assertEquals(2, runs(listOf(long) + distinct))
        assertEquals(2, runs(distinct.take(100) + long + distinct.drop(100)))
        assertEquals(1, short.take(1).let { runs(it) })
    }
}
