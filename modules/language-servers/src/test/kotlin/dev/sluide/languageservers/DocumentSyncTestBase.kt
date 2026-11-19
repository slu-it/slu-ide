package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import dev.sluide.languageservers.files.DirectoryWatcher
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/** The harness of the document sync tests: a session over fake servers, a single-thread executor as the FX thread. */
internal abstract class DocumentSyncTestBase {
    @TempDir
    lateinit var folder: Path

    val spec = TestSpec()

    /** A spec that gates documents: files whose name starts with `old` are not served. */
    val gating =
        TestSpec(gatesDocuments = true, gates = {
            file,
            _,
            ->
            DocumentGate(!file.fileName.toString().startsWith("old"))
        })
    val fx = Executors.newSingleThreadExecutor { Thread(it, "test-fx").apply { isDaemon = true } }
    val scheduler = FakeScheduler()
    val connects = AtomicInteger()
    var servers: LanguageServers? = null
    lateinit var starter: FakeStarter

    @AfterEach
    fun closeServers() {
        servers?.close()
        fx.shutdownNow()
    }

    fun session(
        capabilities: () -> ServerCapabilities = { syncing(TextDocumentSyncKind.Incremental) },
        beforeStart: (Int) -> Unit = {},
        spec: TestSpec = this.spec,
        folderWorker: (LanguageServerSpec) -> Executor = { Executor(Runnable::run) },
        directoryWatcher: DirectoryWatcher = DirectoryWatcher { _, _ -> AutoCloseable {} },
    ): LanguageServerSession {
        starter = FakeStarter({ FakeServer().apply { this.capabilities = capabilities() } }, beforeStart)
        val created =
            LanguageServers(
                listOf(spec),
                { folder },
                { _, _ -> true },
                fx::execute,
                starter,
                scheduler,
                ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
                System::nanoTime,
                directoryWatcher = directoryWatcher,
                folderWorker = folderWorker,
            )
        servers = created
        return created.session(spec).also { session -> session.onConnected { connects.incrementAndGet() } }
    }

    fun <T> onFx(action: () -> T): T = fx.submit(action).get(5, TimeUnit.SECONDS)

    /** Starts the server and waits until the session announced it; the server. */
    fun LanguageServerSession.running(): FakeServer {
        val before = connects.get()
        onFx { ensureStarted() }
        awaitTrue("connected") { connects.get() > before }
        return starter.last.server
    }

    /** Waits until [server] got everything sent before. */
    fun LanguageServerSession.flush(server: FakeServer) {
        val count = server.calls.count { it == "didChangeConfiguration" }
        onFx {
            notify {
                it.workspaceService.didChangeConfiguration(
                    DidChangeConfigurationParams(emptyMap<String, Any>()),
                )
            }
        }
        awaitTrue("flushed") { server.calls.count { it == "didChangeConfiguration" } > count }
    }

    fun LanguageServerSession.open(
        name: String,
        text: String,
    ): SyncedDocument = onFx { documents.open(folder.resolve(name), "python", text) }

    val FakeServer.opens get() = documentNotifications.filterIsInstance<DidOpenTextDocumentParams>()
    val FakeServer.changes get() = documentNotifications.filterIsInstance<DidChangeTextDocumentParams>()
    val FakeServer.closes
        get() = documentNotifications.filterIsInstance<DidCloseTextDocumentParams>().map { it.textDocument.uri }

    fun uri(name: String) = LspUris.of(folder.resolve(name))

    /** The document notifications [this] got, as `open a.py 1`, `change a.py`, `save a.py`, `close a.py`. */
    fun FakeServer.log() =
        documentNotifications.map { notification ->
            when (notification) {
                is DidOpenTextDocumentParams -> "open ${name(
                    notification.textDocument.uri,
                )} ${notification.textDocument.version}"

                is DidChangeTextDocumentParams -> "change ${name(notification.textDocument.uri)}"

                is DidSaveTextDocumentParams -> "save ${name(notification.textDocument.uri)}"

                is DidCloseTextDocumentParams -> "close ${name(notification.textDocument.uri)}"

                else -> notification.toString()
            }
        }

    fun name(uri: String) = LspUris.toPath(uri)?.fileName.toString()

    companion object {
        fun syncing(kind: TextDocumentSyncKind) = ServerCapabilities().apply { setTextDocumentSync(kind) }
    }
}
