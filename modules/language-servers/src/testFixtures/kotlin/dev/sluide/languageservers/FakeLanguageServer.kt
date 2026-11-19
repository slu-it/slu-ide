package dev.sluide.languageservers

import dev.sluide.languageservers.connection.Scheduler
import dev.sluide.languageservers.process.ServerProcess
import dev.sluide.languageservers.process.ServerStarter
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * A language server in the test JVM that records what it receives and lets the test answer or call back. Open: a
 * test overrides the `textDocument/…` methods it needs (hover, completion, references…). Requests and notifications
 * lsp4j does not know reach it as an [Endpoint]: recorded in [customMessages], requests answered by [customAnswer].
 */
open class FakeServer :
    LanguageServer,
    LanguageClientAware,
    TextDocumentService,
    WorkspaceService,
    Endpoint {
    /** The client proxy: sends server-to-client requests and notifications. */
    @Volatile
    lateinit var client: LanguageClient

    /** The client as a raw endpoint: sends notifications and requests lsp4j's `LanguageClient` does not declare. */
    @Volatile
    lateinit var clientEndpoint: Endpoint

    /** The requests and notifications of methods lsp4j does not know, in order: method and parameters as received. */
    val customMessages = CopyOnWriteArrayList<Pair<String, Any?>>()

    /** What a request of a method lsp4j does not know answers. */
    @Volatile
    var customAnswer: (method: String, params: Any?) -> CompletableFuture<*> = { _, _ ->
        CompletableFuture.completedFuture(null)
    }

    /** The `workspace/executeCommand` requests received, in order. */
    val executedCommands = CopyOnWriteArrayList<ExecuteCommandParams>()

    /** What `workspace/executeCommand` answers. */
    @Volatile
    var commandAnswer: (ExecuteCommandParams) -> CompletableFuture<Any> = { CompletableFuture.completedFuture(null) }

    /** The parameters of the `workspace/didChangeConfiguration` notifications received, in order. */
    val configurationChanges = CopyOnWriteArrayList<DidChangeConfigurationParams>()

    /** Methods received, in order; `didOpen` with the document's text. */
    val calls = CopyOnWriteArrayList<String>()

    /** The parameters of the document notifications received (`didOpen`, `didChange`, `didSave`, `didClose`), in order. */
    val documentNotifications = CopyOnWriteArrayList<Any>()

    /** The parameters of the `workspace/didChangeWatchedFiles` notifications received, in order. */
    val watchedFileChanges = CopyOnWriteArrayList<DidChangeWatchedFilesParams>()

    /** The `workspace/didChangeWorkspaceFolders` notifications received, in order; [calls] has them as `-uri +uri`. */
    val workspaceFolderChanges = CopyOnWriteArrayList<DidChangeWorkspaceFoldersParams>()

    @Volatile
    var initializeParams: InitializeParams? = null

    /** What `initialize` answers. */
    @Volatile
    var capabilities = ServerCapabilities()

    /**
     * Whether the messages this server writes carry their `null` fields (`"diagnostics": null`), as a server that is not
     * lsp4j may write them; lsp4j drops them by default. Set before the process starts.
     */
    @Volatile
    var serializeNulls = false

    /** `false`: `initialize` is never answered. */
    @Volatile
    var answerInitialize = true

    /** `false`: `shutdown` is never answered. */
    @Volatile
    var answerShutdown = true

    /** `false`: the process does not end on `exit`. */
    @Volatile
    var honourExit = true

    /** The futures of the `textDocument/definition` requests received, for the test to complete. */
    val definitions = CopyOnWriteArrayList<CompletableFuture<Either<List<Location>, List<LocationLink>>>>()

    /** Set by the process: `exit` ends it. */
    @Volatile
    var onExit: () -> Unit = {}

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        calls += "initialize"
        initializeParams = params
        return if (answerInitialize) {
            CompletableFuture.completedFuture(
                InitializeResult(capabilities),
            )
        } else {
            CompletableFuture()
        }
    }

    override fun initialized(params: InitializedParams) {
        calls += "initialized"
    }

    override fun shutdown(): CompletableFuture<Any?> {
        calls += "shutdown"
        return if (answerShutdown) CompletableFuture.completedFuture(null) else CompletableFuture()
    }

    override fun exit() {
        calls += "exit"
        if (honourExit) onExit()
    }

    override fun getTextDocumentService(): TextDocumentService = this

    override fun getWorkspaceService(): WorkspaceService = this

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        calls += "definition"
        return CompletableFuture<Either<List<Location>, List<LocationLink>>>().also { definitions += it }
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        documentNotifications += params
        calls += "didOpen ${params.textDocument.text}"
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        documentNotifications += params
        calls += "didChange"
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        documentNotifications += params
        calls += "didClose"
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        documentNotifications += params
        calls += "didSave"
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        configurationChanges += params
        calls += "didChangeConfiguration"
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        executedCommands += params
        calls += "executeCommand ${params.command}"
        return commandAnswer(params)
    }

    override fun request(
        method: String,
        parameter: Any?,
    ): CompletableFuture<*> {
        customMessages += method to parameter
        calls += "request $method"
        return customAnswer(method, parameter)
    }

    override fun notify(
        method: String,
        parameter: Any?,
    ) {
        customMessages += method to parameter
        calls += "notify $method"
    }

    override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
        workspaceFolderChanges += params
        val removed = params.event.removed.map { "-${it.uri}" }
        val added = params.event.added.map { "+${it.uri}" }
        calls += "didChangeWorkspaceFolders ${(removed + added).joinToString(" ")}"
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        watchedFileChanges += params
        calls += "didChangeWatchedFiles"
    }
}

