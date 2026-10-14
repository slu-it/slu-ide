package dev.sluide.httpclient.auth

import dev.sluide.httpclient.send.HttpClients
import dev.sluide.httpclient.send.SslContexts
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OAuth2TokenCacheTest {
    private val endpoint = TokenEndpoint()
    private val clients = HttpClients(proxy = HttpClient.Builder.NO_PROXY)
    private val clock = MutableClock()
    private val cache = OAuth2TokenCache(OAuth2Client(clients, Duration.ofSeconds(5)), SslContexts(), clock)
    private val notes = mutableListOf<String>()

    @AfterTest
    fun stop() {
        clients.close()
        endpoint.close()
    }

    private val dev = oauthEnvironment(endpoint.url)

    private fun token(
        environment: dev.sluide.httpclient.env.Environment = dev,
        idToken: Boolean = false,
    ) = cache.token(environment, "a", idToken, notes::add)

    /** Answers grants with `t<n>` and refreshes with `r<n>`, with the given lifetime and a refresh token. */
    private fun respondWithRefreshTokens(expiresIn: Int = 60) {
        endpoint.respond = { request ->
            val prefix = if (request.param("grant_type") == "refresh_token") "r" else "t"
            200 to """{"access_token": "$prefix${endpoint.calls}", "expires_in": $expiresIn, "refresh_token": "rt"}"""
        }
    }

    @Test
    fun `reuses a token until 10 seconds before it expires, then refreshes it`() {
        respondWithRefreshTokens()

        assertEquals("t1", token())
        clock.now = clock.now.plusSeconds(49)
        assertEquals("t1", token())
        assertEquals(1, endpoint.calls)

        clock.now = clock.now.plusSeconds(2)
        assertEquals("r2", token())
        assertEquals(listOf("grant_type" to "refresh_token", "refresh_token" to "rt"), endpoint.last.form)
        assertTrue(notes[0].startsWith("Acquired the token for 'a' (Client Credentials) in "), notes[0])
        assertTrue(notes[1].startsWith("Refreshed the token for 'a' in "), notes[1])

        assertEquals("r2", token())
        assertEquals(2, endpoint.calls)
    }

    @Test
    fun `acquires a new token when refreshing fails`() {
        respondWithRefreshTokens()
        token()
        endpoint.respond = { request ->
            if (request.param("grant_type") == "refresh_token") {
                400 to """{"error": "invalid_grant"}"""
            } else {
                200 to """{"access_token": "new", "expires_in": 60}"""
            }
        }
        clock.now = clock.now.plusSeconds(60)

        assertEquals("new", token())

        assertEquals(
            listOf("client_credentials", "refresh_token", "client_credentials"),
            endpoint.requests.map {
                it.param("grant_type")
            },
        )
        assertTrue(
            notes.any {
                it.startsWith("Refreshing the token for 'a' failed (Token request for 'a' failed: 400 invalid_grant)")
            },
            notes.toString(),
        )
    }

    @Test
    fun `acquires a new token when an expired one has no refresh token`() {
        endpoint.respond = { 200 to """{"access_token": "t${endpoint.calls}", "expires_in": "30"}""" }
        assertEquals("t1", token())

        clock.now = clock.now.plusSeconds(25)

        assertEquals("t2", token())
        assertEquals(
            listOf("client_credentials", "client_credentials"),
            endpoint.requests.map { it.param("grant_type") },
        )
    }

    @Test
    fun `keeps a token without expiry until it is cleared`() {
        assertEquals("t1", token())
        clock.now = clock.now.plus(Duration.ofDays(400))
        assertEquals("t1", token())

        cache.clear("a")

        assertEquals("t2", token())
        cache.clearAll()
        assertEquals(emptyList(), cache.tokens())
    }

    @Test
    fun `keeps tokens per environment and acquires a new one when the configuration changes`() {
        val prod = oauthEnvironment(endpoint.url, name = "prod")
        val changed =
            oauthEnvironment(
                endpoint.url,
                """"Grant Type": "Client Credentials", "Client ID": "client", "Client Secret": "new"""",
            )

        assertEquals("t1", token(dev))
        assertEquals("t2", token(prod))
        assertEquals("t1", token(dev))
        assertEquals("t3", token(changed))
        assertEquals(
            listOf(
                OAuth2TokenCache.TokenInfo("a", "dev", null, false),
                OAuth2TokenCache.TokenInfo("a", "prod", null, false),
            ),
            cache.tokens(),
        )
    }

    @Test
    fun `asks to acquire first when acquiring automatically is off`() {
        val manual =
            oauthEnvironment(
                endpoint.url,
                """"Grant Type": "Client Credentials", "Client ID": "c", "Acquire Automatically": false""",
            )
        respondWithRefreshTokens(expiresIn = 100)

        assertEquals(
            "Acquire the token for 'a' first (Acquire Automatically is off)",
            assertFailsWith<OAuth2Exception> { token(manual) }.message,
        )
        assertEquals(0, endpoint.calls)

        val info = cache.acquire(manual, "a")
        assertEquals(OAuth2TokenCache.TokenInfo("a", "dev", clock.now.plusSeconds(100), true), info)
        assertEquals("t1", token(manual))

        clock.now = clock.now.plusSeconds(95)
        assertEquals("r2", token(manual))
    }

    @Test
    fun `acquire replaces a valid token`() {
        assertEquals("t1", token())

        cache.acquire(dev, "a")

        assertEquals("t2", token())
    }

    @Test
    fun `concurrent requests share one token request`() {
        val release = CountDownLatch(1)
        endpoint.respond = {
            release.await(5, TimeUnit.SECONDS)
            200 to """{"access_token": "shared", "expires_in": 60}"""
        }
        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            val futures = (1..8).map { threads.submit(Callable { token() }) }
            Thread.sleep(200)
            release.countDown()

            assertEquals(List(8) { "shared" }, futures.map { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, endpoint.calls)
        }
    }

    @Test
    fun `gives the ID token when asked for it or when the configuration says so`() {
        endpoint.respond = { 200 to """{"access_token": "at", "id_token": "it"}""" }
        val useIdToken =
            oauthEnvironment(
                endpoint.url,
                """"Grant Type": "Client Credentials", "Client ID": "c", "Use ID Token": true""",
            )

        assertEquals("at", token())
        assertEquals("it", token(idToken = true))
        assertEquals("it", token(useIdToken))
    }

    @Test
    fun `fails readably when there is no ID token`() {
        assertEquals(
            "The token response for 'a' has no id_token",
            assertFailsWith<OAuth2Exception> { token(idToken = true) }.message,
        )
    }

    @Test
    fun `passes token request failures on`() {
        endpoint.respond = { 401 to """{"error": "invalid_client"}""" }

        assertEquals(
            "Token request for 'a' failed: 401 invalid_client",
            assertFailsWith<OAuth2Exception> { token() }.message,
        )
        assertEquals(emptyList(), cache.tokens())
    }

    @Test
    fun `keeps a token with a huge lifetime and passes notes on`() {
        endpoint.respond = { 200 to """{"access_token": "t${endpoint.calls}", "expires_in": ${Long.MAX_VALUE}}""" }

        assertEquals("t1", token())
        clock.now = clock.now.plus(Duration.ofDays(365_000))
        assertEquals("t1", token())
        assertEquals(Instant.MAX, cache.tokens().single().expiresAt)

        endpoint.respond = { 200 to """{"access_token": "t", "expires_in": "soon"}""" }
        cache.acquire(dev, "a", notes::add)
        assertTrue(notes.last().startsWith("The token response for 'a' has an unusable expires_in"), notes.toString())
    }
}
