package dev.sluide.languageservers.process

import dev.sluide.languageservers.LanguageServers
import dev.sluide.languageservers.ServerCommand
import dev.sluide.languageservers.ServerState
import dev.sluide.languageservers.TestSpec
import dev.sluide.languageservers.awaitTrue
import dev.sluide.languageservers.connection.ServerTiming
import dev.sluide.languageservers.connection.SleepingScheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/** Real processes: a JVM running [main] of FakeServerMain, started with the test JVM's own `java` (Linux and macOS). */
class OsServerStarterTest {
    @TempDir
    lateinit var folder: Path

    private var servers: LanguageServers? = null

    @AfterEach
    fun closeServers() {
        servers?.close()
    }

    private val command =
        ServerCommand(
            executable = Path.of(System.getProperty("java.home"), "bin", "java"),
            arguments =
                listOf(
                    "-cp",
                    System.getProperty("java.class.path"),
                    "dev.sluide.languageservers.process.FakeServerMainKt",
                ),
        )

    private fun running(): Pair<TestSpec, ProcessHandle> {
        val before = LiveServerProcesses.all()
        val spec = TestSpec(command = command)
        val created =
            LanguageServers(
                specs = listOf(spec),
                projectFolder = { folder },
                trustGate = { _, _ -> true },
                dispatch = Runnable::run,
                starter = OsServerStarter,
                scheduler = SleepingScheduler,
                timing = ServerTiming(),
                nanoTime = System::nanoTime,
            )
        servers = created
        created.session(spec).ensureStarted()
        // A JVM starts in well under a second here; CI runners are slower.
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        while (created.state(spec).value != ServerState.Running) {
            check(System.nanoTime() < deadline) { "Server not running: ${created.state(spec).value}" }
            Thread.sleep(20)
        }
        return spec to (LiveServerProcesses.all() - before).single()
    }

    @Test
    fun `a server process is listed while it runs and ends when its window closes`() {
        val (_, process) = running()

        servers!!.close()

        awaitTrue("server process ended") { !process.isAlive }
        awaitTrue("server process no longer listed") { process !in LiveServerProcesses.all() }
    }

    @Test
    fun `the JVM shutdown hook's work kills a server still running`() {
        val (spec, process) = running()

        LiveServerProcesses.destroyAll()

        awaitTrue("server process killed") { !process.isAlive }
        assertFalse(process in LiveServerProcesses.all())
        awaitTrue("crash noticed") { servers!!.state(spec).value is ServerState.Restarting }
        assertEquals(ServerState.Restarting(1), servers!!.state(spec).value)
    }
}