/**
 * A [ServerProcess] whose other end is a [FakeServer] on in-memory pipes. [holdsEnd]: it does not end on `exit`, a
 * crash or a kill until the test calls [release], like a process that takes its time to die; [waitFor], [isEnded] and
 * the streams stay those of a live process until then, as `Process.waitFor` and `isAlive` do for an OS process.
 */
internal class FakeProcess(
    override val name: String,
    val server: FakeServer,
    holdsEnd: Boolean = false,
) : ServerProcess {
    private val toServer = BytePipe()
    private val toClient = BytePipe()
    private val ended = CountDownLatch(1)
    private val endAsked = CountDownLatch(1)

    @Volatile
    private var held = holdsEnd

    /** The exit code of the end asked for while [held]. */
    private var heldCode: Int? = null
    private val serverReader = Executors.newSingleThreadExecutor { Thread(it, "fake-$name").apply { isDaemon = true } }

    @Volatile
    private var exitCode = 0

    /** Whether the client killed it. */
    @Volatile
    var destroyed = false
        private set

    /** The thread that waits for the end (the session's exit watcher): joined, it has handled the end. */
    @Volatile
    var waiter: Thread? = null
        private set

    override val input: InputStream = toClient.input
    override val output: OutputStream = toServer.output

    init {
        val launcher =
            Launcher
                .Builder<LanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(LanguageClient::class.java)
                .configureGson { if (server.serializeNulls) it.serializeNulls() }
                .setInput(toServer.input)
                .setOutput(toClient.output)
                .setExecutorService(serverReader)
                .create()
        server.connect(launcher.remoteProxy)
        server.clientEndpoint = launcher.remoteEndpoint
        server.onExit = { end(0) }
        launcher.startListening()
    }

    /** The server dies: its streams close, its exit code is 1. */
    fun crash() = end(1)

    val isEnded: Boolean get() = ended.count == 0L

    override fun waitFor(): Int {
        waiter = Thread.currentThread()
        ended.await()
        return exitCode
    }

    override fun waitFor(millis: Long): Boolean = ended.await(millis, TimeUnit.MILLISECONDS)

    override fun descendants(): List<ProcessHandle> = emptyList()

    override fun destroyTree() {
        destroyed = true
        end(137)
    }

    /** Waits until the exit watcher has handled the end; `false` if it did not within five seconds. */
    fun awaitEndHandled(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (waiter == null || !isEnded) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(5)
        }
        waiter?.join(5_000)
        return waiter?.isAlive == false
    }

    /** Waits until something asked it to end (`exit`, a crash, a kill); `false` if nothing did within five seconds. */
    fun awaitEndAsked(): Boolean = endAsked.await(5, TimeUnit.SECONDS)

    /** Lets a held process end: now if its end was asked for, else when it is. */
    fun release() {
        val code =
            synchronized(this) {
                held = false
                heldCode
            }
        if (code != null) end(code)
    }

    private fun end(code: Int) {
        synchronized(this) {
            if (isEnded) return
            endAsked.countDown()
            if (held) {
                if (heldCode == null) heldCode = code
                return
            }
            exitCode = code
            ended.countDown()
        }
        toServer.close()
        toClient.close()
        serverReader.shutdownNow()
    }
}

