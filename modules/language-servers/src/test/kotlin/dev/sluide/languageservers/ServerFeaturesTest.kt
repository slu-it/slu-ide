package dev.sluide.languageservers

import org.eclipse.lsp4j.RenameOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.WorkspaceSymbolOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
