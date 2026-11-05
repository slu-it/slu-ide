package dev.sluide.diagnostics

import java.nio.file.Path
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportWriterTest {
    private val frame = StackTraceElement("dev.sluide.Busy", "spin", "Busy.kt", 12)
    private val moduleFrame =
        StackTraceElement("app", "java.base", "25", "java.lang.Thread", "run", "Thread.java", 1474)
    private val nativeFrame = StackTraceElement("jdk.internal.misc.Unsafe", "park", null, -2)
    private val runLoop = StackTraceElement("com.sun.glass.ui.gtk.GtkApplication", "_runLoop", null, -2)
    private val epoll = StackTraceElement("sun.nio.ch.EPoll", "wait", null, -2)

    private val sampling =
        SamplingResult(
            wallNanos = 10_000_000_000,
            samples = 40,
            processors = 4,
            processCpuNanos = 12_000_000_000,
            samplerCpuNanos = 40_000_000,
            unattributedNanos = 2_000_000_000,
            threads =
                listOf(
                    ThreadCpu(1, "busy", Thread.State.RUNNABLE, 9_000_000_000),
                    ThreadCpu(2, FX_THREAD, Thread.State.WAITING, 500_000_000),
                    ThreadCpu(3, "short", null, 1_000_000),
                ) + (10L..30L).map { ThreadCpu(it, "idle-$it", Thread.State.WAITING, 0) },
            hot =
                listOf(
                    HotThread("busy", 9_000_000_000, 40, 38, listOf(FrequentStack(listOf(frame), 38, cut = true))),
                    // 1 % of one core: its native waits are idle, their stacks not shown.
                    HotThread(
                        FX_THREAD,
                        100_000_000,
                        40,
                        0,
                        emptyList(),
                        nativeWaits = 38,
                        nestedLoops = 2,
                        waitStacks = listOf(FrequentStack(listOf(runLoop), 38, cut = false)),
                    ),
                    // 20 % of one core in a selector wait: native work, its stack is shown.
                    HotThread(
                        "io",
                        2_000_000_000,
                        40,
                        0,
                        emptyList(),
                        nativeWaits = 40,
                        waitStacks = listOf(FrequentStack(listOf(epoll), 40, cut = false)),
                    ),
                    HotThread("sleeper", 1_000_000, 40, 0, emptyList()),
                ),
            gc = listOf(GcDelta("G1 Young Generation", 3, 25), GcDelta("G1 Old Generation", 0, 0)),
            heap = Heap(512L * 1024 * 1024, 1024L * 1024 * 1024, -1),
            deadlocked = emptyList(),
            finalDump =
                listOf(
                    ThreadSnapshot(1, "busy", Thread.State.RUNNABLE, false, listOf(frame, moduleFrame)),
                    ThreadSnapshot(4, "Signal Dispatcher", Thread.State.RUNNABLE, true, emptyList()),
                    ThreadSnapshot(
                        2,
                        FX_THREAD,
                        Thread.State.BLOCKED,
                        true,
                        listOf(nativeFrame),
                        "java.lang.Object@1a2b",
                        "busy",
                    ),
                ),
        )

    private val report =
        Report(
            time = ZonedDateTime.of(2026, 11, 5, 10, 30, 0, 0, ZoneOffset.ofHours(1)),
            folder = Path.of("/home/u/.sluide/diagnostics/2026-11-05_10-30-00"),
            info = listOf("Version" to "1.2.3", "OS" to "Linux 7.0"),
            windowMillis = 10_000,
            intervalMillis = 250,
            pingMillis = 100,
            cancelledAfterNanos = null,
            jfr = "recording.jfr",
            jfrMissing = null,
            jfrMaxAge = Duration.ofMinutes(2),
            threads = "threads.txt",
            threadsMissing = null,
            sampling = sampling,
            fx =
                FxResponsiveness(
                    latencies = listOf(1_000_000, 3_000_000, 40_000_000),
                    installedAfterNanos = 2_000_000,
                    windows = listOf(WindowPulses("Thoreau – project", 120, 10_000_000_000)),
                ),
            log = LogTail(Path.of("/logs/sluide.log"), listOf("first line", "last line")),
        )

    @Test
    fun `has its sections in order`() {
        val text = reportText(report)

        val headings =
            listOf(
                "Thoreau diagnostics",
                "Summary",
                "Threads by CPU in the window (top 15 of 24)",
                "Hot stacks",
                "Full thread dump (3 threads)",
                "Log (last 2 lines of /logs/sluide.log)",
            )
        val positions = headings.map { text.indexOf(it) }
        assertTrue(positions.none { it < 0 }, "missing heading in:\n$text")
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun `heads with time, folder, the caller's lines, the window and the recording`() {
        val text = reportText(report)

        assertContains(text, "Time:                2026-11-05 10:30:00 +01:00")
        assertContains(text, "Folder:              /home/u/.sluide/diagnostics/2026-11-05_10-30-00")
        assertContains(text, "Version:             1.2.3")
        assertContains(text, "OS:                  Linux 7.0")
        assertContains(text, "10.0 s of 10.0 s, stacks every 250 ms (40 samples), JavaFX pings every 100 ms\n")
        assertContains(text, "JFR:                 recording.jfr (up to the last 2 min before the capture ended)")
        assertContains(text, "All threads:         threads.txt (virtual threads too)")
        assertContains(reportText(report.copy(jfrMaxAge = Duration.ofSeconds(90))), "up to the last 90 s before")
        assertFalse(text.contains("Cancelled"))
    }

    @Test
    fun `summarises CPU, JavaFX, heap, GC and deadlocks`() {
        val text = reportText(report)

        assertContains(text, "Process CPU:         120.0 % of one core, 30.0 % of all 4 cores")
        assertContains(text, "Unattributed CPU:    2000 ms (GC, JIT compiler, JFR and other JVM threads)")
        assertContains(text, "Sampling thread CPU: 40 ms")
        assertContains(text, "JavaFX latency:      median 3 ms, max 40 ms; 3 pings, all answered")
        assertContains(text, "Pulses per second:   Thoreau – project: 12.0")
        assertContains(text, "(includes about 1/s from this capture's own progress row)")
        assertContains(text, "Heap:                used 512 MB, committed 1024 MB, max undefined")
        assertContains(
            text,
            "GC in the window:    G1 Young Generation: 3 collections, 25 ms\n" +
                "                     G1 Old Generation: 0 collections, 0 ms\n",
        )
        assertContains(text, "Deadlocks:           none")
    }

    @Test
    fun `lists the busiest threads, their stacks and the full dump`() {
        val text = reportText(report)

        assertContains(text, "      9000    90.0  RUNNABLE       busy\n")
        assertContains(text, "         1     0.0  ended          short\n")
        assertFalse(text.contains("idle-30"), "only the top 15")
        assertContains(
            text,
            "busy (9000 ms CPU)\n  38 of 40 samples running or blocked:\n    at dev.sluide.Busy.spin(Busy.kt:12)\n    …",
        )
        assertContains(
            text,
            "$FX_THREAD (100 ms CPU)\n" +
                "  in a nested event loop (modal dialog open) in 2 of 40 samples\n" +
                "  in the native event loop or a selector wait in 38 of 40 samples\n\n",
        )
        assertFalse(text.contains("_runLoop"), "an idle event loop's stack is left out")
        assertContains(
            text,
            "io (2000 ms CPU)\n" +
                "  in the native event loop or a selector wait in all 40 samples\n" +
                "  40 of 40 samples in native code, with CPU used in the window:\n" +
                "    at sun.nio.ch.EPoll.wait(Native Method)\n",
        )
        assertContains(text, "sleeper (1 ms CPU)\n  waiting in all 40 samples\n")
        assertContains(text, "virtual-thread work: see threads.txt and recording.jfr")
        assertContains(
            text,
            "\"$FX_THREAD\" #2 daemon BLOCKED\n  waiting for java.lang.Object@1a2b held by \"busy\"\n" +
                "    at jdk.internal.misc.Unsafe.park(Native Method)\n",
        )
        assertContains(text, "    at dev.sluide.Busy.spin(Busy.kt:12)\n    at java.lang.Thread.run(Thread.java:1474)\n")
        assertContains(text, "\"Signal Dispatcher\" #4 daemon RUNNABLE\n    (no Java frames)\n")
        assertContains(text, "first line\nlast line\n")
    }

    @Test
    fun `says what is missing or went wrong`() {
        val text =
            reportText(
                report.copy(
                    cancelledAfterNanos = 3_200_000_000,
                    jfr = null,
                    jfrMissing = "the recorder is not running",
                    threads = null,
                    threadsMissing = "no space left",
                    fx =
                        FxResponsiveness(
                            latencies = emptyList(),
                            unanswered = 30,
                            oldestUnansweredNanos = 3_000_000_000,
                            installedAfterNanos = null,
                        ),
                    sampling =
                        sampling.copy(
                            processCpuNanos = null,
                            unattributedNanos = null,
                            samplerCpuNanos = null,
                            deadlocked = listOf("a", "b"),
                        ),
                    log = LogTail(null, emptyList()),
                ),
            )

        assertContains(text, "Cancelled after 3.2 s")
        assertContains(text, "JFR:                 no JFR recording (the recorder is not running)")
        assertContains(text, "Process CPU:         not available")
        assertContains(text, "Unattributed CPU:    not available")
        assertContains(text, "All threads:         no thread dump (no space left)")
        assertContains(text, "no ping answered; 30 of 30 pings unanswered, the oldest waiting 3000 ms")
        assertContains(text, "not measured: the JavaFX thread never got to install the counters")
        assertContains(text, "Deadlocks:           a, b")
        assertContains(text, "Log\n---\nno log file\n")
    }

    @Test
    fun `says when JavaFX was not running or the counters came late`() {
        assertContains(
            reportText(report.copy(fx = FxResponsiveness(unavailable = "JavaFX is not running"))),
            "JavaFX:              not available (JavaFX is not running)",
        )
        assertContains(
            reportText(report.copy(fx = report.fx.copy(installedAfterNanos = 1_500_000_000))),
            "\n                     counters installed 1500 ms after the start (JavaFX thread busy)\n",
        )
    }

    @Test
    fun `folds runs of identical frames, counts printed lines and shows the probe samples`() {
        val recursion = StackTraceElement("javafx.scene.Parent", "updateBounds", "Parent.java", 1909)
        val stack = listOf(frame) + List(9) { recursion } + moduleFrame
        val hot = HotThread("busy", 1_000_000, 40, 10, listOf(FrequentStack(stack, 10, cut = true)), probeSamples = 3)
        val text =
            reportText(
                report.copy(
                    sampling =
                        sampling.copy(
                            hot = listOf(hot),
                            finalDump = listOf(ThreadSnapshot(1, "busy", Thread.State.RUNNABLE, false, stack)),
                        ),
                ),
            )

        assertEquals(
            2,
            Regex("at javafx.scene.Parent.updateBounds\\(Parent.java:1909\\) \\(× 9\\)").findAll(text).count(),
        )
        assertFalse(text.contains("Parent.java:1909)\n"))
        assertContains(text, "3 of 40 samples answering this capture's pings")
        assertFalse(reportText(report).contains("answering this capture's pings"))
    }
}
