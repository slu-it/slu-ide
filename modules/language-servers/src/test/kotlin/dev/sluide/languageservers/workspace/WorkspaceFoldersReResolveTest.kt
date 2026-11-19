package dev.sluide.languageservers.workspace

import dev.sluide.languageservers.DocumentGate
import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.FileChangeKind
import dev.sluide.languageservers.WorkspaceFolderMode
import dev.sluide.languageservers.awaitTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals

/**
 * What [WorkspaceFolders] does when the project changes under open documents: documents are asked again, folders no
 * document resolves to any more go, folders whose watched folders changed are refreshed, and the affected documents
 * are opened again once. The spec answers `null` (the window folder) for every file but those in [answers].
 */
internal class WorkspaceFoldersReResolveTest : WorkspaceFoldersHostBase() {
    private lateinit var a: Path
    private lateinit var site: Path

    private fun setUp(
        worker: Executor = Executor(Runnable::run),
        mode: WorkspaceFolderMode = WorkspaceFolderMode.WINDOW_FIRST,
    ): WorkspaceFolders {
        a = dir("a")
        site = dir("a/.venv/site")
        watchedBy[a] = listOf(site)
        val folders = folders(spec(mode = mode), worker)
        announce(folders, a)
        return folders
    }

    /** The project changes below `a`: its documents are asked again after the wait. */
    private fun projectChangesInA(folders: WorkspaceFolders) {
        folders.filesChanged(listOf(change("a/pyproject.toml")))
        runDue()
    }

    @Test
    fun `a document that moves to the window folder takes its old folder with it, which is removed and reopened`() {
        val folders = setUp()
        val d = doc("a/d.py").resolvedTo(a)

        projectChangesInA(folders)

        assertEquals(listOf(a to FolderSent.ADDED, a to FolderSent.REMOVED), host.sent)
        assertEquals(listOf(root), folders.current)
        assertEquals(1, d.reopened.size)
        assertEquals(1, host.changed)
        assertEquals(Resolution(null, DocumentGate.SERVED), d.resolution.getNow(null))
        assertEquals(listOf(site), watcher.closes, "the watches of the removed folder end")
        awaitTrue("the server was told") { servers.single().workspaceFolderChanges.size == 2 }
    }

    @Test
    fun `a folder another document still resolves to stays, also one whose answer is not in yet`() {
        val folders = setUp()
        answers["e.py"] = a
        val moved = doc("a/d.py").resolvedTo(a)
        val stays = doc("a/e.py").resolvedTo(a)

        projectChangesInA(folders)

        assertEquals(listOf(a to FolderSent.ADDED), host.sent)
        assertEquals(listOf(root, a), folders.current)
        assertEquals(listOf(1, 0), listOf(moved, stays).map { it.reopened.size })
    }

    @Test
    fun `a folder stays while a document elsewhere has no answer yet`() {
        val folders = setUp()
        doc("a/d.py").resolvedTo(a)
        doc("other/p.py")

        projectChangesInA(folders)

        assertEquals(listOf(a to FolderSent.ADDED), host.sent)
    }

    @Test
    fun `a removed folder reopens the documents below it that it served, not those of an inner folder`() {
        val folders = setUp()
        val inner = dir("a/c")
        announce(folders, inner)
        answers["z.py"] = inner
        val moved = doc("a/d.py").resolvedTo(a)
        val below = doc("a/y.py").resolvedTo(null)
        val innerDoc = doc("a/c/z.py").resolvedTo(inner)

        projectChangesInA(folders)

        assertEquals(listOf(root, inner), folders.current)
        assertEquals(listOf(1, 1, 0), listOf(moved, below, innerDoc).map { it.reopened.size })
    }

    @Test
    fun `a document whose answer was adopted with its gate flip is not opened again, its folder still goes`() {
        val folders = setUp()
        val d = doc("a/d.py").resolvedTo(a).apply { adoptResult = true }

        projectChangesInA(folders)

        assertEquals(1, d.adopted)
        assertEquals(0, d.reopened.size)
        assertEquals(listOf(root), folders.current)
    }

    @Test
    fun `a document whose answer is the same is left alone`() {
        val folders = setUp()
        answers["d.py"] = a
        val d = doc("a/d.py").resolvedTo(a)

        projectChangesInA(folders)

        assertEquals(0, d.adopted)
        assertEquals(0, d.reopened.size)
        assertEquals(listOf(a to FolderSent.ADDED), host.sent)
        assertEquals(1, host.changed, "the host is told after every re-resolution")
    }

