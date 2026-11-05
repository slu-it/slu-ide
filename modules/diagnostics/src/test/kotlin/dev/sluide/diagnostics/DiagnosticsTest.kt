package dev.sluide.diagnostics

import dev.sluide.backgroundtasks.BackgroundTasks
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagnosticsTest {
    @TempDir
    lateinit var temp: Path

    private inner class FakeProbe : FxProbe {
        val begun = CountDownLatch(1)
        val pings = AtomicInteger()
        var ended = false

        /** Runs when the sampling has ended. */
        var onEnd: () -> Unit = {}

        override fun begin() = begun.countDown()

        override fun ping() {
            pings.incrementAndGet()
        }

        override fun end(): FxResponsiveness {
            ended = true
            onEnd()
            return FxResponsiveness(latencies = listOf(2_000_000), installedAfterNanos = 0)
        }
    }

    private val probe = FakeProbe()
    private val taskLines = CopyOnWriteArrayList<String>()
    private val tasks = BackgroundTasks.forTests { taskLines += it }
    private val created = mutableListOf<Diagnostics>()

    private fun diagnostics(
        folder: Path = temp.resolve("diagnostics"),
        window: Duration = Duration.ofMillis(300),
        keep: Int = 10,
    ) = Diagnostics(
        folder = folder,
        probe = { probe },
        window = window,
        interval = Duration.ofMillis(50),
        pingInterval = Duration.ofMillis(50),
        keep = keep,
        clock = Clock.fixed(Instant.parse("2026-11-05T10:30:00Z"), ZoneOffset.UTC),
        dispatch = { it.run() },
    ).also { created += it }

    @AfterEach
    fun stop() = created.forEach { it.stop() }

    /** Starts a capture; the future completes with the report, or exceptionally with what [Diagnostics] failed with. */
    private fun Diagnostics.start(logFile: Path? = null): CompletableFuture<Path> {
        val result = CompletableFuture<Path>()
        capture(
            listOf("Version" to "9.9"),
            logFile,
            tasks,
            { result.complete(it) },
            { result.completeExceptionally(it) },
        )
        return result
    }

    /** The thread names of the "Threads by CPU" table, busiest first. */
    private fun threadsByCpu(report: String) =
        report
            .substringAfter("Threads by CPU")
            .substringBefore("\nHot stacks")
            .lines()
            .dropWhile { !it.trimStart().startsWith("CPU ms") }
            .drop(1)
            .filter { it.isNotBlank() }
            .map { it.substring(it.indexOf(' ', it.indexOfFirst { c -> c.isLetter() }) + 1).trimStart() }

    @Test
    fun `a capture writes a report in which a busy thread ranks first`() {
        val log = temp.resolve("sluide.log").also { it.writeText("started\ncapturing\n") }
        val old = temp.resolve("diagnostics/2020-01-01_00-00-00").createDirectories()
        val spinning = CountDownLatch(1)
        val spin = AtomicBoolean(true)
        val busy =
            Thread.ofPlatform().name("busy-spinner").start {
                var x = 0L
                spinning.countDown()
                while (spin.get()) x += System.nanoTime() % 7
                if (x == 42L) println(x)
            }
        try {
            spinning.await(10, TimeUnit.SECONDS)
            // Long enough that the spinner's CPU stands out on slow runners with few cores.
            val diagnostics = diagnostics(window = Duration.ofMillis(1_500), keep = 1)

            val running = diagnostics.start(log)
            assertTrue(diagnostics.capturing.get())
            val report = running.get(60, TimeUnit.SECONDS)

            assertFalse(diagnostics.capturing.get())
            assertEquals(temp.resolve("diagnostics/2026-11-05_10-30-00/report.txt"), report)
            assertFalse(old.exists(), "older folders beyond keep are deleted")
            assertContains(report.resolveSibling("threads.txt").readText(), "busy-spinner")
            val text = report.readText()
            for (heading in listOf("Summary", "Threads by CPU", "Hot stacks", "Full thread dump", "Log (")) {
                assertContains(text, heading)
            }
            assertContains(text, "Version:             9.9")
            assertContains(text, "JFR:                 no JFR recording (the recorder is not running)")
            assertContains(text, "All threads:         threads.txt (virtual threads too)")
            assertContains(text, "JavaFX latency:      median 2 ms")
            assertContains(text, "started\ncapturing\n")
            // JIT compiler threads are busy at the start of a test JVM, compiling the spinner among others.
            val ranked =
                threadsByCpu(text).filterNot {
                    it.startsWith("C1 CompilerThread") || it.startsWith("C2 CompilerThread")
                }
            assertEquals("busy-spinner", ranked.first(), "busy thread first:\n$text")
            assertContains(text.substringAfter("Hot stacks"), "busy-spinner (")
            assertFalse(text.contains("Diagnostics.captureNow"), "the sampling thread is left out")
            assertTrue(probe.pings.get() > 0)
            assertTrue(probe.ended)
            assertEquals(2, taskLines.size, taskLines.toString())
            assertTrue(taskLines[0].endsWith("started: $TASK_NAME"))
            assertTrue(taskLines[1].contains(" finished in ") && taskLines[1].endsWith(": $TASK_NAME"))
        } finally {
            spin.set(false)
            busy.join()
        }
    }

    @Test
    fun `cancel stops sampling early and still writes the report, one capture at a time`() {
        val diagnostics = diagnostics(window = Duration.ofMinutes(5))

        val running = diagnostics.start()
        val second = diagnostics.start()
        probe.begun.await(30, TimeUnit.SECONDS)
        tasks.shown.single().cancel()
        val report = running.get(60, TimeUnit.SECONDS)

        assertFalse(second.isDone, "a second capture while one runs does nothing")
        assertContains(report.readText(), "Cancelled after")
        assertEquals(1, taskLines.count { it.contains("started") })
        assertTrue(taskLines.last().endsWith(": $TASK_NAME (cancelled)"), taskLines.toString())
        assertFalse(diagnostics.capturing.get())
    }

    @Test
    fun `a cancel after the sampling has ended does not mark the capture cancelled`() {
        val diagnostics = diagnostics()
        probe.onEnd = { tasks.shown.single().cancel() }

        val report = diagnostics.start().get(60, TimeUnit.SECONDS)

        assertFalse(report.readText().contains("Cancelled"))
        assertTrue(taskLines.last().endsWith(": $TASK_NAME"), taskLines.toString())
    }

    @Test
    fun `stop cancels a running capture`() {
        val diagnostics = diagnostics(window = Duration.ofMinutes(5))

        val running = diagnostics.start()
        probe.begun.await(30, TimeUnit.SECONDS)
        diagnostics.stop()

        assertContains(running.get(30, TimeUnit.SECONDS).readText(), "Cancelled after")
    }

    @Test
    fun `a capture keeps the recording of the JFR ring buffer`() {
        val diagnostics = diagnostics()
        diagnostics.startRecorder()

        val report = diagnostics.start().get(60, TimeUnit.SECONDS)

        assertContains(
            report.readText(),
            "JFR:                 recording.jfr (up to the last 2 min before the capture ended)",
        )
        assertEquals(
            setOf("recording.jfr", "report.txt", "threads.txt"),
            report.parent
                .listDirectoryEntries()
                .map { it.name }
                .toSet(),
        )
        assertTrue(temp.resolve("diagnostics/.jfr").listDirectoryEntries().isNotEmpty(), "JFR's chunks in .jfr")
    }

    @Test
    fun `a failure reaches the caller and ends the task`() {
        val notAFolder = temp.resolve("file").also { it.writeText("") }
        val diagnostics = diagnostics(folder = notAFolder)

        val failure = runCatching { diagnostics.start().get(60, TimeUnit.SECONDS) }.exceptionOrNull()

        assertIs<ExecutionException>(failure)
        assertTrue(taskLines.last().contains("failed after"), taskLines.toString())
        assertFalse(diagnostics.capturing.get())
    }
}
