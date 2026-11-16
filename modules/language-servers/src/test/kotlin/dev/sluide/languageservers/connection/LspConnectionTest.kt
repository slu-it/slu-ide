package dev.sluide.languageservers.connection

import dev.sluide.languageservers.FakeProcess
import dev.sluide.languageservers.FakeServer
import dev.sluide.languageservers.LogRecords
import dev.sluide.languageservers.RefreshKind
import dev.sluide.languageservers.ServerFeatures
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun connection(process: FakeProcess): LspConnection {
        val events =
            object : ClientEvents {
                override fun configuration(item: ConfigurationItem): Any? = null

                override fun diagnostics(params: PublishDiagnosticsParams) = Unit

                override fun refresh(kind: RefreshKind) = Unit

                override fun featuresChanged() = Unit
            }
        val features = ServerFeatures()
        return LspConnection(
            process,
            SessionClient(process.name, { emptyList() }, features, events),
            features,
            "stopped",
        ).also { it.listen() }
    }
}