/**
 * Starts a [FakeProcess] with the server [newServer] makes for start number `index` (from 0); [beforeStart] runs on
 * the starting thread first, to hold a start; [holdsEnd] says whether that process ends only on [FakeProcess.release].
 */
internal class FakeStarter(
    private val newServer: (index: Int) -> FakeServer = { FakeServer() },
    private val beforeStart: (index: Int) -> Unit = {},
    private val holdsEnd: (index: Int) -> Boolean = { false },
) : ServerStarter {
    val processes = CopyOnWriteArrayList<FakeProcess>()
    val roots = CopyOnWriteArrayList<Path>()
    val commands = CopyOnWriteArrayList<ServerCommand>()
    private val starts =
        java.util.concurrent.atomic
            .AtomicInteger()

    val last: FakeProcess get() = processes.last()

    override fun start(
        name: String,
        command: ServerCommand,
        root: Path,
    ): ServerProcess {
        val index = starts.getAndIncrement()
        beforeStart(index)
        roots.add(root)
        commands.add(command)
        return FakeProcess(name, newServer(index), holdsEnd(index)).also { processes += it }
    }
}

/** A [Scheduler] whose tasks the test runs. */
internal class FakeScheduler : Scheduler {
    val due = CopyOnWriteArrayList<Pair<Duration, Runnable>>()

    override fun schedule(
        delay: Duration,
        task: Runnable,
    ): AutoCloseable {
        val entry = delay to task
        due += entry
        return AutoCloseable { due.remove(entry) }
    }

    /**
     * Runs the first due task, waiting for one to be scheduled; its delay. The code publishes a state such as
     * `Restarting` before it schedules the retry, so a test that saw the state may get here first.
     */
    fun runNext(): Duration {
        var entry: Pair<Duration, Runnable>? = null
        awaitTrue("a task due") { due.firstOrNull()?.takeIf { due.remove(it) }?.also { entry = it } != null }
        val (delay, task) = checkNotNull(entry)
        task.run()
        return delay
    }
}

/** Polls [condition] until it holds, failing after five seconds. */
internal fun awaitTrue(
    message: String,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting: $message" }
        Thread.sleep(10)
    }
}

/** An in-memory pipe without the writer-thread checks of `PipedInputStream` (lsp4j writes from several threads). */
internal class BytePipe {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var buffer = ByteArray(8192)
    private var start = 0
    private var end = 0
    private var closed = false

    val input: InputStream =
        object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int =
                lock.withLock {
                    while (start == end && !closed) {
                        try {
                            changed.await()
                        } catch (e: InterruptedException) {
                            throw InterruptedIOException(e.toString())
                        }
                    }
                    if (start == end) return -1
                    val count = minOf(len, end - start)
                    System.arraycopy(buffer, start, b, off, count)
                    start += count
                    count
                }

            override fun close() = this@BytePipe.close()
        }

    val output: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) = lock.withLock {
                if (closed) throw IOException("Pipe closed")
                if (end + len > buffer.size) {
                    val kept = end - start
                    val grown = if (kept + len > buffer.size) ByteArray(maxOf(buffer.size * 2, kept + len)) else buffer
                    System.arraycopy(buffer, start, grown, 0, kept)
                    buffer = grown
                    start = 0
                    end = kept
                }
                System.arraycopy(b, off, buffer, end, len)
                end += len
                changed.signalAll()
            }

            override fun close() = this@BytePipe.close()
        }

    fun close() =
        lock.withLock {
            closed = true
            changed.signalAll()
        }
}
