package dev.sluide.codeintelligence.diagnostics

import dev.sluide.languageservers.FakeServer
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.DocumentDiagnosticParams
import org.eclipse.lsp4j.DocumentDiagnosticReport
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake server for the diagnostics, quick fixes and semantic tokens: it syncs incrementally as ty does, keeps the
 * pull and token requests for the test to answer, answers code actions with [codeActions] and resolves them with
 * [resolve]; `executeCommand` and `codeAction/resolve` are recorded in [calls] too.
 */
internal open class LspTestServer(
    configure: ServerCapabilities.() -> Unit = {},
) : FakeServer() {
    val pulls = CopyOnWriteArrayList<Pair<DocumentDiagnosticParams, CompletableFuture<DocumentDiagnosticReport>>>()
    val tokenRequests = CopyOnWriteArrayList<CompletableFuture<SemanticTokens>>()
    val codeActionRequests = CopyOnWriteArrayList<CodeActionParams>()
    val commands = CopyOnWriteArrayList<ExecuteCommandParams>()

    @Volatile
    var codeActions: List<Either<Command, CodeAction>> = emptyList()

    /** What `codeAction/resolve` answers for an action. */
    @Volatile
    var resolve: (CodeAction) -> CodeAction? = { it }

    init {
        capabilities =
            ServerCapabilities().apply {
                textDocumentSync =
                    Either.forRight(
                        TextDocumentSyncOptions().apply {
                            openClose = true
                            change = TextDocumentSyncKind.Incremental
                            save = Either.forRight(SaveOptions(false))
                        },
                    )
                configure()
            }
    }

    override fun diagnostic(params: DocumentDiagnosticParams): CompletableFuture<DocumentDiagnosticReport> =
        CompletableFuture<DocumentDiagnosticReport>().also { pulls += params to it }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> =
        CompletableFuture<SemanticTokens>().also { tokenRequests += it }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        codeActionRequests += params
        return CompletableFuture.completedFuture(codeActions)
    }

    override fun resolveCodeAction(unresolved: CodeAction): CompletableFuture<CodeAction> {
        calls += "resolveCodeAction"
        return CompletableFuture.completedFuture(resolve(unresolved))
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        calls += "executeCommand ${params.command}"
        commands += params
        return CompletableFuture.completedFuture(null)
    }
}
