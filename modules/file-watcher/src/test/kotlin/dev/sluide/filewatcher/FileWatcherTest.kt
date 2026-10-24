package dev.sluide.filewatcher

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * Uses the real file system. Timeouts are generous because the JDK may poll on macOS; on Linux events arrive at once.
 * The tests run in parallel (`junit-platform.properties` of this module enables it, the default mode stays
 * same-thread): each has its own directory and watcher, and on macOS each waits for poll cycles.
 */
@Execution(ExecutionMode.CONCURRENT)
class FileWatcherTest {
    @TempDir
    lateinit var dir: Path

    private val dispatches = CopyOnWriteArrayList<Long>()
    private val watcher =
        FileWatcher(
            dispatch = {
                dispatches.add(System.nanoTime())
                it.run()
            },
            quietPeriod = 50.milliseconds,
        )

    @AfterEach
    fun closeWatcher() = watcher.close()

    private class Recorder : (List<Path>) -> Unit {
        val paths = CopyOnWriteArrayList<Path>()
        val calls = CopyOnWriteArrayList<List<Path>>()

        override fun invoke(changed: List<Path>) {
            calls.add(changed)
            paths.addAll(changed)
        }

        fun awaitPath(expected: Path) = await("$expected reported, got $paths") { expected in paths }
    }

    @Test
    fun `reports created, modified and deleted entries`() {
        val recorder = Recorder()
        watcher.watch(dir, recorder)
        val file = dir.resolve("a.txt")

        file.writeText("one")
        recorder.awaitPath(file)
        recorder.paths.clear()

        file.writeText("two, longer")
        recorder.awaitPath(file)
        recorder.paths.clear()

        file.deleteExisting()
        recorder.awaitPath(file)
    }

    @Test
    fun `a burst of changes is reported once per path in few calls`(
        @TempDir other: Path,
    ) {
        val recorder = Recorder()
        watcher.watch(dir, recorder)
        val files = (1..50).map { dir.resolve("file-$it.txt") }
        // A change in a folder outside the watched one, reported after the burst, shows that a late duplicate would
        // have arrived.
        val sentinel = Recorder()
        watcher.watch(other, sentinel)

        files.forEach { it.writeText("x") }
        await("all 50 files reported, got ${recorder.paths.toSet().size}") { recorder.paths.containsAll(files) }
        sentinel.awaitPath(other.resolve("marker.txt").apply { writeText("x") })

        assertEquals(recorder.paths.toSet().size, recorder.paths.size, "each path once")
        assertTrue(recorder.calls.size <= 5, "events are combined, but got ${recorder.calls.size} calls")
        assertEquals(recorder.calls.size + sentinel.calls.size, dispatches.size, "one dispatch per batch")
    }

    @Test
    fun `nothing is reported after closing the subscription`() {
        val recorder = Recorder()
        val handle = watcher.watch(dir, recorder)
        handle.close()
        // Another directory tells when the watcher has had time to report the change below, if it were to.
        val other = dir.resolve("other").createDirectories()
        val sentinel = Recorder()
        watcher.watch(other, sentinel)

        dir.resolve("late.txt").writeText("x")
        val marker = other.resolve("marker.txt").apply { writeText("x") }
        sentinel.awaitPath(marker)

        assertEquals(emptyList(), recorder.paths)
    }

    @Test
    fun `closing the watcher stops the callbacks of its subscriptions`() {
        val recorder = Recorder()
        watcher.watch(dir, recorder)
        watcher.close()
        val observer =
            FileWatcher(dispatch = { it.run() }, quietPeriod = 50.milliseconds)
        try {
            val sentinel = Recorder()
            observer.watch(dir, sentinel)

            val file = dir.resolve("after-close.txt").apply { writeText("x") }
            sentinel.awaitPath(file)

            assertEquals(emptyList(), recorder.paths)
        } finally {
            observer.close()
        }
    }

    @Test
    fun `a listener that throws does not stop the others or later batches`() {
        val thrower = Recorder()
        val failing: (List<Path>) -> Unit = {
            thrower(it)
            throw IllegalStateException("listener failed")
        }
        val other = Recorder()
        watcher.watch(dir, failing)
        watcher.watch(dir, other)

        val first = dir.resolve("first.txt").apply { writeText("x") }
        other.awaitPath(first)
        val second = dir.resolve("second.txt").apply { writeText("x") }

        other.awaitPath(second)
        thrower.awaitPath(second)
    }

