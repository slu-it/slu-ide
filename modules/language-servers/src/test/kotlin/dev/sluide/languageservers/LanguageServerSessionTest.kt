package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticRegistrationOptions
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LanguageServerSessionTest {
    @TempDir
    lateinit var folder: Path

    private val starter = FakeStarter()
    private val dispatched = AtomicInteger()
    private var servers: LanguageServers? = null

    @AfterEach
    fun closeServers() {
        servers?.close()
    }

    private fun session(
        spec: TestSpec = TestSpec(),
        timing: ServerTiming = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
    ): LanguageServerSession {
        val created =
            LanguageServers(
                listOf(spec),
                { folder },
                { _, _ -> true },
                { runnable ->
                    dispatched.incrementAndGet()
                    runnable.run()
                },
                starter,
                FakeScheduler(),
                timing,
                System::nanoTime,
            )
        servers = created
        return created.session(spec)
    }

    private fun running(session: LanguageServerSession): FakeServer {
        session.ensureStarted()
        awaitTrue("running") { session.state.value == ServerState.Running }
        return starter.last.server
    }

    @Test
    fun `initializes, then sends initialized, declaring the project folder and the capabilities the features use`() {
        val server = running(session())

        assertEquals(listOf("initialize", "initialized"), server.calls.take(2))
        val params = server.initializeParams!!
        @Suppress("DEPRECATION")
        assertEquals(LspUris.of(folder), params.rootUri)
        assertEquals(listOf(LspUris.of(folder)), params.workspaceFolders.map { it.uri })
        val capabilities = params.capabilities
        assertEquals(listOf("utf-16"), capabilities.general.positionEncodings)
        assertTrue(capabilities.window.workDoneProgress)
        assertTrue(capabilities.workspace.configuration)
        assertTrue(capabilities.workspace.didChangeWatchedFiles.dynamicRegistration)
        assertTrue(capabilities.workspace.diagnostics.refreshSupport)
        assertTrue(capabilities.workspace.semanticTokens.refreshSupport)
        val text = capabilities.textDocument
        assertTrue(text.diagnostic.dynamicRegistration)
        assertTrue(text.synchronization.didSave)
        assertFalse(text.completion.completionItem.snippetSupport)
        assertEquals(listOf(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT), text.hover.contentFormat)
        assertTrue(text.rename.prepareSupport)
        assertTrue(text.documentSymbol.hierarchicalDocumentSymbolSupport)
        assertEquals(listOf("relative"), text.semanticTokens.formats)
        assertTrue("parameter" in text.semanticTokens.tokenTypes)
        assertTrue("defaultLibrary" in text.semanticTokens.tokenModifiers)
        assertEquals(listOf("edit"), text.codeAction.resolveSupport.properties)
    }

    @Test
    fun `answers workspace configuration item by item from the spec`() {
        val spec =
            TestSpec(config = { item ->
                if (item.section ==
                    "ty"
                ) {
                    mapOf("diagnosticMode" to "workspace")
                } else {
                    emptyMap<String, Any>()
                }
            })
        val server = running(session(spec))

        val answer =
            server.client
                .configuration(
                    ConfigurationParams(
                        listOf(
                            ConfigurationItem().apply { section = "ty" },
                            ConfigurationItem().apply {
                                section =
                                    "other"
                            },
                        ),
                    ),
                ).get(5, TimeUnit.SECONDS)

        assertEquals(listOf("""{"diagnosticMode":"workspace"}""", "{}"), answer.map { it.toString() })
    }

    @Test
    fun `dynamic registrations of pull diagnostics and file watchers count as supported until unregistered`() {
        val session = session()
        val server = running(session)
        val changes = AtomicInteger()
        session.onFeaturesChanged { changes.incrementAndGet() }
        assertFalse(session.features.supports("textDocument/diagnostic"))

        server.client
            .registerCapability(
                RegistrationParams(
                    listOf(
                        Registration("d", "textDocument/diagnostic", DiagnosticRegistrationOptions()),
                        Registration(
                            "w",
                            "workspace/didChangeWatchedFiles",
                            DidChangeWatchedFilesRegistrationOptions(
                                listOf(FileSystemWatcher(Either.forLeft("**/*.py"))),
                            ),
                        ),
                    ),
                ),
            ).get(5, TimeUnit.SECONDS)

        assertTrue(session.features.supports("textDocument/diagnostic"))
        assertEquals(listOf("**/*.py"), session.features.fileWatchers().map { it.globPattern.left })
        assertEquals(1, changes.get())

        server.client
            .unregisterCapability(
                UnregistrationParams(listOf(Unregistration("d", "textDocument/diagnostic"))),
            ).get(5, TimeUnit.SECONDS)

        assertFalse(session.features.supports("textDocument/diagnostic"))
        assertTrue(session.features.supports("workspace/didChangeWatchedFiles"))
    }

    @Test
    fun `refresh requests are answered and reach the listeners of their kind`() {
        val session = session()
        val server = running(session)
        val heard = CopyOnWriteArrayList<RefreshKind>()
        RefreshKind.entries.forEach { kind -> session.onRefresh(kind) { heard += kind } }

        server.client.refreshDiagnostics().get(5, TimeUnit.SECONDS)
        server.client.refreshSemanticTokens().get(5, TimeUnit.SECONDS)
        server.client.refreshInlayHints().get(5, TimeUnit.SECONDS)

        assertEquals(listOf(RefreshKind.DIAGNOSTICS, RefreshKind.SEMANTIC_TOKENS, RefreshKind.INLAY_HINTS), heard)
    }

    @Test
    fun `diagnostics reach the listeners of their file, also under another form of its URI`() {
        val session = session()
        val server = running(session)
        val file = folder.resolve("da ta.py")
        val heard = CopyOnWriteArrayList<String>()
        session.onDiagnostics(file) { params ->
            heard.add(
                params.diagnostics
                    .single()
                    .message.left,
            )
        }

        server.client.publishDiagnostics(diagnostics(LspUris.of(folder.resolve("other.py")), "other"))
        // The short form `file:/` and an unnormalized path: still the same file.
        server.client.publishDiagnostics(diagnostics("file:$folder/./da%20ta.py", "mine"))

        awaitTrue("diagnostics of the file") { heard.isNotEmpty() }
        assertEquals(listOf("mine"), heard)
    }

    @Test
    fun `a request slot cancels the request sent before, on the server too, and delivers only the latest`() {
        val session = session()
        val server = running(session)
        val slot = RequestSlot()
        val results = CopyOnWriteArrayList<String>()

        slot.send(session, { it.textDocumentService.definition(definitionParams()) }, { results += "first" })
        awaitTrue("first request at the server") { server.definitions.size == 1 }
        slot.send(session, { it.textDocumentService.definition(definitionParams()) }, { results += "second" })
        awaitTrue("second request at the server") { server.definitions.size == 2 }

        awaitTrue("\$/cancelRequest of the first") { server.definitions[0].isCancelled }
        server.definitions[1].complete(Either.forLeft(listOf(Location(LspUris.of(folder.resolve("a.py")), range()))))
        awaitTrue("second result") { results.isNotEmpty() }
        assertEquals(listOf("second"), results)
    }

    @Test
    fun `a result that arrives after the session closed is dropped`() {
        // Shutdown is never answered, so the connection lives on for its timeout while the late result arrives.
        val session = session(timing = ServerTiming(shutdown = 3.seconds, exit = 200.milliseconds))
        val server = running(session)
        server.answerShutdown = false
        val results = CopyOnWriteArrayList<Any>()
        session.request({ it.textDocumentService.definition(definitionParams()) }, { results.add(it) })
        awaitTrue("request at the server") { server.definitions.size == 1 }

        servers!!.close()
        awaitTrue("shutdown sent") { "shutdown" in server.calls }
        val before = dispatched.get()
        server.definitions[0].complete(Either.forLeft(emptyList()))

        awaitTrue("late result handed to the FX thread") { dispatched.get() > before }
        assertEquals(emptyList(), results)
    }

    @Test
    fun `a request without a server gets not running`() {
        val session = session(TestSpec(command = null))
        val errors = CopyOnWriteArrayList<Throwable>()

        session.request({ it.textDocumentService.definition(definitionParams()) }, {}, { errors += it })

        awaitTrue("error") { errors.isNotEmpty() }
        assertIs<LanguageServerNotRunningException>(errors.single())
    }

    private fun diagnostics(
        uri: String,
        message: String,
    ) = PublishDiagnosticsParams(uri, listOf(Diagnostic(range(), message)))

    private fun definitionParams() =
        DefinitionParams(TextDocumentIdentifier(LspUris.of(folder.resolve("a.py"))), Position(0, 0))

    private fun range() = Range(Position(0, 0), Position(0, 1))
}
