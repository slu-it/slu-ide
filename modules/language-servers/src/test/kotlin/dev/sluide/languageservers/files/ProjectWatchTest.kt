package dev.sluide.languageservers.files

import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.FileChangeKind
import dev.sluide.languageservers.ProjectWatchOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.Executor
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The project watch over a fake directory watcher on a real folder: which folders it watches, and the changes it
 * finds when a watched folder reports paths. The worker runs when the test says, so batches are visible.
 */
class ProjectWatchTest {
    @TempDir
    lateinit var root: Path

    private val watcher = FakeDirectoryWatcher()
    private val worker = ManualExecutor()
    private val batches = mutableListOf<List<FileChange>>()

    private fun watch(
        options: ProjectWatchOptions = ProjectWatchOptions(),
        project: Path = root,
    ) = ProjectWatch(project, watcher, worker, Runnable::run, options) { batches += it }.also {
        it.start()
        worker.runAll()
    }

    private fun folder(relative: String) = root.resolve(relative).createDirectories()

    private fun file(
        relative: String,
        text: String = "",
    ): Path = root.resolve(relative).also { it.parent.createDirectories() }.apply { writeText(text) }

    @Test
    fun `every folder is watched except hidden and excluded ones, build output only at the top`() {
        listOf(
            "src/pkg/build",
            "docs",
            ".git/objects",
            ".venv/lib",
            "node_modules/x",
            "src/node_modules",
            "build/out",
            "dist",
            "generated/a",
        ).forEach(::folder)
        val defaults = ProjectWatchOptions()

        val watch = watch(defaults.copy(excludedDirectories = defaults.excludedDirectories + "generated"))

        assertEquals(
            setOf("", "src", "src/pkg", "src/pkg/build", "docs").map(root::resolve).toSet(),
            watch.watchedFolders(),
        )
        assertEquals(watch.watchedFolders(), watcher.listeners.keys)
        assertEquals(emptyList(), batches)
    }

    @Test
    fun `the default cap is lower on macOS, whose JDK lists every watched folder again every 2 s`() {
        assertEquals(300, ProjectWatchOptions.defaultMaxDirectories("Mac OS X"))
        assertEquals(2_000, ProjectWatchOptions.defaultMaxDirectories("Linux"))
    }

    @Test
    fun `a linked folder is not followed, a project folder opened through a link is`() {
        val real = root.resolve("real").createDirectories()
        val inside = file("real/a.py")
        Files.createSymbolicLink(real.resolve("loop"), real)
        val link = Files.createSymbolicLink(root.resolve("link"), real)

        val watch = watch(project = link)
        watcher.report(link, link)
        worker.runAll()

        assertEquals(setOf(link), watch.watchedFolders())
        assertEquals(listOf(link), watcher.listeners.keys.toList())
        assertEquals(emptyList(), batches, "the listing through the link stays: ${inside.fileName} is not deleted")
    }

    @Test
    fun `no more folders are watched past the cap, also new ones`() {
        listOf("a", "b", "c", "d").forEach(::folder)

        val watch = watch(ProjectWatchOptions(maxDirectories = 3))
        assertEquals(3, watch.watchedFolders().size)

        val added = folder("e")
        watcher.report(root, added)
        worker.runAll()

        assertEquals(3, watch.watchedFolders().size)
        assertEquals(listOf(listOf(FileChange(added, FileChangeKind.CREATED))), batches)
    }

    @Test
    fun `a new folder is watched, and what it holds counts as created`() {
        val watch = watch()
        val inner = file("new/inner/a.py")

        watcher.report(root, root.resolve("new"))
        worker.runAll()

        assertTrue(watch.watchedFolders().containsAll(listOf(root.resolve("new"), root.resolve("new/inner"))))
        assertEquals(
            setOf(
                root.resolve("new"),
                root.resolve("new/inner"),
                inner,
            ).map { FileChange(it, FileChangeKind.CREATED) }.toSet(),
            batches.single().toSet(),
        )
    }

