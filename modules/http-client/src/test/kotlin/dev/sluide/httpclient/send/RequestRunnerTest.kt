package dev.sluide.httpclient.send

import dev.sluide.httpclient.auth.OAuth2Client
import dev.sluide.httpclient.auth.OAuth2TokenCache
import dev.sluide.httpclient.auth.TokenEndpoint
import dev.sluide.httpclient.auth.oauthEnvironment
import dev.sluide.httpclient.env.Environment
import dev.sluide.httpclient.env.EnvironmentFiles
import dev.sluide.httpclient.env.Environments
import dev.sluide.httpclient.parse.HttpFileParser
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpClient
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.copyTo
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestRunnerTest {
    private val server = TestServer()
    private val endpoint = TokenEndpoint()
    private val clients = HttpClients(proxy = HttpClient.Builder.NO_PROXY)
    private val executor = RequestExecutor(clients)

    /** The passphrase prompt; runs on the executor thread. */
    @Volatile
    private var prompt: () -> CharArray? = { "secret".toCharArray() }
    private val sslContexts = SslContexts({ prompt() }, TlsServer.trustedCa(), clients::evict)
    private val tokens = OAuth2TokenCache(OAuth2Client(clients), sslContexts)
    private val runner = RequestRunner(executor, tokens, sslContexts)
    private val dev = oauthEnvironment(endpoint.url)

    @TempDir
    lateinit var folder: Path

    @AfterTest
    fun stop() {
        executor.close()
        clients.close()
        endpoint.close()
        server.close()
    }

    private fun start(
        text: String,
        environment: Environment? = dev,
    ): Pair<Cancellable, CompletableFuture<ExchangeResult>> {
        val fileText = "@base = ${server.base}\n\n$text"
        val request = HttpFileParser.parse(fileText).requests.single()
        val result = CompletableFuture<ExchangeResult>()
        val cancellable = runner.run(Path.of("a.http"), fileText, request, environment, result::complete)
        return cancellable to result
    }

    private fun run(
        text: String,
        environment: Environment? = dev,
    ): ExchangeResult = start(text, environment).second.get(30, TimeUnit.SECONDS)

    @Test
    fun `sends a request with an OAuth2 token and reuses the token`() {
        val text = "GET {{base}}/echo\nAuthorization: Bearer {{\$auth.token(\"a\")}}"

        val first = assertIs<HttpResult>(run(text))
        val second = assertIs<HttpResult>(run(text))

        assertTrue("authorization: Bearer t1" in first.body.toString(Charsets.UTF_8))
        assertTrue("authorization: Bearer t1" in second.body.toString(Charsets.UTF_8))
        assertEquals(1, endpoint.calls)
        assertTrue(
            first.notes.any {
                it.startsWith("Acquired the token for 'a' (Client Credentials) in ")
            },
            first.notes.toString(),
        )
        assertTrue(second.notes.none { "token" in it }, second.notes.toString())
    }

    @Test
    fun `finds tokens behind variables`() {
        endpoint.respond = { 200 to """{"access_token": "at", "id_token": "it"}""" }

        val result =
            assertIs<HttpResult>(run("@token = {{\$auth.idToken(\"a\")}}\nGET {{base}}/echo\nX-Token: {{token}}"))

        assertTrue("x-token: it" in result.body.toString(Charsets.UTF_8))
    }

    @Test
    fun `does not send the request when the token request fails`() {
        val sent = AtomicBoolean(false)
        server.handle("/api") { exchange ->
            sent.set(true)
            server.respond(exchange, 200, "")
        }
        endpoint.respond = { 401 to """{"error": "invalid_client", "error_description": "Bad secret"}""" }

        val failure = assertIs<HttpFailure>(run("GET {{base}}/api\nAuthorization: Bearer {{\$auth.token(\"a\")}}"))

        assertEquals("Token request for 'a' failed: 401 invalid_client — Bad secret", failure.message)
        assertFalse(sent.get())
    }

    @Test
    fun `reports unresolved variables and a missing environment`() {
        assertEquals("Unresolved variables: missing", assertIs<HttpFailure>(run("GET {{base}}/{{missing}}")).message)
        assertTrue(
            "needs an environment" in
                assertIs<HttpFailure>(
                    run("GET {{base}}/echo\nAuthorization: Bearer {{\$auth.token(\"a\")}}", null),
                ).message,
        )
    }

    @Test
    fun `cancels a running token request`() {
        val release = CountDownLatch(1)
        endpoint.respond = {
            release.await(30, TimeUnit.SECONDS)
            200 to """{"access_token": "late"}"""
        }
        val (cancellable, result) = start("GET {{base}}/echo\nAuthorization: Bearer {{\$auth.token(\"a\")}}")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (endpoint.calls == 0) {
            check(System.nanoTime() < deadline) { "The token request did not arrive" }
            Thread.sleep(10)
        }

        cancellable.cancel()

        val failure = assertIs<HttpFailure>(result.get(5, TimeUnit.SECONDS))
        assertTrue(failure.cancelled)
        assertEquals("Cancelled", failure.message)
        release.countDown()
    }

    @Test
    fun `sends the client certificate of the environment`() {
        TlsServer().use { tls ->
            val result = assertIs<HttpResult>(run("GET https://localhost:${tls.port}/", mtlsEnvironment()))

            assertEquals("CN=SluIde Test Client", result.body.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `cancels runs that wait for the passphrase prompt`() {
        val prompting = CountDownLatch(1)
        val answer = CountDownLatch(1)
        prompt = {
            prompting.countDown()
            answer.await()
            "secret".toCharArray()
        }
        val mtls = mtlsEnvironment()
        val (first, firstResult) = start("GET https://localhost:1/", mtls)
        assertTrue(prompting.await(5, TimeUnit.SECONDS))
        // The second run waits for the first one's prompt, as both need the same files.
        val (second, secondResult) = start("GET https://localhost:1/", mtls)

        second.cancel()
        first.cancel()

        for (result in listOf(firstResult, secondResult)) {
            val failure = assertIs<HttpFailure>(result.get(5, TimeUnit.SECONDS))
            assertEquals("Cancelled", failure.message)
            assertTrue(failure.cancelled)
        }
        answer.countDown()
    }

    /** An environment with the test client certificate and its encrypted key. */
    private fun mtlsEnvironment(): Environment? {
        TlsServer.fixture("client.pem").copyTo(folder.resolve("client.pem"))
        TlsServer.fixture("client-encrypted.key").copyTo(folder.resolve("client.key"))
        val private =
            folder.resolve(EnvironmentFiles.PRIVATE).apply {
                writeText(
                    """
                    {"mtls": {"SSLConfiguration": {
                      "clientCertificate": "client.pem", "clientCertificateKey": {"path": "client.key", "format": "PEM"},
                      "hasCertificatePassphrase": true
                    }}}
                    """.trimIndent(),
                )
            }
        return Environments.load(EnvironmentFiles(null, private))["mtls"]
    }
}
