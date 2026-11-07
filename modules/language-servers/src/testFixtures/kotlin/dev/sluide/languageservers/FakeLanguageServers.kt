package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ServerTiming
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * [LanguageServers] over in-process [FakeServer]s instead of processes: for logic tests of the modules that use
 * language servers (`testImplementation(testFixtures(project(":modules:language-servers")))`). The specs' commands
 * are asked as usual (`null` stays [ServerState.Unavailable]) but never run; [newServer] makes the server of start
 * number `index` (from 0). Results and listener calls run on the thread that delivers them unless [dispatch] says
 * otherwise; restarts after a crash wait for [runNextRetry].
 */
class FakeLanguageServers(
    specs: List<LanguageServerSpec>,
    projectFolder: Path?,
    trustGate: TrustGate = TrustGate { _, _ -> true },
    dispatch: (Runnable) -> Unit = Runnable::run,
    newServer: (index: Int) -> FakeServer = { FakeServer() },
) : AutoCloseable {
    private val starter = FakeStarter(newServer)
    private val scheduler = FakeScheduler()

    /** The servers under test. */
    val servers =
        LanguageServers(
            specs = specs,
            projectFolder = { projectFolder },
            trustGate = trustGate,
            dispatch = dispatch,
            starter = starter,
            scheduler = scheduler,
            timing = ServerTiming(shutdown = 200.milliseconds, exit = 200.milliseconds),
            nanoTime = System::nanoTime,
        )

    /** The fake servers started so far, oldest first. */
    val started: List<FakeServer> get() = starter.processes.map { it.server }

    /** Starts [spec]'s server and waits until it runs; the fake server that runs. */
    fun awaitRunning(spec: LanguageServerSpec): FakeServer {
        val session = servers.session(spec)
        session.ensureStarted()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (session.state.value != ServerState.Running) {
            check(System.nanoTime() < deadline) { "${spec.id} not running: ${session.state.value}" }
            Thread.sleep(10)
        }
        return starter.last.server
    }

    /** The latest server process dies as a crash would end it. */
    fun crashLatest() = starter.last.crash()

    /** Runs the restart that waits after a crash; its delay. */
    fun runNextRetry(): Duration = scheduler.runNext()

    override fun close() = servers.close()
}
