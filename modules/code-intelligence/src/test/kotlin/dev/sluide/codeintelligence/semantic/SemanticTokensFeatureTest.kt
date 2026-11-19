package dev.sluide.codeintelligence.semantic

import dev.sluide.codeintelligence.BoundEditorHarness
import dev.sluide.codeintelligence.ManualDelays
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.diagnostics.LspTestServer
import dev.sluide.fileviewers.StyledRange
import dev.sluide.languageservers.ServerState
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
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
        assertEquals(3, harness.editor.overlayCalls[SEMANTIC_LAYER], "shown, cleared once as it stopped, shown again")
    }

    private val full = "textDocument/semanticTokens/full"

    private val semanticCss =
        checkNotNull(SemanticTokensFeature::class.java.getResource("semantic-tokens.css")).toExternalForm()

    /** A server that declares [provider], which `full: false` or no provider make not offer the tokens. */
    private fun serverWith(provider: SemanticTokensWithRegistrationOptions?) =
        { LspTestServer { semanticTokensProvider = provider } }

    private fun providerWithFull(full: Boolean) =
        SemanticTokensWithRegistrationOptions(SemanticTokensLegend(listOf("parameter"), emptyList())).apply {
            this.full = Either.forLeft(full)
        }

    private fun open(
        newServer: () -> LspTestServer,
        delay: (kotlin.time.Duration, () -> Unit) -> AutoCloseable = delays::schedule,
    ) {
        harness =
            BoundEditorHarness(
                folder,
                "def f(a):\n",
                newServer,
                extra = listOf(SemanticTokensFeature(500.milliseconds, delay)),
            )
    }

    private fun registerFull() {
        harness.server.client
            .registerCapability(RegistrationParams(listOf(Registration("t", full))))
            .get()
        harness.serverIdle()
    }

    @Test
    fun `a server that does not offer the tokens is asked once it registers them, and not after it unregisters`() {
        open(serverWith(providerWithFull(false)))
        val server = harness.server
        harness.serverIdle()
        harness.server.client
            .registerCapability(RegistrationParams(listOf(Registration("w", "workspace/didChangeConfiguration"))))
            .get()
        harness.serverIdle()
        assertEquals(null, harness.editor.overlayCalls[SEMANTIC_LAYER], "nothing was shown, nothing is cleared")
        harness.onFx { harness.editor.edit(0, 0, "#") }
        assertEquals(0, delays.scheduled, "no request is waited for")
        server.client.refreshSemanticTokens()
        harness.onFx { harness.editor.fileProperty.set(folder.resolve("b.py")) }
        harness.serverIdle()
        assertEquals(0, server.tokenRequests.size, "not on a refresh, not on a moved file")

        registerFull()
        awaitTrue("asked once registered") { server.tokenRequests.size == 1 }
        server.tokenRequests[0].complete(parameterAt(7))
        awaitTrue("shown") { layer == listOf(StyledRange(7, 8, "sem-parameter")) }

        harness.onFx { harness.editor.fileProperty.set(folder.resolve("c.py")) }
        awaitTrue("asked again for the moved file") { server.tokenRequests.size == 2 }

        val other = Registration("w", "workspace/didChangeConfiguration")
        harness.server.client
            .registerCapability(RegistrationParams(listOf(other)))
            .get()
        harness.serverIdle()
        assertEquals(2, server.tokenRequests.size, "an unrelated registration asks nothing")
        assertEquals(listOf(StyledRange(7, 8, "sem-parameter")), layer, "and keeps the colours")
        assertEquals(listOf(StyledRange(7, 8, "sem-parameter")), layer, "and keeps the colours")

        server.client.unregisterCapability(UnregistrationParams(listOf(Unregistration("t", full)))).get()
        awaitTrue("colours gone") { layer == emptyList<StyledRange>() }
    }

    @Test
    fun `a server without a provider or a legend is not asked even when it registers the tokens`() {
        open(serverWith(null))
        registerFull()
        assertEquals(0, harness.server.tokenRequests.size, "no provider")
        harness.close()

        open(serverWith(SemanticTokensWithRegistrationOptions().apply { this.full = Either.forLeft(true) }))
        harness.serverIdle()
        assertEquals(0, harness.server.tokenRequests.size, "no legend")
    }

    @Test
    fun `the delay is the given one, and a pending request is dropped by a request that goes first`() {
        val given = mutableListOf<kotlin.time.Duration>()
        open(::tokenServer, { delay, action -> delays.schedule(delay, action).also { given += delay } })
        val server = harness.server
        awaitTrue("asked after open") { server.tokenRequests.size == 1 }
        harness.onFx { harness.editor.edit(0, 0, "#") }
        assertEquals(listOf(500.milliseconds), given)
        assertEquals(1, delays.count)

        server.client.refreshSemanticTokens()
        awaitTrue("asked on refresh") { server.tokenRequests.size == 2 }
        assertEquals(0, delays.count, "the request that went first took the waiting one's place")
    }

    @Test
    fun `an answer without tokens keeps the colours, one without data or with null numbers is decoded as it goes`() {
        open(::tokenServer)
        val server = harness.server
        awaitTrue("asked after open") { server.tokenRequests.size == 1 }
        server.tokenRequests[0].complete(parameterAt(6))
        awaitTrue("shown") { layer == listOf(StyledRange(6, 7, "sem-parameter")) }

        server.client.refreshSemanticTokens()
        awaitTrue("asked on refresh") { server.tokenRequests.size == 2 }
        server.tokenRequests[1].complete(null)
        harness.serverIdle()
        assertEquals(listOf(StyledRange(6, 7, "sem-parameter")), layer, "a null answer changes nothing")

        server.client.refreshSemanticTokens()
        awaitTrue("asked again") { server.tokenRequests.size == 3 }
        server.tokenRequests[2].complete(SemanticTokens())
        awaitTrue("no data, no colours") { layer == emptyList<StyledRange>() }

        server.client.refreshSemanticTokens()
        awaitTrue("asked a fourth time") { server.tokenRequests.size == 4 }
        @Suppress("UNCHECKED_CAST")
        server.tokenRequests[3].complete(SemanticTokens(listOf(0, 6, 1, 0, null) as List<Int>))
        awaitTrue("a null number is 0") { layer == listOf(StyledRange(6, 7, "sem-parameter")) }
    }

    @Test
    fun `closing the binding removes the stylesheet, the colours, the listeners and the waiting request`() {
        open(::tokenServer)
        val server = harness.server
        awaitTrue("asked after open") { server.tokenRequests.size == 1 }
        server.tokenRequests[0].complete(parameterAt(6))
        awaitTrue("shown") { layer == listOf(StyledRange(6, 7, "sem-parameter")) }
        assertEquals(listOf(semanticCss), harness.onFx { harness.editor.stylesheets.toList() })
        harness.onFx { harness.editor.edit(0, 0, "#") }
        assertEquals(1, delays.count)

        harness.onFx { harness.binding.close() }
        assertEquals(emptyList(), harness.onFx { harness.editor.stylesheets.toList() })
        assertEquals(emptyList<StyledRange>(), layer)
        assertEquals(0, delays.count, "the waiting request is dropped")
        assertEquals(0, harness.onFx { harness.editor.subscribed }, "no listener is left on the editor")
    }

    @Test
    fun `after the binding closed, a change of what the server offers touches nothing`() {
        open(serverWith(providerWithFull(false)))
        registerFull()
        awaitTrue("asked once registered") { harness.server.tokenRequests.size == 1 }
        harness.server.tokenRequests[0].complete(parameterAt(7))
        awaitTrue("shown") { layer == listOf(StyledRange(7, 8, "sem-parameter")) }
        harness.onFx { harness.binding.close() }
        val calls = harness.editor.overlayCalls[SEMANTIC_LAYER]

        harness.server.client
            .unregisterCapability(UnregistrationParams(listOf(Unregistration("t", full))))
            .get()
        harness.serverIdle()
        assertEquals(calls, harness.editor.overlayCalls[SEMANTIC_LAYER])
    }

    @Test
    fun `an answer the JSON gives a null data array is no colours`() {
        open({ tokenServer().apply { serializeNulls = true } })
        val server = harness.server
        awaitTrue("asked after open") { server.tokenRequests.size == 1 }
        server.tokenRequests[0].complete(parameterAt(6))
        awaitTrue("shown") { layer == listOf(StyledRange(6, 7, "sem-parameter")) }

        server.client.refreshSemanticTokens()
        awaitTrue("asked on refresh") { server.tokenRequests.size == 2 }
        val withoutData = SemanticTokens(emptyList())
        SemanticTokens::class.java
            .getDeclaredField("data")
            .apply { isAccessible = true }
            .set(withoutData, null)
        server.tokenRequests[1].complete(withoutData)
        awaitTrue("no data, no colours") { layer == emptyList<StyledRange>() }
    }
}
