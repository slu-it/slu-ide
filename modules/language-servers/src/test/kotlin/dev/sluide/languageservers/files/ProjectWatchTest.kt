package dev.sluide.languageservers.files

import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.FileChangeKind
import dev.sluide.languageservers.LogRecords
import dev.sluide.languageservers.ProjectWatchOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executor
import java.util.logging.Level
import java.util.logging.Logger
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
    private val logger = Logger.getLogger(ProjectWatch::class.java.name).also { it.level = Level.ALL }
    private val logs = LogRecords(logger)

    @AfterEach
    fun closeLogs() = logs.close()

    private fun watch(
        options: ProjectWatchOptions = ProjectWatchOptions(),
        project: Path = root,
        dispatch: (Runnable) -> Unit = Runnable::run,
    ) = ProjectWatch(project, watcher, worker, dispatch, options) { batches += it }.also {
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
        assertEquals(
            listOf("lsp file watch: 3 folders watched in ${root.toAbsolutePath().normalize()}, not watching more"),
            logs.messages(Level.INFO),
            "said once, not for every folder past the cap",
        )
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
        assertEquals(listOf(root), watcher.watches, "a file is not a folder to watch")
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
        assertEquals(listOf(root), watcher.closes, "the old watch is closed once the new one runs")
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
    fun `a hidden or excluded folder that appears or goes is reported as an entry of its parent, not watched`() {
        // What the workspace folders hear when a venv is created or deleted in a project (ADR 0151).
        val project = folder("project")
        watch()
        val venvs = listOf(".venv", "venv").map { project.resolve(it).createDirectories() }
        project.resolve(".venv/pyvenv.cfg").writeText("home = /usr/bin")

        watcher.report(project, *venvs.toTypedArray())
        worker.runAll()
        venvs.forEach { it.toFile().deleteRecursively() }
        watcher.report(project, *venvs.toTypedArray())
        worker.runAll()

        assertEquals(
            listOf(
                venvs.map { FileChange(it, FileChangeKind.CREATED) }.toSet(),
                venvs.map { FileChange(it, FileChangeKind.DELETED) }.toSet(),
            ),
            batches.map { it.toSet() },
        )
        assertTrue(venvs.none { it in watcher.listeners })
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

    @Test
    fun `closing forgets every folder and closes every watch`() {
        folder("src")
        val watch = watch()

        watch.close()
        worker.runAll()

        assertEquals(emptySet(), watch.watchedFolders())
        assertEquals(setOf(root, root.resolve("src")), watcher.closes.toSet())
    }

    @Test
    fun `a close while the tree is being watched stops the listing at once`() {
        folder("a")
        folder("b")
        lateinit var project: ProjectWatch
        val closing =
            DirectoryWatcher {
                directory,
                listener,
                ->
                watcher.watch(directory, listener).also { project.close() }
            }
        project = ProjectWatch(root, closing, worker, Runnable::run, ProjectWatchOptions()) { batches += it }

        project.start()
        worker.runAll()

        assertEquals(listOf(root), watcher.watches)
    }

    @Test
    fun `a start after the close watches nothing, a second start nothing again`() {
        val closed = ProjectWatch(root, watcher, worker, Runnable::run, ProjectWatchOptions()) { batches += it }
        closed.close()
        closed.start()
        worker.runAll()
        assertEquals(emptyList(), watcher.watches)

        val started = watch()
        started.start()
        worker.runAll()

        assertEquals(listOf(root), watcher.watches)
    }

    @Test
    fun `reports before the worker runs queue one drain, and a drain leaves nothing behind`() {
        val a = folder("a")
        val b = folder("b")
        watch()
        val first = file("a/1.py")

        watcher.report(a, first)
        watcher.report(a, a.resolve("1.py"))
        watcher.report(b, b)
        assertEquals(1, worker.pending)
        worker.runAll()
        // Nothing reports a/2.py: only b's report may be handled by the second drain.
        file("a/2.py")
        val third = file("b/3.py")
        watcher.report(b, third)
        worker.runAll()

        assertEquals(listOf(first, third), batches.flatMap { batch -> batch.map { it.path } })
    }

    @ParameterizedTest(name = "reports {0} then {1}: the folder is watched again {2} time(s)")
    @CsvSource("self, a.py, 1", "a.py, self, 1", "self, self, 1", "a.py, b.py, 0")
    fun `a folder that reported itself anywhere in a batch is watched again`(
        first: String,
        second: String,
        renewals: Int,
    ) {
        watch()
        val paths = listOf(first, second).map { if (it == "self") root else file(it) }

        watcher.report(root, *paths.toTypedArray())
        worker.runAll()

        assertEquals(1 + renewals, watcher.watches.count { it == root })
    }

    @Test
    fun `a report that arrives after the close is not handled, the renewed watch stays closed`() {
        val watch = watch()
        file("late.py")

        watcher.report(root, root)
        watch.close()
        worker.runAll()

        assertEquals(1, watcher.watches.count { it == root })
        assertEquals(emptyList(), batches)
    }

    @Test
    fun `changes found before a close do not reach the listener after it`() {
        val held = mutableListOf<Runnable>()
        val watch = watch(dispatch = held::add)
        watcher.report(root, file("a.py"))
        worker.runAll()
        assertEquals(1, held.size)

        watch.close()
        held.forEach(Runnable::run)

        assertEquals(emptyList(), batches)
    }

    @Test
    fun `a renewed watch is the one a later close ends`() {
        val watch = watch()
        watcher.report(root, root)
        worker.runAll()

        watch.close()
        worker.runAll()

        assertEquals(emptyMap(), watcher.listeners)
        assertEquals(listOf(root, root), watcher.closes)
    }

    @Test
    fun `a project folder that is missing is watched anyway and read when it reports itself`() {
        val missing = root.resolve("missing")
        val watch = watch(project = missing)
        assertEquals(setOf(missing), watch.watchedFolders())

        val created = missing.resolve("a.py").also { it.parent.createDirectories() }.apply { writeText("") }
        watcher.report(missing, missing)
        worker.runAll()

        assertEquals(listOf(listOf(FileChange(created, FileChangeKind.CREATED))), batches)
    }

    @Test
    fun `a folder's own report after its parent removed it finds nothing more`() {
        val inside = file("src/pkg/a.py")
        val watch = watch()
        root.resolve("src").toFile().deleteRecursively()

        watcher.report(root, root.resolve("src"))
        watcher.report(root.resolve("src"), root.resolve("src"))
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
    }

    @Test
    fun `a deleted folder that only reports itself ends its watch, its parent's entry is the parent's to report`() {
        val inside = file("src/pkg/a.py")
        val watch = watch()
        root.resolve("src").toFile().deleteRecursively()

        watcher.report(root.resolve("src"), root.resolve("src"))
        worker.runAll()

        assertEquals(setOf(root), watch.watchedFolders())
        assertEquals(setOf(root), watcher.listeners.keys)
        assertEquals(
            listOf(inside, root.resolve("src/pkg")).map { FileChange(it, FileChangeKind.DELETED) }.toSet(),
            batches.single().toSet(),
        )
    }

    @Test
    fun `a folder only its parent reports as gone is forgotten with everything below it`() {
        val inside = file("src/pkg/a.py")
        val watch = watch()
        root.resolve("src").toFile().deleteRecursively()

        watcher.report(root, root.resolve("src"))
        worker.runAll()

        assertEquals(setOf(root), watch.watchedFolders())
        assertEquals(setOf(root), watcher.listeners.keys)
        assertEquals(
            setOf(
                root.resolve("src/pkg"),
                root.resolve("src"),
                inside,
            ).map { FileChange(it, FileChangeKind.DELETED) }.toSet(),
            batches.single().toSet(),
        )
        assertEquals(setOf(root.resolve("src"), root.resolve("src/pkg")), watcher.closes.toSet())
    }

    @Test
    fun `a file that becomes a folder is deleted and created, its content created, the folder watched unless hidden`() {
        val plain = file("x")
        val hidden = file(".h")
        watch()
        Files.delete(plain)
        Files.delete(hidden)
        val inner = file("x/in.py")
        folder(".h")

        watcher.report(root, plain, hidden)
        worker.runAll()

        assertEquals(
            setOf(
                FileChange(plain, FileChangeKind.DELETED),
                FileChange(plain, FileChangeKind.CREATED),
                FileChange(inner, FileChangeKind.CREATED),
                FileChange(hidden, FileChangeKind.DELETED),
                FileChange(hidden, FileChangeKind.CREATED),
            ),
            batches.single().toSet(),
        )
        assertEquals(listOf(root, plain), watcher.watches.distinct())
    }

    @Test
    fun `a folder that becomes a file is forgotten with its content, deleted, and created as a file`() {
        val inside = file("d/inner.py")
        val folder = root.resolve("d")
        val watch = watch()
        folder.toFile().deleteRecursively()
        folder.writeText("now a file")

        watcher.report(root, folder)
        worker.runAll()

        assertEquals(
            setOf(
                FileChange(inside, FileChangeKind.DELETED),
                FileChange(folder, FileChangeKind.DELETED),
                FileChange(folder, FileChangeKind.CREATED),
            ),
            batches.single().toSet(),
        )
        assertEquals(setOf(root), watch.watchedFolders())
        assertEquals(setOf(root), watcher.listeners.keys)
        assertEquals(listOf(root, folder), watcher.watches)
    }

    @Test
    fun `a folder whose listing changed is not a changed file`() {
        file("src/a.py")
        val src = root.resolve("src")
        watch()
        Files.setLastModifiedTime(src, FileTime.fromMillis(Files.getLastModifiedTime(src).toMillis() - 60_000))

        watcher.report(root, src)
        worker.runAll()

        assertEquals(emptyList(), batches)
    }

    @Test
    fun `a watched folder replaced by a link is no folder, the link is not followed`() {
        val inside = file("x/a.py")
        val target = folder("y")
        val watch = watch()
        root.resolve("x").toFile().deleteRecursively()
        Files.createSymbolicLink(root.resolve("x"), target)

        watcher.report(root.resolve("x"), root.resolve("x"))
        worker.runAll()

        assertEquals(setOf(root, target), watch.watchedFolders())
        assertEquals(listOf(FileChange(inside, FileChangeKind.DELETED)), batches.single())
    }

    @Test
    fun `an entry whose attributes cannot be read is left out of the listing`() {
        assumeFalse(System.getProperty("user.name") == "root", "root reads everything")
        val locked = folder("locked")
        val hidden = file("locked/f.py")
        // Readable names without search permission: listing works, reading an entry's attributes fails.
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r--r--r--"))
        try {
            val watch = watch()
            assertEquals(setOf(root, locked), watch.watchedFolders())
            assertEquals(emptyList(), batches)
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        }

        watcher.report(locked, hidden)
        worker.runAll()

        assertEquals(listOf(listOf(FileChange(hidden, FileChangeKind.CREATED))), batches)
    }

    @Test
    fun `a folder that reports after its watch was dropped is not listed again`() {
        val src = folder("src")
        val watch = watch()
        val dropped = watcher.listeners.getValue(src)
        src.toFile().deleteRecursively()
        watcher.report(root, src)
        worker.runAll()
        batches.clear()
        folder("src")

        dropped(listOf(src))
        worker.runAll()

        assertEquals(setOf(root), watch.watchedFolders())
        assertEquals(1, watcher.watches.count { it == src })
        assertEquals(emptyList(), batches)
    }

    @Test
    fun `a file is changed when only its size or only its modification time differs`() {
        val sized = file("sized.py", "aaa")
        val timed = file("timed.py", "aaa")
        val same = file("same.py", "aaa")
        val sizedTime = Files.getLastModifiedTime(sized)
        watch()
        sized.writeText("aaaa")
        Files.setLastModifiedTime(sized, sizedTime)
        Files.setLastModifiedTime(timed, FileTime.fromMillis(Files.getLastModifiedTime(timed).toMillis() + 60_000))

        watcher.report(root, sized, timed, same)
        worker.runAll()

        assertEquals(
            setOf(FileChange(sized, FileChangeKind.CHANGED), FileChange(timed, FileChangeKind.CHANGED)),
            batches.single().toSet(),
        )
    }

    @Test
    fun `a folder that cannot be listed is watched with no entries and read again when it reports itself`() {
        assumeFalse(System.getProperty("user.name") == "root", "root reads everything")
        val sealed = file("sealed/a.py").parent
        Files.setPosixFilePermissions(sealed, PosixFilePermissions.fromString("---------"))
        try {
            val watch = watch()
            assertEquals(setOf(root, sealed), watch.watchedFolders())
            assertTrue(logs.messages(Level.FINE).single().startsWith("lsp file watch: cannot list $sealed: "))
        } finally {
            Files.setPosixFilePermissions(sealed, PosixFilePermissions.fromString("rwxr-xr-x"))
        }

        watcher.report(sealed, sealed)
        worker.runAll()

        assertEquals(listOf(listOf(FileChange(sealed.resolve("a.py"), FileChangeKind.CREATED))), batches)
    }

    /** Runs its tasks when the test calls [runAll]. */
    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val pending: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
