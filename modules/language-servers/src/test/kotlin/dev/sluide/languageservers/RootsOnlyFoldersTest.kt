package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import javafx.beans.InvalidationListener
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Workspace folders in [WorkspaceFolderMode.ROOTS_ONLY] (ADR 0179) over fake servers: no window folder, each root in a
 * notification of its own, again after every start, loose documents without a folder. A single-thread executor plays
 * the FX thread. The test spec's roots are the folders with a `build.gradle`, the window folder included.
 */
class RootsOnlyFoldersTest {
    @TempDir
    lateinit var folder: Path

    private val fx = Executors.newSingleThreadExecutor { Thread(it, "test-fx").apply { isDaemon = true } }
    private val scheduler = FakeScheduler()
    private val connects = AtomicInteger()
    private val starter = FakeStarter()
    private var servers: LanguageServers? = null

    /** Runs once on the session's outbound thread right before its next hand-over to the FX thread. */
    @Volatile
    private var onOutboundDispatch: (() -> Unit)? = null

    /** Holds the spec's answer for `slow.java`. */
    private val slow = CountDownLatch(1)

    /** Hears of the folders, on the FX thread. */
    private val extension = RecordingExtension(isDispatched = { Thread.currentThread().name == "test-fx" })

    private val spec =
        TestSpec(
            workspaceFolderMode = WorkspaceFolderMode.ROOTS_ONLY,
            extension = { _, access -> extension.also { it.access = access } },
            folders = { file, projectFolder ->
                when (file.fileName.toString()) {
                    "slow.java" -> slow.await(5, TimeUnit.SECONDS)

                    // Left loose wherever it lies.
                    "Scratch.java" -> return@TestSpec null
                }
                generateSequence(file.parent) { it.parent }
                    .takeWhile { it.startsWith(projectFolder) }
                    .firstOrNull { Files.exists(it.resolve("build.gradle")) }
            },
            affects = { true },
        )

    @AfterEach
    fun closeServers() {
        slow.countDown()
        servers?.close()
        fx.shutdownNow()
    }

    private fun session(
        timing: ServerTiming = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
    ): LanguageServerSession {
        val created =
            LanguageServers(
                listOf(spec),
                { folder },
                { _, _ -> true },
                { runnable ->
                    if (Thread.currentThread().name == "sluide-lsp-test") {
                        onOutboundDispatch?.also { onOutboundDispatch = null }?.invoke()
                    }
                    fx.execute(runnable)
                },
                starter,
                scheduler,
                timing,
                System::nanoTime,
                folderWorker = {
                    Executors.newSingleThreadExecutor {
                        Thread(
                            it,
                            "test-folders",
                        ).apply { isDaemon = true }
                    }
                },
            )
        servers = created
        return created.session(spec).also { session -> session.onConnected { connects.incrementAndGet() } }
    }

    private fun <T> onFx(action: () -> T): T = fx.submit(action).get(5, TimeUnit.SECONDS)