    @Test
    fun `an answer for a document that closed or was asked again meanwhile is dropped`() {
        val worker = ManualWorker()
        val folders = setUp(worker)
        worker.runAll()
        val closed = doc("a/closed.py").resolvedTo(a)
        val again = doc("a/again.py").resolvedTo(a)
        projectChangesInA(folders)

        host.docs.remove(closed)
        again.resolvedTo(a)
        worker.runAll()

        assertEquals(listOf(0, 0), listOf(closed, again).map { it.adopted })
        assertEquals(listOf(a to FolderSent.ADDED), host.sent)
        assertEquals(listOf(root, a), folders.current)
    }

    @Test
    fun `a folder whose watched folders changed is refreshed, with the documents it serves or may serve reopened`() {
        val folders = setUp()
        val serves = doc("a/d.py").resolvedTo(a)
        answers["d.py"] = a
        val pending = doc("other/p.py")
        val window = doc("w.py").resolvedTo(null)
        val newSite = dir("a/.venv/lib")
        watchedBy[a] = listOf(newSite)

        projectChangesInA(folders)

        assertEquals(
            listOf(a to FolderSent.ADDED, a to FolderSent.REMOVED, a to FolderSent.ADDED),
            host.sent,
        )
        assertEquals(listOf(1, 1, 0), listOf(serves, pending, window).map { it.reopened.size })
        assertEquals(1, host.changed)
        assertEquals(newSite, watcher.listeners.keys.single { it != root && it.startsWith(a) })

        // The same answer again changes nothing.
        projectChangesInA(folders)

        assertEquals(3, host.sent.size)
    }

    @Test
    fun `a folder that goes is not refreshed on the way even if its watched folders changed`() {
        val folders = setUp()
        doc("a/d.py").resolvedTo(a)
        watchedBy[a] = listOf(dir("a/.venv/lib"))

        projectChangesInA(folders)

        assertEquals(listOf(a to FolderSent.ADDED, a to FolderSent.REMOVED), host.sent)
    }

    @Test
    fun `a folder whose watches are not registered yet is not refreshed for them`() {
        val worker = ManualWorker()
        val folders = setUp(worker)
        worker.tasks.clear()
        doc("a/d.py").resolvedTo(a)
        answers["d.py"] = a
        watchedBy[a] = listOf(dir("a/.venv/lib"))

        projectChangesInA(folders)
        worker.runAll()

        assertEquals(listOf(a to FolderSent.ADDED), host.sent)
    }

    @Test
    fun `refreshing a known folder removes and adds it, in roots-only mode in one notification`() {
        val plain = setUp()
        val d = doc("a/d.py").resolvedTo(a)

        plain.refresh(a)
        plain.refresh(dir("unknown"))

        assertEquals(listOf(a to FolderSent.ADDED, a to FolderSent.REMOVED, a to FolderSent.ADDED), host.sent)
        assertEquals(1, d.reopened.size)
        assertEquals(1, host.changed)
    }

    @Test
    fun `refreshing a folder in roots-only mode sends one notification`() {
        val folders = setUp(mode = WorkspaceFolderMode.ROOTS_ONLY)

        folders.refresh(a)

        assertEquals(listOf(a to FolderSent.ADDED, a to FolderSent.REFRESHED), host.sent)
    }

    @Test
    fun `a folder removed while no server runs is forgotten and nothing is sent`() {
        val folders = setUp()
        host.running = null
        doc("a/d.py").resolvedTo(a)

        projectChangesInA(folders)

        assertEquals(listOf(root), folders.current)
        assertEquals(listOf(a to FolderSent.ADDED), host.sent)
    }

    @Test
    fun `a folder nobody sent is removed without a notification`() {
        val folders = setUp()
        val unsent = dir("b")
        doc("b/d.py").resolvedTo(unsent)

        folders.filesChanged(listOf(change("b/pyproject.toml")))
        runDue()

        assertEquals(listOf(a to FolderSent.ADDED), host.sent)
        assertEquals(listOf(root, a), folders.current)
    }

    @Test
    fun `a watch registered before the removal is decided on goes with it, a refresh after it sends nothing`() {
        val worker = ManualWorker()
        val folders = setUp(worker)
        worker.runAll()
        val listener = watcher.listeners.getValue(site)
        doc("a/d.py").resolvedTo(a)
        host.defer = true

        projectChangesInA(folders)
        worker.runAll()
        assertEquals(emptyMap(), watcher.listeners, "the removal ended the watches at once")
        // A report of the old watch asks for a refresh while the removal waits on the outbound thread.
        listener(listOf(site.resolve("x")))
        runDue()
        worker.runAll()
        assertEquals(setOf(site), watcher.listeners.keys, "the worker watched the still known folder again")
        host.runQueued()

        assertEquals(emptyMap(), watcher.listeners, "the decided removal ended it for good")
        assertEquals(listOf(a to FolderSent.ADDED, a to FolderSent.REMOVED), host.sent)
        assertEquals(listOf(root), folders.current)
    }

