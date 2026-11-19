package dev.sluide.git.cli

import dev.sluide.git.FAKE_GIT_START
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitIgnoreLineUse
import dev.sluide.git.GitIgnoreRules
import dev.sluide.git.failingGit
import dev.sluide.git.fakeGit
import dev.sluide.git.recordedCalls
import dev.sluide.git.recordingGit
import dev.sluide.git.testGitEnvironment
import dev.sluide.git.unavailableGit
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.time.Duration.ofSeconds
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What [ignoreRules] answers when a git call fails, hangs, vanishes or is too slow, and where its limits fall: the
 * real git for the rest, a script that stands in for it for the hangs (checked against git's `-z` records in
 * [GitCliIgnoreRulesTest]).
 */
class GitCliIgnoreRulesFailuresTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var repository: TestRepository
    private val repo get() = repository.root

    private val git = GitCli("git", testGitEnvironment())

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repository = TestRepository(folder.resolve("repo"))
    }

    private fun git(vararg arguments: String) = repository.git(*arguments)

    private fun files(vararg paths: String) {
        for (path in paths) repo.resolve(path).apply { parent.createDirectories() }.writeText("x\n")
    }

    private fun ignoring(vararg examples: String) =
        GitIgnoreLineUse(examples.size, 0, 0, examples.toList(), emptyList())

    private fun rules(
        git: GitCli = this.git,
        ignoreFile: Path = repo.resolve(".gitignore"),
        cancellation: GitCancellation? = null,
        limit: Int = MAX_IGNORE_CANDIDATES,
        maxListing: Long = MAX_IGNORE_LISTING,
        deadline: Duration = IGNORE_DEADLINE,
        callTimeout: Duration = 10.seconds,
        root: Path = repo,
    ): GitIgnoreRules? = git.ignoreRules(root, ignoreFile, cancellation, limit, maxListing, deadline, callTimeout)

    /** A repository with a tracked ignored file and an untracked ignored one, so that every call has work. */
    private fun withTrackedAndUntracked() {
        files("t.log")
        git("add", "-f", "t.log")
        git("commit", "-q", "-m", "Initial")
        files("u.log")
        repo.resolve(".gitignore").writeText("*.log\n")
    }

    private fun <T> within(block: () -> T): T = assertTimeoutPreemptively(ofSeconds(30), ThrowingSupplier { block() })

    @Test
    fun `a git that is not available is not asked`() {
        withTrackedAndUntracked()

        assertNotNull(rules(), "the same repository gives an answer")
        assertNull(rules(unavailableGit(folder)))
    }

    @Test
    fun `an ignore file above the folder git runs in has no answer`() {
        withTrackedAndUntracked()
        val sub = repo.resolve("sub").createDirectories()

        // Git would answer for `../`: the ignore file of the folder above is not below the root.
        assertNull(rules(root = sub, ignoreFile = repo.resolve(".gitignore")))
        assertNotNull(rules(root = sub, ignoreFile = sub.resolve(".gitignore")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "ls-files", "check-ignore", "--no-index"])
    fun `a call that fails gives no answer`(word: String) {
        withTrackedAndUntracked()

        assertNull(rules(failingGit(folder, word)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "ls-files", "check-ignore"])
    fun `a git that vanishes before a call gives no answer`(word: String) {
        withTrackedAndUntracked()
        val script = folder.resolve("vanishing-git")
        // Removes itself as the call with [word] starts, which still runs: the calls after it find no executable.
        script.writeText(
            FAKE_GIT_START + "case \" \$* \" in *' $word '*) rm -f \"\$0\";; esac\nexec git \"\$@\"\n",
        )
        script.toFile().setExecutable(true)
        val vanishing = GitCli(script.toString(), testGitEnvironment())
        assertTrue(vanishing.available)

        assertNull(rules(vanishing))
        assertFalse(script.exists(), "it was asked")
    }

    @Test
    fun `no call is started once the time is up`() {
        withTrackedAndUntracked()
        // The command log has the start of every call, before git is started (a recording script could be killed first).
        val started = CopyOnWriteArrayList<String>()
        val previous = GitCommandLogSink.sink
        GitCommandLogSink.sink = { _, message, _ -> started += message }
        try {
            assertNull(rules(deadline = Duration.ZERO))
        } finally {
            GitCommandLogSink.sink = previous
        }

        assertEquals(emptyList(), started)
    }

    @Test
    fun `paths are asked in one chunk up to the limit of the input, with no prefix counted`() {
        val names = (1..100).map { "f%03d".format(it) + "x".repeat(144) + ".log" }
        files(*names.toTypedArray())
        repo.resolve(".gitignore").writeText("*.log\n")
        // 100 names of 152 bytes and ".gitignore" with their NULs: 15311 bytes of the 15360 allowed.
        val log = folder.resolve("calls")

        val use = assertNotNull(rules(recordingGit(folder, log))).lines.getValue(1)

        assertEquals(100, use.ignored)
        assertEquals(1, recordedCalls(log).count { "check-ignore" in it }, "one chunk")
    }

    @Test
    fun `a nested ignore file asks about the candidates below its folder only`() {
        files("a/x.log", "b1.log", "b2.log", "b3.log", "b4.log")
        repo.resolve("a/.gitignore").writeText("*.log\n")

        // Below a/: the ignore file itself and x.log. The four files above are no candidates, and do not fill the limit.
        val nested = assertNotNull(rules(ignoreFile = repo.resolve("a/.gitignore"), limit = 3))

        assertTrue(nested.complete)
        assertEquals(mapOf(1 to ignoring("x.log")), nested.lines)
    }

    @Test
    fun `a limit that only the untracked candidates reach makes the result incomplete`() {
        files("a.log", "b.log", "c.log")
        repo.resolve(".gitignore").writeText("*.log\n")

        // The ignore file and three files, nothing tracked.
        val capped = assertNotNull(rules(limit = 3))

        assertFalse(capped.complete)
        assertEquals(mapOf(1 to ignoring("a.log", "b.log")), capped.lines)
        assertTrue(assertNotNull(rules(limit = 4)).complete)
    }

    @Test
    fun `a limit that only the tracked candidates reach makes the result incomplete`() {
        files("a.log", "b.log")
        git("add", "-f", "a.log", "b.log")
        git("commit", "-q", "-m", "Initial")
        repo.resolve(".gitignore").writeText("*.log\n")

        // The ignore file is the one untracked candidate; two tracked ones are ignored.
        val capped = assertNotNull(rules(limit = 2))

        assertFalse(capped.complete)
        assertEquals(GitIgnoreLineUse(0, 0, 1, emptyList(), listOf("a.log")), capped.lines.getValue(1))
        assertTrue(assertNotNull(rules(limit = 3)).complete)
    }

    @Test
    fun `a status listing cut off after a complete-looking record leaves that record out`() {
        files("a.log", "b.log.log")
        repo.resolve(".gitignore").writeText("*.log\n")
        // "? .gitignore\0! a.log\0! b.log" is 28 bytes: the record that is cut off looks like the file b.log.
        val capped = assertNotNull(rules(maxListing = 28))

        assertFalse(capped.complete)
        assertEquals(mapOf(1 to ignoring("a.log")), capped.lines)
    }

    @Test
    fun `a listing of tracked files cut off after a complete-looking record leaves that record out`() {
        files("a.log", "b.log.log")
        repo.resolve(".gitignore").writeText("*.log\n")
        git("add", "-f", "a.log", "b.log.log", ".gitignore")
        git("commit", "-q", "-m", "Initial")
        // "a.log\0b.log" is 11 bytes: the record that is cut off looks like the file b.log.
        val capped = assertNotNull(rules(maxListing = 11))

        assertFalse(capped.complete)
        assertEquals(mapOf(1 to GitIgnoreLineUse(0, 0, 1, emptyList(), listOf("a.log"))), capped.lines)
    }

    /**
     * A stand-in for git whose `status` lists one untracked file, whose `ls-files` lists nothing and whose
     * `check-ignore` says that `.gitignore` line 1 ignores it; the call with [hold] leaves a process behind that holds
     * its output open, so that git ended but its output is not complete; the call with [hang] does not end.
     */
    private fun ignoreRulesGit(
        hold: String? = null,
        hang: String? = null,
        pipeGrace: Duration = 300.milliseconds,
    ): GitCli {
        // The script waits 0.3 s before it ends so that the reader is reading (docs/testing.md "Portability").
        val held = "(sleep 3 &); sleep 0.3"

        fun hold(word: String) =
            when (word) {
                hold -> held
                hang -> "sleep 30"
                else -> ""
            }
        val body =
            "case \" \$* \" in\n" +
                "*' status '*) printf '? a.log\\000'; ${hold("status")};;\n" +
                "*' ls-files '*) ${hold("ls-files")};;\n" +
                "*' check-ignore '*) cat > /dev/null; printf '.gitignore\\0001\\000*.log\\000a.log\\000'; " +
                "${hold("check-ignore")};;\n" +
                "esac"
        return fakeGit(folder, body, pipeGrace = pipeGrace)
    }

    @Test
    fun `the fake git answers as it is meant to`() {
        assertEquals(mapOf(1 to ignoring("a.log")), assertNotNull(rules(ignoreRulesGit())).lines)
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "ls-files", "check-ignore"])
    fun `a call whose output is held open past git's end gives no answer`(word: String) {
        within { assertNull(rules(ignoreRulesGit(hold = word))) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "ls-files", "check-ignore"])
    fun `a call that hangs for its own time gives no answer, not an incomplete one`(word: String) {
        // 15 s in all, 0.5 s for each call: it is the call that is too slow, not the whole.
        within { assertNull(rules(ignoreRulesGit(hang = word), callTimeout = 500.milliseconds)) }
    }

    /**
     * Candidates that need several chunks: `status` lists 600 long names, the first `check-ignore` answers and holds
     * its output open until [release] exists, the second one is recorded in [calls].
     */
    private fun slowChunks(
        started: Path,
        release: Path,
        calls: Path,
    ): GitCli {
        val body =
            "case \" \$* \" in\n" +
                "*' status '*) i=0; while [ \$i -lt 600 ]; do\n" +
                "  printf '! a-rather-long-file-name-to-fill-the-input-of-check-ignore-%s.log\\000' \$i; " +
                "i=\$((i+1)); done ;;\n" +
                "*' check-ignore '*) cat > /dev/null; echo x >> '$calls'\n" +
                "  [ -e '$started' ] && exit 0\n" +
                "  touch '$started'; printf '%s\\000' .gitignore 1 '*.log' first.log\n" +
                "  ( while [ ! -e '$release' ]; do sleep 0.05; done ) &\n" +
                // Waits so that the reader is reading when the script ends (docs/testing.md "Portability").
                "  sleep 0.3 ;;\n" +
                "esac"
        // The output is held open until the test says so, which may be after the default grace of 2 s.
        return fakeGit(folder, body, pipeGrace = 20.seconds)
    }

    /**
     * Runs [ignoreRules] on [slowChunks] with a deadline of 2 s, lets the deadline pass while the first chunk's output
     * is still held open, runs [betweenChunks], then releases the output: the result of the run.
     */
    private fun runPastTheDeadline(
        calls: Path,
        betweenChunks: () -> Unit,
        cancellation: GitCancellation? = null,
    ): GitIgnoreRules? {
        val started = folder.resolve("started")
        val release = folder.resolve("release")
        val git = slowChunks(started, release, calls)
        val begin = AtomicLong()
        var result: GitIgnoreRules? = null
        val runner =
            Thread.ofPlatform().start {
                begin.set(System.nanoTime())
                result = rules(git, cancellation = cancellation, deadline = 2.seconds)
            }
        val limit = System.nanoTime() + 20.seconds.inWholeNanoseconds
        // The signal is the first chunk's start; then the 2 s of the deadline have to pass: there is no signal for that.
        while (!started.exists()) {
            if (System.nanoTime() > limit) fail("check-ignore did not start")
            Thread.sleep(10)
        }
        while (System.nanoTime() < begin.get() + 2.5.seconds.inWholeNanoseconds) Thread.sleep(20)
        betweenChunks()
        release.writeText("go")
        runner.join(TimeUnit.SECONDS.toMillis(20))
        assertFalse(runner.isAlive, "ignoreRules ended")
        return result
    }

    @Test
    fun `a chunk that ends after the deadline is the last, with its lines, and no other is started`() {
        val calls = folder.resolve("check-ignore-calls")

        val rules = assertNotNull(runPastTheDeadline(calls, betweenChunks = {}))

        assertFalse(rules.complete)
        assertEquals(mapOf(1 to ignoring("first.log")), rules.lines)
        assertEquals(1, calls.toFile().readLines().size, "the second chunk was not asked")
    }

    @Test
    fun `a run cancelled after the deadline has no answer`() {
        val cancellation = GitCancellation()

        val rules =
            runPastTheDeadline(folder.resolve("check-ignore-calls"), { cancellation.cancel() }, cancellation)

        assertNull(rules)
    }

    @Test
    fun `a listing stopped for writing too much is cut off, not failed, though git was killed`() {
        // The stand-in writes its first record and then waits: it is killed when the output passes the limit, so that
        // its exit value is not 0.
        val stopped = assertNotNull(rules(ignoreRulesGit(hang = "status"), maxListing = 5))

        assertFalse(stopped.complete)
        assertEquals(emptyMap(), stopped.lines)
    }

    @Test
    fun `untracked paths are asked with the index, tracked ones without it`() {
        withTrackedAndUntracked()
        val log = folder.resolve("calls")

        assertNotNull(rules(recordingGit(folder, log)))

        val asked = recordedCalls(log).filter { "check-ignore" in it }
        assertEquals(2, asked.size)
        assertFalse("--no-index" in asked[0], asked[0])
        assertTrue("--no-index" in asked[1], asked[1])
    }

    @Test
    fun `a run cancelled while a chunk runs has no answer, whatever time the deadline has left`() {
        files("a.log")
        repo.resolve(".gitignore").writeText("*.log\n")
        val started = folder.resolve("started")
        val script = folder.resolve("hanging-git")
        script.writeText(
            FAKE_GIT_START +
                "case \" \$* \" in *' check-ignore '*) cat > /dev/null; touch '$started'; sleep 30;; esac\n" +
                "exec git \"\$@\"\n",
        )
        script.toFile().setExecutable(true)
        val hanging = GitCli(script.toString(), testGitEnvironment())
        val cancellation = GitCancellation()
        var result: GitIgnoreRules? = null
        // The deadline of 5 s ends before the 10 s of a call: the chunk is stopped at what is left of it.
        val runner =
            Thread.ofPlatform().start { result = rules(hanging, cancellation = cancellation, deadline = 5.seconds) }
        val limit = System.nanoTime() + 20.seconds.inWholeNanoseconds
        while (!started.exists()) {
            if (System.nanoTime() > limit) fail("check-ignore did not start")
            Thread.sleep(10)
        }

        cancellation.cancel()
        runner.join(TimeUnit.SECONDS.toMillis(20))

        assertFalse(runner.isAlive, "ignoreRules ended")
        assertNull(result)
    }
}
