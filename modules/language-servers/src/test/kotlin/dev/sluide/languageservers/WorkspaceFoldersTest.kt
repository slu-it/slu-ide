package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import dev.sluide.languageservers.files.FakeDirectoryWatcher
import dev.sluide.languageservers.workspace.Resolution
import javafx.beans.InvalidationListener
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Several workspace folders per session (ADR 0151) over fake servers: a folder added before the first `didOpen` it
 * serves, kept across restarts, refreshed when a folder watched for it changes or its venv comes or goes, documents
 * re-resolved when the project changes, folders no document resolves to any more removed. A single-thread executor
 * plays the FX thread. The test spec's project roots are the folders with a `uv.lock`, the window folder included, and
 * it watches `.venv/site` in those that have a `.venv`.
 */
class WorkspaceFoldersTest {
    @TempDir
    lateinit var folder: Path

    @Volatile
    private var window: Path? = null

    private val fx = Executors.newSingleThreadExecutor { Thread(it, "test-fx").apply { isDaemon = true } }
    private val scheduler = FakeScheduler()
    private val watcher = FakeDirectoryWatcher()
    private val connects = AtomicInteger()
    private val fired = AtomicInteger()
    private val askedOn = CopyOnWriteArrayList<String>()
    private val watchedAsked = CopyOnWriteArrayList<Path>()

    /** Holds the spec's answer for `slow.py`. */
    private val slow = CountDownLatch(1)
    private var servers: LanguageServers? = null
    private lateinit var starter: FakeStarter

    private val spec =
        TestSpec(
            folders = { file, projectFolder ->
                askedOn.add(Thread.currentThread().name)
                when (file.fileName.toString()) {
                    "broken.py" -> error("no answer")
                    "slow.py" -> slow.await(5, TimeUnit.SECONDS)
                }
                generateSequence(file.parent) { it.parent }
                    .takeWhile { it.startsWith(projectFolder) }
                    .firstOrNull { Files.exists(it.resolve("uv.lock")) }
            },
            watched = { workspaceFolder ->
                watchedAsked.add(workspaceFolder)
                val venv = workspaceFolder.resolve(".venv")
                if (Files.isDirectory(venv)) listOf(venv.resolve("site")) else emptyList()
            },
            // Broad, as a real spec may be: anything but notes asks the documents' folders again.
            affects = { it.fileName.toString() != "notes.txt" },
        )

    private val gateAsked = CopyOnWriteArrayList<Path>()

    /**
     * Gates documents (ADR 0160): a file is not served while an `old` marker lies in its folder or in its folder's
     * `node_modules`, which its answer watches. `broken.py` fails, `slow.py` waits for [slow]. It watches `site` in
     * every workspace folder.
     */
    private val gating =
        TestSpec(
            gatesDocuments = true,
            folders = { file, _ ->
                if (file.fileName.toString() == "slow.py") slow.await(5, TimeUnit.SECONDS)
                null
            },
            gates = { file, _ ->
                gateAsked.add(file)
                if (file.fileName.toString() == "broken.py") error("no answer")
                val modules = file.parent.resolve("node_modules")
                val old = Files.exists(file.parent.resolve("old")) || Files.exists(modules.resolve("old"))
                DocumentGate(!old, watched = listOf(modules))
            },
            watched = { listOf(it.resolve("site")) },
            affects = { it.fileName.toString() != "notes.txt" },
        )

    @AfterEach
    fun closeServers() {
        slow.countDown()
        servers?.close()
        fx.shutdownNow()
    }

    private fun session(
        spec: TestSpec = this.spec,
        folderWorker: Executor = Executor(Runnable::run),
        timing: ServerTiming = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
        beforeStart: (Int) -> Unit = {},
    ): LanguageServerSession {
        window = folder
        starter = FakeStarter(beforeStart = beforeStart)
        val created =
            LanguageServers(
                listOf(spec),
                { window },
                { _, _ -> true },
                fx::execute,
                starter,
                scheduler,
                timing,
                System::nanoTime,
                directoryWatcher = watcher,
                folderWorker = { folderWorker },
            )
        servers = created
        return created.session(spec).also { session ->
            session.onConnected { connects.incrementAndGet() }
            onFx { session.workspaceChanges.addListener(InvalidationListener { fired.incrementAndGet() }) }
        }
    }

    private fun <T> onFx(action: () -> T): T = fx.submit(action).get(5, TimeUnit.SECONDS)

