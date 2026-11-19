package dev.sluide.languageservers.connection

import dev.sluide.languageservers.BytePipe
import dev.sluide.languageservers.FakeProcess
import dev.sluide.languageservers.FakeServer
import dev.sluide.languageservers.LanguageServerNotRunningException
import dev.sluide.languageservers.LogRecords
import dev.sluide.languageservers.RefreshKind
import dev.sluide.languageservers.ServerFeatures
import dev.sluide.languageservers.awaitTrue
import dev.sluide.languageservers.process.ServerProcess
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** How a connection ends its process: it waits, within bounds, until the process is gone. */
class LspConnectionTest {
    private val logs = LogRecords(Logger.getLogger(LspConnection::class.java.name))
    private val processes = mutableListOf<FakeProcess>()

    @AfterEach
    fun cleanUp() {
        logs.close()
        processes.forEach(FakeProcess::release)
    }

    @ParameterizedTest
    @ValueSource(strings = ["kill", "shutdown"])
    fun `ending a server returns once its killed process is gone`(how: String) {
        val process = process(FakeServer().apply { honourExit = false })
        val connection = connection(process)
        thread(isDaemon = true) {
            if (process.awaitEndAsked()) {
                // The moment a killed process takes to die; the check below waits on the end, not on this.
                Thread.sleep(50)
                process.release()
            }
        }

        end(connection, how, exitMillis = 1_000)

        assertTrue(process.isEnded)
        assertEquals(emptyList(), stillRuns())
    }

