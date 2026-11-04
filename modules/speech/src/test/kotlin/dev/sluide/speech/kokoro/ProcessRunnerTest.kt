package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.SpeechException
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [ProcessRunner.Default] against `/bin/sh` and its tools, which macOS and Linux have. */
class ProcessRunnerTest {
    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun onlyUnix() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "needs /bin/sh")
    }

    private fun sh(script: String) = listOf("/bin/sh", "-c", script)

    /** The process id the script wrote with `echo $$`; waits until it is there. */
    private fun pid(file: Path): Long {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val text = if (Files.exists(file)) Files.readString(file) else ""
            if (text.endsWith("\n")) return text.trim().toLong()
            Thread.sleep(10)
        }
        error("the script did not write its pid")
    }

    private fun assertGone(pid: Long) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) != true) return
            Thread.sleep(10)
        }
        error("process $pid is still running")
    }

    @Test
    fun `stdin goes to the process and its stdout comes back as UTF-8`() {
        val result = ProcessRunner.Default.run(listOf("/bin/cat"), "həlˈO wɜːld\nsecond", 5.seconds)
        assertEquals("həlˈO wɜːld\nsecond", result.stdout)
        assertEquals(0, result.exitCode)
        assertEquals(false, result.timedOut)
    }

    @Test
    fun `a non zero exit code is reported`() {
        val result = ProcessRunner.Default.run(sh("cat > /dev/null; exit 3"), "x", 5.seconds)
        assertEquals(3, result.exitCode)
        assertEquals(false, result.timedOut)
    }

    @Test
    fun `a process that exits without reading its input still reports its exit code`() {
        val result = ProcessRunner.Default.run(sh("exit 4"), "x".repeat(200_000), 5.seconds)
        assertEquals(4, result.exitCode)
    }

    @Test
    fun `a process that outlives the timeout is killed`() {
        val pidFile = dir.resolve("pid")
        val result = ProcessRunner.Default.run(sh("echo \$\$ > '$pidFile'; exec sleep 30"), "", 500.milliseconds)
        assertTrue(result.timedOut)
        assertGone(pid(pidFile))
    }

    @Test
    fun `a missing executable is a speech exception`() {
        val e =
            assertFailsWith<SpeechException> {
                ProcessRunner.Default.run(listOf(dir.resolve("nothing").toString()), "x", 5.seconds)
            }
        assertTrue("cannot be started" in e.message!!, e.message)
    }

    @Test
    fun `an interrupt kills the process and is rethrown`() {
        val pidFile = dir.resolve("pid")
        val thrown = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val thread =
            Thread {
                try {
                    ProcessRunner.Default.run(sh("echo \$\$ > '$pidFile'; exec sleep 30"), "", 20.seconds)
                } catch (t: Throwable) {
                    thrown.set(t)
                } finally {
                    done.countDown()
                }
            }
        thread.start()
        val pid = pid(pidFile)
        thread.interrupt()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(thrown.get() is InterruptedException, "was ${thrown.get()}")
        assertGone(pid)
    }
}
