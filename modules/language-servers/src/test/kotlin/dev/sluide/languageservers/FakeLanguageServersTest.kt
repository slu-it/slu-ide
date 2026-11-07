package dev.sluide.languageservers

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.time.Duration.Companion.seconds

/** The fixture other modules' tests drive sessions with. */
class FakeLanguageServersTest {
    @TempDir
    lateinit var folder: Path

    @Test
    fun `runs a fake server per start and restarts it after a crash when the test says so`() {
        val spec = TestSpec()
        FakeLanguageServers(listOf(spec), folder).use { fake ->
            val first = fake.awaitRunning(spec)

            fake.crashLatest()
            awaitTrue("restarting") { fake.servers.state(spec).value == ServerState.Restarting(1) }
            assertEquals(1.seconds, fake.runNextRetry())
            val second = fake.awaitRunning(spec)

            assertNotSame(first, second)
            assertEquals(listOf(first, second), fake.started)
            assertEquals(listOf("initialize", "initialized"), second.calls)
        }
    }
}
