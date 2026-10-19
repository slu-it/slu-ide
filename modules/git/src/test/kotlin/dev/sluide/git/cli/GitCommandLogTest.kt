package dev.sluide.git.cli

import dev.sluide.git.FAKE_GIT_START
import dev.sluide.git.GitCancellation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class GitCommandLogTest {
    @TempDir
    lateinit var folder: Path

    private val lines = CopyOnWriteArrayList<Pair<System.Logger.Level, String>>()
    private var fakes = 0
    private val previous = GitCommandLogSink.sink

    @BeforeEach
    fun capture() {
        GitCommandLogSink.sink = { level, message, _ -> lines += level to message }
    }

    @AfterEach
    fun restore() {
        GitCommandLogSink.sink = previous
    }

    private fun fakeGit(script: String): GitCli {
        val executable =
            folder.resolve("fake-git-${++fakes}").apply {
                writeText(FAKE_GIT_START + "$script\n")
                toFile().setExecutable(true)
            }
        return GitCli(executable.toString(), System.getenv()).also {
            assumeTrue(it.available, "no /bin/sh to stand in for git")
            lines.clear()
        }
    }

    private fun lines(marker: String) = lines.filter { marker in it.second }

    /** The lines of the command whose start line has [marker]. */
    private fun commandLines(marker: String): List<Pair<System.Logger.Level, String>> {
        val id = Regex("git#\\d+").find(lines.first { " start " in it.second && marker in it.second }.second)!!.value
        return lines.filter { it.second.startsWith("$id ") }
    }

    @Test
    fun `a successful command logs its start and end`() {
        val git = fakeGit("echo hello")
        assertEquals("hello\n", git.run(listOf("marker-ok", "a"), folder, 5.seconds))

        val (start, end) = commandLines("marker-ok")
        assertEquals(System.Logger.Level.INFO, start.first)
        assertTrue(Regex("git#\\d+ start \\[.*] marker-ok a \\(in .*\\)").matches(start.second), start.second)
        assertTrue(Regex("git#\\d+ exit 0 in \\d+ ms, 6 bytes").matches(end.second), end.second)
    }

    @Test
    fun `a failing read logs its standard error in the end line`() {
        val git = fakeGit("printf 'fatal: one\\nsecond\\n' >&2; exit 128")
        assertNull(git.run(listOf("marker-fail"), folder, 5.seconds))

        val end = commandLines("marker-fail").single { " exit " in it.second }
        assertEquals(System.Logger.Level.INFO, end.first)
        assertTrue(
            end.second.endsWith(
                "exit 128 in ${end.second.substringAfter(
                    "in ",
                ).substringBefore(" ms")} ms, 0 bytes stderr: fatal: one | second",
            ),
        )
        assertTrue(lines.none { it.first == System.Logger.Level.WARNING })
    }

    @Test
    fun `a timeout is logged once as a warning`() {
        val git = fakeGit("sleep 5")
        assertNull(git.run(listOf("marker-slow"), folder, 1.seconds))

        val warnings = lines("marker-slow").filter { it.first == System.Logger.Level.WARNING }
        assertEquals(1, warnings.size)
        assertTrue("took longer than 1s" in warnings.single().second)
    }

    @Test
    fun `a failed write is logged as a warning with git's message`() {
        val git = fakeGit("echo 'fatal: refused' >&2; exit 1")
        git.write(folder, listOf("marker-write"))

        val warnings = commandLines("marker-write").filter { it.first == System.Logger.Level.WARNING }
        assertEquals(1, warnings.size)
        assertTrue("marker-write" in warnings.single().second)
        assertTrue("fatal: refused" in warnings.single().second)
    }

    @Test
    fun `long arguments are cut and the input is never logged`() {
        val git = fakeGit("cat >/dev/null")
        git.write(folder, listOf("x".repeat(500)), input = "secret".toByteArray())

        val start = lines.first { " start " in it.second }.second
        assertTrue("…" in start)
        assertTrue("x".repeat(300) in start && "x".repeat(301) !in start)
        assertTrue("(stdin 6 bytes)" in start)
        assertTrue(lines.none { "secret" in it.second })
    }

    private fun warnings(marker: String) =
        commandLines(marker).filter { it.first == System.Logger.Level.WARNING }.map { it.second }

    @Test
    fun `a megabyte on standard error neither blocks a read nor makes it incomplete`() {
        val git = fakeGit("head -c 1048576 /dev/zero | tr '\\0' 'e' >&2; echo ok")
        val execution = git.execute(listOf("marker-noisy"), folder, 10.seconds, null, write = false)!!

        assertEquals(0, execution.exitValue)
        assertTrue(execution.complete)
        assertEquals("ok\n", execution.output.toString(Charsets.UTF_8))
        assertEquals(4096, execution.errors.size)
    }

    @Test
    fun `standard error is cut to 500 characters in the log`() {
        val git = fakeGit("head -c 5000 /dev/zero | tr '\\0' 'x' >&2; exit 1")
        assertNull(git.run(listOf("marker-long-stderr"), folder, 5.seconds))

        val end = commandLines("marker-long-stderr").single { " exit " in it.second }.second
        assertEquals("x".repeat(500) + "…", end.substringAfter("stderr: "))
    }

    @Test
    fun `a command on the JavaFX thread starts with a warning`() {
        val git = fakeGit("echo ok")
        val thread =
            Thread.ofPlatform().name("JavaFX Application Thread").start {
                git.run(listOf("marker-fx"), folder, 5.seconds)
            }
        thread.join()

        val start = commandLines("marker-fx").first()
        assertEquals(System.Logger.Level.WARNING, start.first)
        assertTrue(start.second.endsWith(" on the JavaFX thread"), start.second)
    }

    @Test
    fun `an unnamed thread is shown by its description`() {
        val git = fakeGit("echo ok")
        Thread.ofVirtual().start { git.run(listOf("marker-virtual"), folder, 5.seconds) }.join()

        assertTrue(
            Regex(
                "git#\\d+ start \\[VirtualThread.*] marker-virtual",
            ).containsMatchIn(commandLines("marker-virtual").first().second),
        )
    }

    @Test
    fun `a sink that throws does not break the git call`() {
        val git = fakeGit("echo ok")
        GitCommandLogSink.sink = { _, _, _ -> throw IllegalStateException("broken log") }
        assertEquals("ok\n", git.run(listOf("marker-broken"), folder, 5.seconds))
    }

    @Test
    fun `more output than allowed is a warning`() {
        val git = fakeGit("yes")
        val execution =
            git.execute(
                listOf("marker-overflow"),
                folder,
                10.seconds,
                null,
                write = false,
                maxOutput = 100,
            )!!

        assertTrue(execution.overflowed)
        assertTrue(warnings("marker-overflow").single().contains("more output than allowed"))
    }

    @Test
    fun `a cancelled command is a warning`() {
        val git = fakeGit("sleep 10")
        val cancellation = GitCancellation()
        Thread.ofPlatform().start {
            Thread.sleep(500)
            cancellation.cancel()
        }
        git.execute(listOf("marker-cancel"), folder, 20.seconds, null, write = false, cancellation = cancellation)

        assertTrue(warnings("marker-cancel").single().contains(" cancelled: "))
    }

    @Test
    fun `an interrupted command is a warning`() {
        val git = fakeGit("sleep 10")
        val caller = Thread.ofPlatform().start { git.run(listOf("marker-interrupt"), folder, 20.seconds) }
        while (lines("marker-interrupt").isEmpty()) Thread.sleep(20)
        caller.interrupt()
        caller.join(10_000)

        assertTrue(warnings("marker-interrupt").single().contains(" interrupted: "))
    }

    @Test
    fun `output that is not read to the end is a warning`() {
        // A child that keeps standard output open after git has ended.
        val git = fakeGit("sleep 4 &\necho hi")
        val execution = git.execute(listOf("marker-incomplete"), folder, 10.seconds, null, write = false)!!

        assertTrue(!execution.complete)
        assertTrue(warnings("marker-incomplete").single().contains("not read to the end"))
    }
}
