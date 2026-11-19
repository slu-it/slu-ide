package dev.sluide.languageservers.workspace

import dev.sluide.languageservers.DocumentGate
import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.FileChangeKind
import dev.sluide.languageservers.TestSpec
import dev.sluide.languageservers.WorkspaceFolderMode
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.logging.Level
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

internal class WorkspaceFoldersHostTest : WorkspaceFoldersHostBase() {
    // The gates' watches

    @Test
    fun `a change in a gate's folder goes to the host, an empty report only asks the gates again`() {
        val f = dir("f")
        val d = doc("d.py")
        val folders = folders(spec(gating = true))
        folders.gates.name(d, listOf(f))

        val created = f.resolve("a.py").also { it.writeText("") }

        watcher.report(f, created)
        assertEquals(listOf(listOf(FileChange(created, FileChangeKind.CREATED))), host.watchedFiles)
        watcher.report(f, f.resolve("sub/deep.py"))

        assertEquals(1, host.watchedFiles.size, "nothing changed in the second report")
        assertEquals(1, scheduler.due.size, "one wait for the folder")
        assertEquals(emptyList(), gateAsked)
        runDue()
        assertEquals(listOf(d.file), gateAsked)
        assertEquals(1, d.resolution.getNow(null)?.let { 1 } ?: 0, "the document took the new answer")
    }

    @Test
    fun `the gates of documents that no longer name a folder are not asked when its wait is over`() {
        val f = dir("f")
        val d = doc("d.py")
        val folders = folders(spec(gating = true))
        folders.gates.name(d, listOf(f))
        watcher.report(f, f.resolve("a.py"))

        folders.gates.forget(d)
        runDue()

        assertEquals(emptyList(), gateAsked)
        assertEquals(0, host.changed, "nothing was asked, nothing to apply")
    }

    @Test
    fun `without a window folder nothing is asked again`() {
        val f = dir("f")
        val d = doc("d.py")
        val folders = folders(spec(gating = true))
        folders.gates.name(d, listOf(f))
        watcher.report(f, f.resolve("a.py"))

        window = null
        runDue()

        assertEquals(emptyList(), gateAsked)
        assertEquals(0, host.changed)
        assertEquals(emptyList(), logs.messages(Level.WARNING))
    }

    // resolve

