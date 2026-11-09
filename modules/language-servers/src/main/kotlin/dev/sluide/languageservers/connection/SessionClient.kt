package dev.sluide.languageservers.connection

import com.google.gson.Gson
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.RefreshKind
import dev.sluide.languageservers.ServerFeatures
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.LogTraceParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowDocumentParams
import org.eclipse.lsp4j.ShowDocumentResult
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

private val log = System.getLogger("dev.sluide.languageservers.connection.SessionClient")

/** Longest server message logged. */
private const val MESSAGE_CHARS = 500

/** `window/logMessage` lines logged per connection; a chatty server must not fill the log. */
internal const val LOG_MESSAGES = 1_000

/** The level of a server's `window/logMessage`: its own log and info lines are debug output to Thoreau. */
internal fun logMessageLevel(type: MessageType?): System.Logger.Level =
    when (type) {
        MessageType.Error -> System.Logger.Level.WARNING
        MessageType.Warning -> System.Logger.Level.INFO
        else -> System.Logger.Level.DEBUG
    }

/** Gson configured as lsp4j's wire format: reads registration options into protocol types. */
internal val protocolGson: Gson = MessageJsonHandler(emptyMap()).gson

/** What the client hands on to its session: called on the connection's reader thread, must not block. */
internal interface ClientEvents {
    fun configuration(item: ConfigurationItem): Any?

    fun diagnostics(params: PublishDiagnosticsParams)

    fun refresh(kind: RefreshKind)

    fun featuresChanged()
}

/**
 * Thoreau's side of one connection: answers every server-to-client request the spikes saw (configuration,
 * dynamic registration, progress creation, refreshes) and hands notifications on to [events]. Runs on the reader
 * thread, so nothing here blocks.
 */
internal class SessionClient(
    private val name: String,
    /** The session's workspace folders now, the window folder first; a snapshot that never blocks. */
    private val folders: () -> List<Path>,
    private val features: ServerFeatures,
    private val events: ClientEvents,
) : LanguageClient {
    private val logMessages = AtomicInteger()

    override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any?>> =
        CompletableFuture.completedFuture(params.items.map(::configuration))

    /** The spec's answer to [item] as JSON; one it cannot give or that is no JSON value answers `null`. */
    private fun configuration(item: ConfigurationItem): Any? =
        try {
            jsonTree(events.configuration(item))
        } catch (e: RuntimeException) {
            log.log(
                System.Logger.Level.WARNING,
                "lsp $name: no configuration for section ${item.section}: ${e.message}",
            )
            null
        }

    override fun registerCapability(params: RegistrationParams): CompletableFuture<Void?> {
        log.log(System.Logger.Level.INFO, "lsp $name registers ${params.registrations.joinToString { it.method }}")
        features.register(params.registrations)
        events.featuresChanged()
        return CompletableFuture.completedFuture(null)
    }

    override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void?> {
        features.unregister(params.unregisterations)
        events.featuresChanged()
        return CompletableFuture.completedFuture(null)
    }

    override fun workspaceFolders(): CompletableFuture<List<WorkspaceFolder>> =
        CompletableFuture.completedFuture(folders().map(::workspaceFolder))

    override fun createProgress(params: WorkDoneProgressCreateParams): CompletableFuture<Void?> =
        CompletableFuture.completedFuture(null)

    override fun notifyProgress(params: ProgressParams) = Unit

    override fun refreshDiagnostics(): CompletableFuture<Void?> = refreshed(RefreshKind.DIAGNOSTICS)

    override fun refreshSemanticTokens(): CompletableFuture<Void?> = refreshed(RefreshKind.SEMANTIC_TOKENS)

    override fun refreshInlayHints(): CompletableFuture<Void?> = refreshed(RefreshKind.INLAY_HINTS)

    override fun refreshCodeLenses(): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

    override fun refreshInlineValues(): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

    override fun refreshFoldingRanges(): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) = events.diagnostics(diagnostics)

    override fun showMessage(messageParams: MessageParams) {
        val level =
            when (messageParams.type) {
                MessageType.Error, MessageType.Warning -> System.Logger.Level.WARNING
                else -> System.Logger.Level.INFO
            }
        log.log(level, "lsp $name says: ${messageParams.message.orEmpty().take(MESSAGE_CHARS)}")
    }

    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem?> {
        // No dialog yet: the request counts as dismissed.
        showMessage(MessageParams(requestParams.type, requestParams.message))
        return CompletableFuture.completedFuture(null)
    }

    override fun showDocument(params: ShowDocumentParams): CompletableFuture<ShowDocumentResult> =
        CompletableFuture.completedFuture(ShowDocumentResult(false))

    override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> =
        CompletableFuture.completedFuture(
            ApplyWorkspaceEditResponse(false).apply { failureReason = "Thoreau does not apply server edits yet" },
        )

    override fun logMessage(message: MessageParams) {
        val count = logMessages.incrementAndGet()
        if (count <= LOG_MESSAGES) {
            log.log(logMessageLevel(message.type), "lsp $name log: ${message.message.orEmpty().take(MESSAGE_CHARS)}")
        } else if (count == LOG_MESSAGES + 1) {
            log.log(System.Logger.Level.INFO, "lsp $name log: further messages not logged")
        }
    }

    override fun logTrace(params: LogTraceParams) = Unit

    override fun telemetryEvent(`object`: Any?) = Unit

    private fun refreshed(kind: RefreshKind): CompletableFuture<Void?> {
        events.refresh(kind)
        return CompletableFuture.completedFuture(null)
    }
}

internal fun workspaceFolder(root: Path) =
    WorkspaceFolder(
        LspUris.of(root),
        root.fileName?.toString() ?: root.toString(),
    )
