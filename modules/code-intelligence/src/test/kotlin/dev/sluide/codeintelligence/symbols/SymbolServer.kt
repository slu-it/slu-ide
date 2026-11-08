package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.syncingServer
import dev.sluide.languageservers.FakeServer
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameOptions
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolOptions
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake server that syncs as ty does and answers rename (if [renames], with `prepareRename` if [prepares]), document
 * and workspace symbols (if [symbols], resolving them if [resolves]) from what the test sets; it keeps the requests.
 */
internal class SymbolServer(
    prepares: Boolean = true,
    resolves: Boolean = false,
    renames: Boolean = true,
    symbols: Boolean = true,
) : FakeServer() {
    @Volatile
    var prepareAnswer: (PrepareRenameParams) -> Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>? =
        { null }

    @Volatile
    var renameAnswer: (RenameParams) -> WorkspaceEdit? = { null }

    @Volatile
    var documentSymbols: List<DocumentSymbol> = emptyList()

    @Volatile
    var workspaceSymbols: (String) -> List<WorkspaceSymbol> = { emptyList() }

    @Volatile
    var resolveAnswer: (WorkspaceSymbol) -> WorkspaceSymbol = { it }

    val prepareRequests = CopyOnWriteArrayList<PrepareRenameParams>()
    val renameRequests = CopyOnWriteArrayList<RenameParams>()
    val symbolQueries = CopyOnWriteArrayList<String>()
    val resolved = CopyOnWriteArrayList<WorkspaceSymbol>()

    init {
        capabilities =
            syncingServer().capabilities.apply {
                if (renames) renameProvider = Either.forRight(RenameOptions(prepares))
                if (symbols) {
                    documentSymbolProvider = Either.forLeft(true)
                    workspaceSymbolProvider = Either.forRight(WorkspaceSymbolOptions(resolves))
                }
            }
    }

    override fun prepareRename(params: PrepareRenameParams) =
        CompletableFuture.completedFuture(prepareAnswer(params)).also { prepareRequests += params }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
        renameRequests += params
        return CompletableFuture.completedFuture(renameAnswer(params))
    }

    override fun documentSymbol(params: DocumentSymbolParams) =
        CompletableFuture.completedFuture(
            documentSymbols.map { Either.forRight<SymbolInformation, DocumentSymbol>(it) },
        )

    override fun symbol(
        params: WorkspaceSymbolParams,
    ): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        symbolQueries += params.query
        return CompletableFuture.completedFuture(Either.forRight(workspaceSymbols(params.query)))
    }

    override fun resolveWorkspaceSymbol(workspaceSymbol: WorkspaceSymbol): CompletableFuture<WorkspaceSymbol> {
        resolved += workspaceSymbol
        return CompletableFuture.completedFuture(resolveAnswer(workspaceSymbol))
    }
}