    private fun LanguageServerSession.running(): FakeServer {
        onFx { ensureStarted() }
        awaitTrue("connected") { connects.get() == 1 }
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

    private fun LanguageServerSession.open(name: String) {
        onFx { documents.open(folder.resolve(name), "java", name.substringAfterLast('/').substringBefore('.')) }
    }

    private fun LanguageServerSession.folderOf(name: String): Path? =
        onFx { workspaceFolderOf(folder.resolve(name)).value }

    private fun root(name: String): Path =
        folder.resolve(name).createDirectories().also { it.resolve("build.gradle").writeText("") }

    /** The workspace folder changes and document opens and closes [this] got, in order, from [from] on. */
    private fun FakeServer.sync(from: Int = 0) =
        calls.drop(from).filter {
            it.startsWith("didChangeWorkspaceFolders") || it.startsWith("didOpen") || it == "didClose"
        }

    /** How many folders each `didChangeWorkspaceFolders` carried, added and removed together. */
    private fun FakeServer.folderCounts() = workspaceFolderChanges.map { it.event.added.size + it.event.removed.size }

    private fun added(path: Path) = "didChangeWorkspaceFolders +${LspUris.of(path)}"

    private fun removed(path: Path) = "didChangeWorkspaceFolders -${LspUris.of(path)}"

    /** What the server hears when it asks for the workspace folders. */
    private fun FakeServer.askedFolders() = client.workspaceFolders().get(5, TimeUnit.SECONDS).map { it.uri }

    @Test
    fun `initialize gets no folder and no root, each root comes in a notification of its own, loose files in none`() {
        val app = root("app")
        val lib = root("lib")
        val session = session()
        val server = session.running()
        val noneAsked = server.askedFolders()

        session.open("app/A.java")
        session.open("lib/B.java")
        session.open("app/C.java")
        session.open("Loose.java")
        session.flush(server)

        val params = server.initializeParams!!
        assertEquals(emptyList(), params.workspaceFolders)
        @Suppress("DEPRECATION")
        assertNull(params.rootUri)
        assertEquals(emptyList(), noneAsked)
        assertEquals(
            listOf(added(app), "didOpen A", added(lib), "didOpen B", "didOpen C", "didOpen Loose"),
            server.sync(),
        )
        assertEquals(listOf(1, 1), server.folderCounts())
        assertEquals(listOf(LspUris.of(app), LspUris.of(lib)), server.askedFolders())
        assertNull(session.folderOf("Loose.java"))
        awaitTrue("folder known on the FX thread") { session.folderOf("app/A.java") == app }
    }

    @Test
    fun `the window folder is a root only when the spec names it`() {
        folder.resolve("build.gradle").writeText("")
        val session = session()
        val server = session.running()

        session.open("A.java")
        session.flush(server)

        assertEquals(listOf(added(folder), "didOpen A"), server.sync())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `every new server gets the roots again one by one, before the documents open again`(crash: Boolean) {
        val app = root("app")
        val lib = root("lib")
        val session = session()
        val first = session.running()
        session.open("app/A.java")
        session.open("lib/B.java")
        session.open("Loose.java")
        session.flush(first)

        if (crash) {
            starter.last.crash()
            awaitTrue("restarting") { onFx { session.state.value } == ServerState.Restarting(1) }
            scheduler.runNext()
        } else {
            onFx { session.restart() }
        }
        awaitTrue("connected again") { connects.get() == 2 }
        val second = starter.last.server
        session.flush(second)

        assertEquals(emptyList(), second.initializeParams!!.workspaceFolders)
        assertEquals(
            listOf(added(app), added(lib), "didOpen A", "didOpen B", "didOpen Loose"),
            second.sync(),
        )
        assertEquals(listOf(1, 1), second.folderCounts())
    }

    @Test
    fun `the extension hears of each root sent in order, again after a restart, and of a refresh as removed, added`() {
        val app = root("app")
        val lib = root("lib")
        val session = session()
        val first = session.running()
        session.open("app/A.java")
        session.open("lib/B.java")
        session.flush(first)
        onFx { extension.access.refreshFolder(lib) }
        session.flush(first)

        onFx { extension.access.restart() }
        awaitTrue("connected again") { connects.get() == 2 }
        val second = starter.last.server
        session.flush(second)
        onFx {}

        assertEquals(
            listOf(
                "connected",
                "added $app",
                "added $lib",
                "removed $lib",
                "added $lib",
                "stopped",
                "connected",
                "added $app",
                "added $lib",
            ),
            extension.events,
        )
        assertEquals(0, extension.undispatched.get())
        assertEquals(listOf(removed(lib), added(lib), "didClose", "didOpen B"), first.sync().drop(4))
    }

    @Test
    fun `the extension does not hear of a folder sent to a server that was replaced before the report`() {
        val app = root("app")
        val lib = root("lib")
        val session = session()
        val first = session.running()
        session.open("app/A.java")
        session.flush(first)

        // Between writing lib's notification and reporting it, a restart replaces the server.
        onOutboundDispatch = { session.restart() }
        session.open("lib/B.java")
        awaitTrue("connected again") { connects.get() == 2 }
        session.flush(starter.last.server)
        onFx {}

        assertEquals(
            listOf("connected", "added $app", "stopped", "connected", "added $app", "added $lib"),
            extension.events,
        )
    }

    @Test
    fun `a refresh removes the root and adds it again, one notification each, and reopens only its documents`() {
        folder.resolve("build.gradle").writeText("")
        val session = session()
        val server = session.running()
        session.open("A.java")
        session.open("Scratch.java")
        session.flush(server)
        val before = server.calls.size
        val changesBefore = server.workspaceFolderChanges.size

        onFx { session.workspace.refresh(folder) }
        session.flush(server)

        assertEquals(listOf(removed(folder), added(folder), "didClose", "didOpen A"), server.sync(before))
        assertEquals(listOf(1, 1), server.folderCounts().drop(changesBefore))
    }

    @Test
    fun `a root no open document resolves to any more is removed, its documents opened again loose`() {
        // The window folder, which window-first mode never removes.
        val build = folder.resolve("build.gradle").also { it.writeText("") }
        val session = session()
        val server = session.running()
        session.open("A.java")
        session.flush(server)
        awaitTrue("folder known on the FX thread") { session.folderOf("A.java") == folder }
        val before = server.calls.size

        build.deleteExisting()
        onFx { servers!!.filesChanged(listOf(FileChange(build, FileChangeKind.DELETED))) }
        scheduler.due
            .removeAt(0)
            .second
            .run()
        awaitTrue("removed") { onFx { session.workspace.current }.isEmpty() }
        session.flush(server)

        assertEquals(listOf(removed(folder), "didClose", "didOpen A"), server.sync(before))
        assertNull(session.folderOf("A.java"))
    }

    @Test
    fun `a document whose root comes too late is opened loose, then again under its root`() {
        val app = root("app")
        val timing =
            ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds, workspaceFolderWait = 100.milliseconds)
        val session = session(timing)
        val server = session.running()
        session.open("app/A.java")

        session.open("app/slow.java")
        session.flush(server)
        val loose = server.sync()
        slow.countDown()
        awaitTrue("opened under its root") { server.sync().size == 5 }

        assertEquals(listOf(added(app), "didOpen A", "didOpen slow"), loose)
        assertEquals(listOf("didClose", "didOpen slow"), server.sync().drop(3))
    }

    @Test
    fun `a document whose late answer is no root stays loose and is not opened again`() {
        val app = root("app")
        val timing =
            ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds, workspaceFolderWait = 100.milliseconds)
        val session = session(timing)
        val server = session.running()
        session.open("slow.java")
        session.flush(server)

        slow.countDown()
        // The folder worker answers in order: once A's didOpen arrived, slow's answer was handed to the FX thread, so a
        // second flush comes after whatever that answer sent.
        session.open("app/A.java")
        session.flush(server)
        session.flush(server)

        assertEquals(listOf("didOpen slow", added(app), "didOpen A"), server.sync())
    }

    @Test
    fun `refreshing a folder that is no root does nothing, closing forgets the roots`() {
        root("app")
        val session = session()
        val server = session.running()
        session.open("app/A.java")
        session.flush(server)
        val before = server.calls.size
        val fired = AtomicInteger()
        onFx { session.workspaceChanges.addListener(InvalidationListener { fired.incrementAndGet() }) }

        onFx { session.workspace.refresh(folder.resolve("lib")) }
        session.flush(server)
        servers!!.close()

        assertEquals(emptyList(), server.sync(before))
        assertEquals(0, fired.get())
        assertEquals(emptyList(), session.workspace.current)
    }
}