    @Test
    fun `subscriptions on the same directory are independent`() {
        val first = Recorder()
        val second = Recorder()
        val firstHandle = watcher.watch(dir, first)
        watcher.watch(dir, second)

        val a = dir.resolve("a.txt").apply { writeText("x") }
        first.awaitPath(a)
        second.awaitPath(a)

        firstHandle.close()
        val b = dir.resolve("b.txt").apply { writeText("x") }
        second.awaitPath(b)
        assertTrue(b !in first.paths)
    }

    @Test
    fun `watching a subfolder reports only its entries`() {
        val sub = dir.resolve("sub").createDirectories()
        val recorder = Recorder()
        watcher.watch(sub, recorder)

        dir.resolve("outside.txt").writeText("x")
        val inside = sub.resolve("inside.txt").apply { writeText("x") }

        recorder.awaitPath(inside)
        assertTrue(recorder.paths.none { it.fileName.toString() == "outside.txt" })
    }

    @Test
    fun `a deleted directory is reported, and watching it again after recreation works`() {
        val sub = dir.resolve("sub").createDirectories()
        val first = Recorder()
        watcher.watch(sub, first)

        sub.deleteExisting()
        first.awaitPath(sub)

        sub.createDirectories()
        val second = Recorder()
        watcher.watch(sub, second)
        val file = sub.resolve("after.txt").apply { writeText("x") }
        second.awaitPath(file)
    }

    /**
     * On macOS a poll cycle of the JDK is longer than the quiet period, so delivery there comes from the quiet
     * period and not from the maximum delay this test is about; only Linux, where events arrive at once, proves it.
     */
    @Test
    fun `a directory that keeps changing is still reported`() {
        val recorder = Recorder()
        watcher.watch(dir, recorder)
        val file = dir.resolve("log.txt")

        // A change every 20 ms never leaves the 50 ms quiet period; the 250 ms maximum delay must still deliver.
        val start = System.nanoTime()
        var deliveredWhileWriting = false
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3)) {
            file.writeText("${System.nanoTime()}")
            Thread.sleep(20)
            if (file in recorder.paths) {
                deliveredWhileWriting = true
                break
            }
        }
        assertTrue(deliveredWhileWriting, "reported while the changes continued")
    }

    @Test
    fun `two paths to the same directory are both watched`() {
        val real = dir.resolve("real").createDirectories()
        val link = Files.createSymbolicLink(dir.resolve("link"), real)
        val viaReal = Recorder()
        val viaLink = Recorder()
        val realHandle = watcher.watch(real, viaReal)
        watcher.watch(link, viaLink)

        real.resolve("a.txt").writeText("x")
        viaReal.awaitPath(real.resolve("a.txt"))
        viaLink.awaitPath(link.resolve("a.txt"))

        realHandle.close()
        real.resolve("b.txt").writeText("x")
        viaLink.awaitPath(link.resolve("b.txt"))
    }

    @Test
    fun `a failing dispatcher does not end the watching`() {
        val failures = AtomicInteger()
        val failingOnce =
            FileWatcher(
                dispatch = {
                    if (failures.getAndIncrement() == 0) throw IllegalStateException("Toolkit not running")
                    it.run()
                },
                quietPeriod = 50.milliseconds,
            )
        try {
            val recorder = Recorder()
            failingOnce.watch(dir, recorder)

            dir.resolve("lost.txt").writeText("x")
            await("the dispatcher was called") { failures.get() > 0 }
            val file = dir.resolve("later.txt").apply { writeText("x") }
            recorder.awaitPath(file)
        } finally {
            failingOnce.close()
        }
    }

    @Test
    fun `watching a missing directory or after closing does nothing and does not throw`() {
        watcher.watch(dir.resolve("missing"), Recorder()).close()
        watcher.close()
        watcher.watch(dir, Recorder()).close()
    }
}

private fun await(
    message: String,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (!condition()) {
        if (System.nanoTime() > deadline) fail(message)
        Thread.sleep(20)
    }
}
