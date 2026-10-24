package dev.sluide.httpclient

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.httpclient.send.HttpClients
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class HttpClientSessionTest {
    private val session = HttpClientSession(BackgroundTasks.forTests(), Runnable::run)

    @Test
    fun `remembers the environment per file`() {
        val api = Path.of("project", "api.http")

        session.chooseEnvironment(api, "dev")
        session.chooseEnvironment(Path.of("project", "other.http"), "local")

        assertEquals("dev", session.environment(api.toAbsolutePath()))
        assertEquals("dev", session.environment(Path.of("project", "x", "..", "api.http")))
        session.chooseEnvironment(api, null)
        assertNull(session.environment(api))
        assertEquals("local", session.environment(Path.of("project", "other.http")))
    }

    @Test
    fun `the environment follows a moved file`() {
        val old = Path.of("project", "api.http")
        val new = Path.of("project", "users.http")
        session.chooseEnvironment(old, "dev")

        session.moveEnvironment(old, new)

        assertNull(session.environment(old))
        assertEquals("dev", session.environment(new))
    }

    @Test
    fun `a background task that throws still reports its failure`() {
        val done = CompletableFuture<Result<Int>>()

        session.inBackground({ error("boom") }, done::complete).join()

        assertEquals("boom", done.get(5, TimeUnit.SECONDS).exceptionOrNull()?.message)
    }

    @Test
    fun `a background task that fails with an error still reports it`() {
        val done = CompletableFuture<Result<Int>>()

        session.inBackground({ throw StackOverflowError("deep") }, done::complete).join()

        assertIs<StackOverflowError>(done.get(5, TimeUnit.SECONDS).exceptionOrNull())
    }

    @Test
    fun `a background task hands its value over`() {
        val done = CompletableFuture<Result<Int>>()

        session.inBackground({ 42 }, done::complete).join()

        assertEquals(42, done.get(5, TimeUnit.SECONDS).getOrNull())
    }

    @Test
    fun `closing the handle stops the git status listener`() {
        var calls = 0
        val handle = session.onGitStatusChanged { calls++ }

        session.gitStatusChanged()
        handle.close()
        session.gitStatusChanged()

        assertEquals(1, calls)
    }

    @Test
    fun `closing stops the clients`() {
        session.close()

        assertFailsWith<IllegalStateException> {
            session.clients.client(HttpClients.ClientKey(true, true, Duration.ofSeconds(1)))
        }
    }
}
