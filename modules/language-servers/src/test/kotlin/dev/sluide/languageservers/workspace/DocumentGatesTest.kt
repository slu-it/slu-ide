package dev.sluide.languageservers.workspace

import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.FileChangeKind
import dev.sluide.languageservers.files.FakeDirectoryWatcher
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watches of the folders the open documents' gate answers name: which are watched when, and the changes a report
 * becomes. The folder worker runs when the test says.
 */
class DocumentGatesTest {
    @TempDir
    lateinit var root: Path

    private val watcher = FakeDirectoryWatcher()
    private val tasks = ArrayDeque<() -> Unit>()
    private val changes = mutableListOf<Pair<Path, List<FileChange>>>()
    private val gates = DocumentGates(watcher, { tasks.addLast(it) }) { folder, found -> changes += folder to found }

    private class Doc(
        override val file: Path,
    ) : WorkspaceDocument {
        override val resolution: CompletableFuture<Resolution> = CompletableFuture()

        override fun adopt(resolution: CompletableFuture<Resolution>) = false

        override fun reopenOnServer() = Unit
    }

    private fun runWorker() {
        while (tasks.isNotEmpty()) tasks.removeFirst()()
    }

    private fun folder(name: String): Path =
        root
            .resolve(name)
            .createDirectories()
            .toAbsolutePath()
            .normalize()

    private fun doc(name: String = "a.py") = Doc(root.resolve(name))

    @Test
    fun `a named folder is watched on the worker, not before, and the document is known to name it`() {
        val f = folder("f")
        val doc = doc()

        gates.name(doc, listOf(f))
        assertEquals(emptyList(), watcher.watches)
        runWorker()

        assertEquals(listOf(f), watcher.watches)
        assertEquals(listOf(doc), gates.naming(f))
        assertEquals(emptyList(), gates.naming(folder("g")))
    }

    @Test
    fun `a folder that two documents name stays watched until both let go`() {
        val f = folder("f")
        val (one, two) = doc("1.py") to doc("2.py")
        gates.name(one, listOf(f))
        gates.name(two, listOf(f))
        runWorker()
        assertEquals(listOf(f), watcher.watches, "watched once")
        assertEquals(setOf(one, two), gates.naming(f).toSet())

        gates.forget(one)
        runWorker()
        assertEquals(emptyList(), watcher.closes)
        assertEquals(listOf(two), gates.naming(f))

        gates.forget(two)
        runWorker()
        assertEquals(listOf(f), watcher.closes)
        assertEquals(emptyMap(), watcher.listeners)
        assertEquals(emptyList(), gates.naming(f))
    }

    @Test
    fun `a document asking again watches the new folders and ends those nobody names`() {
        val (a, b, c) = listOf("a", "b", "c").map(::folder)
        val doc = doc()
        gates.name(doc, listOf(a, b))
        runWorker()

        gates.name(doc, listOf(b, c))
        runWorker()

        assertEquals(listOf(a, b, c), watcher.watches)
        assertEquals(listOf(a), watcher.closes)
        assertEquals(setOf(b, c), watcher.listeners.keys)
        assertEquals(emptyList(), gates.naming(a))
        assertEquals(listOf(doc), gates.naming(c))
    }

    @Test
    fun `naming no folder forgets the document`() {
        val f = folder("f")
        val doc = doc()
        gates.name(doc, listOf(f))
        runWorker()

        gates.name(doc, emptyList())
        runWorker()

        assertEquals(emptyList(), gates.naming(f))
        assertEquals(listOf(f), watcher.closes)
    }

    @Test
    fun `forgetting a document that named nothing does nothing`() {
        gates.forget(doc())
        runWorker()

        assertEquals(emptyList(), watcher.watches)
        assertEquals(emptyList(), watcher.closes)
    }

    @Test
    fun `a folder named again before the worker ends its watch is renewed, and what changed is reported`() {
        val f = folder("f")
        f.resolve("a.py").writeText("")
        val (one, two) = doc("1.py") to doc("2.py")
        gates.name(one, listOf(f))
        runWorker()

        gates.forget(one)
        f.resolve("b.py").writeText("")
        gates.name(two, listOf(f))
        runWorker()

        assertEquals(listOf(f, f), watcher.watches)
        assertEquals(listOf(f), watcher.closes, "only the old watch ended")
        assertEquals(setOf(f), watcher.listeners.keys)
        assertEquals(listOf(f to listOf(FileChange(f.resolve("b.py"), FileChangeKind.CREATED))), changes)
    }

    @Test
    fun `a folder nobody names any more is not watched when its start comes late`() {
        val f = folder("f")
        val doc = doc()

        gates.name(doc, listOf(f))
        gates.forget(doc)
        runWorker()

        assertEquals(emptyList(), watcher.watches)
    }