    @ParameterizedTest
    @ValueSource(strings = ["kill", "shutdown"])
    fun `ending a server whose killed process never dies gives up after the exit wait and logs it`(how: String) {
        val process = process(FakeServer().apply { honourExit = false })
        val connection = connection(process)

        val started = System.nanoTime()
        end(connection, how, exitMillis = 200)

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 200, "waited for the end")
        assertFalse(process.isEnded)
        assertTrue(process.destroyed)
        assertEquals(listOf("lsp fake#1 still runs 200 ms after it was killed"), stillRuns())
    }

    private fun stillRuns() = logs.messages(Level.INFO).filter { "still runs" in it }

    private fun end(
        connection: LspConnection,
        how: String,
        exitMillis: Long,
    ) = if (how == "kill") connection.kill(exitMillis) else connection.shutdown(100, exitMillis)

    private fun process(server: FakeServer) = FakeProcess("fake#1", server, holdsEnd = true).also { processes += it }

    private fun connection(
        process: ServerProcess,
        client: LanguageClient? = null,
        notifications: ((method: String, params: Any?) -> Boolean)? = null,
    ): LspConnection {
        val events =
            object : ClientEvents {
                override fun configuration(item: ConfigurationItem): Any? = null

                override fun diagnostics(params: PublishDiagnosticsParams) = Unit

                override fun refresh(kind: RefreshKind) = Unit

                override fun featuresChanged() = Unit
            }
        val features = ServerFeatures()
        val used = client ?: SessionClient(process.name, { emptyList() }, features, events)
        // Without the argument the connection routes nothing, which is what some tests rely on.
        return (
            if (notifications == null) {
                LspConnection(process, used, features, "stopped")
            } else {
                LspConnection(process, used, features, "stopped", notifications)
            }
        ).also { it.listen() }
    }

    /** A client that records what reaches it, as lsp4j hands it over. */
    private class RecordingClient : LanguageClient {
        val telemetry = CopyOnWriteArrayList<Any?>()
        val logged = CopyOnWriteArrayList<String>()

        override fun telemetryEvent(`object`: Any?) {
            telemetry += `object`
        }

        override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) = Unit

        override fun showMessage(messageParams: MessageParams) = Unit

        override fun showMessageRequest(
            requestParams: ShowMessageRequestParams,
        ): CompletableFuture<MessageActionItem?> = CompletableFuture.completedFuture(null)

        override fun logMessage(message: MessageParams) {
            logged += message.message
        }
    }

    @Test
    fun `the notifications of the server are offered first, one that is taken does not reach the client`() {
        val server = FakeServer()
        val process = process(server)
        val client = RecordingClient()
        val offered = CopyOnWriteArrayList<Pair<String, Any?>>()
        connection(process, client, notifications = { method, params ->
            offered += method to params
            method == "telemetry/event"
        })

        server.client.telemetryEvent("taken")
        // A request of the server is no notification: it is answered, never offered.
        server.client.showMessageRequest(ShowMessageRequestParams()).get(5, TimeUnit.SECONDS)
        server.client.logMessage(MessageParams().apply { message = "second" })
        awaitTrue("the later notification reached the client") { client.logged.isNotEmpty() }

        assertEquals(listOf("second"), client.logged)
        assertEquals(emptyList(), client.telemetry)
        assertEquals(listOf("telemetry/event", "window/logMessage"), offered.map { it.first })
        assertEquals("taken", offered.first().second)
    }

    @Test
    fun `without a router every notification reaches the client`() {
        val server = FakeServer()
        val client = RecordingClient()
        connection(process(server), client)

        server.client.telemetryEvent("plain")

        awaitTrue("the notification reached the client") { client.telemetry.isNotEmpty() }
        assertEquals(listOf<Any?>("plain"), client.telemetry)
    }

    @Test
    fun `a notification the router fails on is logged as a warning and still reaches the client`() {
        val server = FakeServer()
        val process = process(server)
        val client = RecordingClient()
        connection(process, client, notifications = { _, _ -> throw IllegalStateException("router broke") })

        server.client.telemetryEvent("still delivered")
        awaitTrue("the notification reached the client") { client.telemetry.isNotEmpty() }

        assertEquals(listOf<Any?>("still delivered"), client.telemetry)
        assertEquals(listOf("lsp fake#1: routing telemetry/event failed"), logs.messages(Level.WARNING))
    }

    /** A future that counts how often it was failed from outside. */
    private class CountingFuture : CompletableFuture<String>() {
        var failed = 0

        override fun completeExceptionally(ex: Throwable): Boolean {
            failed++
            return super.completeExceptionally(ex)
        }
    }

    @Test
    fun `a tracked request fails when the connection ends, one that completed before is let go`() {
        val process = process(FakeServer())
        val connection = connection(process)
        val waiting = CountingFuture()
        val done = CountingFuture()
        connection.track(waiting)
        connection.track(done)
        done.complete("answered")

        connection.kill(200)

        val failure = assertFailsWith<java.util.concurrent.ExecutionException> { waiting.get(5, TimeUnit.SECONDS) }
        assertIs<LanguageServerNotRunningException>(failure.cause)
        assertEquals("stopped", failure.cause?.message)
        assertEquals(1, waiting.failed)
        assertEquals("answered", done.get())
        assertEquals(0, done.failed, "a completed request is no longer tracked")
    }

    @Test
    fun `a request tracked after the connection ended fails at once`() {
        val process = process(FakeServer())
        val connection = connection(process)
        connection.shutdown(100, 200)
        val late = CountingFuture()

        connection.track(late)

        val failure = assertFailsWith<java.util.concurrent.ExecutionException> { late.get(5, TimeUnit.SECONDS) }
        assertIs<LanguageServerNotRunningException>(failure.cause)
        assertEquals("stopped", failure.cause?.message)
    }

    /** A process whose input tells the test when a read on it fails or hits the end, and on which thread it reads. */
    private class WatchedReads(
        private val process: FakeProcess,
    ) : ServerProcess by process {
        val readEnded = CountDownLatch(1)
        val reading = CountDownLatch(1)

        @Volatile
        var readerName: String? = null

        override val input: InputStream =
            object : InputStream() {
                override fun read(): Int {
                    readerName = Thread.currentThread().name
                    reading.countDown()
                    try {
                        return process.input.read().also { if (it < 0) readEnded.countDown() }
                    } catch (e: IOException) {
                        readEnded.countDown()
                        throw e
                    }
                }
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["kill", "shutdown"])
    fun `ending a server interrupts its reader even if the process never ends`(how: String) {
        val process = WatchedReads(process(FakeServer().apply { honourExit = false }))
        val connection = connection(process)
        assertTrue(process.reading.await(5, TimeUnit.SECONDS), "the reader waits for the server")
        assertEquals("sluide-lsp-fake#1-reader", process.readerName)

        end(connection, how, exitMillis = 100)

        assertTrue(process.readEnded.await(5, TimeUnit.SECONDS), "the read of the reader was interrupted")
    }

    @Test
    fun `a shutdown asks with shutdown, then exit, and logs a shutdown nobody answers`() {
        val server = FakeServer().apply { answerShutdown = false }
        val process = FakeProcess("fake#1", server).also { processes += it }
        val connection = connection(process)

        val started = System.nanoTime()
        connection.shutdown(300, 100)

        // The exit wait starts when exit was sent, after the wait for the answer to shutdown.
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 300, "waited for the answer")
        assertEquals(listOf("shutdown", "exit"), server.calls)
        assertTrue(process.isEnded)
        assertFalse(process.destroyed)
        val unanswered = logs.messages(Level.INFO).filter { "not answered" in it }
        assertEquals(1, unanswered.size)
        assertTrue(unanswered.single().startsWith("lsp fake#1 shutdown not answered: "), unanswered.single())
        assertEquals(emptyList(), logs.messages(Level.INFO).filter { "killed" in it })
    }

    @Test
    fun `a server that stays after exit is killed, and that is logged`() {
        val process = process(FakeServer().apply { honourExit = false })
        val connection = connection(process)

        connection.shutdown(100, 100)

        assertTrue(process.destroyed)
        assertEquals(
            listOf("lsp fake#1 did not exit after shutdown, killed"),
            logs.messages(Level.INFO).filter { "did not exit" in it },
        )
    }

    /** A process whose stdin takes no byte: the first write blocks until its thread is interrupted. */
    private class StuckWriteProcess : ServerProcess {
        private val ended = CountDownLatch(1)
        private val blocked = CountDownLatch(1)
        val interrupted = CountDownLatch(1)

        @Volatile
        var writerName: String? = null
        private val writes = AtomicInteger()

        override val name = "stuck#1"
        override val input: InputStream = BytePipe().input
        override val output: OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    if (writes.getAndIncrement() > 0) throw IOException("stdin gone")
                    writerName = Thread.currentThread().name
                    blocked.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (_: InterruptedException) {
                        interrupted.countDown()
                        throw IOException("interrupted")
                    }
                }
            }

        fun awaitBlocked() = blocked.await(5, TimeUnit.SECONDS)

        override fun waitFor(): Int {
            ended.await()
            return 137
        }

        override fun waitFor(millis: Long) = ended.await(millis, TimeUnit.MILLISECONDS)

        override fun descendants(): List<ProcessHandle> = emptyList()

        override fun destroyTree() = ended.countDown()
    }

    @Test
    fun `a shutdown whose write is stuck gives up after the waits, kills the process and frees the writing thread`() {
        val process = StuckWriteProcess()
        val features = ServerFeatures()
        val connection =
            LspConnection(process, RecordingClient(), features, "stopped").also { it.listen() }

        val ender = thread(isDaemon = true) { connection.shutdown(100, 100) }
        assertTrue(process.awaitBlocked(), "the shutdown request is being written")
        ender.join(5_000)

        assertFalse(ender.isAlive, "the shutdown returned although the write is stuck")
        assertTrue(process.waitFor(0), "the process was killed")
        assertTrue(process.interrupted.await(5, TimeUnit.SECONDS), "the stuck write was interrupted")
        assertEquals("sluide-lsp-stuck#1-shutdown", process.writerName)
    }
}
