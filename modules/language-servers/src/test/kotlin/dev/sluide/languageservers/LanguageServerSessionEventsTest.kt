package dev.sluide.languageservers

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.languageservers.connection.ServerTiming
import javafx.beans.InvalidationListener
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a session hands on and keeps around its connections: subscriptions and their end, what an old connection may
 * still say, kept pushes, how requests end and what a notification that fails does. Dispatch is inline unless
 * [hold] is set, then the FX thread's work waits in [held].
 */
class LanguageServerSessionEventsTest {
    @TempDir
    lateinit var folder: Path

    private var starter = FakeStarter()
    private val scheduler = FakeScheduler()
    private var servers: LanguageServers? = null
    private val logs = CopyOnWriteArrayList<LogRecords>()

    @Volatile
    private var hold = false
    private val held = LinkedBlockingQueue<Runnable>()

    @AfterEach
    fun closeServers() {
        servers?.close()
        logs.forEach(LogRecords::close)
    }

    private fun sessionLog(): LogRecords =
        LogRecords(Logger.getLogger(LanguageServerSession::class.java.name)).also { logs += it }

    private fun session(
        spec: TestSpec = TestSpec(),
        trustGate: TrustGate = TrustGate { _, _ -> true },
        timing: ServerTiming = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
        projectFolder: () -> Path? = { folder },
    ): LanguageServerSession {
        val created =
            LanguageServers(
                listOf(spec),
                projectFolder,
                trustGate,
                { runnable -> if (hold) held.add(runnable) else runnable.run() },
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

    private fun definitionParams() =
        DefinitionParams(TextDocumentIdentifier(LspUris.of(folder.resolve("a.py"))), Position(0, 0))

    private fun range() = Range(Position(0, 0), Position(0, 1))

    private fun diagnostics(
        file: Path,
        message: String,
    ) = PublishDiagnosticsParams(LspUris.of(file), listOf(Diagnostic(range(), message)))

    private fun PublishDiagnosticsParams.message() =
        this.diagnostics
            .single()
            .message.left

    private fun watcherRegistration() =
        RegistrationParams(
            listOf(
                Registration(
                    "w",
                    "workspace/didChangeWatchedFiles",
                    DidChangeWatchedFilesRegistrationOptions(listOf(FileSystemWatcher(Either.forLeft("**/*.py")))),
                ),
            ),
        )

    // --- subscriptions -------------------------------------------------------------------------------------------

    @Test
    fun `a closed diagnostics subscription hears nothing more, the other of its file goes on`() {
        val session = session()
        val server = running(session)
        val file = folder.resolve("a.py")
        val first = CopyOnWriteArrayList<String>()
        val second = CopyOnWriteArrayList<String>()
        val subscription = session.onDiagnostics(file) { first += it.message() }
        session.onDiagnostics(file) { second += it.message() }
        server.client.publishDiagnostics(diagnostics(file, "both"))
        awaitTrue("both heard") { first.isNotEmpty() && second.isNotEmpty() }

        subscription.close()
        server.client.publishDiagnostics(diagnostics(file, "one"))

        awaitTrue("the other listener") { second.size == 2 }
        assertEquals(listOf("both"), first)
        assertEquals(listOf("both", "one"), second)
    }

    @Test
    fun `once the last listener of a file is closed its pushes are kept again, if the spec keeps them`() {
        val session = session(TestSpec(keeps = { true }))
        val server = running(session)
        val file = folder.resolve("tsconfig.json")
        val heard = CopyOnWriteArrayList<String>()
        val subscription = session.onDiagnostics(file) { heard += it.message() }
        server.client.publishDiagnostics(diagnostics(file, "listened"))
        awaitTrue("heard") { heard.isNotEmpty() }
        assertEquals(emptyList(), session.keptDiagnostics(file))

        subscription.close()
        server.client.publishDiagnostics(diagnostics(file, "kept"))

        awaitTrue("kept") { session.keptDiagnostics(file).isNotEmpty() }
        assertEquals(listOf("kept"), session.keptDiagnostics(file).map { it.message.left })
        assertEquals(listOf("listened"), heard)
    }

    @Test
    fun `a closed refresh, features and connect subscription hears nothing more, the others of its kind go on`() {
        val session = session()
        val closedRefresh = AtomicInteger()
        val heardRefresh = AtomicInteger()
        val closedFeatures = AtomicInteger()
        val heardFeatures = AtomicInteger()
        val closedConnect = AtomicInteger()
        val heardConnect = AtomicInteger()
        session.onRefresh(RefreshKind.DIAGNOSTICS) { closedRefresh.incrementAndGet() }.close()
        session.onRefresh(RefreshKind.DIAGNOSTICS) { heardRefresh.incrementAndGet() }
        session.onFeaturesChanged { closedFeatures.incrementAndGet() }.close()
        session.onFeaturesChanged { heardFeatures.incrementAndGet() }
        session.onConnected { closedConnect.incrementAndGet() }.close()
        session.onConnected { heardConnect.incrementAndGet() }

        val server = running(session)
        server.client.refreshDiagnostics().get(5, TimeUnit.SECONDS)

        assertEquals(0, closedRefresh.get())
        assertEquals(1, heardRefresh.get())
        assertEquals(0, closedFeatures.get())
        assertEquals(1, heardFeatures.get())
        assertEquals(0, closedConnect.get())
        assertEquals(1, heardConnect.get())
    }

    // --- what the connections say --------------------------------------------------------------------------------

    @Test
    fun `a refresh of a kind nobody listens to is answered and reaches no other kind's listener`() {
        val session = session()
        val server = running(session)
        val heard = CopyOnWriteArrayList<RefreshKind>()
        session.onRefresh(RefreshKind.SEMANTIC_TOKENS) { heard += RefreshKind.SEMANTIC_TOKENS }

        server.client.refreshDiagnostics().get(5, TimeUnit.SECONDS)
        server.client.refreshSemanticTokens().get(5, TimeUnit.SECONDS)

        assertEquals(listOf(RefreshKind.SEMANTIC_TOKENS), heard)
    }

    @Test
    fun `refreshes and registrations an old server sends after a restart are dropped`() {
        val session = session(timing = ServerTiming(shutdown = 3.seconds, exit = 200.milliseconds))
        val old = running(session)
        old.answerShutdown = false
        val refreshes = AtomicInteger()
        val features = AtomicInteger()
        session.onRefresh(RefreshKind.DIAGNOSTICS) { refreshes.incrementAndGet() }
        session.onFeaturesChanged { features.incrementAndGet() }

        session.restart()
        awaitTrue("shutdown sent to the old server") { "shutdown" in old.calls }
        val before = features.get()
        old.client.refreshDiagnostics().get(5, TimeUnit.SECONDS)
        old.client.registerCapability(watcherRegistration()).get(5, TimeUnit.SECONDS)
        // Answered after the client has read the notification before it: the old connection's events were handled.
        old.client.configuration(org.eclipse.lsp4j.ConfigurationParams(emptyList())).get(5, TimeUnit.SECONDS)

        assertEquals(0, refreshes.get())
        assertEquals(before, features.get())

        awaitTrue("the new server runs") { starter.processes.size == 2 && session.state.value == ServerState.Running }
        val connected = features.get()
        starter.last.server.client
            .registerCapability(watcherRegistration())
            .get(5, TimeUnit.SECONDS)
        assertEquals(connected + 1, features.get(), "the current server's registration does reach the listener")
    }

    @Test
    fun `a push for something that is no file is not kept, one the spec's decision fails for is not kept and logged`() {
        val failing = TestSpec(keeps = { if (it.fileName.toString() == "boom.json") error("spec bug") else true })
        val session = session(failing)
        val server = running(session)
        val fired = AtomicInteger()
        session.keptDiagnosticsChanges.addListener(InvalidationListener { fired.incrementAndGet() })
        val warnings = sessionLog()
        val config = folder.resolve("tsconfig.json")

        server.client.publishDiagnostics(
            PublishDiagnosticsParams("untitled:Untitled-1", listOf(Diagnostic(range(), "x"))),
        )
        server.client.publishDiagnostics(diagnostics(folder.resolve("boom.json"), "boom"))
        server.client.publishDiagnostics(diagnostics(config, "kept"))

        awaitTrue("the last push") { session.keptDiagnostics(config).isNotEmpty() }
        assertEquals(1, fired.get(), "only the third push was kept")
        assertEquals(emptyList(), session.keptDiagnostics(folder.resolve("boom.json")))
        assertEquals(
            listOf("lsp test: keepsDiagnostics failed for ${folder.resolve("boom.json")}"),
            warnings.messages(Level.WARNING),
        )
    }

    @Test
    fun `a push is kept only if its connection is still the session's when the FX thread gets to it`() {
        val session =
            session(TestSpec(keeps = { true }), timing = ServerTiming(shutdown = 3.seconds, exit = 200.milliseconds))
        val old = running(session)
        old.answerShutdown = false
        val config = folder.resolve("tsconfig.json")
        hold = true

        old.client.publishDiagnostics(diagnostics(config, "stale"))
        awaitTrue("the push waits for the FX thread") { held.isNotEmpty() }
        // The restart makes the old connection no longer current; the push is already on its way to the FX thread.
        session.restart()
        checkNotNull(held.poll()).run()

        assertEquals(emptyList(), session.keptDiagnostics(config))
        hold = false
    }

    // --- requests ------------------------------------------------------------------------------------------------

    @Test
    fun `a request without a server gets not running with the spec's name`() {
        val session = session(TestSpec(command = null))
        val errors = CopyOnWriteArrayList<Throwable>()

        session.request<Any>({ CompletableFuture() }, {}, { errors += it })

        awaitTrue("error") { errors.isNotEmpty() }
        assertEquals("Test language server is not running", errors.single().message)
    }

    @Test
    fun `a call that throws gets the error delivered, a failed future gets its cause, not the wrapper`() {
        val session = session()
        running(session)
        val errors = CopyOnWriteArrayList<Throwable>()

        session.request<Any>({ throw IllegalStateException("call") }, {}, { errors += it })
        session.request<Any>(
            { CompletableFuture.supplyAsync { throw IllegalArgumentException("future") } },
            {},
            { errors += it },
        )

        awaitTrue("both failed") { errors.size == 2 }
        val byMessage = errors.associateBy { it.message }
        assertIs<IllegalStateException>(byMessage.getValue("call"))
        assertIs<IllegalArgumentException>(byMessage.getValue("future"))
    }

    @Test
    fun `a request whose future was cancelled by someone else delivers neither result nor error`() {
        val session = session()
        running(session)
        val future = CompletableFuture<Any?>()
        val sent = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<String>()

        session.request({ future.also { sent.countDown() } }, { outcomes += "result" }, { outcomes += "error" })
        assertTrue(sent.await(5, TimeUnit.SECONDS))
        future.cancel(true)
        session.request(
            { CompletableFuture.completedFuture<Any?>(null) },
            { outcomes += "sentinel" },
            { outcomes += "error" },
        )

        awaitTrue("the later request") { "sentinel" in outcomes }
        assertEquals(listOf("sentinel"), outcomes)
    }

    @Test
    fun `a request cancelled while the server starts is never sent`() {
        val release = CountDownLatch(1)
        starter = FakeStarter(beforeStart = { release.await(5, TimeUnit.SECONDS) })
        val session = session()
        val sent = AtomicInteger()
        val outcomes = CopyOnWriteArrayList<String>()

        val pending =
            session.request<Any>({ sent.incrementAndGet().let { CompletableFuture() } }, { outcomes += "r" }, {
                outcomes +=
                    "e"
            })
        pending.cancel()
        release.countDown()
        awaitTrue("running") { session.state.value == ServerState.Running }
        session.request(
            { CompletableFuture.completedFuture<Any?>(null) },
            { outcomes += "sentinel" },
            { outcomes += "e" },
        )

        awaitTrue("the later request") { "sentinel" in outcomes }
        assertEquals(0, sent.get(), "the cancelled request's call never ran")
        assertEquals(listOf("sentinel"), outcomes)
    }

    @Test
    fun `without an error handler a failure is logged at INFO with the spec's id`() {
        val session = session()
        running(session)
        val infos = sessionLog()

        session.request<Any>({ throw IllegalStateException("boom") }, {})
        awaitTrue("logged") { infos.messages(Level.INFO).isNotEmpty() }

        assertEquals(listOf("lsp test request: java.lang.IllegalStateException: boom"), infos.messages(Level.INFO))
    }

    // --- notifications and the rest ------------------------------------------------------------------------------

    @Test
    fun `a notification that throws is logged and the next one still goes out`() {
        val session = session()
        val server = running(session)
        val infos = sessionLog()
        val change = DidChangeConfigurationParams(emptyMap<String, Any>())

        session.notify { throw IllegalStateException("boom") }
        session.notify { it.workspaceService.didChangeConfiguration(change) }

        awaitTrue("the next notification") { "didChangeConfiguration" in server.calls }
        assertEquals(
            listOf("lsp test notification failed: java.lang.IllegalStateException: boom"),
            infos.messages(Level.INFO),
        )
    }

    @Test
    fun `a closed session fires no workspace change`() {
        val session = session()
        val fired = AtomicInteger()
        session.workspaceChanges.addListener(InvalidationListener { fired.incrementAndGet() })

        session.workspaceChanged()
        servers!!.close()
        session.workspaceChanged()

        assertEquals(1, fired.get())
    }

    @Test
    fun `a request failing with a wrapper that has no cause delivers the wrapper as the error`() {
        val session = session()
        running(session)
        val future = CompletableFuture<Any?>()
        val sent = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<String>()

        session.request({ future.also { sent.countDown() } }, { outcomes += "result" }, {
            outcomes +=
                "error ${it.message}"
        })
        assertTrue(sent.await(5, TimeUnit.SECONDS))
        future.completeExceptionally(CompletionException("no cause", null))

        awaitTrue("delivered") { outcomes.isNotEmpty() }
        assertEquals(listOf("error no cause"), outcomes)
    }

    @Test
    fun `a listener that throws is logged and the others of its kind still hear`() {
        val session = session()
        val server = running(session)
        val file = folder.resolve("a.py")
        val heard = CopyOnWriteArrayList<String>()
        val errors = sessionLog()
        session.onDiagnostics(file) { error("listener bug") }
        session.onDiagnostics(file) { heard += it.message() }

        server.client.publishDiagnostics(diagnostics(file, "one"))

        awaitTrue("the second listener") { heard.isNotEmpty() }
        assertEquals(listOf("lsp test: a diagnostics listener failed"), errors.messages(Level.SEVERE))
    }

    @Test
    fun `what was kept is gone once the session closed`() {
        val session = session(TestSpec(keeps = { true }))
        val server = running(session)
        val config = folder.resolve("tsconfig.json")
        server.client.publishDiagnostics(diagnostics(config, "kept"))
        awaitTrue("kept") { session.keptDiagnostics(config).isNotEmpty() }

        servers!!.close()

        assertEquals(emptyList(), session.keptDiagnostics(config))
    }

    @Test
    fun `work that was on its way to the FX thread when the session closed is dropped there`() {
        val session = session()
        val server = running(session)
        val outcomes = CopyOnWriteArrayList<String>()
        session.request(
            { it.textDocumentService.definition(definitionParams()) },
            { outcomes += "result" },
            { outcomes += "error" },
        )
        awaitTrue("request at the server") { server.definitions.size == 1 }
        hold = true
        server.definitions[0].complete(Either.forLeft(emptyList()))
        awaitTrue("the result waits for the FX thread") { held.isNotEmpty() }

        servers!!.close()
        generateSequence { held.poll() }.forEach(Runnable::run)

        assertEquals(emptyList(), outcomes)
    }

    @Test
    fun `a server that connected after the session closed is announced to nobody`() {
        val extension = RecordingExtension()
        val session = session(TestSpec(extension = { _, _ -> extension }))
        val connected = AtomicInteger()
        session.onConnected { connected.incrementAndGet() }
        hold = true
        session.ensureStarted()
        awaitTrue("running published") { held.size >= 2 && "initialized" in starter.last.server.calls }

        servers!!.close()
        generateSequence { held.poll() }.forEach(Runnable::run)

        assertEquals(0, connected.get())
        assertEquals(listOf("close"), extension.events, "neither connected nor stopped after the close")
    }

    // --- starts of the extension and of a gating server --------------------------------------------------------

    private fun extensionMadeFor(made: MutableList<Path>) =
        TestSpec(command = null, extension = { projectFolder, _ ->
            made.add(projectFolder)
            RecordingExtension()
        })

    @Test
    fun `a start that is refused makes the extension for the new folder only when it is asked to start anyway`() {
        val made = CopyOnWriteArrayList<Path>()
        val unavailable = extensionMadeFor(made)
        val window = AtomicReference(folder)
        val session = session(unavailable, projectFolder = { window.get() })
        session.ensureStarted()
        awaitTrue("unavailable") { session.state.value == ServerState.Unavailable }
        val other = folder.resolve("other")
        window.set(other)
        unavailable.command = ServerCommand(Path.of("fake-server"))
        val errors = CopyOnWriteArrayList<Throwable>()

        // A request does not retry a server that was not available: no start, no extension.
        session.request<Any>({ CompletableFuture() }, {}, { errors += it })
        awaitTrue("answered") { errors.isNotEmpty() }
        assertEquals(listOf(folder), made)

        session.ensureStarted()

        awaitTrue("started") { session.state.value == ServerState.Running }
        assertEquals(listOf(folder, other), made)
    }

    @Test
    fun `a running server keeps its extension when the window folder changed and a start is asked for`() {
        val made = CopyOnWriteArrayList<Path>()
        val window = AtomicReference(folder)
        val session =
            session(
                TestSpec(extension = { projectFolder, _ ->
                    made.add(projectFolder)
                    RecordingExtension()
                }),
                projectFolder = { window.get() },
            )
        running(session)

        window.set(folder.resolve("other"))
        session.ensureStarted()

        assertEquals(listOf(folder), made)
    }

    /** A request after what a mutant would have started: it is queued behind the start, and answered not running. */
    private fun awaitNotRunning(session: LanguageServerSession) {
        val errors = CopyOnWriteArrayList<Throwable>()
        session.request<Any>({ CompletableFuture() }, {}, { errors += it })
        awaitTrue("not running") { errors.isNotEmpty() }
    }

    @Test
    fun `a gating server that was not available is not restarted while no document is served`() {
        val asked = AtomicInteger()
        val unavailable =
            TestSpec(command = null, gatesDocuments = true, commandFor = { asked.incrementAndGet().let { null } })
        val session = session(unavailable)
        val document = session.documents.open(folder.resolve("a.py"), "python", "x")
        awaitTrue("unavailable") { session.state.value == ServerState.Unavailable }
        unavailable.command = ServerCommand(Path.of("fake-server"))
        val starts = asked.get()
        document.close()

        session.restart()
        awaitNotRunning(session)

        assertEquals(starts, asked.get())
        assertEquals(ServerState.Unavailable, session.state.value)
    }

    @Test
    fun `a gating server that was not trusted is not restarted while no document is served`() {
        val asked = AtomicInteger()
        val untrusted = TestSpec(runsProjectCode = true, trustCheckId = "x", gatesDocuments = true)
        val session = session(untrusted, TrustGate { _, _ -> asked.incrementAndGet().let { false } })
        val document = session.documents.open(folder.resolve("a.py"), "python", "x")
        awaitTrue("untrusted") { session.state.value == ServerState.Untrusted }
        val asks = asked.get()
        document.close()

        session.restart()
        awaitNotRunning(session)

        assertEquals(asks, asked.get())
    }

    @Test
    fun `a push with a null list of diagnostics, as a server that is not lsp4j sends it, clears what was kept`() {
        starter = FakeStarter({ FakeServer().apply { serializeNulls = true } })
        val session = session(TestSpec(keeps = { true }))
        val server = running(session)
        val config = folder.resolve("tsconfig.json")
        server.client.publishDiagnostics(diagnostics(config, "kept"))
        awaitTrue("kept") { session.keptDiagnostics(config).isNotEmpty() }

        // LSP 3.17 wants a list; lsp4j reads an explicit `null` as null (a missing key as an empty list).
        val push =
            JsonObject().apply {
                addProperty("uri", LspUris.of(config))
                add("diagnostics", JsonNull.INSTANCE)
            }
        server.clientEndpoint.notify("textDocument/publishDiagnostics", push)

        awaitTrue("cleared") { session.keptDiagnostics(config).isEmpty() }
    }

    @Test
    fun `a notification made while the server is down is dropped without a failure`() {
        val session = session()
        running(session)
        val failures = LogRecords(Logger.getLogger(NamedThreads::class.java.name)).also { logs += it }
        starter.last.crash()
        awaitTrue("restarting") { session.state.value == ServerState.Restarting(1) }

        session.notify { error("not sent: no server runs") }
        scheduler.runNext()

        awaitTrue("restarted") { session.state.value == ServerState.Running }
        assertEquals(emptyList(), failures.messages())
    }
}
