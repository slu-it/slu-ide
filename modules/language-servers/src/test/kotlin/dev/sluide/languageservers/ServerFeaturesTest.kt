package dev.sluide.languageservers

import dev.sluide.languageservers.connection.protocolGson
import org.eclipse.lsp4j.ExecuteCommandOptions
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RenameOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.WorkspaceSymbolOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The methods a server's static capabilities declare, where an option of another method's provider decides. */
class ServerFeaturesTest {
    private fun supported(capabilities: ServerCapabilities): List<Boolean> =
        ServerFeatures().apply { initialized(capabilities) }.let { features ->
            listOf("workspace/symbol", "workspaceSymbol/resolve", "textDocument/rename", "textDocument/prepareRename")
                .map(features::supports)
        }

    @Test
    fun `workspace symbols resolve and rename prepares only when their provider's options say so`() {
        val plain =
            ServerCapabilities().apply {
                workspaceSymbolProvider = Either.forLeft(true)
                renameProvider = Either.forLeft(true)
            }
        assertEquals(listOf(true, false, true, false), supported(plain))

        val resolving =
            ServerCapabilities().apply {
                workspaceSymbolProvider = Either.forRight(WorkspaceSymbolOptions(true))
                renameProvider = Either.forRight(RenameOptions(true))
            }
        assertEquals(listOf(true, true, true, true), supported(resolving))
    }

    /** A registration as the connection hands it over: its options as the server's JSON tree. */
    private fun registration(
        id: String,
        options: Any?,
        method: String = "workspace/executeCommand",
    ) = Registration(id, method, options?.let(protocolGson::toJsonTree))

    private fun features(commands: List<String>? = null) =
        ServerFeatures().apply {
            initialized(
                ServerCapabilities().apply {
                    commands?.let { executeCommandProvider = ExecuteCommandOptions(it) }
                },
            )
        }

    @Test
    fun `a command is offered when the server declares it or registers it for workspace-executeCommand`() {
        val features = features(listOf("java.known"))
        features.register(listOf(registration("r", ExecuteCommandOptions(listOf("x.dynamic")))))

        assertTrue(features.offersCommand("java.known"))
        assertTrue(features.offersCommand("x.dynamic"))
        assertFalse(features.offersCommand("x.other"))
        assertFalse(features.offersCommand(""))
    }

    @Test
    fun `no command is offered by a server that declares and registers none, or while no server runs`() {
        assertFalse(features().offersCommand("x.run"))
        assertFalse(ServerFeatures().offersCommand("x.run"))
    }

    @Test
    fun `a registration of another method or one that is unregistered offers nothing`() {
        val features = features()
        features.register(
            listOf(
                registration("a", ExecuteCommandOptions(listOf("x.other")), method = "textDocument/rename"),
                registration("b", ExecuteCommandOptions(listOf("x.gone"))),
            ),
        )
        features.unregister(listOf(org.eclipse.lsp4j.Unregistration("b", "workspace/executeCommand")))

        assertFalse(features.offersCommand("x.other"))
        assertFalse(features.offersCommand("x.gone"))
    }

    @Test
    fun `a registration of commands the server wrote oddly is rejected as a whole, the others still count`() {
        val features = features()
        val odd =
            listOf(
                mapOf("commands" to mapOf("x.odd" to 1)),
                mapOf("commands" to listOf(mapOf("x.odd" to 1), "x.odd")),
                mapOf("other" to listOf("x.odd")),
            )
        features.register(odd.mapIndexed { index, options -> registration("odd$index", options) })
        features.register(listOf(registration("ok", ExecuteCommandOptions(listOf("x.ok")))))
        features.register(listOf(Registration("none", "workspace/executeCommand", null)))

        assertTrue(features.offersCommand("x.ok"))
        assertFalse(features.offersCommand("x.odd"))
    }
}
