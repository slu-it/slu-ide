package dev.sluide.filewatcher

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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
 */
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

        file.writeText("two")
        recorder.awaitPath(file)
        recorder.paths.clear()

        file.deleteExisting()
        recorder.awaitPath(file)
    }

    @Test
    fun `a burst of changes is reported once per path in few calls`() {
        val recorder = Recorder()
        watcher.watch(dir, recorder)
        val files = (1..50).map { dir.resolve("file-$it.txt") }

        files.forEach { it.writeText("x") }
        await("all 50 files reported, got ${recorder.paths.toSet().size}") { recorder.paths.containsAll(files) }
        Thread.sleep(200)

        assertEquals(recorder.paths.toSet().size, recorder.paths.size, "each path once")
        assertTrue(recorder.calls.size <= 5, "events are combined, but got ${recorder.calls.size} calls")
        assertEquals(recorder.calls.size, dispatches.size, "one dispatch per batch")
    }

    @Test
    fun `nothing is reported after closing the subscription`() {
        val recorder = Recorder()
        val handle = watcher.watch(dir, recorder)
        handle.close()

        dir.resolve("late.txt").writeText("x")
        Thread.sleep(500)

        assertEquals(emptyList(), recorder.paths)
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
