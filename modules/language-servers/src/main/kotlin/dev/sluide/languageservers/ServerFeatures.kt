package dev.sluide.languageservers

import com.google.gson.JsonElement
import dev.sluide.languageservers.connection.protocolGson
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentChangeRegistrationOptions
import org.eclipse.lsp4j.TextDocumentSaveRegistrationOptions
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.ConcurrentHashMap

private val log = System.getLogger(ServerFeatures::class.java.name)

/**
 * What a running server offers: the capabilities of its `initialize` answer merged with what it registered
 * dynamically since (`client/registerCapability`; pull diagnostics and file watchers often come only that way). One per
 * connection: a restarted server starts with new features. Readable from any thread.
 */
class ServerFeatures internal constructor() {
    @Volatile
    private var declared: ServerCapabilities? = null

    /** The server's static capabilities from its `initialize` answer; `null` until then and while no server runs. */
    val capabilities: ServerCapabilities? get() = declared

    private val registered = ConcurrentHashMap<String, Registration>()

    /**
     * Whether the server answers [method] (`textDocument/definition`, `textDocument/diagnostic`,
     * `completionItem/resolve`, `workspace/didChangeWatchedFiles`…): declared at `initialize` or registered since.
     */
    fun supports(method: String): Boolean = registered.values.any { it.method == method } || declares(method)

    /** The dynamic registrations of [method], with their options (Gson's JSON tree, as the server sent them). */
    fun registrations(method: String): List<Registration> = registered.values.filter { it.method == method }

    /** The file watchers registered for `workspace/didChangeWatchedFiles`: what the server wants to hear about. */
    fun fileWatchers(): List<FileSystemWatcher> =
        registrations(DID_CHANGE_WATCHED_FILES).flatMap { registration ->
            registration.options(DidChangeWatchedFilesRegistrationOptions::class.java)?.watchers.orEmpty()
        }

    /**
     * How the server wants document changes (`didChange`): a dynamic registration of `textDocument/didChange` wins
     * over the static `textDocumentSync` of the `initialize` answer; nothing declared is [TextDocumentSyncKind.None].
     */
    fun textDocumentSyncKind(): TextDocumentSyncKind {
        registrations(DID_CHANGE).lastOrNull()?.let { registration ->
            return registration.options(TextDocumentChangeRegistrationOptions::class.java)?.syncKind
                ?: TextDocumentSyncKind.None
        }
        val sync = capabilities?.textDocumentSync ?: return TextDocumentSyncKind.None
        val kind = if (sync.isLeft) sync.left else sync.right?.change
        return kind ?: TextDocumentSyncKind.None
    }

    /**
     * Whether and how the server wants `didSave`: `null` if not at all, else with or without the text. A dynamic
     * registration of `textDocument/didSave` wins over the static `textDocumentSync`, whose old form (a bare sync kind
     * other than `None`) counts as `didSave` without the text, as VS Code reads it.
     */
    fun saveOptions(): SaveOptions? {
        registrations(DID_SAVE).lastOrNull()?.let { registration ->
            val options = registration.options(TextDocumentSaveRegistrationOptions::class.java)
            return SaveOptions(options?.includeText ?: false)
        }
        val sync = capabilities?.textDocumentSync ?: return null
        if (sync.isLeft) {
            return SaveOptions(
                false,
            ).takeIf { sync.left != null && sync.left != TextDocumentSyncKind.None }
        }
        val save = sync.right?.save ?: return null
        return if (save.isLeft) SaveOptions(false).takeIf { save.left == true } else save.right ?: SaveOptions(false)
    }

    /** The options of this registration read as [type]; `null` without options or if unreadable (logged). */
    private fun <T> Registration.options(type: Class<T>): T? {
        val options = registerOptions as? JsonElement ?: return null
        return try {
            protocolGson.fromJson(options, type)
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "Unreadable options of registration $id ($method): $e")
            null
        }
    }

    internal fun initialized(capabilities: ServerCapabilities?) {
        declared = capabilities
    }

    internal fun register(registrations: List<Registration>) = registrations.forEach { registered[it.id] = it }

    internal fun unregister(unregistrations: List<Unregistration>) =
        unregistrations.forEach { registered.remove(it.id) }

    private fun declares(method: String): Boolean {
        val caps = capabilities ?: return false
        return when (method) {
            "textDocument/definition" -> {
                caps.definitionProvider.on()
            }

            "textDocument/declaration" -> {
                caps.declarationProvider.on()
            }

            "textDocument/typeDefinition" -> {
                caps.typeDefinitionProvider.on()
            }

            "textDocument/implementation" -> {
                caps.implementationProvider.on()
            }

            "textDocument/references" -> {
                caps.referencesProvider.on()
            }

            "textDocument/documentHighlight" -> {
                caps.documentHighlightProvider.on()
            }

            "textDocument/hover" -> {
                caps.hoverProvider.on()
            }

            "textDocument/completion" -> {
                caps.completionProvider != null
            }

            "completionItem/resolve" -> {
                caps.completionProvider?.resolveProvider == true
            }

            "textDocument/signatureHelp" -> {
                caps.signatureHelpProvider != null
            }

            "textDocument/codeAction" -> {
                caps.codeActionProvider.on()
            }

            "codeAction/resolve" -> {
                caps.codeActionProvider?.right?.resolveProvider == true
            }

            "textDocument/rename" -> {
                caps.renameProvider.on()
            }

            "textDocument/prepareRename" -> {
                caps.renameProvider?.right?.prepareProvider == true
            }

            "textDocument/documentSymbol" -> {
                caps.documentSymbolProvider.on()
            }

            "workspace/symbol" -> {
                caps.workspaceSymbolProvider.on()
            }

            "textDocument/semanticTokens/full" -> {
                caps.semanticTokensProvider?.full.on()
            }

            "textDocument/semanticTokens/full/delta" -> {
                caps.semanticTokensProvider
                    ?.full
                    ?.right
                    ?.delta == true
            }

            "textDocument/semanticTokens/range" -> {
                caps.semanticTokensProvider?.range.on()
            }

            "textDocument/diagnostic" -> {
                caps.diagnosticProvider != null
            }

            "textDocument/inlayHint" -> {
                caps.inlayHintProvider.on()
            }

            "textDocument/formatting" -> {
                caps.documentFormattingProvider.on()
            }

            else -> {
                false
            }
        }
    }

    internal companion object {
        const val DID_CHANGE_WATCHED_FILES = "workspace/didChangeWatchedFiles"
        const val DID_CHANGE = "textDocument/didChange"
        const val DID_SAVE = "textDocument/didSave"

        /** While no server runs: supports nothing. */
        val NONE = ServerFeatures()
    }
}

private fun Either<Boolean, *>?.on(): Boolean = this != null && (isRight || left == true)
