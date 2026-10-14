package dev.sluide.httpclient.auth

import dev.sluide.httpclient.send.HttpClients
import java.net.InetAddress
import java.net.ServerSocket
import java.net.http.HttpClient
import java.time.Duration
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuth2ClientTest {
    private val endpoint = TokenEndpoint()
    private val clients = HttpClients(proxy = HttpClient.Builder.NO_PROXY)
    private val client = OAuth2Client(clients, Duration.ofSeconds(5))

    @AfterTest
    fun stop() {
        clients.close()
        endpoint.close()
    }

    private fun config(auth: String) = OAuth2Config.of(oauthEnvironment(endpoint.url, auth), "a")

    @Test
    fun `requests a token with the client credentials grant and Basic client authentication`() {
        endpoint.respond = {
            200 to
                """
                {"access_token": "at", "token_type": "Bearer", "expires_in": 300,
                 "refresh_token": "rt", "id_token": "it"}
                """.trimIndent()
        }

        val token =
            client.grant(
                config(""""Grant Type": "Client Credentials", "Client ID": "my client", "Client Secret": "s&cret:1""""),
            )

        assertEquals("at", token.accessToken)
        assertEquals("Bearer", token.tokenType)
        assertEquals(Duration.ofSeconds(300), token.expiresIn)
        assertEquals("rt", token.refreshToken)
        assertEquals("it", token.idToken)
        val request = endpoint.last
        assertEquals("application/x-www-form-urlencoded", request.contentType)
        assertEquals(listOf("grant_type" to "client_credentials"), request.form)
        // RFC 6749 2.3.1: form-encoded before Base64.
        val expected = Base64.getEncoder().encodeToString("my+client:s%26cret%3A1".toByteArray())
        assertEquals("Basic $expected", request.authorization)
    }

    @Test
    fun `requests a token with the password grant and the client in the body`() {
        client.grant(
            config(
                """
                "Grant Type": "Password", "Client ID": "c", "Client Secret": "s", "Client Credentials": "in body",
                "Username": "user", "Password": "p&ss w=rd"
                """.trimIndent(),
            ),
        )

        val request = endpoint.last
        assertNull(request.authorization)
        assertEquals(
            listOf(
                "grant_type" to "password",
                "username" to "user",
                "password" to "p&ss w=rd",
                "client_id" to "c",
                "client_secret" to "s",
            ),
            request.form,
        )
    }

    @Test
    fun `sends only the client id without client authentication`() {
        client.grant(config(""""Grant Type": "Client Credentials", "Client ID": "c", "Client Credentials": "none""""))

        assertNull(endpoint.last.authorization)
        assertEquals(listOf("grant_type" to "client_credentials", "client_id" to "c"), endpoint.last.form)
    }

    @Test
    fun `adds the scope and the custom parameters for token requests`() {
        client.grant(
            config(
                """
                "Grant Type": "Client Credentials", "Client ID": "c", "Scope": "read write",
                "Custom Request Parameters": {
                  "audience": "api", "resource": ["r1", "r2"],
                  "token": {"Value": "t", "Use": "In Token Request"},
                  "prompt": {"Value": "login", "Use": "In Auth Request"}
                }
                """.trimIndent(),
            ),
        )

        val request = endpoint.last
        assertEquals("read write", request.param("scope"))
        assertEquals("api", request.param("audience"))
        assertEquals(listOf("r1", "r2"), request.params("resource"))
        assertEquals("t", request.param("token"))
        assertEquals(emptyList(), request.params("prompt"))
    }

    @Test
    fun `refreshes with the refresh token`() {
        val config = config(""""Grant Type": "Client Credentials", "Client ID": "c", "Scope": "s"""")

        client.refresh(config, "rt")

        assertEquals(
            listOf("grant_type" to "refresh_token", "refresh_token" to "rt", "scope" to "s"),
            endpoint.last.form,
        )
        assertTrue(
            endpoint.last.authorization
                .orEmpty()
                .startsWith("Basic "),
        )
    }

    @Test
    fun `reports error responses readably`() {
        val config = config(""""Grant Type": "Client Credentials", "Client ID": "c"""")
        endpoint.respond = { 401 to """{"error": "invalid_client", "error_description": "Unknown client"}""" }
        assertEquals(
            "Token request for 'a' failed: 401 invalid_client — Unknown client",
            assertFailsWith<OAuth2Exception> { client.grant(config) }.message,
        )

        // Bodies other than OAuth2 errors may echo secrets and stay out of the message.
        endpoint.respond = { 500 to "invalid secret 'secret'" }
        assertEquals(
            "Token request for 'a' failed: 500 Internal Server Error",
            assertFailsWith<OAuth2Exception> { client.grant(config) }.message,
        )

        endpoint.respond = { 200 to """{"token_type": "Bearer"}""" }
        assertEquals(
            "Token request for 'a' failed: the response has no access_token",
            assertFailsWith<OAuth2Exception> { client.grant(config) }.message,
        )

        endpoint.respond = { 200 to "access_token=x" }
        assertEquals(
            "Token request for 'a' failed: 200 OK, but the response is no JSON object",
            assertFailsWith<OAuth2Exception> { client.grant(config) }.message,
        )
    }

    @Test
    fun `reports an unreachable token endpoint`() {
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val url = "http://127.0.0.1:$port/token"

        val message =
            assertFailsWith<OAuth2Exception> {
                client.grant(OAuth2Config.of(oauthEnvironment(url), "a"))
            }.message.orEmpty()

        assertTrue(message.startsWith("Token request for 'a' failed: cannot connect to 127.0.0.1:"), message)
    }

    @Test
    fun `reads expires_in from numbers and numeric strings and ignores other values with a note`() {
        val config = config(""""Grant Type": "Client Credentials", "Client ID": "c"""")

        fun expiresIn(value: String): Pair<Duration?, List<String>> {
            endpoint.respond = { 200 to """{"access_token": "at", "expires_in": $value}""" }
            val response = client.grant(config)
            return response.expiresIn to response.notes
        }

        assertEquals(Duration.ofSeconds(3600) to emptyList(), expiresIn("3600"))
        assertEquals(Duration.ofSeconds(3600) to emptyList(), expiresIn("3600.9"))
        assertEquals(Duration.ofSeconds(3600) to emptyList(), expiresIn("\" 3600.5\""))
        assertEquals(Duration.ofSeconds(Long.MAX_VALUE) to emptyList(), expiresIn("${Long.MAX_VALUE}"))
        assertEquals(null to emptyList(), expiresIn("null"))
        for (value in listOf("-1", "1e30", "\"soon\"", "{\"s\": 1}", "[1]")) {
            val (duration, notes) = expiresIn(value)
            assertNull(duration, value)
            assertTrue(notes.single().startsWith("The token response for 'a' has an unusable expires_in"), value)
        }
    }
}
