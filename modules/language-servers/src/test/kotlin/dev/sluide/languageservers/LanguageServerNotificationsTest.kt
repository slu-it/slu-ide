package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.messages.CancelParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Notifications per connection, the re-sync on connect and what an old connection still sends. */
class LanguageServerNotificationsTest {
    @TempDir
    lateinit var folder: Path

    private var spec = TestSpec()
    private val starter = FakeStarter()
    private var servers: LanguageServers? = null

    private val logs = CopyOnWriteArrayList<LogRecords>()

    @AfterEach
    fun closeServers() {
        servers?.close()
        logs.forEach(LogRecords::close)
    }

    private fun session(
        timing: ServerTiming = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
    ): LanguageServerSession {
        val created =
            LanguageServers(listOf(spec), {
                folder
            }, { _, _ -> true }, Runnable::run, starter, FakeScheduler(), timing, System::nanoTime)
        servers = created
        return created.session(spec)
    }

    @Test
    fun `notifications made before a server was announced are dropped, the re-sync on connect and later ones arrive`() {
        val session = session()
        session.onConnected { session.notify { it.textDocumentService.didOpen(open("re-sync on connect")) } }

        session.notify { it.textDocumentService.didOpen(open("made while starting")) }
        awaitTrue("running") { session.state.value == ServerState.Running }
        val server = starter.last.server
        awaitTrue("re-sync") { "didOpen re-sync on connect" in server.calls }
        session.notify { it.textDocumentService.didOpen(open("after connect")) }
        awaitTrue("later notification") { "didOpen after connect" in server.calls }

        assertEquals(
            listOf("initialize", "initialized", "didOpen re-sync on connect", "didOpen after connect"),
            server.calls,
        )
    }

    @Test
    fun `a notification made for one server does not reach the next one`() {
        val session = session()
        val reSyncs = CopyOnWriteArrayList<Int>()
        session.onConnected { reSyncs += reSyncs.size }
        session.ensureStarted()
        awaitTrue("first connect") { reSyncs.size == 1 }
        val release = CountDownLatch(1)
        // Holds the outbound thread, so that the restart's start runs before the notification made for the first server.
        session.request({
            release.await(5, TimeUnit.SECONDS)
            CompletableFuture.completedFuture(Unit)
        }, {})
        session.restart()
        session.notify { it.textDocumentService.didOpen(open("for the first server")) }

        release.countDown()

        awaitTrue("second connect") { reSyncs.size == 2 }
        session.notify { it.textDocumentService.didOpen(open("for the second server")) }
        val second = starter.last.server
        awaitTrue("second notification") { "didOpen for the second server" in second.calls }
        assertEquals(listOf("initialize", "initialized", "didOpen for the second server"), second.calls)
    }

    @Test
    fun `a listener that throws neither stops the other connect listeners nor the feature listeners`() {
        val session = session()
        val heard = CopyOnWriteArrayList<String>()
        session.onConnected { throw IllegalStateException("listener bug") }
        session.onConnected { heard += "connected" }
        session.onFeaturesChanged { heard += "features" }

        session.ensureStarted()

        awaitTrue("listeners") { heard.size == 2 }
        assertEquals(listOf("connected", "features"), heard)
    }

    /** Waits until the client handled everything [this] server sent before: a request answered after it. */
    private fun FakeServer.barrier() {
        client.configuration(ConfigurationParams(emptyList())).get(5, TimeUnit.SECONDS)
    }

    private fun extendedSession(extension: RecordingExtension): FakeServer {
        spec = extendedSpec(extension)
        session().ensureStarted()
        awaitTrue("connected") { "connected" in extension.events }
        return starter.last.server
    }

    @Test
    fun `a notification the extension names reaches it with its params, others pass by, and so does a method sent`() {
        val extension = RecordingExtension(setOf("language/status"))
        val server = extendedSession(extension)

        server.clientEndpoint.notify("language/status", mapOf("type" to "Started", "message" to "Ready"))
        server.clientEndpoint.notify("language/other", mapOf("type" to "Ignored"))
        server.clientEndpoint.notify("language/status", null)
        // The same method from Thoreau to the server is not taken.
        extension.access.notify("language/status", mapOf("type" to "Sent"))
        awaitTrue("sent to the server") { server.customMessages.isNotEmpty() }
        server.barrier()

        assertEquals(
            listOf(
                "connected",
                "notification language/status {\"type\":\"Started\",\"message\":\"Ready\"}",
                "notification language/status null",
            ),
            extension.events,
        )
        assertEquals(
            listOf("language/status {\"type\":\"Sent\"}"),
            server.customMessages.map { "${it.first} ${it.second}" },
        )
    }