    @Test
    fun `created, changed and deleted files are found by comparing the folder with its last listing`() {
        val kept = file("kept.py", "same")
        val edited = file("edited.py", "old")
        val touched = file("touched.py", "same")
        val removed = file("removed.py")
        watch()
        val created = file("created.py")
        edited.writeText("new and longer")
        // Same size, other modification time.
        Files.setLastModifiedTime(touched, FileTime.fromMillis(Files.getLastModifiedTime(touched).toMillis() - 60_000))
        Files.delete(removed)

        watcher.report(root, created, edited, touched, removed, kept)
        worker.runAll()

        assertEquals(
            setOf(
                FileChange(created, FileChangeKind.CREATED),
                FileChange(edited, FileChangeKind.CHANGED),
                FileChange(touched, FileChangeKind.CHANGED),
                FileChange(removed, FileChangeKind.DELETED),
            ),
            batches.single().toSet(),
        )
    }

    @Test
    fun `reports of several folders before the worker runs come as one batch`() {
        folder("a")
        folder("b")
        watch()
        val first = file("a/1.py")
        val second = file("b/2.py")

        watcher.report(root.resolve("a"), first)
        watcher.report(root.resolve("b"), second)
        worker.runAll()

        assertEquals(listOf(setOf(first, second)), batches.map { batch -> batch.map { it.path }.toSet() })
    }

    @Test
    fun `a deleted folder ends its watch and those below it, its files count as deleted`() {
        val inside = file("src/pkg/a.py")
        val watch = watch()

        root.resolve("src").toFile().deleteRecursively()
        // The folder reports itself as it goes, its parent reports the entry.
        watcher.report(root.resolve("src"), root.resolve("src"))
        watcher.report(root, root.resolve("src"))
        worker.runAll()

        assertEquals(setOf(root), watch.watchedFolders())
        assertEquals(
            setOf(
                inside,
                root.resolve("src/pkg"),
                root.resolve("src"),
            ).map { FileChange(it, FileChangeKind.DELETED) }.toSet(),
            batches.single().toSet(),
        )
        assertEquals(batches.single().size, batches.single().toSet().size, "each change once")
    }

    @Test
    fun `a folder that reports itself without being deleted is listed again and watched again`() {
        // What an OVERFLOW (events lost) looks like: nothing on disk says whether the watch still lives.
        watch()
        val created = file("created.py")

        watcher.report(root, root)
        worker.runAll()

        assertEquals(listOf(listOf(FileChange(created, FileChangeKind.CREATED))), batches)
        assertEquals(2, watcher.watches.count { it == root })
        assertTrue(root in watcher.listeners)
    }

    @Test
    fun `a folder deleted and created again is watched again, and what it holds now is reported`() {
        val old = file("gen/old.py")
        val gen = root.resolve("gen")
        val watch = watch()
        gen.toFile().deleteRecursively()
        // As `rm -rf gen && mkdir gen`: Linux may give the new folder the old one's inode number.
        val new = file("gen/new.py")

        watcher.report(gen, gen)
        watcher.report(root, gen)
        worker.runAll()

        assertEquals(2, watcher.watches.count { it == gen })
        assertTrue(gen in watch.watchedFolders() && gen in watcher.listeners)
        assertEquals(
            setOf(FileChange(old, FileChangeKind.DELETED), FileChange(new, FileChangeKind.CREATED)),
            batches.single().toSet(),
        )
    }

    @Test
    fun `a folder its parent reports as an entry keeps its watch`() {
        val src = folder("src")
        watch()

        watcher.report(root, src)
        worker.runAll()

        assertEquals(1, watcher.watches.count { it == src })
    }

    @Test
    fun `closing ends every watch and later reports find nothing`() {
        folder("src")
        val watch = watch()
        val listener = watcher.listeners.getValue(root)

        watch.close()
        worker.runAll()
        file("late.py")
        listener(listOf(root.resolve("late.py")))
        worker.runAll()

        assertEquals(emptyMap(), watcher.listeners)
        assertEquals(emptyList(), batches)
    }

    /** Records watches; [report] calls a folder's listener as `FileWatcher` would. */
    private class FakeDirectoryWatcher : DirectoryWatcher {
        val listeners = HashMap<Path, (List<Path>) -> Unit>()
        val watches = mutableListOf<Path>()

        override fun watch(
            directory: Path,
            listener: (List<Path>) -> Unit,
        ): AutoCloseable {
            listeners[directory] = listener
            watches.add(directory)
            return AutoCloseable { listeners.remove(directory, listener) }
        }

        fun report(
            directory: Path,
            vararg paths: Path,
        ) = listeners.getValue(directory)(paths.toList())
    }

    /** Runs its tasks when the test calls [runAll]. */
    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
