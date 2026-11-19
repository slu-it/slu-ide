package dev.sluide.languageservers.connection

import com.google.gson.JsonElement
import dev.sluide.languageservers.LogRecords
import dev.sluide.languageservers.RefreshKind
import dev.sluide.languageservers.ServerFeatures
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowDocumentParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the client answers a server without a user interface for, and what it logs of the server's messages. */
class SessionClientMessagesTest {
    private val logger =
        Logger.getLogger("dev.sluide.languageservers.connection.SessionClient").also {
            it.level =
                Level.ALL
        }
    private val logs = LogRecords(logger)

    private var configurationFailure: RuntimeException? = null

    private val minimal =
        object : ClientEvents {
            override fun configuration(item: ConfigurationItem): Any? = configurationFailure?.let { throw it }

            override fun diagnostics(params: PublishDiagnosticsParams) = Unit

            override fun refresh(kind: RefreshKind) = Unit

            override fun featuresChanged() = Unit
        }

    private val client = SessionClient("t", { emptyList() }, ServerFeatures(), minimal)

    @AfterEach
    fun closeLogs() = logs.close()

    @ParameterizedTest(name = "a {0} message is logged at {1}")
    @CsvSource("Error, WARNING", "Warning, WARNING", "Info, INFO", "Log, INFO")
    fun `window showMessage is logged by its type`(
        type: MessageType,
        level: String,
    ) {
        client.showMessage(MessageParams(type, "hello"))

        assertEquals(listOf("lsp t says: hello"), logs.messages(Level.parse(level)))
        assertEquals(1, logs.messages().size)
    }

    @Test
    fun `a message without a type or text is logged as an info line with no text`() {
        client.showMessage(MessageParams())
        client.logMessage(MessageParams())

        assertEquals(listOf("lsp t says: "), logs.messages(Level.INFO))
        assertEquals(listOf("lsp t log: "), logs.messages(Level.FINE))
    }

    @Test
    fun `a shown message is cut to 500 characters`() {
        client.showMessage(MessageParams(MessageType.Info, "x".repeat(501)))

        assertEquals("lsp t says: " + "x".repeat(500), logs.messages().single())
    }

    @Test
    fun `a message request is logged like a message and counts as dismissed`() {
        val answer =
            client.showMessageRequest(
                ShowMessageRequestParams().apply {
                    type = MessageType.Error
                    message = "pick"
                },
            )

        assertNull(answer.get())
        assertEquals(listOf("lsp t says: pick"), logs.messages(Level.WARNING))
    }

    @Test
    fun `a server asked to show a document or apply an edit is told no`() {
        val shown = client.showDocument(ShowDocumentParams("file:///a.py")).get()
        val applied = client.applyEdit(ApplyWorkspaceEditParams(WorkspaceEdit())).get()

        assertFalse(shown.isSuccess)
        assertFalse(applied.isApplied)
        assertEquals("Thoreau does not apply server edits yet", applied.failureReason)
    }

    @Test
    fun `refreshes the client does not act on are still answered`() {
        assertNull(client.refreshCodeLenses().get())
        assertNull(client.refreshInlineValues().get())
        assertNull(client.refreshFoldingRanges().get())
    }

    @Test
    fun `a server's log messages are logged by their type, a thousand of them, then one note`() {
        repeat(LOG_MESSAGES) { client.logMessage(MessageParams(MessageType.Error, "m$it")) }
        assertEquals(LOG_MESSAGES, logs.messages(Level.WARNING).size)
        assertEquals("lsp t log: m0", logs.messages().first())
        assertEquals("lsp t log: m${LOG_MESSAGES - 1}", logs.messages().last())

        client.logMessage(MessageParams(MessageType.Error, "one more"))
        client.logMessage(MessageParams(MessageType.Error, "and another"))

        assertEquals(LOG_MESSAGES + 1, logs.messages().size)
        assertEquals("lsp t log: further messages not logged", logs.messages(Level.INFO).single())
    }

    @Test
    fun `a server's log message is cut to 500 characters and logged below its own level`() {
        client.logMessage(MessageParams(MessageType.Info, "y".repeat(501)))

        assertEquals(listOf("lsp t log: " + "y".repeat(500)), logs.messages(Level.FINE))
    }

    @Test
    fun `a configuration the spec cannot give is answered null and logged with its section`() {
        configurationFailure = IllegalStateException("boom")

        val answer =
            client
                .configuration(
                    ConfigurationParams(listOf(ConfigurationItem().apply { section = "ty" })),
                ).get()

        assertEquals(listOf<Any?>(null), answer)
        assertEquals(listOf("lsp t: no configuration for section ty: boom"), logs.messages(Level.WARNING))
    }

    @Test
    fun `registered capabilities are logged by method`() {
        client
            .registerCapability(
                RegistrationParams(listOf(Registration("1", "a/b"), Registration("2", "c/d"))),
            ).get()

        assertEquals(listOf("lsp t registers a/b, c/d"), logs.messages(Level.INFO))
    }

    @Test
    fun `progress that cannot be written is logged and not handed on`() {
        val handed = mutableListOf<String>()
        val wanting =
            object : ClientEvents by minimal {
                override val wantsProgress: Boolean get() = true

                override fun progress(
                    token: String,
                    value: JsonElement,
                ) {
                    handed.add(token)
                }
            }
        // Gson refuses to write a class object.
        SessionClient("t", {
            emptyList()
        }, ServerFeatures(), wanting).notifyProgress(
            ProgressParams(Either.forLeft("tok"), Either.forRight(String::class.java)),
        )

        assertTrue(logs.messages(Level.WARNING).single().startsWith("lsp t: progress not read: "))
        assertEquals(emptyList(), handed)
    }

    @Test
    fun `events left at their defaults take no notification and want no progress`() {
        assertFalse(minimal.notification("any/method", null))
        assertFalse(minimal.wantsProgress)
    }
}
