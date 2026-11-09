package dev.sluide.languageservers.connection

import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.RefreshKind
import dev.sluide.languageservers.ServerFeatures
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

class SessionClientTest {
    private val asked = CopyOnWriteArrayList<ConfigurationItem>()

    @Volatile
    private var folders = listOf(Path.of("/work/mono"))

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

                override fun featuresChanged() = Unit
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
