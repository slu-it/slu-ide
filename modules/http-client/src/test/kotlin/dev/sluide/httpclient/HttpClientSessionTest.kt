package dev.sluide.httpclient

import dev.sluide.httpclient.send.HttpClients
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpClientSessionTest {
    private val session = HttpClientSession(Runnable::run)

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
    fun `closing stops the clients`() {
        session.close()

        assertFailsWith<IllegalStateException> {
            session.clients.client(HttpClients.ClientKey(true, true, Duration.ofSeconds(1)))
        }
    }
}