    @Test
    fun `a folder that also holds the documents' new answers is refreshed once, one only a document left too`() {
        val folders = setUp()
        val b = dir("b")
        val bSite = dir("b/.venv/site")
        watchedBy[b] = listOf(bSite)
        announce(folders, b)
        answers["m.py"] = b
        val mover = doc("a/m.py").resolvedTo(a)
        doc("b/x.py").resolvedTo(b)
        doc("b/y.py").resolvedTo(b)
        val keeper = doc("elsewhere/k.py").resolvedTo(a)
        watchedBy[a] = listOf(dir("a/.venv/lib"))
        watchedBy[b] = listOf(dir("b/.venv/lib"))

        projectChangesInA(folders)

        // a: left by the mover (before), still served by the keeper; b: entered by the mover (answer).
        val refreshes = host.sent.drop(2)
        assertEquals(
            listOf(a to FolderSent.REMOVED, a to FolderSent.ADDED, b to FolderSent.REMOVED, b to FolderSent.ADDED),
            refreshes,
        )
        assertEquals(1, mover.reopened.size)
        assertEquals(1, keeper.reopened.size, "the keeper is served by a refreshed folder")
    }

    @Test
    fun `the window folder is refreshed when what the spec watches for it changed and a document resolves to it`() {
        val folders = setUp()
        val d = doc("d.py").resolvedTo(null)
        watchedBy[root] = listOf(dir("lib"))

        folders.filesChanged(listOf(change("pyproject.toml")))
        runDue()

        assertEquals(
            listOf(a to FolderSent.ADDED, root to FolderSent.REMOVED, root to FolderSent.ADDED),
            host.sent,
        )
        assertEquals(1, d.reopened.size)
    }

    @ParameterizedTest(name = "window folder known: {0}")
    @ValueSource(booleans = [true, false])
    fun `a document that moves between no folder and the window folder itself has not moved`(started: Boolean) {
        a = dir("a")
        val folders = folders(start = started)
        answers["d.py"] = root
        val d = doc("d.py")

        folders.filesChanged(listOf(change("pyproject.toml")))
        runDue()

        assertEquals(1, d.adopted)
        assertEquals(0, d.reopened.size)
        assertEquals(emptyList(), host.sent)
    }

    @Test
    fun `a document that leaves the window folder for a folder is reopened, the window folder stays`() {
        val folders = setUp()
        answers["d.py"] = a
        val d = doc("d.py").resolvedTo(root)
        val other = doc("w.py").resolvedTo(null)

        folders.filesChanged(listOf(change("pyproject.toml")))
        runDue()

        assertEquals(1, d.reopened.size)
        assertEquals(0, other.reopened.size, "the window folder does not go, so nothing under it is reopened")
        assertEquals(listOf(root, a), folders.current)
        assertEquals(listOf(a to FolderSent.ADDED), host.sent)
    }

    @Test
    fun `a document without an answer yet that gets its folder is reopened and removes no folder`() {
        val folders = setUp()
        answers["d.py"] = a
        val d = doc("a/d.py")

        projectChangesInA(folders)

        assertEquals(1, d.reopened.size)
        assertEquals(listOf(root, a), folders.current)
        assertEquals(listOf(a to FolderSent.ADDED), host.sent)
    }

    @Test
    fun `a change at the file system root asks nothing`() {
        a = dir("a")
        val folders = folders(spec(affects = { true }))
        doc("a/d.py")

        folders.filesChanged(listOf(FileChange(Path.of("/"), FileChangeKind.CHANGED)))

        assertEquals(emptyList(), scheduler.due)
    }

    @Test
    fun `a watch is not registered for a folder that closed or was dropped before the worker ran`() {
        val worker = ManualWorker()
        val folders = setUp(worker)
        worker.runAll()
        watcher.watches.clear()
        val b = dir("b")
        watchedBy[b] = listOf(dir("b/site"))
        announce(folders, b)
        folders.foldersFor(dir("elsewhere"))
        worker.runAll()
        assertEquals(emptyList(), watcher.watches, "the folder is no longer known")
        assertEquals(emptyList(), watchedAsked.filter { it == b }, "the spec is not asked for it either")

        val c = dir("elsewhere/c")
        watchedBy[c] = listOf(dir("elsewhere/c/site"))
        announce(folders, c)
        folders.close()
        worker.runAll()

        assertEquals(emptyList(), watcher.watches, "the session closed")
        assertEquals(emptyList(), watchedAsked.filter { it == c }, "the spec is not asked for it either")

        val other = dir("other")
        folders.foldersFor(other)
        worker.runAll()

        assertEquals(emptyList(), watchedAsked.filter { it == other }, "nor for a window folder after the close")
    }

