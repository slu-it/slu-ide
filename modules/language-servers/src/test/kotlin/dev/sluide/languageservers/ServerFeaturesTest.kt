package dev.sluide.languageservers

import dev.sluide.languageservers.connection.protocolGson
import org.eclipse.lsp4j.CodeActionOptions
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.DefinitionOptions
import org.eclipse.lsp4j.DiagnosticRegistrationOptions
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.DocumentFormattingOptions
import org.eclipse.lsp4j.DocumentHighlightOptions
import org.eclipse.lsp4j.DocumentSymbolOptions
import org.eclipse.lsp4j.ExecuteCommandOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.HoverOptions
import org.eclipse.lsp4j.InlayHintRegistrationOptions
import org.eclipse.lsp4j.ReferenceOptions
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RenameOptions
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensServerFull
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.TextDocumentChangeRegistrationOptions
import org.eclipse.lsp4j.TextDocumentSaveRegistrationOptions
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.WorkspaceSymbolOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What a server offers: its static capabilities and its registrations, where an option of a provider decides. */
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
        features.unregister(listOf(Unregistration("b", "workspace/executeCommand")))

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredCases")
    fun `a method is declared by its own provider and no other method is`(case: DeclaredCase) {
        val features = ServerFeatures().apply { initialized(case.capabilities) }

        assertEquals(case.supported, METHODS.filter(features::supports))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredCases")
    fun `a method name that only has the hash of a declared method is not supported`(case: DeclaredCase) {
        val features = ServerFeatures().apply { initialized(case.capabilities) }

        case.supported.forEach { method ->
            // Two neighbouring characters moved by +1 and -31 keep the String hash: a lookup by hash alone says yes.
            val twin = "${method[0] + 1}${method[1] - 31}${method.substring(2)}"
            assertEquals(method.hashCode(), twin.hashCode())
            assertFalse(features.supports(twin), twin)
        }
    }

    @Test
    fun `providers declared as false, a method nobody knows and a missing server declare nothing`() {
        val declinedEverything =
            ServerCapabilities().apply {
                definitionProvider = Either.forLeft(false)
                declarationProvider = Either.forLeft(false)
                typeDefinitionProvider = Either.forLeft(false)
                implementationProvider = Either.forLeft(false)
                referencesProvider = Either.forLeft(false)
                documentHighlightProvider = Either.forLeft(false)
                hoverProvider = Either.forLeft(false)
                codeActionProvider = Either.forLeft(false)
                renameProvider = Either.forLeft(false)
                documentSymbolProvider = Either.forLeft(false)
                workspaceSymbolProvider = Either.forLeft(false)
                inlayHintProvider = Either.forLeft(false)
                documentFormattingProvider = Either.forLeft(false)
                semanticTokensProvider =
                    semantic {
                        full = Either.forLeft(false)
                        range = Either.forLeft(false)
                    }
            }
        val features = ServerFeatures().apply { initialized(declinedEverything) }

        assertEquals(emptyList(), METHODS.filter(features::supports))
        assertFalse(features.supports("textDocument/unknown"))
        assertEquals(emptyList(), METHODS.filter(ServerFeatures()::supports))
    }

    @Test
    fun `a registered method is supported until it is unregistered, whatever the server declared`() {
        val features = features()
        features.register(listOf(Registration("d", "textDocument/diagnostic", null)))

        assertTrue(features.supports("textDocument/diagnostic"))
        assertFalse(features.supports("textDocument/hover"))
        assertEquals(listOf("d"), features.registrations("textDocument/diagnostic").map { it.id })
        assertEquals(emptyList(), features.registrations("textDocument/hover").map { it.id })

        features.unregister(listOf(Unregistration("d", "textDocument/diagnostic")))
        assertFalse(features.supports("textDocument/diagnostic"))
    }

    @Test
    fun `the capabilities are the ones of the initialize answer, none before it and none once cleared`() {
        val features = ServerFeatures()
        assertNull(features.capabilities)
        val declared = ServerCapabilities()
        features.initialized(declared)
        assertSame(declared, features.capabilities)
        features.initialized(null)
        assertNull(features.capabilities)
    }

    @Test
    fun `file watchers are the ones of the workspace-didChangeWatchedFiles registrations only`() {
        val features = features()

        fun watchers(vararg globs: String) =
            DidChangeWatchedFilesRegistrationOptions(globs.map { FileSystemWatcher(Either.forLeft(it)) })
        features.register(
            listOf(
                registration("a", watchers("**/*.kt"), "workspace/didChangeWatchedFiles"),
                registration("b", watchers("**/*.java"), "workspace/didChangeWatchedFiles"),
                registration("c", watchers("**/*.other"), "textDocument/rename"),
                Registration("d", "workspace/didChangeWatchedFiles", null),
                Registration("e", "workspace/didChangeWatchedFiles", "not options"),
            ),
        )

        assertEquals(
            listOf("**/*.java", "**/*.kt"),
            features.fileWatchers().map { it.globPattern.left }.sorted(),
        )
        assertEquals(emptyList(), ServerFeatures().fileWatchers())
    }

    private fun syncKind(
        sync: Either<TextDocumentSyncKind, TextDocumentSyncOptions>?,
        vararg registrations: Registration,
    ): TextDocumentSyncKind =
        ServerFeatures()
            .apply {
                initialized(ServerCapabilities().apply { textDocumentSync = sync })
                register(registrations.toList())
            }.textDocumentSyncKind()

    private fun didChange(kind: TextDocumentSyncKind) =
        registration("c", TextDocumentChangeRegistrationOptions(kind), "textDocument/didChange")

    @Test
    fun `the sync kind is the static one, or none if the server declares nothing readable`() {
        assertEquals(TextDocumentSyncKind.None, ServerFeatures().textDocumentSyncKind())
        assertEquals(TextDocumentSyncKind.None, syncKind(null))
        assertEquals(TextDocumentSyncKind.Full, syncKind(Either.forLeft(TextDocumentSyncKind.Full)))
        assertEquals(TextDocumentSyncKind.Incremental, syncKind(Either.forLeft(TextDocumentSyncKind.Incremental)))
        assertEquals(TextDocumentSyncKind.None, syncKind(Either.forLeft(null)))
        assertEquals(
            TextDocumentSyncKind.Incremental,
            syncKind(Either.forRight(TextDocumentSyncOptions().apply { change = TextDocumentSyncKind.Incremental })),
        )
        assertEquals(TextDocumentSyncKind.None, syncKind(Either.forRight(TextDocumentSyncOptions())))
        assertEquals(TextDocumentSyncKind.None, syncKind(Either.forRight(null)))
    }

    @Test
    fun `a registration of textDocument-didChange decides the sync kind over the static one`() {
        val incremental =
            Either.forLeft<TextDocumentSyncKind, TextDocumentSyncOptions>(
                TextDocumentSyncKind.Incremental,
            )

        assertEquals(TextDocumentSyncKind.Full, syncKind(incremental, didChange(TextDocumentSyncKind.Full)))
        assertEquals(TextDocumentSyncKind.Incremental, syncKind(null, didChange(TextDocumentSyncKind.Incremental)))
        assertEquals(
            TextDocumentSyncKind.None,
            syncKind(incremental, registration("c", emptyMap<String, Any>(), "textDocument/didChange")),
        )
        assertEquals(
            TextDocumentSyncKind.None,
            syncKind(incremental, Registration("c", "textDocument/didChange", null)),
        )
        assertEquals(
            TextDocumentSyncKind.None,
            syncKind(incremental, Registration("c", "textDocument/didChange", "unreadable")),
        )
        assertEquals(
            TextDocumentSyncKind.Incremental,
            syncKind(incremental, registration("o", TextDocumentChangeRegistrationOptions(), "textDocument/didSave")),
        )
    }

    private fun saveOptions(
        sync: Either<TextDocumentSyncKind, TextDocumentSyncOptions>?,
        vararg registrations: Registration,
    ): SaveOptions? =
        ServerFeatures()
            .apply {
                initialized(ServerCapabilities().apply { textDocumentSync = sync })
                register(registrations.toList())
            }.saveOptions()

    private fun saves(save: Either<Boolean, SaveOptions>?) =
        Either.forRight<TextDocumentSyncKind, TextDocumentSyncOptions>(
            TextDocumentSyncOptions().apply { this.save = save },
        )

    @Test
    fun `didSave is wanted when the static sync says so, with the text only if it asks for it`() {
        assertNull(ServerFeatures().saveOptions())
        assertNull(saveOptions(null))
        assertEquals(SaveOptions(false), saveOptions(Either.forLeft(TextDocumentSyncKind.Full)))
        assertEquals(SaveOptions(false), saveOptions(Either.forLeft(TextDocumentSyncKind.Incremental)))
        assertNull(saveOptions(Either.forLeft(TextDocumentSyncKind.None)))
        assertNull(saveOptions(Either.forLeft(null)))
        assertNull(saveOptions(Either.forRight(null)))
        assertNull(saveOptions(Either.forRight(TextDocumentSyncOptions())))
        assertEquals(SaveOptions(false), saveOptions(saves(Either.forLeft(true))))
        assertNull(saveOptions(saves(Either.forLeft(false))))
        assertEquals(SaveOptions(true), saveOptions(saves(Either.forRight(SaveOptions(true)))))
        assertEquals(SaveOptions(false), saveOptions(saves(Either.forRight(SaveOptions(false)))))
        assertEquals(SaveOptions(false), saveOptions(saves(Either.forRight(null))))
    }

    @Test
    fun `a registration of textDocument-didSave decides over the static sync, the text if its options say so`() {
        fun didSave(options: Any?) = registration("s", options, "textDocument/didSave")
        val nothing = saves(Either.forLeft(false))

        assertEquals(SaveOptions(true), saveOptions(nothing, didSave(TextDocumentSaveRegistrationOptions(true))))
        assertEquals(SaveOptions(false), saveOptions(nothing, didSave(TextDocumentSaveRegistrationOptions(false))))
        assertEquals(SaveOptions(false), saveOptions(nothing, didSave(TextDocumentSaveRegistrationOptions())))
        assertEquals(SaveOptions(false), saveOptions(null, didSave(null)))
        assertEquals(SaveOptions(false), saveOptions(null, Registration("s", "textDocument/didSave", "unreadable")))
        assertEquals(
            SaveOptions(false),
            saveOptions(saves(Either.forRight(SaveOptions(true))), didSave(TextDocumentSaveRegistrationOptions(false))),
        )
        assertNull(saveOptions(nothing, registration("o", TextDocumentSaveRegistrationOptions(true), "x")))
    }

    @Test
    fun `options a server wrote unreadably are logged as a warning naming the registration and its method`() {
        LogRecords(Logger.getLogger(ServerFeatures::class.java.name)).use { logs ->
            val features = features()
            features.register(listOf(Registration("odd-1", "workspace/executeCommand", protocolGson.toJsonTree(1))))

            assertFalse(features.offersCommand("x"))

            val warnings = logs.messages(Level.WARNING)
            assertEquals(1, warnings.size)
            assertTrue(
                warnings.single().startsWith("Unreadable options of registration odd-1 (workspace/executeCommand): "),
                warnings.single(),
            )
        }
    }

    companion object {
        /** Every method [ServerFeatures] maps to a capability. */
        private val METHODS =
            listOf(
                "textDocument/definition",
                "textDocument/declaration",
                "textDocument/typeDefinition",
                "textDocument/implementation",
                "textDocument/references",
                "textDocument/documentHighlight",
                "textDocument/hover",
                "textDocument/completion",
                "completionItem/resolve",
                "textDocument/signatureHelp",
                "textDocument/codeAction",
                "codeAction/resolve",
                "textDocument/rename",
                "textDocument/prepareRename",
                "textDocument/documentSymbol",
                "workspace/symbol",
                "workspaceSymbol/resolve",
                "textDocument/semanticTokens/full",
                "textDocument/semanticTokens/full/delta",
                "textDocument/semanticTokens/range",
                "textDocument/diagnostic",
                "textDocument/inlayHint",
                "textDocument/formatting",
            )

        /** Capabilities and the methods of [METHODS] they declare, no others. */
        class DeclaredCase(
            val name: String,
            val supported: List<String>,
            val capabilities: ServerCapabilities,
        ) {
            override fun toString() = name
        }

        private fun case(
            name: String,
            vararg supported: String,
            declare: ServerCapabilities.() -> Unit,
        ) = DeclaredCase(name, METHODS.filter { it in supported }, ServerCapabilities().apply(declare))

        private fun semantic(configure: SemanticTokensWithRegistrationOptions.() -> Unit) =
            SemanticTokensWithRegistrationOptions(SemanticTokensLegend()).apply(configure)

        @JvmStatic
        fun declaredCases(): List<DeclaredCase> =
            listOf(
                case("definition as true", "textDocument/definition") { definitionProvider = Either.forLeft(true) },
                case("definition as options", "textDocument/definition") {
                    definitionProvider = Either.forRight(DefinitionOptions())
                },
                case("declaration", "textDocument/declaration") { declarationProvider = Either.forLeft(true) },
                case("typeDefinition", "textDocument/typeDefinition") { typeDefinitionProvider = Either.forLeft(true) },
                case("implementation", "textDocument/implementation") {
                    implementationProvider = Either.forLeft(true)
                },
                case("references as true", "textDocument/references") { referencesProvider = Either.forLeft(true) },
                case("references as options", "textDocument/references") {
                    referencesProvider = Either.forRight(ReferenceOptions())
                },
                case("documentHighlight", "textDocument/documentHighlight") {
                    documentHighlightProvider = Either.forRight(DocumentHighlightOptions())
                },
                case("hover", "textDocument/hover") { hoverProvider = Either.forRight(HoverOptions()) },
                case("completion", "textDocument/completion") { completionProvider = CompletionOptions() },
                case("completion that resolves", "textDocument/completion", "completionItem/resolve") {
                    completionProvider = CompletionOptions(true, listOf("."))
                },
                case("completion that does not resolve", "textDocument/completion") {
                    completionProvider = CompletionOptions(false, listOf("."))
                },
                case("signatureHelp", "textDocument/signatureHelp") { signatureHelpProvider = SignatureHelpOptions() },
                case("codeAction as true", "textDocument/codeAction") { codeActionProvider = Either.forLeft(true) },
                case("codeAction as options", "textDocument/codeAction") {
                    codeActionProvider = Either.forRight(CodeActionOptions())
                },
                case("codeAction that resolves", "textDocument/codeAction", "codeAction/resolve") {
                    codeActionProvider = Either.forRight(CodeActionOptions().apply { resolveProvider = true })
                },
                case("codeAction that does not resolve", "textDocument/codeAction") {
                    codeActionProvider = Either.forRight(CodeActionOptions().apply { resolveProvider = false })
                },
                case("rename that does not prepare", "textDocument/rename") {
                    renameProvider = Either.forRight(RenameOptions(false))
                },
                case("rename that prepares", "textDocument/rename", "textDocument/prepareRename") {
                    renameProvider = Either.forRight(RenameOptions(true))
                },
                case("documentSymbol as true", "textDocument/documentSymbol") {
                    documentSymbolProvider = Either.forLeft(true)
                },
                case("documentSymbol as options", "textDocument/documentSymbol") {
                    documentSymbolProvider = Either.forRight(DocumentSymbolOptions())
                },
                case("workspace symbol that does not resolve", "workspace/symbol") {
                    workspaceSymbolProvider = Either.forRight(WorkspaceSymbolOptions(false))
                },
                case("workspace symbol that resolves", "workspace/symbol", "workspaceSymbol/resolve") {
                    workspaceSymbolProvider = Either.forRight(WorkspaceSymbolOptions(true))
                },
                case("semantic tokens without full or range") { semanticTokensProvider = semantic { } },
                case("semantic tokens full as true", "textDocument/semanticTokens/full") {
                    semanticTokensProvider = semantic { full = Either.forLeft(true) }
                },
                case(
                    "semantic tokens full with delta",
                    "textDocument/semanticTokens/full",
                    "textDocument/semanticTokens/full/delta",
                ) {
                    semanticTokensProvider = semantic { full = Either.forRight(SemanticTokensServerFull(true)) }
                },
                case("semantic tokens full without delta", "textDocument/semanticTokens/full") {
                    semanticTokensProvider = semantic { full = Either.forRight(SemanticTokensServerFull(false)) }
                },
                case("semantic tokens range as true", "textDocument/semanticTokens/range") {
                    semanticTokensProvider = semantic { range = Either.forLeft(true) }
                },
                case("semantic tokens range as options", "textDocument/semanticTokens/range") {
                    semanticTokensProvider = semantic { range = Either.forRight(Any()) }
                },
                case("diagnostic", "textDocument/diagnostic") { diagnosticProvider = DiagnosticRegistrationOptions() },
                case("inlayHint as true", "textDocument/inlayHint") { inlayHintProvider = Either.forLeft(true) },
                case("inlayHint as options", "textDocument/inlayHint") {
                    inlayHintProvider = Either.forRight(InlayHintRegistrationOptions())
                },
                case("formatting as true", "textDocument/formatting") {
                    documentFormattingProvider = Either.forLeft(true)
                },
                case("formatting as options", "textDocument/formatting") {
                    documentFormattingProvider = Either.forRight(DocumentFormattingOptions())
                },
            )
    }
}
