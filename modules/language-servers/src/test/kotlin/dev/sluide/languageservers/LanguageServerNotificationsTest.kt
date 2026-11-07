package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Notifications per connection, the re-sync on connect and what an old connection still sends. */
class LanguageServerNotificationsTest {
    @TempDir
    lateinit var folder: Path

    private val spec = TestSpec()
    private val starter = FakeStarter()
    private var servers: LanguageServers? = null

    @AfterEach
    fun closeServers() {
        servers?.close()
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