    @Test
    fun `a document still without an answer that is answered with no folder has not moved`() {
        val folders = setUp()
        val d = doc("a/d.py")

        projectChangesInA(folders)

        assertEquals(1, d.adopted)
        assertEquals(0, d.reopened.size)
        assertEquals(listOf(root, a), folders.current)
    }

    @Test
    fun `in roots-only mode the window folder is a folder like another, removed when no document resolves to it`() {
        val folders = setUp(mode = WorkspaceFolderMode.ROOTS_ONLY)
        announce(folders, root)
        val d = doc("d.py").resolvedTo(root)

        folders.filesChanged(listOf(change("pyproject.toml")))
        runDue()

        assertEquals(listOf(a to FolderSent.ADDED, root to FolderSent.ADDED, root to FolderSent.REMOVED), host.sent)
        assertEquals(listOf(a), folders.current)
        assertEquals(1, d.reopened.size)
    }

    @Test
    fun `a folder that goes is not refreshed while its removal waits on the outbound thread`() {
        val folders = setUp()
        doc("a/d.py").resolvedTo(a)
        watchedBy[a] = listOf(dir("a/.venv/lib"))
        host.defer = true
        watchedAsked.clear()

        projectChangesInA(folders)
        host.runQueued()

        assertEquals(1, watchedAsked.count { it == a }, "asked to compare, not again for a refresh")
        assertEquals(listOf(a to FolderSent.ADDED, a to FolderSent.REMOVED), host.sent)
    }

    @Test
    fun `a document answered for a window folder that changed since the server started has moved from the old one`() {
        val folders = setUp()
        val changed = dir("w2")
        window = changed
        answers["d.py"] = changed
        val d = doc("w2/d.py")

        folders.filesChanged(listOf(change("w2/pyproject.toml")))
        runDue()

        assertEquals(1, d.adopted)
        assertEquals(1, d.reopened.size)
    }

    @Test
    fun `in roots-only mode the window folder's watches are asked for once, also when a document names it at once`() {
        val worker = ManualWorker()
        val folders = folders(spec(mode = WorkspaceFolderMode.ROOTS_ONLY), worker)
        watchedBy[root] = listOf(dir("site"))
        announce(folders, root)

        worker.runAll()

        assertEquals(1, watchedAsked.count { it == root })
        assertEquals(listOf(dir("site")), watcher.watches)
    }

    @Test
    fun `an inner folder announced first does not pass for the outer one when a folder goes, in roots-only mode`() {
        a = dir("a")
        val folders = folders(spec(mode = WorkspaceFolderMode.ROOTS_ONLY))
        val inner = dir("a/c")
        announce(folders, inner)
        announce(folders, a)
        answers["z.py"] = inner
        val moved = doc("a/d.py").resolvedTo(a)
        val innerDoc = doc("a/c/z.py").resolvedTo(inner)

        projectChangesInA(folders)

        assertEquals(1, moved.reopened.size)
        assertEquals(0, innerDoc.reopened.size, "its innermost folder is a/c, which stays")
        assertEquals(listOf(inner), folders.current)
    }

    @Test
    fun `closing cancels the waits and the watches`() {
        val folders = setUp()
        folders.filesChanged(listOf(change("a/pyproject.toml")))
        doc("a/d.py")
        folders.filesChanged(listOf(change("a/pyproject.toml")))
        assertEquals(1, scheduler.due.size)

        folders.close()

        assertEquals(0, scheduler.due.size)
        assertEquals(emptyMap(), watcher.listeners)
    }

    @Test
    fun `a spec that fails on the folders to watch is logged and watches nothing`() {
        val failing = spec(watched = { throw IllegalStateException("boom") })
        val folders = folders(failing)

        announce(folders, dir("a"))

        assertEquals(emptyList(), watcher.watches)
        assertEquals(
            listOf(root, root.resolve("a")).map { "lsp test: no watched folders for $it" },
            logs.messages(Level.WARNING),
        )
    }

    @Test
    fun `the window folder the spec is asked with is absolute and normalized`() {
        val folders = folders()
        window = Path.of("rel/../x")

        folders.resolve(root.resolve("a.py")).get(1, TimeUnit.SECONDS)

        assertEquals(Path.of("x").toAbsolutePath(), folderAsked.single().second)
        dir("unused").createDirectories()
    }
}
