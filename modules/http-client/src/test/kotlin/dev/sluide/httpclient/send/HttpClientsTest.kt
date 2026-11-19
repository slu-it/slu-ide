package dev.sluide.httpclient.send

import java.net.http.HttpClient
import java.time.Duration
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpClientsTest {
    private val timeout = Duration.ofSeconds(5)

    private fun key(sslContext: SSLContext? = null) =
        HttpClients.ClientKey(
            followRedirects = true,
            cookieJar = false,
            connectTimeout = timeout,
            sslContext = sslContext,
        )

    private fun context() = SSLContext.getInstance("TLS").apply { init(null, null, null) }

    /** Waits until [client] is terminated, which the JDK does asynchronously after a shutdown. */
    private fun terminates(client: HttpClient) = client.awaitTermination(Duration.ofSeconds(10))

    @Test
    fun `hands out one client per key`() {
        HttpClients().use { clients ->
            assertSame(clients.client(key()), clients.client(key()))
            assertNotSame(clients.client(key()), clients.client(key().copy(followRedirects = false)))
        }
    }

    @Test
    fun `hands out no client after close and says so`() {
        val clients = HttpClients()
        clients.close()

        val failure = assertFailsWith<IllegalStateException> { clients.client(key()) }
        assertEquals("The HTTP clients are closed", failure.message)
    }

    @Test
    fun `close stops every client`() {
        val clients = HttpClients()
        val first = clients.client(key())
        val second = clients.client(key().copy(cookieJar = true))

        clients.close()

        assertTrue(terminates(first))
        assertTrue(terminates(second))
    }

    @Test
    fun `evicting a context stops its clients only and builds a new one on the next request`() {
        val evicted = context()
        val kept = context()
        HttpClients().use { clients ->
            val evictedClient = clients.client(key(evicted))
            val keptClient = clients.client(key(kept))
            val plain = clients.client(key())

            clients.evict(evicted)

            assertTrue(terminates(evictedClient))
            assertFalse(keptClient.isTerminated)
            assertFalse(plain.isTerminated)
            assertSame(keptClient, clients.client(key(kept)))
            assertSame(plain, clients.client(key()))
            assertNotSame(evictedClient, clients.client(key(evicted)))
        }
    }
}
