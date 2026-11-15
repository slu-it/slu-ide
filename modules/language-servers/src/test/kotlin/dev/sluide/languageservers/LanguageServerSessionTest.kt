package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticRegistrationOptions
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LanguageServerSessionTest {
    @TempDir
    lateinit var folder: Path

    private val starter = FakeStarter()
    private val scheduler = FakeScheduler()
    private val dispatched = AtomicInteger()

    /** Whether the current thread runs something the session dispatched: what the session hands to the FX thread. */
    private val inDispatch = ThreadLocal.withInitial { false }
    private var servers: LanguageServers? = null

    @AfterEach
    fun closeServers() {
        servers?.close()
    }

    private fun session(
        spec: TestSpec = TestSpec(),
        timing: ServerTiming = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
        projectFolder: () -> Path? = { folder },
        trustGate: TrustGate = TrustGate { _, _ -> true },
    ): LanguageServerSession {
        val created =
            LanguageServers(
                listOf(spec),
                projectFolder,
                trustGate,
                { runnable ->
                    dispatched.incrementAndGet()
                    inDispatch.set(true)
                    try {
                        runnable.run()
                    } finally {
                        inDispatch.set(false)
                    }
                },
                starter,
                scheduler,
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
        // Running follows sending `initialized`; the fake records it when it arrives on its own reader thread.
        awaitTrue("initialized received") { server.calls.size >= 2 }

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
        assertEquals(CompletionItemKind.entries, text.completion.completionItemKind.valueSet)
        assertEquals(SymbolKind.entries, text.documentSymbol.symbolKind.valueSet)
        assertEquals(SymbolKind.entries, capabilities.workspace.symbol.symbolKind.valueSet)
        assertTrue(text.implementation.linkSupport)
        assertTrue(capabilities.workspace.didChangeConfiguration.dynamicRegistration)
        assertEquals("Thoreau", params.clientInfo.name)
    }

    @Test
    fun `answers workspace configuration item by item from the spec, null for what is no JSON value`() {
        val answers =
            mapOf(
                "ty" to mapOf("diagnosticMode" to "workspace", "paths" to listOf("src", 1, true, null)),
                "empty" to emptyMap<String, Any>(),
                "jackson-like" to StringBuilder("not JSON"),
            )
        val server = running(session(TestSpec(config = { item -> answers.getValue(item.section) })))

        val answer =
            server.client
                .configuration(
                    ConfigurationParams(answers.keys.map(::section)),
                ).get(5, TimeUnit.SECONDS)

        assertEquals(
            listOf("""{"diagnosticMode":"workspace","paths":["src",1,true,null]}""", "{}", "null"),
            answer.map { it?.toString() ?: "null" },
        )
    }

    @Test
    fun `sends the spec's initialization options as JSON and leaves out what is no JSON value`() {
        val server = running(session(TestSpec(initOptions = mapOf("untrustedWorkspace" to false))))
        assertEquals("""{"untrustedWorkspace":false}""", server.initializationOptions())
        servers!!.close()

        val other = running(session(TestSpec(initOptions = Any())))

        assertEquals(null, other.initializationOptions())
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
    fun `pushes for files no editor listens to are kept as the spec says, the latest per file, until a restart`() {
        val session = session(TestSpec(keeps = { it.fileName.toString() == "tsconfig.json" }))
        val server = running(session)
        val config = folder.resolve("tsconfig.json")
        val shown = folder.resolve("shown/tsconfig.json")
        val fired = AtomicInteger()
        session.keptDiagnosticsChanges.addListener { fired.incrementAndGet() }
        val heard = CopyOnWriteArrayList<String>()
        session.onDiagnostics(shown) { params ->
            heard.add(
                params.diagnostics
                    .single()
                    .message.left,
            )
        }

        fun messages() = session.keptDiagnostics(config).map { it.message.left }

        server.client.publishDiagnostics(diagnostics(LspUris.of(config), "old"))
        // Another form of the same URI replaces it.
        server.client.publishDiagnostics(diagnostics("file:$folder/./tsconfig.json", "new"))
        server.client.publishDiagnostics(diagnostics(LspUris.of(folder.resolve("other.json")), "dropped"))
        // A file an editor listens to: its listener gets it, nothing is kept.
        server.client.publishDiagnostics(diagnostics(LspUris.of(shown), "shown"))
        // Pushes arrive in order on one reader thread (and are dispatched inline): all before it arrived.
        awaitTrue("the listened file's push") { heard.isNotEmpty() }

        assertEquals(listOf("new"), messages())
        assertEquals(emptyList(), session.keptDiagnostics(folder.resolve("other.json")))
        assertEquals(emptyList(), session.keptDiagnostics(shown))
        assertEquals(listOf("shown"), heard)
        assertEquals(2, fired.get())

        server.client.publishDiagnostics(PublishDiagnosticsParams(LspUris.of(config), emptyList()))
        awaitTrue("removed by an empty push") { messages().isEmpty() }
        server.client.publishDiagnostics(diagnostics(LspUris.of(config), "again"))
        awaitTrue("kept again") { messages() == listOf("again") }
        val before = fired.get()

        session.restart()
        awaitTrue("cleared by the restart") { messages().isEmpty() }
        assertEquals(before + 1, fired.get())
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
    fun `nothing arrives after the session closed, not even the failure of a waiting request`() {
        val session = session()
        val server = running(session)
        val outcomes = CopyOnWriteArrayList<Any>()
        session.request(
            { it.textDocumentService.definition(definitionParams()) },
            { outcomes.add(it) },
            { outcomes.add(it) },
        )
        awaitTrue("request at the server") { server.definitions.size == 1 }
        val before = dispatched.get()

        servers!!.close()

        // The state Stopped and the waiting request's failure are handed to the FX thread, and dropped there.
        awaitTrue("both handed to the FX thread") { dispatched.get() >= before + 2 }
        assertEquals(emptyList(), outcomes)
    }

    @Test
    fun `a request without a server gets not running`() {
        val session = session(TestSpec(command = null))
        val errors = CopyOnWriteArrayList<Throwable>()

        session.request({ it.textDocumentService.definition(definitionParams()) }, {}, { errors += it })

        awaitTrue("error") { errors.isNotEmpty() }
        assertIs<LanguageServerNotRunningException>(errors.single())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a request still waiting when its server crashes or restarts gets not running`(crash: Boolean) {
        val session = session()
        val server = running(session)
        val errors = CopyOnWriteArrayList<Throwable>()
        session.request({ it.textDocumentService.definition(definitionParams()) }, {}, { errors += it })
        awaitTrue("request at the server") { server.definitions.size == 1 }

        if (crash) starter.last.crash() else session.restart()

        awaitTrue("error") { errors.isNotEmpty() }
        assertEquals(LanguageServerNotRunningException::class, errors.single()::class)
        assertEquals("Test language server stopped", errors.single().message)
    }

    @Test
    fun `the extension is made once for the window folder, hears of each connect and stop, and is closed once`() {
        val made = AtomicInteger()
        val extension = RecordingExtension(isDispatched = { inDispatch.get() })
        val session =
            session(
                TestSpec(
                    extension = { projectFolder, _ ->
                        made.incrementAndGet()
                        extension.projectFolder = projectFolder
                        extension
                    },
                ),
            )
        running(session)

        starter.last.crash()
        awaitTrue("restarting") { session.state.value == ServerState.Restarting(1) }
        scheduler.runNext()
        awaitTrue("connected again") { extension.events.size == 3 }
        starter.last.crash()
        awaitTrue("restarting again") { session.state.value == ServerState.Restarting(2) }
        // Restarting, then Starting: one stop.
        session.restart()
        awaitTrue("connected a third time") { extension.events.size == 5 }
        servers!!.close()
        servers!!.close()

        assertEquals(1, made.get())
        assertEquals(folder, extension.projectFolder)
        assertEquals(
            listOf("connected", "stopped", "connected", "stopped", "connected", "close"),
            extension.events,
        )
        assertEquals(0, extension.undispatched.get(), "every call through dispatch")
    }

    @Test
    fun `the extension is made at the first start with a window folder, never without one, and hears of that server`() {
        val window = AtomicReference<Path?>(null)
        val made = CopyOnWriteArrayList<Path>()
        val extension = RecordingExtension(setOf("language/status"))
        val app = folder.resolve("app")
        val spec =
            TestSpec(
                workspaceFolderMode = WorkspaceFolderMode.ROOTS_ONLY,
                folders = { _, _ -> app },
                extension = { projectFolder, access ->
                    made.add(projectFolder)
                    extension.also { it.access = access }
                },
            )
        // The window gets its folder only after its language servers were made (ProjectWindows.show).
        val session = session(spec, projectFolder = { window.get() })
        val withoutFolder = CompletableFuture<Throwable>()
        session.request({ it.shutdown() }, {}, { withoutFolder.complete(it) })
        assertIs<LanguageServerNotRunningException>(withoutFolder.get(5, TimeUnit.SECONDS))
        val askedWithoutFolder = made.toList()

        window.set(folder)
        session.documents.open(app.resolve("A.py"), "python", "a")
        awaitTrue("the root reported") { "added $app" in extension.events }
        starter.last.server.clientEndpoint
            .notify("language/status", mapOf("type" to "Started"))
        awaitTrue("the notification") { extension.events.size == 3 }

        assertEquals(emptyList(), askedWithoutFolder)
        assertEquals(listOf(folder), made)
        assertEquals(
            listOf("connected", "added $app", "notification language/status {\"type\":\"Started\"}"),
            extension.events,
        )
    }

    @Test
    fun `a start with another window folder closes the old folder's extension and makes one, a closed session none`() {
        val window = AtomicReference<Path?>(folder)
        val made = CopyOnWriteArrayList<Path>()
        val extensions = CopyOnWriteArrayList<RecordingExtension>()
        val spec =
            TestSpec(
                extension = { projectFolder, _ ->
                    made.add(projectFolder)
                    RecordingExtension().also { extensions += it }
                },
            )
        val session = session(spec, projectFolder = { window.get() })
        running(session)
        val other = folder.resolve("other")

        window.set(other)
        // What only sends to the running server starts nothing, so it makes no extension either.
        session.notify { }
        val beforeTheStart = made.toList()
        session.restart()
        awaitTrue("the new extension connected") { extensions.size == 2 && "connected" in extensions[1].events }
        servers!!.close()
        // A closed session makes none, whatever folder its window has then.
        window.set(folder.resolve("third"))
        session.ensureStarted()

        assertEquals(listOf(folder), beforeTheStart)
        assertEquals(listOf(folder, other), made)
        assertEquals(listOf("connected", "close"), extensions[0].events)
        assertEquals(listOf("connected", "close"), extensions[1].events)
    }

    @Test
    fun `a start after the trust refused, with another window folder, makes the extension for the new folder`() {
        val window = AtomicReference<Path?>(folder)
        val other = folder.resolve("other")
        val made = CopyOnWriteArrayList<Path>()
        val extensions = CopyOnWriteArrayList<RecordingExtension>()
        val spec =
            TestSpec(
                runsProjectCode = true,
                trustCheckId = "check",
                extension = { projectFolder, _ ->
                    made.add(projectFolder)
                    RecordingExtension().also { extensions += it }
                },
            )
        // Refused for the first folder, allowed for the second.
        val session =
            session(spec, projectFolder = { window.get() }, trustGate = TrustGate { _, _ -> window.get() == other })
        session.ensureStarted()
        awaitTrue("untrusted") { session.state.value == ServerState.Untrusted }

        window.set(other)
        session.ensureStarted()
        awaitTrue("the latest extension connected") { "connected" in extensions.last().events }

        assertEquals(listOf(folder, other), made)
        assertEquals(listOf("close"), extensions[0].events)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an extension that throws, or a spec that fails to make one, leaves the session working`(failsToMake: Boolean) {
        val extension = RecordingExtension(failing = true)
        val spec =
            if (failsToMake) {
                TestSpec(extension = { _, _ -> error("spec bug") })
            } else {
                extendedSpec(extension)
            }
        val session = session(spec)
        val connected = CopyOnWriteArrayList<String>()
        session.onConnected { connected += "connected" }

        val server = running(session)
        val definition = session.requestDefinition()
        awaitTrue("request at the server") { server.definitions.size == 1 }
        server.definitions.single().complete(Either.forLeft<List<Location>, List<LocationLink>>(emptyList()))

        assertEquals(emptyList<Location>(), definition.get(5, TimeUnit.SECONDS))
        assertEquals(listOf("connected"), connected)
        assertEquals(if (failsToMake) emptyList() else listOf("connected"), extension.events)
    }

    /** Sends a definition request through the session; its result. */
    private fun LanguageServerSession.requestDefinition(): CompletableFuture<Any?> {
        val result = CompletableFuture<Any?>()
        request({ it.textDocumentService.definition(definitionParams()) }, { result.complete(it.left) }, {
            result.completeExceptionally(it)
        })
        return result
    }

    @Test
    fun `commands and requests of the extension reach the running server and answer on the FX thread`() {
        val extension = RecordingExtension()
        val session = session(extendedSpec(extension))
        val server = running(session)
        val commandAnswer = CompletableFuture<Any>()
        val requestAnswer = CompletableFuture<Any?>()
        server.commandAnswer = { commandAnswer }
        server.customAnswer = { _, _ -> requestAnswer }
        val onFx = CopyOnWriteArrayList<Boolean>()

        val command = extension.access.executeCommand("java.project.getAll", listOf("a", 1, StringBuilder("no JSON")))
        val request = extension.access.request("java/projectSettings", mapOf("uri" to "file:///p", "keys" to listOf(1)))
        command.whenComplete { _, _ -> onFx += inDispatch.get() }
        request.whenComplete { _, _ -> onFx += inDispatch.get() }
        awaitTrue("both at the server") { server.executedCommands.size == 1 && server.customMessages.size == 1 }
        commandAnswer.complete(listOf("file:///p"))
        requestAnswer.complete(mapOf("compliance" to "17"))
        // Before get(): a thread waiting in get() may run the callbacks itself once the result is set.
        awaitTrue("both answered") { onFx.size == 2 }

        // lsp4j reads the untyped result of executeCommand with Gson's plain types.
        assertEquals(listOf("file:///p"), command.get(5, TimeUnit.SECONDS))
        assertEquals("{\"compliance\":\"17\"}", request.get(5, TimeUnit.SECONDS).toString())
        assertEquals(listOf(true, true), onFx)
        assertEquals(
            "[\"a\", 1, null]",
            server.executedCommands
                .single()
                .arguments
                .toString(),
        )
        assertEquals(
            "java/projectSettings {\"uri\":\"file:///p\",\"keys\":[1]}",
            server.customMessages.single().let { "${it.first} ${it.second}" },
        )
        assertEquals(1, starter.processes.size)
    }

    @Test
    fun `the extension's requests and notifications start no server and fail as not running, the session's do`() {
        val extension = RecordingExtension()
        // The extension is made for the window folder, the start then finds none and settles Stopped.
        val session =
            session(extendedSpec(extension), projectFolder = {
                folder.takeUnless { Thread.currentThread().name == "sluide-lsp-test" }
            })
        val starts = AtomicInteger()
        session.state.addListener { _, _, state -> if (state == ServerState.Starting) starts.incrementAndGet() }
        session.ensureStarted()
        awaitTrue("stopped again") { session.state.value == ServerState.Stopped }

        val command = extension.access.executeCommand("java.project.getAll", emptyList())
        val request = extension.access.request("java/projectSettings", null)
        extension.access.notify("java/projectConfigurationUpdate", null)
        extension.access.configurationChanged(emptyMap<String, Any>())
        awaitTrue("both answered") { command.isDone && request.isDone }
        val startsByTheExtension = starts.get() - 1
        session.requestDefinition()

        assertIs<LanguageServerNotRunningException>(command.exceptionNow())
        assertIs<LanguageServerNotRunningException>(request.exceptionNow())
        assertEquals(0, startsByTheExtension)
        assertEquals(2, starts.get(), "the session's own request starts it")
    }

    @Test
    fun `the extension's notifications and configuration changes reach the running server as JSON`() {
        val extension = RecordingExtension()
        val session = session(extendedSpec(extension))
        val server = running(session)

        extension.access.notify("java/projectConfigurationUpdate", mapOf("uri" to "file:///p/settings.gradle"))
        extension.access.configurationChanged(mapOf("java" to mapOf("home" to "/jdk", "jdks" to listOf("17", 21))))
        extension.access.configurationChanged(StringBuilder("no JSON"))
        awaitTrue("both changes at the server") { server.configurationChanges.size == 2 }

        assertEquals(
            listOf("java/projectConfigurationUpdate {\"uri\":\"file:///p/settings.gradle\"}"),
            server.customMessages.map { "${it.first} ${it.second}" },
        )
        assertEquals(
            listOf("{\"java\":{\"home\":\"/jdk\",\"jdks\":[\"17\",21]}}", "null"),
            server.configurationChanges.map { it.settings?.toString() ?: "null" },
        )
        assertEquals(ServerState.Running, session.state.value)
    }

    @Test
    fun `the extension reads kept diagnostics, fires workspace changes and restarts the server`() {
        val extension = RecordingExtension()
        val spec =
            TestSpec(keeps = { true }, extension = { _, access -> extension.also { it.access = access } })
        val session = session(spec)
        val server = running(session)
        val fired = AtomicInteger()
        session.workspaceChanges.addListener { fired.incrementAndGet() }
        val build = folder.resolve("build.gradle")
        server.client.publishDiagnostics(diagnostics(LspUris.of(build), "Can't use Java 25"))
        awaitTrue("kept") { session.keptDiagnostics(build).isNotEmpty() }

        val kept = extension.access.keptDiagnostics(build).map { it.message.left }
        extension.access.workspaceChanged()
        extension.access.restart()

        assertEquals(listOf("Can't use Java 25"), kept)
        assertEquals(1, fired.get())
        awaitTrue("restarted") { starter.processes.size == 2 && session.state.value == ServerState.Running }
    }

    private fun section(name: String) = ConfigurationItem().apply { section = name }

    private fun FakeServer.initializationOptions() = initializeParams!!.initializationOptions?.toString()

    private fun diagnostics(
        uri: String,
        message: String,
    ) = PublishDiagnosticsParams(uri, listOf(Diagnostic(range(), message)))

    private fun definitionParams() =
        DefinitionParams(TextDocumentIdentifier(LspUris.of(folder.resolve("a.py"))), Position(0, 0))

    private fun range() = Range(Position(0, 0), Position(0, 1))
}