    @Test
    fun `renewing watches the still named folders again and reports what changed meanwhile`() {
        val (named, unnamed) = listOf("named", "unnamed").map(::folder)
        val kept = named.resolve("kept.py").also { it.writeText("") }
        val doc = doc()
        gates.name(doc, listOf(named))
        runWorker()

        gates.renew(listOf(named, unnamed))
        runWorker()
        assertEquals(emptyList(), changes, "nothing changed")
        assertEquals(listOf(named, named), watcher.watches)
        assertEquals(listOf(named), watcher.closes)

        Files.delete(kept)
        val added = named.resolve("added.py").also { it.writeText("") }
        gates.renew(listOf(named))
        runWorker()

        assertEquals(
            listOf(
                named to
                    listOf(
                        FileChange(kept, FileChangeKind.DELETED),
                        FileChange(added, FileChangeKind.CREATED),
                    ),
            ),
            changes,
        )
    }

    @Test
    fun `closing ends every watch for good, nothing is watched or ended again after it`() {
        val f = folder("f")
        val doc = doc()
        gates.name(doc, listOf(f))
        runWorker()

        gates.close()
        assertEquals(listOf(f), watcher.closes)
        gates.forget(doc)
        gates.name(doc, listOf(folder("g")))
        gates.renew(listOf(f))
        runWorker()

        assertEquals(listOf(f), watcher.closes, "ended once")
        assertEquals(listOf(f), watcher.watches)
    }

    @Test
    fun `a report of an entry becomes created, changed or deleted by what the folder had`() {
        val f = folder("f")
        val known = f.resolve("known.py").also { it.writeText("") }
        val fresh = f.resolve("fresh.py")
        val gone = f.resolve("gone.py").also { it.writeText("") }
        gates.name(doc(), listOf(f))
        runWorker()
        fresh.writeText("")
        Files.delete(gone)

        watcher.report(f, known, fresh, gone)
        runWorker()
        assertEquals(
            listOf(
                f to
                    listOf(
                        FileChange(known, FileChangeKind.CHANGED),
                        FileChange(fresh, FileChangeKind.CREATED),
                        FileChange(gone, FileChangeKind.DELETED),
                    ),
            ),
            changes,
        )

        // What was reported is known now: fresh changes, gone (and not there) is nothing.
        changes.clear()
        watcher.report(f, fresh, gone)
        runWorker()

        assertEquals(listOf(f to listOf(FileChange(fresh, FileChangeKind.CHANGED))), changes)
    }

    @Test
    fun `a report of something unknown and absent, or deeper than the folder, is a call without changes`() {
        val f = folder("f")
        gates.name(doc(), listOf(f))
        runWorker()
        val deep = f.resolve("sub/deep.py").also { it.parent.createDirectories() }.apply { writeText("") }

        watcher.report(f, f.resolve("never.py"), deep)
        runWorker()

        assertEquals(listOf(f to emptyList()), changes)
    }

    @Test
    fun `a report of the folder itself compares its entries whole`() {
        val f = folder("f")
        val old = f.resolve("old.py").also { it.writeText("") }
        gates.name(doc(), listOf(f))
        runWorker()
        Files.delete(old)
        val new = f.resolve("new.py").also { it.writeText("") }

        watcher.report(f, f)
        runWorker()
        assertEquals(
            listOf(f to listOf(FileChange(old, FileChangeKind.DELETED), FileChange(new, FileChangeKind.CREATED))),
            changes,
        )

        changes.clear()
        watcher.report(f, f)
        runWorker()

        assertEquals(listOf(f to emptyList()), changes, "the comparison is against what it found last")
    }

    @Test
    fun `a report from a watch that was ended is not handled`() {
        val f = folder("f")
        val doc = doc()
        gates.name(doc, listOf(f))
        runWorker()
        val ended = watcher.listeners.getValue(f)
        gates.forget(doc)
        runWorker()

        ended(listOf(f.resolve("late.py")))
        runWorker()

        assertEquals(emptyList(), changes)
    }

    @Test
    fun `a folder that does not exist yet is watched with no entries, and its files are created once it does`() {
        val f = root.resolve("later").toAbsolutePath().normalize()
        gates.name(doc(), listOf(f))
        runWorker()
        f.createDirectories()
        val file = f.resolve("a.py").also { it.writeText("") }

        watcher.report(f, f)
        runWorker()

        assertEquals(listOf(f to listOf(FileChange(file, FileChangeKind.CREATED))), changes)
    }

    @Test
    fun `a folder that cannot be listed counts as empty`() {
        assumeFalse(System.getProperty("user.name") == "root", "root reads everything")
        val sealed = folder("sealed")
        sealed.resolve("a.py").writeText("")
        Files.setPosixFilePermissions(sealed, PosixFilePermissions.fromString("---------"))
        try {
            gates.name(doc(), listOf(sealed))
            runWorker()
            watcher.report(sealed, sealed)
            runWorker()
        } finally {
            Files.setPosixFilePermissions(sealed, PosixFilePermissions.fromString("rwxr-xr-x"))
        }

        assertTrue(watcher.listeners.containsKey(sealed))
        assertEquals(listOf(sealed to emptyList()), changes)
    }
}
