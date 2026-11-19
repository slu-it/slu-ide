package dev.sluide.languageservers.connection

import dev.sluide.languageservers.FakeProcess
import dev.sluide.languageservers.FakeScheduler
import dev.sluide.languageservers.FakeServer
import dev.sluide.languageservers.FakeStarter
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.RefreshKind
import dev.sluide.languageservers.ServerState
import dev.sluide.languageservers.TestSpec
import dev.sluide.languageservers.TrustGate
import dev.sluide.languageservers.awaitTrue
import dev.sluide.languageservers.process.ServerProcess
import dev.sluide.languageservers.process.ServerStarter
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drives a [ServerLifecycle] without a session: its published states in [states], fake processes from [starter],
 * retries run by hand through [scheduler]. The waits are short so that a held process is given up on quickly.
 */
internal abstract class ServerLifecycleTestBase {
    @TempDir
    lateinit var folder: Path

    protected val states = CopyOnWriteArrayList<Pair<ServerState, Int?>>()
    protected var starter: ServerStarter = FakeStarter()
    protected val scheduler = FakeScheduler()
    private val lifecycles = CopyOnWriteArrayList<ServerLifecycle>()
    private val fakeStarters = CopyOnWriteArrayList<FakeStarter>()

    @AfterEach
    fun closeLifecycles() {
        lifecycles.forEach(ServerLifecycle::close)
        fakeStarters.forEach { it.processes.forEach(FakeProcess::release) }
    }

    protected fun fakeStarter(
        newServer: (index: Int) -> FakeServer = { FakeServer() },
        beforeStart: (index: Int) -> Unit = {},
        holdsEnd: (index: Int) -> Boolean = { false },
    ) = FakeStarter(newServer, beforeStart, holdsEnd).also {
        fakeStarters += it
        starter = it
    }

    protected val noEvents =
        object : ClientEvents {
            override fun configuration(item: ConfigurationItem): Any? = null

            override fun diagnostics(params: PublishDiagnosticsParams) = Unit

            override fun refresh(kind: RefreshKind) = Unit

            override fun featuresChanged() = Unit
        }

    protected fun lifecycle(
        spec: LanguageServerSpec = TestSpec(),
        timing: ServerTiming = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
        projectFolder: () -> Path? = { folder },
        trustGate: TrustGate = TrustGate { _, _ -> true },
        nanoTime: () -> Long = System::nanoTime,
        workspaceFolders: (Path) -> List<Path> = { listOf(it) },
    ): ServerLifecycle =
        ServerLifecycle(
            spec,
            projectFolder,
            trustGate,
            starter,
            scheduler,
            timing,
            nanoTime,
            { state, epoch -> states += state to epoch },
            workspaceFolders,
        ) { features, name, _ -> SessionClient(name, { listOf(folder) }, features, noEvents) }
            .also { lifecycles += it }

    protected fun awaitState(
        message: String,
        condition: (ServerState) -> Boolean,
    ) = awaitTrue(message) { states.any { condition(it.first) } }

    protected fun awaitRunning(count: Int = 1) =
        awaitTrue("running $count times") { states.count { it.first == ServerState.Running } >= count }

    protected fun started(
        lifecycle: ServerLifecycle,
        evenIfRefused: Boolean = false,
    ): ServerLifecycle {
        lifecycle.ensureStarted(evenIfRefused)
        awaitRunning()
        return lifecycle
    }

    /** Waits until everything the lifecycle's outbound thread had queued has run. */
    protected fun flushOutbound(lifecycle: ServerLifecycle) {
        val done = CountDownLatch(1)
        lifecycle.onServer { done.countDown() }
        check(done.await(5, TimeUnit.SECONDS)) { "the outbound thread did not run its queue" }
    }
}

/** A process whose stdin offers [hook] each chunk written to it, as text, before the write; the hook may block or throw. */
internal class HookedStdin(
    private val process: FakeProcess,
    private val hook: (written: String) -> Unit,
) : ServerProcess by process {
    override val output: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = process.output.write(b)

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {
                hook(String(b, off, len, Charsets.UTF_8))
                process.output.write(b, off, len)
            }

            override fun flush() = process.output.flush()
        }
}
