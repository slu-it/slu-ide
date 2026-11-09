package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import dev.sluide.languageservers.files.DirectoryWatcher
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.RelativePattern
import org.eclipse.lsp4j.WatchKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** Workspace file events: which servers hear about changes on disk, and when the project is watched. */
class LanguageServerFilesTest {
    @TempDir
    lateinit var folder: Path

    /** The paths the first spec was asked whether they affect workspace folders. */
    private val askedAffects = CopyOnWriteArrayList<Path>()
    private val first =
        TestSpec(
            id = "first",
            affects = { change ->
                askedAffects.add(change)
                false
            },
        )
    private val second = TestSpec(id = "second")
    private val starter = FakeStarter()
    private val scheduler = FakeScheduler()
    private val listeners = ConcurrentHashMap<Path, (List<Path>) -> Unit>()
    private var servers: LanguageServers? = null

    @AfterEach
    fun closeServers() {
        servers?.close()
    }

    private fun servers(): LanguageServers =
        LanguageServers(
            listOf(first, second),
            { folder },
            { _, _ -> true },
            Runnable::run,
            starter,
            scheduler,
            ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
            System::nanoTime,
            DirectoryWatcher { directory, listener ->
                listeners[directory] = listener
                AutoCloseable { listeners.remove(directory, listener) }
            },
            Runnable::run,
        ).also { servers = it }

    /** Starts [spec]'s server and waits until its session announced it; the server. */
    private fun LanguageServers.running(spec: LanguageServerSpec): FakeServer {
        val session = session(spec)
        val connected = AtomicBoolean()
        val subscription = session.onConnected { connected.set(true) }
        session.ensureStarted()
        awaitTrue("${spec.id} connected") { connected.get() }
        subscription.close()
        return starter.processes.last { it.name.startsWith(spec.id) }.server
    }

    private fun FakeServer.watch(
        pattern: String,
        kinds: Int? = null,
    ) {
        val watcher =
            FileSystemWatcher(Either.forRight(RelativePattern(Either.forRight(LspUris.of(folder)), pattern)), kinds)
        val registration =
            Registration(
                "w",
                "workspace/didChangeWatchedFiles",
                DidChangeWatchedFilesRegistrationOptions(listOf(watcher)),
            )
        client.registerCapability(RegistrationParams(listOf(registration))).get(5, TimeUnit.SECONDS)
    }

    /** Waits until [server] got everything [session] sent before. */
    private fun LanguageServerSession.flush(server: FakeServer) {
        val count = server.calls.count { it == "didChangeConfiguration" }
        notify { it.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(emptyMap<String, Any>())) }
        awaitTrue("flushed") { server.calls.count { it == "didChangeConfiguration" } > count }
    }

    private fun FakeServer.events() = watchedFileChanges.flatMap { params -> params.changes.map { it.uri to it.type } }

    @Test
    fun `changes reach the running servers whose watchers want them`() {
        val servers = servers()
        val server = servers.running(first)
        server.watch("**/*.py", WatchKind.Create or WatchKind.Delete)
        val created = folder.resolve("new.py")
        val deleted = folder.resolve("old.py")

        servers.filesChanged(
            listOf(
                FileChange(created, FileChangeKind.CREATED),
                FileChange(folder.resolve("a.py"), FileChangeKind.CHANGED),
                FileChange(folder.resolve("notes.txt"), FileChangeKind.CREATED),
                FileChange(deleted, FileChangeKind.DELETED),
            ),
        )
        servers.filesChanged(listOf(FileChange(folder.resolve("notes.txt"), FileChangeKind.DELETED)))
        servers.session(first).flush(server)

        assertEquals(
            listOf(LspUris.of(created) to FileChangeType.Created, LspUris.of(deleted) to FileChangeType.Deleted),
            server.events(),
        )
        assertEquals(1, server.watchedFileChanges.size, "nothing sent when no watcher wants a change")
    }

    @Test
    fun `a change on disk in the project reaches the server through the project watch`() {
        val servers = servers()
        val server = servers.running(first)
        server.watch("**")
        val file = folder.resolve("main.py").apply { writeText("x = 1") }

        assertNotNull(listeners[folder], "the project folder is watched")(listOf(file))
        servers.session(first).flush(server)

        assertEquals(listOf(LspUris.of(file) to FileChangeType.Created), server.events())
    }

    @Test
    fun `every change of the project watch reaches the specs' workspace folders, also one no server watcher wants`() {
        val servers = servers()
        val server = servers.running(first)
        val file = folder.resolve("uv.lock").apply { writeText("version = 1") }
        val venv = folder.resolve(".venv").createDirectories()

        assertNotNull(listeners[folder], "the project folder is watched")(listOf(file, venv))
        servers.session(first).flush(server)

        assertEquals(setOf(file, venv), askedAffects.toSet())
        assertEquals(emptyList(), server.watchedFileChanges, "the server registered no watchers")
    }

    @Test
    fun `the project is watched from the first server's start until the last one stops`() {
        val servers = servers()
        assertNull(servers.currentProjectWatch)

        servers.running(first)
        val watch = assertNotNull(servers.currentProjectWatch)
        servers.running(second)
        assertEquals(watch, servers.currentProjectWatch, "one watch for every server")

        fail(servers, first)
        assertEquals(watch, servers.currentProjectWatch, "kept while the second server runs")
        assertEquals(setOf(folder), listeners.keys)

        fail(servers, second)
        assertNull(servers.currentProjectWatch)
        assertEquals(emptySet(), listeners.keys)
    }

    /** Crashes [spec]'s server until no restart is left. */
    private fun fail(
        servers: LanguageServers,
        spec: LanguageServerSpec,
    ) {
        repeat(4) { crash ->
            starter.processes.last { it.name.startsWith(spec.id) }.crash()
            if (crash < 3) {
                awaitTrue("${spec.id} restarting") { servers.state(spec).value == ServerState.Restarting(crash + 1) }
                scheduler.runNext()
                awaitTrue("${spec.id} running again") { servers.state(spec).value == ServerState.Running }
            }
        }
        awaitTrue("${spec.id} failed") { servers.state(spec).value is ServerState.Failed }
    }
}