    @Test
    fun `a document is resolved by the spec with the window folder, normalized and absolute`() {
        answers["a.py"] = root.resolve("a/../a")
        val folders = folders()

        val resolution = folders.resolve(root.resolve("a/a.py")).get(1, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(Resolution(root.resolve("a"), DocumentGate.SERVED), resolution)
        assertEquals(listOf(root.resolve("a/a.py") to root), folderAsked)
    }

    @Test
    fun `a folder outside the window folder, or none, resolves to the window folder`() {
        answers["out.py"] = root.parent
        answers["none.py"] = null
        val folders = folders()

        assertNull(folders.resolve(root.resolve("out.py")).get(1, java.util.concurrent.TimeUnit.SECONDS).folder)
        assertNull(folders.resolve(root.resolve("none.py")).get(1, java.util.concurrent.TimeUnit.SECONDS).folder)
    }

    @Test
    fun `a spec that fails is logged and answers the window folder`() {
        val folders =
            folders(spec(folders = { _, _ -> throw IllegalStateException("no answer") }))

        val resolution = folders.resolve(root.resolve("x.py")).get(1, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(Resolution(null, DocumentGate.SERVED), resolution)
        assertEquals(listOf("lsp test: no workspace folder for ${root.resolve("x.py")}"), logs.messages(Level.WARNING))
    }

    @Test
    fun `a gate that fails is logged and the document is not served`() {
        val failing =
            TestSpec(gatesDocuments = true, gates = { _, _ -> throw IllegalStateException("no gate") })
        val folders = folders(failing)

        val resolution = folders.resolve(root.resolve("x.py")).get(1, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(Resolution.NOT_SERVED, resolution.gate)
        assertEquals(
            listOf("lsp test: no gate answer for ${root.resolve("x.py")}; not served"),
            logs.messages(Level.WARNING),
        )
    }

    @Test
    fun `a gate's watched folders are normalized and listed once`() {
        val gating =
            TestSpec(
                gatesDocuments = true,
                gates = { _, _ ->
                    val watched = listOf(root.resolve("n/./m"), root.resolve("n/x/../m"), root.resolve("o"))
                    DocumentGate(true, watched = watched)
                },
            )
        val folders = folders(gating)

        val gate = folders.resolve(root.resolve("x.py")).get(1, java.util.concurrent.TimeUnit.SECONDS).gate

        assertEquals(listOf(root.resolve("n/m"), root.resolve("o")), gate.watched)
    }

    @Test
    fun `without a window folder a document is answered at once with the window folder, not served if gated`() {
        window = null
        val plain = folders(start = false)
        window = null
        val gated = folders(spec(gating = true), start = false)
        window = null

        answers["x.py"] = root.resolve("a")
        assertEquals(Resolution(null, DocumentGate.SERVED), plain.resolve(root.resolve("x.py")).getNow(null))
        assertEquals(emptyList(), folderAsked, "the spec is not asked without a window folder")
        assertEquals(emptyList(), logs.messages(Level.WARNING), "and nothing fails for want of one")
        assertEquals(Resolution(null, Resolution.NOT_SERVED), gated.resolve(root.resolve("x.py")).getNow(null))
    }

    @Test
    fun `a worker that refuses the task answers the window folder at once`() {
        val refusing = Executor { throw RejectedExecutionException("shut down") }
        val folders = folders(worker = refusing)

        assertEquals(Resolution(null, DocumentGate.SERVED), folders.resolve(root.resolve("x.py")).getNow(null))
    }

    @Test
    fun `the window folder is watched by what the spec names, once, unless it stays the same`() {
        val venv = dir("venv-site")
        watchedBy[root] = listOf(venv)
        val folders = folders()
        assertEquals(listOf(venv), watcher.watches)

        folders.foldersFor(root)
        assertEquals(listOf(venv), watcher.watches, "the same window folder is not set up again")
        assertEquals(listOf(root), folders.current)

        val other = dir("other")
        folders.foldersFor(other)

        assertEquals(listOf(venv), watcher.closes, "the watches of the old window folder end")
        assertEquals(listOf(other), folders.current)
    }

    @Test
    fun `in roots-only mode no folder is known or watched before a document names one`() {
        watchedBy[root] = listOf(dir("venv-site"))
        val folders = folders(spec(mode = WorkspaceFolderMode.ROOTS_ONLY))

        assertEquals(emptyList(), folders.foldersFor(root))
        assertEquals(emptyList(), folders.current)
        assertEquals(emptyList(), watcher.watches)
    }

    // announce

    @Test
    fun `an announced folder is added once, inside the window folder, until the session closes`() {
        val a = dir("a")
        val folders = folders()
        announce(folders, a)
        announce(folders, a)
        announce(folders, root.parent)
        folders.close()
        announce(folders, dir("b"))

        assertEquals(listOf(a to FolderSent.ADDED), host.sent)
        assertEquals(emptyList(), folders.current)
    }

    @Test
    fun `a folder announced before the window folder is known is not added`() {
        val folders = folders(start = false)

        announce(folders, dir("a"))

        assertEquals(emptyList(), host.sent)
    }

    @Test
    fun `an answer that is late is logged, leaves the document to the window folder and reopens it when it comes`() {
        val a = dir("a")
        val folders = folders(resolveWait = 30.milliseconds)
        val d = doc("a/d.py")
        val pending = d.resolution

        folders.announce(pending, running())
        assertEquals(emptyList(), host.sent)
        assertEquals(listOf("lsp test: no workspace folder after 30ms"), logs.messages(Level.WARNING))
        folders.announce(pending, running())
        assertEquals(2, logs.messages(Level.WARNING).size, "logged at each wait, handled once")

        pending.complete(Resolution(a, DocumentGate.SERVED))

        assertEquals(1, d.reopened.size)
        assertEquals(1, host.changed)
    }

    @Test
    fun `a late answer that is the window folder or none reopens nothing`() {
        val folders = folders(resolveWait = 20.milliseconds)
        val none = doc("none.py")
        val window = doc("window.py")

        folders.announce(none.resolution, running())
        folders.announce(window.resolution, running())
        none.resolution.complete(Resolution(null, DocumentGate.SERVED))
        window.resolution.complete(Resolution(root, DocumentGate.SERVED))

        assertEquals(0, none.reopened.size + window.reopened.size)
        assertEquals(0, host.changed)
    }

    @Test
    fun `an answer that failed adds nothing and is not logged as late`() {
        val folders = folders()
        val failed = CompletableFuture<Resolution>().apply { completeExceptionally(IllegalStateException("x")) }

        folders.announce(failed, running())

        assertEquals(emptyList(), host.sent)
        assertEquals(emptyList(), logs.messages(Level.WARNING))
    }

    @Test
    fun `an interrupted wait adds nothing and keeps the interrupt`() {
        val folders = folders()
        Thread.currentThread().interrupt()

        try {
            folders.announce(CompletableFuture(), running())

            assertTrue(Thread.currentThread().isInterrupted, "the interrupt is kept for the caller")
        } finally {
            Thread.interrupted()
        }
        assertEquals(emptyList(), host.sent)
    }

    @Test
    fun `a wait after the folder wait only runs while the session is open`() {
        val folders = folders()
        val ran = mutableListOf<String>()
        folders.afterWait { ran += "open" }
        folders.afterWait { ran += "closed" }

        runDue()
        assertEquals(listOf("open", "closed"), ran)
        folders.afterWait { ran += "after close" }
        folders.close()
        runDue()

        assertEquals(listOf("open", "closed"), ran)
    }

    // changes on disk

    @Test
    fun `a change the spec names asks the documents below it again after the wait, others do not`() {
        val a = dir("a")
        val d = doc("a/d.py")
        val folders = folders()

        folders.filesChanged(listOf(change("a/notes.txt")))
        assertEquals(emptyList(), scheduler.due)
        folders.filesChanged(listOf(change("elsewhere/pyproject.toml")))
        assertEquals(emptyList(), scheduler.due, "no document below it")
        folders.filesChanged(listOf(change("a/pyproject.toml"), change("a/uv.lock")))
        assertEquals(1, scheduler.due.size, "one wait per folder")
        runDue()

        assertEquals(listOf(d.file to root), folderAsked)
        assertEquals(1, host.changed)
        assertTrue(a.toFile().exists())
    }

    @Test
    fun `nothing is asked of the spec once the session closed`() {
        doc("a/d.py")
        val folders = folders()
        folders.close()

        folders.filesChanged(listOf(change("a/pyproject.toml")))

        assertEquals(emptyList(), affectsAsked)
    }

    @Test
    fun `a spec that fails on a change is logged and the change counts as not named`() {
        doc("a/d.py")
        val folders = folders(spec(affects = { throw IllegalStateException("boom") }))

        folders.filesChanged(listOf(change("a/pyproject.toml")))

        assertEquals(emptyList(), scheduler.due)
        assertEquals(
            listOf("lsp test: affectsWorkspaceFolders failed for ${root.resolve("a/pyproject.toml")}"),
            logs.messages(Level.WARNING),
        )
    }
}
