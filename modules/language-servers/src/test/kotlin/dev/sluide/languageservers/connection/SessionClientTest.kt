package dev.sluide.languageservers.connection

import com.google.gson.JsonElement
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.RefreshKind
import dev.sluide.languageservers.ServerFeatures
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressNotification
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionClientTest {
    private val asked = CopyOnWriteArrayList<ConfigurationItem>()

    @Volatile
    private var folders = listOf(Path.of("/work/mono"))

    private val changed = AtomicInteger()
    private val taken = CopyOnWriteArrayList<String>()
    private val progress = CopyOnWriteArrayList<Pair<String, String>>()

    @Volatile
    private var wanted = true

    private val client =
        SessionClient(
            "test#1",
            { folders },
            ServerFeatures(),
            object : ClientEvents {
                override fun configuration(item: ConfigurationItem): Any? {
                    asked += item
                    return mapOf("scope" to item.scopeUri)
                }

                override fun diagnostics(params: PublishDiagnosticsParams) = Unit

                override fun refresh(kind: RefreshKind) = Unit

                override fun featuresChanged() {
                    changed.incrementAndGet()
                }

                override fun notification(
                    method: String,
                    params: Any?,
                ): Boolean {
                    taken += method
                    return method == "x/taken"
                }

                override val wantsProgress: Boolean get() = wanted

                override fun progress(
                    token: String,
                    value: JsonElement,
                ) {
                    progress += token to value.toString()
                }
            },
        )

    @ParameterizedTest
    @CsvSource("Error, WARNING", "Warning, INFO", "Info, DEBUG", "Log, DEBUG")
    fun `a server's log messages are logged at a level below its own`(
        type: MessageType,
        level: System.Logger.Level,
    ) {
        assertEquals(level, logMessageLevel(type))
    }

    @Test
    fun `a message without a type is logged as debug output`() {
        assertEquals(System.Logger.Level.DEBUG, logMessageLevel(null))
    }

    @Test
    fun `registering and unregistering capabilities tells the session its features changed`() {
        val registration = Registration("r1", "workspace/didChangeWatchedFiles")

        client.registerCapability(RegistrationParams(listOf(registration))).get()
        assertEquals(1, changed.get())

        client.unregisterCapability(UnregistrationParams(listOf(Unregistration("r1", registration.method)))).get()
        assertEquals(2, changed.get())
    }

    @Test
    fun `a custom notification says whether the session took it`() {
        assertTrue(client.customNotification("x/taken", null))
        assertFalse(client.customNotification("x/other", null))
        assertEquals(listOf("x/taken", "x/other"), taken)
    }

    @Test
    fun `progress nobody wants, without a token or without a value is not handed on`() {
        val begin = Either.forLeft<WorkDoneProgressNotification, Any>(WorkDoneProgressBegin().apply { title = "t" })
        wanted = false
        client.notifyProgress(ProgressParams(Either.forLeft("tok"), begin))
        wanted = true

        client.notifyProgress(ProgressParams().apply { value = begin })
        client.notifyProgress(ProgressParams(Either.forLeft("tok"), Either.forRight(null)))

        assertEquals(emptyList(), progress)
    }

    @Test
    fun `the folder named for a root without a file name is the root itself`() {
        folders = listOf(Path.of("/"))

        assertEquals(listOf("/"), client.workspaceFolders().get().map { it.name })
    }

    @Test
    fun `workspace folders are answered with the session's folders now, the window folder first`() {
        val window = Path.of("/work/mono")
        val backend = window.resolve("deployables/backend_one")
        assertEquals(listOf(LspUris.of(window)), client.workspaceFolders().get().map { it.uri })

        folders = listOf(window, backend)

        assertEquals(
            listOf(LspUris.of(window) to "mono", LspUris.of(backend) to "backend_one"),
            client.workspaceFolders().get().map { it.uri to it.name },
        )
    }

    @Test
    fun `each configuration item reaches the spec with its scope, the workspace folder it asks for`() {
        val scope = LspUris.of(Path.of("/work/mono/deployables/backend_one"))
        val item =
            ConfigurationItem().apply {
                this.scopeUri = scope
                section = "ty"
            }

        val answer = client.configuration(ConfigurationParams(listOf(item))).get()

        assertEquals(listOf(scope to "ty"), asked.map { it.scopeUri to it.section })
        assertEquals("""[{"scope":"$scope"}]""", answer.toString())
    }
}
