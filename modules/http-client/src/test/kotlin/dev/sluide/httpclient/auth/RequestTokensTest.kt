package dev.sluide.httpclient.auth

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.httpclient.send.HttpClients
import dev.sluide.httpclient.send.SslContexts
import java.net.http.HttpClient
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestTokensTest {
    private val endpoint = TokenEndpoint()
    private val clients = HttpClients(proxy = HttpClient.Builder.NO_PROXY)
    private val cache =
        OAuth2TokenCache(
            OAuth2Client(clients, Duration.ofSeconds(5)),
            SslContexts(),
            BackgroundTasks.forTests(),
        )

    @AfterTest
    fun stop() {
        clients.close()
        endpoint.close()
    }

    @Test
    fun `takes a token once per request and kind`() {
        endpoint.respond = { 200 to """{"access_token": "at", "id_token": "it"}""" }
        val tokens = RequestTokens(cache, oauthEnvironment(endpoint.url))

        assertEquals("at", tokens.token("a", false))
        assertEquals("at", tokens.token("a", false))
        assertEquals(1, endpoint.calls)

        assertEquals("it", tokens.token("a", true))
        assertEquals("it", tokens.token("a", true))
        assertEquals(1, endpoint.calls, "the cache holds both tokens of one acquisition")
        assertEquals(listOf("at", "it"), tokens.taken.sorted())
    }

    @Test
    fun `keeps the token for the request even if the cache gets a new one`() {
        val environment = oauthEnvironment(endpoint.url)
        val tokens = RequestTokens(cache, environment)
        assertEquals("t1", tokens.token("a", false))

        cache.acquire(environment, "a")

        assertEquals("t1", tokens.token("a", false))
        assertEquals("t2", RequestTokens(cache, environment).token("a", false))
        assertEquals(listOf("t1"), tokens.taken)
    }
}