    @Test
    fun `a method lsp4j handles stays with the session even when the extension names it`() {
        val extension = RecordingExtension(setOf("textDocument/publishDiagnostics", "\$/progress", "\$/cancelRequest"))
        val server = extendedSession(extension)
        val file = folder.resolve("a.py")
        val heard = CopyOnWriteArrayList<String>()
        servers!!.session(spec).onDiagnostics(file) {
            heard +=
                it.diagnostics
                    .single()
                    .message.left
        }
        val cancels = lsp4jWarnings()

        server.client.publishDiagnostics(
            PublishDiagnosticsParams(LspUris.of(file), listOf(Diagnostic(range(), "kept"))),
        )
        server.client.notifyProgress(ProgressParams(Either.forLeft("t1"), Either.forLeft(WorkDoneProgressEnd())))
        server.clientEndpoint.notify("\$/cancelRequest", CancelParams().apply { setId("99") })
        server.barrier()

        assertEquals(listOf("kept"), heard)
        assertEquals(listOf("connected", "progress t1 {\"kind\":\"end\"}"), extension.events)
        assertEquals(listOf("Unmatched cancel notification for request id 99"), cancels.messages())
    }

    @Test
    fun `a server's cancellation reaches lsp4j's handling`() {
        val server = extendedSession(RecordingExtension())
        val warnings = lsp4jWarnings()

        server.clientEndpoint.notify("\$/cancelRequest", CancelParams().apply { setId("42") })
        server.barrier()

        // lsp4j read it as a cancellation (of no request it knows), not as a method of its own.
        assertEquals(listOf("Unmatched cancel notification for request id 42"), warnings.messages())
    }

    /** Collects what lsp4j's endpoint logs from now on, until the test ends. */
    private fun lsp4jWarnings(): LogRecords =
        LogRecords(Logger.getLogger(RemoteEndpoint::class.java.name)).also { logs += it }

    @Test
    fun `progress reaches the extension with its token as text, and progress creation is still answered`() {
        val extension = RecordingExtension()
        val server = extendedSession(extension)

        server.client.createProgress(WorkDoneProgressCreateParams(Either.forLeft("t1"))).get(5, TimeUnit.SECONDS)
        val begin = WorkDoneProgressBegin().apply { title = "Updating workspace folders" }
        server.client.notifyProgress(ProgressParams(Either.forLeft("t1"), Either.forLeft(begin)))
        server.client.notifyProgress(ProgressParams(Either.forRight(7), Either.forLeft(WorkDoneProgressEnd())))
        server.barrier()

        assertEquals(
            listOf(
                "connected",
                "progress t1 {\"kind\":\"begin\",\"title\":\"Updating workspace folders\"}",
                "progress 7 {\"kind\":\"end\"}",
            ),
            extension.events,
        )
    }

    @Test
    fun `notifications and progress an old server sends after a restart do not reach the extension`() {
        val extension = RecordingExtension(setOf("language/status"))
        val old = extendedSession(extension)
        old.answerShutdown = false
        val session = servers!!.session(spec)

        session.restart()
        awaitTrue("shutdown sent to the old server") { "shutdown" in old.calls }
        old.clientEndpoint.notify("language/status", mapOf("type" to "Stale"))
        old.client.notifyProgress(ProgressParams(Either.forLeft("t1"), Either.forLeft(WorkDoneProgressEnd())))
        old.barrier()

        assertEquals(
            emptyList(),
            extension.events.filter { it.startsWith("notification") || it.startsWith("progress") },
        )
    }

    @Test
    fun `diagnostics an old server sends after a restart are dropped`() {
        // The old server does not answer shutdown, so its connection lives on while it sends.
        val session = session(ServerTiming(shutdown = 3.seconds, exit = 200.milliseconds))
        session.ensureStarted()
        awaitTrue("running") { session.state.value == ServerState.Running }
        val old = starter.last.server
        old.answerShutdown = false
        val file = folder.resolve("a.py")
        val heard = CopyOnWriteArrayList<String>()
        session.onDiagnostics(file) {
            heard +=
                it.diagnostics
                    .single()
                    .message.left
        }

        session.restart()
        awaitTrue("shutdown sent to the old server") { "shutdown" in old.calls }
        old.client.publishDiagnostics(PublishDiagnosticsParams(LspUris.of(file), listOf(Diagnostic(range(), "stale"))))
        // Answered after the client has read the notification before it: the old connection's events were handled.
        old.client.configuration(ConfigurationParams(emptyList())).get(5, TimeUnit.SECONDS)

        assertEquals(emptyList(), heard)
    }

    private fun open(text: String) =
        DidOpenTextDocumentParams(TextDocumentItem(LspUris.of(folder.resolve("a.py")), "python", 1, text))

    private fun range() = Range(Position(0, 0), Position(0, 1))
}

/** The messages [logger] logs while this is open; holds the logger, which java.util.logging keeps only weakly. */
private class LogRecords(
    private val logger: Logger,
) : Handler(),
    AutoCloseable {
    private val records = CopyOnWriteArrayList<LogRecord>()

    init {
        logger.addHandler(this)
    }

    fun messages(): List<String> = records.map { it.message }

    override fun publish(record: LogRecord) {
        records += record
    }

    override fun flush() = Unit

    override fun close() {
        logger.removeHandler(this)
    }
}
