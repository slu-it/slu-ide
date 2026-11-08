package dev.sluide.codeintelligence.semantic

import dev.sluide.codeintelligence.BoundEditorHarness
import dev.sluide.codeintelligence.ManualDelays
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.diagnostics.LspTestServer
import dev.sluide.fileviewers.StyledRange
import dev.sluide.languageservers.ServerState
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Semantic highlighting of a bound editor over a fake server: tokens asked after open, once after a burst of changes
 * and on refresh; an answer for an older text is dropped and asked again; the colours go when the server stops and
 * come again from the new one, decoded with its legend; shown as the following overlay layer.
 */
class SemanticTokensFeatureTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var harness: BoundEditorHarness<LspTestServer>
    private val delays = ManualDelays()

    @AfterEach
    fun close() = harness.close()

    private var starts = 0

    /** A server with semantic tokens; the second one started has the legend's types the other way round. */
    private fun tokenServer(): LspTestServer {
        val types = if (starts++ == 0) listOf("parameter", "variable") else listOf("variable", "parameter")
        return LspTestServer {
            semanticTokensProvider =
                SemanticTokensWithRegistrationOptions(SemanticTokensLegend(types, emptyList())).apply {
                    full = Either.forLeft(true)
                }
        }
    }

    private val layer get() = harness.onFx { harness.editor.layers[SEMANTIC_LAYER] }

    /** One parameter token on line 0 at [character], one character long. */
    private fun parameterAt(character: Int) = SemanticTokens(listOf(0, character, 1, 0, 0))

    @Test
    fun `tokens are asked after open, after a burst of changes and on refresh, and an older text's answer again`() {
        harness =
            BoundEditorHarness(
                folder,
                "def f(a):\n",
                ::tokenServer,
                extra = listOf(SemanticTokensFeature(500.milliseconds, delays::schedule)),
            )
        val server = harness.server
        awaitTrue("asked after open") { server.tokenRequests.size == 1 }
        server.tokenRequests[0].complete(parameterAt(6))
        awaitTrue("shown") { layer == listOf(StyledRange(6, 7, "sem-parameter")) }

        harness.onFx { harness.editor.edit(0, 0, "#") }
        harness.onFx { harness.editor.edit(0, 1, "") }
        assertEquals(1, delays.count, "the second change replaced the first one's request")
        harness.run(delays)
        awaitTrue("asked after the changes") { server.tokenRequests.size == 2 }

        harness.onFx { harness.editor.edit(0, 0, " ") }
        server.tokenRequests[1].complete(parameterAt(0))
        awaitTrue("asked again after the debounce") { delays.scheduled == 4 }
        assertEquals(listOf(StyledRange(6, 7, "sem-parameter")), layer, "the older text's answer is dropped")

        server.client.refreshSemanticTokens()
        awaitTrue("asked on refresh") { server.tokenRequests.size == 3 }
        server.tokenRequests[2].complete(parameterAt(7))
        awaitTrue("shown") { layer == listOf(StyledRange(7, 8, "sem-parameter")) }
    }

    @Test
    fun `a stopped server's colours go, and the new server is asked and decoded with its own legend`() {
        harness =
            BoundEditorHarness(
                folder,
                "def f(a):\n",
                ::tokenServer,
                extra = listOf(SemanticTokensFeature(500.milliseconds, delays::schedule)),
            )
        val first = harness.server
        awaitTrue("asked after open") { first.tokenRequests.size == 1 }
        first.tokenRequests[0].complete(parameterAt(6))
        awaitTrue("shown") { layer == listOf(StyledRange(6, 7, "sem-parameter")) }

        harness.onFx { harness.binding.session.restart() }
        awaitTrue("gone when the server stopped") { layer == emptyList<StyledRange>() }
        awaitTrue("restarted") { harness.fake.started.size == 2 }
        harness.awaitState(ServerState.Running)
        val second = harness.server
        awaitTrue("the new server asked") { second.tokenRequests.size == 1 }
        // Type 1 is `parameter` in the new legend (it was `variable`).
        second.tokenRequests[0].complete(SemanticTokens(listOf(0, 6, 1, 1, 0)))
        awaitTrue("shown") { layer == listOf(StyledRange(6, 7, "sem-parameter")) }
    }
}