    /** Starts the server, or restarts it with [restart], and waits until the session announced it; the server. */
    private fun LanguageServerSession.running(restart: Boolean = false): FakeServer {
        val before = connects.get()
        onFx { if (restart) restart() else ensureStarted() }
        awaitTrue("connected") { connects.get() > before }
        return starter.last.server
    }

    /** Waits until [server] got everything sent before, also what the FX thread queued until now. */
    private fun LanguageServerSession.flush(server: FakeServer) {
        val count = server.calls.count { it == "didChangeConfiguration" }
        onFx {
            notify {
                it.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(emptyMap<String, Any>()))
            }
        }
        awaitTrue("flushed") { server.calls.count { it == "didChangeConfiguration" } > count }
    }

    private fun LanguageServerSession.open(
        name: String,
        text: String,
    ): SyncedDocument = onFx { documents.open(folder.resolve(name), "python", text) }

    private fun LanguageServerSession.folderOf(name: String): Path? =
        onFx { workspaceFolderOf(folder.resolve(name)).value }

    /** Reports [changes] in the project and runs the re-resolution they ask for, once its wait is over. */
    private fun changed(vararg changes: FileChange) {
        onFx { servers!!.filesChanged(changes.toList()) }
        runDebounced()
    }

    /** Runs the debounced work that is due, then what it queued on the FX thread. */
    private fun runDebounced() = runDebounced(scheduler.due.removeAt(0).second)

    /**
     * Runs [task], then what it queued on the FX thread: a re-resolution asks on the (inline) worker in one FX step and
     * applies the answers in the next.
     */
    private fun runDebounced(task: Runnable) {
        task.run()
        onFx {}
        onFx {}
    }

    /** Makes [dir] a project: a `uv.lock`, and a `.venv` if [venv]. */
    private fun project(
        dir: Path,
        venv: Boolean = true,
    ): Path =
        dir.createDirectories().also {
            it.resolve("uv.lock").writeText("")
            if (venv) it.resolve(".venv").createDirectories()
        }

    private fun project(
        name: String,
        venv: Boolean = true,
    ): Path = project(folder.resolve(name), venv)

    /** The workspace folder changes and document opens and closes [this] got, in order, from [from] on. */
    private fun FakeServer.sync(from: Int = 0) =
        calls.drop(from).filter {
            it.startsWith("didChangeWorkspaceFolders") || it.startsWith("didOpen") ||
                it == "didClose"
        }

    private fun added(path: Path) = "didChangeWorkspaceFolders +${LspUris.of(path)}"

    private fun removed(path: Path) = "didChangeWorkspaceFolders -${LspUris.of(path)}"

    private fun FakeServer.initializedFolders() = initializeParams?.workspaceFolders?.map { it.uri }

    private fun created(path: Path) = FileChange(path, FileChangeKind.CREATED)

    private fun deleted(path: Path) = FileChange(path, FileChangeKind.DELETED)

    @Test
    fun `a document's folder is asked off the FX thread and added before the first didOpen it serves, once`() {
        val backend = project("backend")
        val folders = Executors.newSingleThreadExecutor { Thread(it, "test-folders").apply { isDaemon = true } }
        val session = session(folderWorker = folders::execute)
        val server = session.running()

        session.open("backend/x.py", "x")
        session.open("backend/y.py", "y")
        session.open("native.py", "native")
        session.flush(server)

        assertEquals(listOf(added(backend), "didOpen x", "didOpen y", "didOpen native"), server.sync())
        assertEquals(listOf("test-folders"), askedOn.distinct())
        awaitTrue("folder known on the FX thread") { session.folderOf("backend/x.py") == backend }
        assertNull(session.folderOf("native.py"))
        assertNull(session.folderOf("closed.py"))
        assertTrue(fired.get() > 0)
        folders.shutdownNow()
    }

    @Test
    fun `a spec that fails or answers too late leaves the document to the window folder until its answer comes`() {
        val backend = project("backend")
        val folders = Executors.newSingleThreadExecutor { Thread(it, "test-folders").apply { isDaemon = true } }
        val timing =
            ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds, workspaceFolderWait = 100.milliseconds)
        val session = session(folderWorker = folders::execute, timing = timing)
        val server = session.running()

        session.open("broken.py", "broken")
        session.open("backend/slow.py", "slow")
        session.flush(server)
        assertEquals(listOf("didOpen broken", "didOpen slow"), server.sync())
        assertNull(session.folderOf("backend/slow.py"))
        val before = server.calls.size

        slow.countDown()
        awaitTrue("re-opened under its folder") { server.sync(before).size == 3 }

        assertEquals(listOf("didClose", added(backend), "didOpen slow"), server.sync(before))
        assertEquals(backend, session.folderOf("backend/slow.py"))
        assertNull(session.folderOf("broken.py"))
        folders.shutdownNow()
    }

    @Test
    fun `a spec that keeps the defaults has only the window folder and watches nothing`() {
        project("backend")
        val session = session(spec = TestSpec())
        val server = session.running()

        session.open("backend/x.py", "x")
        session.flush(server)

        assertEquals(listOf(LspUris.of(folder)), server.initializedFolders())
        assertEquals(listOf("didOpen x"), server.sync())
        assertEquals(emptyList(), watcher.watches)
        assertNull(session.folderOf("backend/x.py"))
    }

    @Test
    fun `a document opened while the server starts adds its folder after initialize, a restart keeps the folders`() {
        val backend = project("backend")
        val release = CountDownLatch(1)
        val session = session(beforeStart = { if (it == 0) release.await(5, TimeUnit.SECONDS) })
        onFx { session.ensureStarted() }
        awaitTrue("starting") { onFx { session.state.value } == ServerState.Starting }
        session.open("backend/x.py", "x")
        release.countDown()
        awaitTrue("connected") { connects.get() == 1 }
        val first = starter.last.server
        session.flush(first)

        val second = session.running(restart = true)
        session.flush(second)

        assertEquals(listOf(LspUris.of(folder)), first.initializedFolders())
        assertEquals(listOf(added(backend), "didOpen x"), first.sync())
        assertEquals(listOf(LspUris.of(folder), LspUris.of(backend)), second.initializedFolders())
        assertEquals(listOf("didOpen x"), second.sync())
    }

    @Test
    fun `a moved document is asked for its folder again and opened under it`() {
        project("backend")
        val other = project("other")
        val session = session()
        val server = session.running()
        val document = session.open("backend/x.py", "x")
        session.flush(server)
        val before = server.calls.size

        onFx { document.moved(other.resolve("x.py")) }
        session.flush(server)

        assertEquals(listOf("didClose", added(other), "didOpen x"), server.sync(before))
        assertEquals(other, session.folderOf("other/x.py"))
    }

    @Test
    fun `a burst of changes in a folder watched for a workspace folder refreshes it once, re-opening its documents`() {
        val backend = project("backend")
        val session = session()
        val server = session.running()
        session.open("backend/x.py", "x")
        session.open("native.py", "native")
        session.flush(server)
        val site = backend.resolve(".venv/site")
        awaitTrue("site watched") { site in watcher.listeners }
        val before = server.calls.size
        val firedBefore = fired.get()

        repeat(3) { watcher.report(site, site.resolve("django")) }
        assertEquals(listOf(1.seconds), scheduler.due.map { it.first })
        runDebounced()
        session.flush(server)

        assertEquals(listOf(removed(backend), added(backend), "didClose", "didOpen x"), server.sync(before))
        assertEquals(listOf(backend, backend), watchedAsked.filter { it == backend }, "asked again what to watch")
        assertTrue(fired.get() > firedBefore)
    }

    @Test
    fun `a uv lock that appears re-opens the documents below it in their new folder, added first`() {
        val session = session()
        val server = session.running()
        session.open("backend/x.py", "x")
        session.open("native.py", "native")
        session.flush(server)
        val backend = project("backend", venv = false)
        val before = server.calls.size
        val firedBefore = fired.get()

        onFx {
            servers!!.filesChanged(
                listOf(
                    created(backend.resolve("uv.lock")),
                    // Not named by the spec, and no document below the other: neither waits.
                    created(folder.resolve("notes.txt")),
                    created(folder.resolve("other/uv.lock")),
                ),
            )
        }
        assertEquals(1, scheduler.due.size)
        runDebounced()
        session.flush(server)

        assertEquals(listOf("didClose", added(backend), "didOpen x"), server.sync(before))
        assertEquals(backend, session.folderOf("backend/x.py"))
        assertTrue(fired.get() > firedBefore)
    }

    @Test
    fun `a venv that appears in a document's folder refreshes that folder and is watched`() {
        val backend = project("backend", venv = false)
        val session = session()
        val server = session.running()
        session.open("backend/x.py", "x")
        session.flush(server)
        val before = server.calls.size

        backend.resolve(".venv").createDirectories()
        changed(created(backend.resolve(".venv")))
        session.flush(server)

        assertEquals(listOf(removed(backend), added(backend), "didClose", "didOpen x"), server.sync(before))
        assertTrue(backend.resolve(".venv/site") in watcher.listeners)
    }

    @Test
    fun `a uv lock alone at the window root makes no other folder of it and sends nothing`() {
        val session = session()
        val server = session.running()
        session.open("native.py", "native")
        session.flush(server)
        val before = server.calls.size

        folder.resolve("uv.lock").writeText("")
        changed(created(folder.resolve("uv.lock")))
        session.flush(server)

        assertEquals(emptyList(), server.sync(before))
        assertEquals(folder, session.folderOf("native.py"), "the spec's answer, the window folder itself")
    }

    @Test
    fun `a change that leaves the folders and what they watch as they were sends nothing`() {
        val backend = project("backend")
        val session = session()
        val server = session.running()
        session.open("backend/x.py", "x")
        session.flush(server)
        val before = server.calls.size
        val asked = askedOn.size

        changed(created(backend.resolve("__pycache__")))
        session.flush(server)

        assertTrue(askedOn.size > asked, "asked again")
        assertEquals(emptyList(), server.sync(before))
    }

    @Test
    fun `a window folder that becomes a project or stops being one is refreshed and watched like any other`() {
        val session = session()
        val server = session.running()
        session.open("native.py", "native")
        session.flush(server)
        val site = folder.resolve(".venv/site")
        val first = server.calls.size

        project(folder)
        changed(created(folder.resolve("uv.lock")), created(folder.resolve(".venv")))
        session.flush(server)

        assertEquals(listOf(removed(folder), added(folder), "didClose", "didOpen native"), server.sync(first))
        assertEquals(folder, session.folderOf("native.py"))
        assertTrue(site in watcher.listeners)
        val second = server.calls.size

        folder.resolve("uv.lock").deleteExisting()
        folder.resolve(".venv").toFile().deleteRecursively()
        changed(deleted(folder.resolve("uv.lock")), deleted(folder.resolve(".venv")))
        session.flush(server)

        assertEquals(listOf(removed(folder), added(folder), "didClose", "didOpen native"), server.sync(second))
        assertNull(session.folderOf("native.py"))
        assertFalse(site in watcher.listeners)
    }

    @Test
    fun `a project no open document resolves to any more is removed, its documents re-opened in their new folder`() {
        val backend = project("backend")
        val other = project("other")
        val session = session()
        val server = session.running()
        session.open("backend/x.py", "x")
        // The spec cannot answer for it, so the window folder serves it; ty took it from backend all the same.
        session.open("backend/broken.py", "broken")
        onFx { session.documents.open(other.resolve("y.py"), "python", "y").close() }
        session.flush(server)
        val before = server.calls.size

        backend.resolve("uv.lock").deleteExisting()
        backend.resolve(".venv").toFile().deleteRecursively()
        changed(deleted(backend.resolve("uv.lock")), deleted(backend.resolve(".venv")))
        session.flush(server)

        assertEquals(
            listOf(removed(backend), "didClose", "didOpen x", "didClose", "didOpen broken"),
            server.sync(before),
        )
        assertNull(session.folderOf("backend/x.py"))
        assertEquals(listOf(folder, other), session.workspace.current, "a folder whose documents closed stays")
        assertFalse(backend.resolve(".venv/site") in watcher.listeners)
    }

    @Test
    fun `a project left while the server restarts is removed all the same, the next server does not get it`() {
        val backend = project("backend")
        val debounce = 2.seconds
        val session =
            session(
                timing =
                    ServerTiming(
                        shutdown = 200.milliseconds,
                        exit = 200.milliseconds,
                        workspaceDebounce = debounce,
                    ),
            )
        val first = session.running()
        session.open("backend/x.py", "x")
        session.flush(first)
        starter.last.crash()
        awaitTrue("restarting") { onFx { session.state.value } == ServerState.Restarting(1) }

        backend.resolve("uv.lock").deleteExisting()
        onFx { servers!!.filesChanged(listOf(deleted(backend.resolve("uv.lock")))) }
        val resolve = scheduler.due.single { it.first == debounce }
        scheduler.due.remove(resolve)
        runDebounced(resolve.second)
        val before = connects.get()
        scheduler.runNext()
        awaitTrue("restarted") { connects.get() > before }
        val second = starter.last.server
        session.flush(second)

        assertEquals(listOf(LspUris.of(folder)), second.initializedFolders())
        assertEquals(listOf("didOpen x"), second.sync())
        assertEquals(listOf(folder), session.workspace.current)
    }

    /** Registers a file watcher on [this] server that wants every file. */
    private fun FakeServer.watchEverything() {
        val options = DidChangeWatchedFilesRegistrationOptions(listOf(FileSystemWatcher(Either.forLeft("**/*"))))
        client
            .registerCapability(
                RegistrationParams(listOf(Registration("w", "workspace/didChangeWatchedFiles", options))),
            ).get(5, TimeUnit.SECONDS)
    }

    /** The file events [this] server got, in order: URI and type. */
    private fun FakeServer.fileEvents() = watchedFileChanges.flatMap { it.changes }.map { it.uri to it.type }

    /** Waits until the first server connected; it. */
    private fun connected(): FakeServer {
        awaitTrue("connected") { connects.get() == 1 }
        return starter.last.server
    }

    @Test
    fun `a gate answer that flips opens the document with its current text and next version, or closes it`() {
        val session = session(spec = gating)
        val app = folder.resolve("app").createDirectories()
        val marker = app.resolve("old").also { it.writeText("") }
        val document = session.open("app/x.py", "x")
        assertEquals(
            DocumentGate(false, listOf(app.resolve("node_modules"))),
            onFx { session.gateOf(app.resolve("x.py")) },
        )
        assertEquals(emptyList(), starter.processes, "no server for a document that is not served")
        val firedBefore = fired.get()

        marker.deleteExisting()
        changed(deleted(marker))
        val server = connected()
        session.flush(server)
        assertEquals(listOf("didOpen x"), server.sync())
        assertTrue(fired.get() > firedBefore, "the gate change is a workspace change")

        marker.writeText("")
        changed(created(marker))
        onFx { document.changed(DocumentChange(1, "", "y")) { "xy" } }
        // A refresh of the window folder re-opens only what is served.
        watcher.report(folder.resolve("site"), folder.resolve("site/pkg"))
        runDebounced()
        session.flush(server)
        assertEquals(listOf("didOpen x", "didClose", removed(folder), added(folder)), server.sync())

        marker.deleteExisting()
        changed(deleted(marker))
        session.flush(server)
        assertEquals(listOf("didOpen x", "didClose", removed(folder), added(folder), "didOpen xy"), server.sync())
        // The first didOpen went to the starting server and was dropped; the reopen on connect counts one more.
        assertEquals(
            listOf(2, 4),
            server.documentNotifications.filterIsInstance<DidOpenTextDocumentParams>().map { it.textDocument.version },
        )
        server.assertClosesFollowOpens()
    }

    @Test
    fun `a change in a folder a gate watches asks only the documents that named it again and reaches the server`() {
        val session = session(spec = gating)
        val a = folder.resolve("a/node_modules").createDirectories()
        val b = folder.resolve("b/node_modules")
        session.open("a/x.py", "x")
        val y = session.open("b/y.py", "y")
        val server = connected()
        session.flush(server)
        assertTrue(a in watcher.listeners && b in watcher.listeners, "the named folders are watched, also one missing")

        // No watcher of the server wants it yet: nothing is sent.
        a.resolve("other.txt").writeText("")
        watcher.report(a, a.resolve("other.txt"))
        session.flush(server)
        assertEquals(emptyList(), server.watchedFileChanges)
        server.watchEverything()
        gateAsked.clear()

        a.resolve("old").writeText("")
        watcher.report(a, a.resolve("old"), a.resolve("other.txt"))
        session.flush(server)
        val events = server.watchedFileChanges.single().changes
        assertEquals(
            listOf(
                LspUris.of(a.resolve("old")) to FileChangeType.Created,
                LspUris.of(a.resolve("other.txt")) to FileChangeType.Changed,
            ),
            events.map { it.uri to it.type },
        )
        assertEquals(listOf(1.seconds), scheduler.due.map { it.first }, "one wait for the burst")
        runDebounced()
        session.flush(server)

        assertEquals(listOf(folder.resolve("a/x.py")), gateAsked, "only the document that named the folder")
        assertEquals(listOf("didOpen x", "didOpen y", "didClose"), server.sync())
        assertEquals(DocumentGate(false, listOf(a)), onFx { session.gateOf(folder.resolve("a/x.py")) })

        onFx { y.close() }
        assertFalse(b in watcher.listeners, "no open document names it any more")
        servers!!.close()
        assertEquals(emptyMap(), watcher.listeners)
        server.assertClosesFollowOpens()
    }

    @Test
    fun `a named folder that appears is watched again after the wait, and its entries reach the server as created`() {
        val modules = folder.resolve("node_modules").createDirectories()
        val typescript = modules.resolve("typescript")
        val spec = TestSpec(gatesDocuments = true, gates = { _, _ -> DocumentGate(true, listOf(modules, typescript)) })
        val session = session(spec = spec)
        session.open("x.py", "x")
        val server = connected()
        server.watchEverything()
        assertEquals(1, watcher.watches.count { it == typescript }, "watched while missing")

        typescript.createDirectories().resolve("package.json").writeText("{}")
        watcher.report(modules, typescript)
        session.flush(server)
        assertEquals(listOf(LspUris.of(typescript) to FileChangeType.Created), server.fileEvents())
        runDebounced()
        session.flush(server)

        assertEquals(2, watcher.watches.count { it == typescript }, "watched again: its watch may have ended with it")
        assertEquals(
            listOf(
                LspUris.of(typescript) to FileChangeType.Created,
                LspUris.of(typescript.resolve("package.json")) to FileChangeType.Created,
            ),
            server.fileEvents(),
        )
    }

    @Test
    fun `a gating spec that fails or has not answered in time does not serve the document until its answer comes`() {
        val folders = Executors.newSingleThreadExecutor { Thread(it, "test-folders").apply { isDaemon = true } }
        val timing = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds)
        val session = session(spec = gating, folderWorker = folders::execute, timing = timing)
        session.open("x.py", "x")
        val server = connected()
        session.open("broken.py", "broken")
        session.open("slow.py", "slow")
        awaitTrue("broken answered") { onFx { session.gateOf(folder.resolve("broken.py")) } != null }
        assertEquals(Resolution.NOT_SERVED, onFx { session.gateOf(folder.resolve("broken.py")) })
        assertNull(onFx { session.gateOf(folder.resolve("slow.py")) }, "not answered yet")

        val wait = scheduler.due.single { it.first == timing.workspaceFolderWait }
        scheduler.due.remove(wait)
        runDebounced(wait.second)
        assertEquals(Resolution.NOT_SERVED, onFx { session.gateOf(folder.resolve("slow.py")) }, "fail closed")
        session.flush(server)
        assertEquals(listOf("didOpen x"), server.sync())

        slow.countDown()
        awaitTrue("the late answer") { onFx { session.gateOf(folder.resolve("slow.py")) }?.served == true }
        session.flush(server)
        assertEquals(listOf("didOpen x", "didOpen slow"), server.sync())
        server.assertClosesFollowOpens()
        folders.shutdownNow()
    }

    @Test
    fun `a document closed while its gate answer is pending is never opened or closed on the server`() {
        val folders = Executors.newSingleThreadExecutor { Thread(it, "test-folders").apply { isDaemon = true } }
        val session = session(spec = gating, folderWorker = folders::execute)
        session.open("x.py", "x")
        val server = connected()
        val pending = session.open("slow.py", "slow")

        onFx { pending.close() }
        slow.countDown()
        session.flush(server)
        // The answer arrived on the worker and was dropped on the FX thread: the worker is idle once it ran this.
        folders.submit {}.get(5, TimeUnit.SECONDS)
        onFx {}
        session.flush(server)

        assertEquals(listOf("didOpen x"), server.sync())
        server.assertClosesFollowOpens()
        folders.shutdownNow()
    }

    @Test
    fun `a new window folder starts over with only itself and ends the old watches, closing ends the rest`() {
        val backend = project("backend")
        val session = session()
        val first = session.running()
        session.open("backend/x.py", "x")
        session.flush(first)
        awaitTrue("site watched") { backend.resolve(".venv/site") in watcher.listeners }
        val other = folder.resolve("other").createDirectories()
        val service = project("other/service")

        window = other
        val second = session.running(restart = true)
        session.open("other/service/y.py", "y")
        session.flush(second)

        assertEquals(listOf(LspUris.of(other)), second.initializedFolders())
        assertEquals(listOf("didOpen x", added(service), "didOpen y"), second.sync(), "the old folder is not added")
        assertEquals(setOf(service.resolve(".venv/site")), watcher.listeners.keys)

        servers!!.close()
        assertEquals(emptyMap(), watcher.listeners)
    }
}
