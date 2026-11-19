package dev.sluide.httpclient.auth

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.httpclient.env.Environment
import dev.sluide.httpclient.env.EnvironmentFiles
import dev.sluide.httpclient.env.Environments
import dev.sluide.httpclient.send.HttpClients
import dev.sluide.httpclient.send.PassphraseAnswer
import dev.sluide.httpclient.send.SslConfigException
import dev.sluide.httpclient.send.SslContexts
import dev.sluide.httpclient.send.TlsServer
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.copyTo
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuth2TokenCacheTest {
    private val endpoint = TokenEndpoint()
    private val clients = HttpClients(proxy = HttpClient.Builder.NO_PROXY)
    private val clock = MutableClock()

    /** The log lines of the background tasks of the token requests, without their numbers. */
    private val taskLines = CopyOnWriteArrayList<String>()
    private val tasks = BackgroundTasks.forTests { taskLines += it.replace(Regex("task#\\d+"), "task") }
    private val cache = OAuth2TokenCache(OAuth2Client(clients, Duration.ofSeconds(5)), SslContexts(), tasks, clock)
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
    fun `every token request is a background task that fails with the readable message`() {
        respondWithRefreshTokens()
        token()
        clock.now = clock.now.plusSeconds(60)
        token()
        endpoint.respond = { 400 to """{"error": "invalid_client"}""" }
        assertFailsWith<OAuth2Exception> { cache.acquire(dev, "a") }
        assertFailsWith<OAuth2Exception> { cache.acquire(dev, "missing") }

        val lines = taskLines.map { it.replace(Regex(" (after|in) \\d+(\\.\\d+)? s"), " $1") }
        assertEquals(
            listOf(
                "task started: Acquiring token 'a'",
                "task finished in: Acquiring token 'a'",
                "task started: Refreshing token 'a'",
                "task finished in: Refreshing token 'a'",
                "task started: Acquiring token 'a'",
                "task failed after: Acquiring token 'a': Token request for 'a' failed: 400 invalid_client",
                "task started: Acquiring token 'missing'",
            ),
            lines.dropLast(1),
        )
        assertTrue(
            lines.last().startsWith("task failed after: Acquiring token 'missing': There is no auth"),
            lines.last(),
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
        val callers = CopyOnWriteArrayList<Thread>()
        val arrived = CountDownLatch(1)
        endpoint.respond = {
            arrived.countDown()
            release.await(5, TimeUnit.SECONDS)
            200 to """{"access_token": "shared", "expires_in": 60}"""
        }
        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            val futures =
                (1..8).map {
                    threads.submit(
                        Callable {
                            callers += Thread.currentThread()
                            token()
                        },
                    )
                }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))
            // The first caller waits for the answer, the other seven for the first one's lock: all eight are parked.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (callers.size < 8 || callers.any { it.state != Thread.State.WAITING }) {
                check(System.nanoTime() < deadline) { "Not all callers are waiting: ${callers.map { it.state }}" }
                Thread.sleep(5)
            }
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

    private val acquire = "Acquiring token"

    /** The environment [name] with the auths `a` and `b` for the token endpoint. */
    private fun twoAuths(name: String) =
        environment(
            """
            {"tokenUrl": "${endpoint.url}", "Security": {"Auth": {
              "a": {"Type": "OAuth2", "Grant Type": "Client Credentials", "Token URL": "{{tokenUrl}}", "Client ID": "a"},
              "b": {"Type": "OAuth2", "Grant Type": "Client Credentials", "Token URL": "{{tokenUrl}}", "Client ID": "b"}
            }}}
            """.trimIndent(),
            name,
        )

    private fun <T> onOtherThread(block: () -> T): T {
        val result = CompletableFuture<Result<T>>()
        Thread { result.complete(runCatching(block)) }.apply { isDaemon = true }.start()
        return result.get(10, TimeUnit.SECONDS).getOrThrow()
    }

    @Test
    fun `lists the tokens by environment and then auth id and clear forgets one auth in all environments`() {
        val environments = listOf("prod", "dev").map(::twoAuths)
        for (environment in environments) for (id in listOf("b", "a")) cache.acquire(environment, id)

        assertEquals(
            listOf("dev" to "a", "dev" to "b", "prod" to "a", "prod" to "b"),
            cache.tokens().map { it.environment to it.id },
        )

        cache.clear("a")

        assertEquals(listOf("dev" to "b", "prod" to "b"), cache.tokens().map { it.environment to it.id })
    }

    @Test
    fun `treats a token as expired from exactly 10 seconds before its expiry`() {
        respondWithRefreshTokens()
        assertEquals("t1", token())

        clock.now = clock.now.plusSeconds(50).minusNanos(1)
        assertEquals("t1", token())
        clock.now = clock.now.plusNanos(1)
        assertEquals("r2", token())
    }

    private val manual =
        oauthEnvironment(
            endpoint.url,
            """"Grant Type": "Client Credentials", "Client ID": "c", "Acquire Automatically": false""",
        )

    @Test
    fun `says an expired token must be acquired again when it has no refresh token and acquiring is manual`() {
        endpoint.respond = { 200 to """{"access_token": "at", "expires_in": 30}""" }
        cache.acquire(manual, "a")
        clock.now = clock.now.plusSeconds(30)

        assertEquals(
            "The token for 'a' has expired; acquire it again",
            assertFailsWith<OAuth2Exception> { token(manual) }.message,
        )
    }

    @Test
    fun `a failed refresh drops the token and is passed on when acquiring is manual`() {
        respondWithRefreshTokens()
        cache.acquire(manual, "a")
        endpoint.respond = { 400 to """{"error": "invalid_grant"}""" }
        clock.now = clock.now.plusSeconds(60)

        assertEquals(
            "Token request for 'a' failed: 400 invalid_grant",
            assertFailsWith<OAuth2Exception> { token(manual) }.message,
        )

        assertEquals(emptyList(), cache.tokens())
        assertEquals(listOf("client_credentials", "refresh_token"), endpoint.requests.map { it.param("grant_type") })
    }

    @Test
    fun `a refresh response may leave out what the old one keeps valid`() {
        // RFC 6749 6: the server may issue a new refresh token; if it does not, the client keeps using the old one.
        endpoint.respond = {
            200 to """{"access_token": "t1", "expires_in": 60, "refresh_token": "rt", "id_token": "it"}"""
        }
        token()
        clock.now = clock.now.plusSeconds(60)
        endpoint.respond = { 200 to """{"access_token": "r1", "expires_in": 60}""" }

        assertEquals("r1", token())

        assertEquals("rt", endpoint.last.param("refresh_token"))
        assertEquals("it", token(idToken = true))
        assertEquals(
            listOf(OAuth2TokenCache.TokenInfo("a", "dev", clock.now.plusSeconds(60), true)),
            cache.tokens(),
        )

        clock.now = clock.now.plusSeconds(60)
        endpoint.respond = {
            200 to """{"access_token": "r2", "expires_in": 60, "refresh_token": "rt2", "id_token": "it2"}"""
        }
        assertEquals("r2", token())
        assertEquals("it2", token(idToken = true))

        clock.now = clock.now.plusSeconds(60)
        endpoint.respond = { 200 to """{"access_token": "r3"}""" }
        assertEquals("r3", token())
        assertEquals("rt2", endpoint.last.param("refresh_token"))
        assertEquals(listOf(OAuth2TokenCache.TokenInfo("a", "dev", null, true)), cache.tokens())
    }

    @Test
    fun `tells how long token requests took`() {
        // A clock that moves 7 ms with every reading, so that each request takes exactly 7 ms.
        val nanos = AtomicLong()
        val timed =
            OAuth2TokenCache(
                OAuth2Client(clients, Duration.ofSeconds(5)),
                SslContexts(),
                tasks,
                clock,
                nanoTime = { nanos.addAndGet(7_000_000) },
            )
        respondWithRefreshTokens()

        timed.token(dev, "a", note = notes::add)
        clock.now = clock.now.plusSeconds(60)
        timed.token(dev, "a", note = notes::add)

        assertEquals(
            listOf("Acquired the token for 'a' (Client Credentials) in 7 ms", "Refreshed the token for 'a' in 7 ms"),
            notes,
        )
    }

    @Test
    fun `measures the real time of a token request by default`() {
        // The endpoint answers after 25 ms of its own clock (a poll loop, not a sleep).
        endpoint.respond = {
            val until = System.nanoTime() + Duration.ofMillis(25).toNanos()
            while (System.nanoTime() < until) Thread.onSpinWait()
            200 to """{"access_token": "at"}"""
        }

        token()

        val milliseconds =
            Regex("in (\\d+) ms")
                .find(notes.single())
                ?.groupValues
                ?.get(1)
                ?.toLong()
        assertTrue(milliseconds != null && milliseconds in 25..4_000, notes.toString())
    }

    @Test
    fun `acquiring on a fresh cache works and every call releases the lock for other threads`() {
        assertEquals(OAuth2TokenCache.TokenInfo("a", "dev", null, false), cache.acquire(dev, "a"))
        assertEquals("t1", onOtherThread { token() })

        // Another thread would wait for ever if either way of taking the lock did not release it.
        assertEquals("t1", onOtherThread { token() })
        onOtherThread { cache.acquire(dev, "a") }
        assertEquals("t2", onOtherThread { token() })
    }

    @Test
    fun `ends the task of a failure with the thread interrupted as cancelled`() {
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<OAuth2Exception> { cache.acquire(dev, "missing") }
        } finally {
            Thread.interrupted()
        }

        assertTrue(
            taskLines.any { it.startsWith("task cancelled after ") && it.endsWith(": $acquire 'missing'") },
            taskLines.toString(),
        )
    }

    @TempDir
    lateinit var folder: Path

    private val prompts = AtomicInteger()
    private val sslContexts =
        SslContexts(
            { _, _, _ ->
                prompts.incrementAndGet()
                PassphraseAnswer("secret".toCharArray(), save = false)
            },
            TlsServer.trustedCa(),
        )
    private val mtlsCache =
        OAuth2TokenCache(OAuth2Client(clients, Duration.ofSeconds(5)), sslContexts, tasks, clock)

    /** An environment `mtls` with the test client certificate (encrypted key) and the auth `a` at the endpoint. */
    private fun mtlsEnvironment(certificate: String = "client.pem"): Environment {
        TlsServer.fixture("client.pem").copyTo(folder.resolve("client.pem"))
        TlsServer.fixture("client-encrypted.key").copyTo(folder.resolve("client.key"))
        val private =
            folder.resolve(EnvironmentFiles.PRIVATE).apply {
                writeText(
                    """
                    {"mtls": {"tokenUrl": "${endpoint.url}",
                      "Security": {"Auth": {"a": {"Type": "OAuth2", "Grant Type": "Client Credentials",
                        "Token URL": "{{tokenUrl}}", "Client ID": "c"}}},
                      "SSLConfiguration": {
                        "clientCertificate": "$certificate",
                        "clientCertificateKey": {"path": "client.key", "format": "PEM"},
                        "hasCertificatePassphrase": true}}}
                    """.trimIndent(),
                )
            }
        return checkNotNull(Environments.load(EnvironmentFiles(null, private))["mtls"])
    }

    @Test
    fun `token requests use the SSL context of the environment, also when refreshing and acquiring again`() {
        respondWithRefreshTokens()
        val mtls = mtlsEnvironment()

        assertEquals("t1", mtlsCache.token(mtls, "a"))
        assertEquals(1, prompts.get())

        // A context that is created anew asks for the passphrase again, which shows that the call asked for it.
        sslContexts.clear()
        clock.now = clock.now.plusSeconds(60)
        assertEquals("r2", mtlsCache.token(mtls, "a"))
        assertEquals(2, prompts.get())

        sslContexts.clear()
        mtlsCache.acquire(mtls, "a")
        assertEquals(3, prompts.get())

        // The refresh fails and the new acquisition needs the context as well.
        sslContexts.clear()
        endpoint.respond = { request ->
            if (request.param("grant_type") == "refresh_token") {
                400 to """{"error": "x"}"""
            } else {
                200 to """{"access_token": "n"}"""
            }
        }
        clock.now = clock.now.plusSeconds(60)
        assertEquals("n", mtlsCache.token(mtls, "a"))
        assertEquals(4, prompts.get())
    }

    @Test
    fun `fails the task with the exception when the SSL configuration cannot be used`() {
        val broken = mtlsEnvironment(certificate = "missing.pem")

        assertIs<SslConfigException>(assertFailsWith<RuntimeException> { mtlsCache.token(broken, "a") })
        assertIs<SslConfigException>(assertFailsWith<RuntimeException> { mtlsCache.acquire(broken, "a") })

        assertEquals(0, endpoint.calls)
        val failed = taskLines.filter { it.startsWith("task failed after ") }
        assertEquals(2, failed.size, taskLines.toString())
        assertTrue(failed.all { it.endsWith(": $acquire 'a'") }, failed.toString())
    }

    @Test
    fun `works without a note callback`() {
        assertEquals("t1", cache.token(dev, "a"))
        assertNull(cache.acquire(dev, "a").expiresAt)
    }
}
