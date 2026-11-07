package dev.sluide.languageservers

import dev.sluide.languageservers.connection.Scheduler
import dev.sluide.languageservers.process.ServerProcess
import dev.sluide.languageservers.process.ServerStarter
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.ServerCapabilities
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

/** A language server in the test JVM that records what it receives and lets the test answer or call back. */
internal class FakeServer :
    LanguageServer,
    LanguageClientAware,
    TextDocumentService,
    WorkspaceService {
    /** The client proxy: sends server-to-client requests and notifications. */
    @Volatile
    lateinit var client: LanguageClient

    /** Methods received, in order; notifications of documents with their URI. */
    val calls = CopyOnWriteArrayList<String>()

    @Volatile
    var initializeParams: InitializeParams? = null

    @Volatile
    var capabilities = ServerCapabilities()

    /** `false`: `shutdown` is never answered. */
    @Volatile
    var answerShutdown = true

    /** The futures of the `textDocument/definition` requests received, for the test to complete. */
    val definitions = CopyOnWriteArrayList<CompletableFuture<Either<List<Location>, List<LocationLink>>>>()

    @Volatile
    var onExit: () -> Unit = {}

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        calls += "initialize"
        initializeParams = params
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
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
        onExit()
    }

    override fun getTextDocumentService(): TextDocumentService = this

    override fun getWorkspaceService(): WorkspaceService = this

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        calls += "definition"
        return CompletableFuture<Either<List<Location>, List<LocationLink>>>().also { definitions += it }
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        calls += "didOpen ${params.textDocument.text}"
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        calls += "didChange"
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        calls += "didClose"
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        calls += "didSave"
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        calls += "didChangeConfiguration"
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        calls += "didChangeWatchedFiles"
    }
}

/** A [ServerProcess] whose other end is a [FakeServer] on in-memory pipes. */
internal class FakeProcess(
    override val name: String,
    val server: FakeServer,
) : ServerProcess {
    private val toServer = BytePipe()
    private val toClient = BytePipe()
    private val ended = CountDownLatch(1)
    private val serverReader = Executors.newSingleThreadExecutor { Thread(it, "fake-$name").apply { isDaemon = true } }

    @Volatile
    private var exitCode = 0

    @Volatile
    var destroyed = false
        private set

    override val input: InputStream = toClient.input
    override val output: OutputStream = toServer.output

    init {
        val launcher =
            Launcher
                .Builder<LanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(LanguageClient::class.java)
                .setInput(toServer.input)
                .setOutput(toClient.output)
                .setExecutorService(serverReader)
                .create()
        server.connect(launcher.remoteProxy)
        server.onExit = { end(0) }
        launcher.startListening()
    }

    /** The server dies: its streams close, its exit code is 1. */
    fun crash() = end(1)

    override fun waitFor(): Int {
        ended.await()
        return exitCode
    }

    override fun waitFor(millis: Long): Boolean = ended.await(millis, TimeUnit.MILLISECONDS)

    override fun destroyTree() {
        destroyed = true
        end(137)
    }

    private fun end(code: Int) {
        if (ended.count == 0L) return
        exitCode = code
        toServer.close()
        toClient.close()
        ended.countDown()
        serverReader.shutdownNow()
    }
}

/** Starts a [FakeProcess] with a new [FakeServer] per start. */
internal class FakeStarter(
    private val configure: (FakeServer) -> Unit = {},
) : ServerStarter {
    val processes = CopyOnWriteArrayList<FakeProcess>()
    val roots = CopyOnWriteArrayList<Path>()

    val last: FakeProcess get() = processes.last()

    override fun start(
        name: String,
        command: ServerCommand,
        root: Path,
    ): ServerProcess {
        roots.add(root)
        return FakeProcess(name, FakeServer().also(configure)).also { processes += it }
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

    /** Runs the first due task; its delay. */
    fun runNext(): Duration {
        val (delay, task) = due.removeAt(0)
        task.run()
        return delay
    }
}

internal class TestSpec(
    @Volatile var command: ServerCommand? = ServerCommand(Path.of("fake-server")),
    override val runsProjectCode: Boolean = false,
    override val trustCheckId: String? = null,
    private val config: (ConfigurationItem) -> Any? = { null },
) : LanguageServerSpec {
    override val id = "test"
    override val name = "Test"

    override fun matches(file: Path) = file.fileName.toString().endsWith(".py")

    override fun languageId(file: Path) = "python"

    override fun command() = command

    override fun configuration(item: ConfigurationItem) = config(item)
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
