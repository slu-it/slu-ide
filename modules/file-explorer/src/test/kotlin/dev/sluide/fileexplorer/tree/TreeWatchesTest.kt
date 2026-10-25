package dev.sluide.fileexplorer.tree

import dev.sluide.filewatcher.FileWatcher
import javafx.scene.control.TreeItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The watches of the tree with a real [FileWatcher] on a temporary folder; its callbacks run on the test thread, taken
 * from a queue, so the tree items need no display.
 */
class TreeWatchesTest {
    @TempDir
    lateinit var project: Path

    private val callbacks = LinkedBlockingQueue<Runnable>()
    private val watcher = FileWatcher({ callbacks.add(it) }, 50.milliseconds)
    private val reported = mutableListOf<Set<Path>>()
    private val hidden = TreeItem<Path>()
    private val watches =
        TreeWatches(
            watcher,
            isShown = { item -> generateSequence(item) { it.parent }.last() === hidden },
            keepSelection = { it() },
            onFilesChanged = { reported += it },
        )

    @AfterEach
    fun closeWatcher() = watcher.close()

    /** The project folder, compacting and watched as in the view, loaded. */
    private fun openProject(): FileTreeItem =
        FileTreeItem(
            project,
            compact = { true },
            onChildrenAdded = watches::watchChildMiddles,
            onChildrenLoaded = watches::watch,
        ).also {
            hidden.children.add(it)
            it.children
        }

    private fun FileTreeItem.rows() = children.map { (it as FileTreeItem).displayName }

    /** Runs the watcher's callbacks until [condition] holds; fails after 10 seconds. */
    private fun runCallbacksUntil(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
        while (!condition()) {
            if (System.nanoTime() > deadline) fail(message)
            callbacks.poll(50, TimeUnit.MILLISECONDS)?.run()
        }
    }

    /**
     * Runs the watcher's callbacks until none comes for 500 ms, ten times its quiet period: no signal tells that a
     * deletion has been reported by every watch, and a late report would read the tree again after the next change.
     */
    private fun runCallbacksUntilQuiet() {
        while (true) callbacks.poll(500, TimeUnit.MILLISECONDS)?.run() ?: return
    }

    @Test
    fun `a new entry in a middle folder splits the row`() {
        project.resolve("src/main/kotlin").createDirectories()
        val top = openProject()
        assertEquals(listOf("src/main/kotlin"), top.rows())

        project.resolve("src/main/notes.txt").createFile()

        runCallbacksUntil("the row splits") { top.rows() == listOf("src/main") }
    }

    // A new entry in a folder changes its modification time, which the watch of its parent folder reports as a change
    // of it: so the folder holding the new entry is never the first middle folder here, whose parent is always
    // watched, and the middle folders above it are recreated too.

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `middle folders deleted and recreated with the first one, also of a collapsed row, are watched again`() {
        project.resolve("gen/src/main").createDirectories()
        project.resolve("other.txt").createFile()
        val top = openProject()
        assertEquals(listOf("gen/src/main", "other.txt"), top.rows())
        val row = top.children.first()
        val gen = project.resolve("gen")

        gen.deleteRecursively()
        project.resolve("gen/src/main").createDirectories()
        runCallbacksUntil("the deletion is reported") { reported.any { gen in it } }
        runCallbacksUntilQuiet()
        assertEquals(listOf("gen/src/main", "other.txt"), top.rows(), "the chain is the same")
        assertSame(row, top.children.first(), "the row is kept")

        project.resolve("gen/src/more.txt").createFile()

        runCallbacksUntil("the row splits") { top.rows() == listOf("gen/src", "other.txt") }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `middle folders deleted and recreated below the first one are watched again`() {
        project.resolve("a/b/c/d").createDirectories()
        val top = openProject()
        assertEquals(listOf("a/b/c/d"), top.rows())
        val row = top.children.first()
        val b = project.resolve("a/b")

        b.deleteRecursively()
        project.resolve("a/b/c/d").createDirectories()
        runCallbacksUntil("the deletion is reported") { reported.flatten().any { it.startsWith(b) } }
        runCallbacksUntilQuiet()
        assertEquals(listOf("a/b/c/d"), top.rows(), "the chain is the same")
        assertSame(row, top.children.first(), "the row is kept")

        project.resolve("a/b/c/more.txt").createFile()

        runCallbacksUntil("the row splits") { top.rows() == listOf("a/b/c") }
    }
}
