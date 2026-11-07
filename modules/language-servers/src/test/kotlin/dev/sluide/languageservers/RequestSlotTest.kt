package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Cancelled requests deliver nothing: on the server, on the way and once delivered to the FX thread. */
class RequestSlotTest {
    @TempDir
    lateinit var folder: Path

    private val starter = FakeStarter()

    /** While set, what is handed to the FX thread waits in [held] instead of running at once. */
    @Volatile
    private var hold = false
    private val held = LinkedBlockingQueue<Runnable>()
    private var servers: LanguageServers? = null

    @AfterEach
    fun closeServers() {
        servers?.close()
    }

    private fun running(): Pair<LanguageServerSession, FakeServer> {
        val spec = TestSpec()
        val created =
            LanguageServers(
                specs = listOf(spec),
                projectFolder = { folder },
                trustGate = { _, _ -> true },
                dispatch = { runnable -> if (hold) held.add(runnable) else runnable.run() },
                starter = starter,
                scheduler = FakeScheduler(),
                timing = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
                nanoTime = System::nanoTime,
            )
        servers = created
        val session = created.session(spec)
        session.ensureStarted()
        awaitTrue("running") { session.state.value == ServerState.Running }
        return session to starter.last.server
    }

    @Test
    fun `a result already on its way to the FX thread is dropped once the slot sends the next request`() {
        val (session, server) = running()
        val slot = RequestSlot()
        val results = CopyOnWriteArrayList<String>()
        slot.send(session, { it.textDocumentService.definition(definitionParams()) }, { results += "first" })
        awaitTrue("first request at the server") { server.definitions.size == 1 }
        hold = true
        server.definitions[0].complete(Either.forLeft(emptyList()))
        val firstDelivery = assertNotNull(held.poll(5, TimeUnit.SECONDS))
        hold = false

        slot.send(session, { it.textDocumentService.definition(definitionParams()) }, { results += "second" })
        firstDelivery.run()

        awaitTrue("second request at the server") { server.definitions.size == 2 }
        server.definitions[1].complete(Either.forLeft(emptyList()))
        awaitTrue("second result") { results.isNotEmpty() }
        assertEquals(listOf("second"), results)
    }

    @Test
    fun `a request cancelled before it was sent cancels its future when it is sent`() {
        val pending = PendingRequest()
        pending.cancel()
        val future = CompletableFuture<Any>()

        assertFalse(pending.sent(future))
        assertTrue(future.isCancelled)
    }

    @Test
    fun `cancelling a sent request cancels its future`() {
        val pending = PendingRequest()
        val future = CompletableFuture<Any>()

        assertTrue(pending.sent(future))
        pending.cancel()

        assertTrue(future.isCancelled)
        assertTrue(pending.isCancelled)
    }

    private fun definitionParams() =
        DefinitionParams(TextDocumentIdentifier(LspUris.of(folder.resolve("a.py"))), Position(0, 0))
}
