package dev.sluide.httpclient.auth

import dev.sluide.httpclient.auth.OAuth2Config.Grant
import dev.sluide.httpclient.env.AuthConfig.ClientCredentials
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuth2ConfigTest {
    private fun failure(
        auth: String,
        id: String = "a",
    ) = assertFailsWith<OAuth2Exception> {
        OAuth2Config.of(environment("""{"Security": {"Auth": {"a": {$auth}}}}"""), id)
    }.message

    @Test
    fun `resolves all values with the environment`() {
        val environment =
            environment(
                """
                {"base": "http://127.0.0.1:1", "id": "client", "secret": "s", "user": "u",
                 "Security": {"Auth": {"a": {
                   "Type": "OAuth2", "Grant Type": "Password", "Token URL": "{{base}}/token",
                   "Client ID": "{{id}}", "Client Secret": "{{secret}}", "Client Credentials": "in body",
                   "Username": "{{user}}", "Password": "p3ss", "Scope": "read {{user}}",
                   "Custom Request Parameters": {
                     "audience": "{{base}}",
                     "resource": ["r1", {"Value": "r2", "Use": "In Token Request"}],
                     "prompt": {"Value": "login", "Use": "In Auth Request"}
                   },
                   "Use ID Token": true, "Acquire Automatically": false
                 }}}}
                """.trimIndent(),
            )

        val config = OAuth2Config.of(environment, "a")

        assertEquals(Grant.PASSWORD, config.grant)
        assertEquals(URI("http://127.0.0.1:1/token"), config.tokenUrl)
        assertEquals("client", config.clientId)
        assertEquals("s", config.clientSecret)
        assertEquals(ClientCredentials.IN_BODY, config.clientCredentials)
        assertEquals("u", config.username)
        assertEquals("p3ss", config.password)
        assertEquals("read u", config.scope)
        assertEquals(
            listOf("audience" to "http://127.0.0.1:1", "resource" to "r1", "resource" to "r2"),
            config.parameters,
        )
        assertTrue(config.useIdToken)
        assertFalse(config.acquireAutomatically)
        assertFalse("p3ss" in config.toString())
    }

    @Test
    fun `refuses types other than OAuth2`() {
        assertEquals("Auth 'a' is of Type 'Basic'; only OAuth2 is supported", failure(""""Type": "Basic""""))
        assertEquals("Auth 'a' has no Type; only OAuth2 is supported", failure(""""Grant Type": "Password""""))
    }

    @Test
    fun `names the supported grant types`() {
        assertEquals(
            "Grant Type 'Authorization Code' of auth 'a' is not supported; supported are Client Credentials and Password",
            failure(
                """"Type": "OAuth2", "Grant Type": "Authorization Code", "Token URL": "http://h", "Client ID": "c"""",
            ),
        )
        assertTrue(
            "Device Authorization" in
                failure(""""Type": "OAuth2", "Grant Type": "Device Authorization"""").orEmpty(),
        )
        assertTrue("has no Grant Type" in failure(""""Type": "OAuth2"""").orEmpty())
    }

    @Test
    fun `needs a token URL, a client id and for the password grant the user's credentials`() {
        assertEquals(
            "Auth 'a' needs a Token URL",
            failure(""""Type": "OAuth2", "Grant Type": "Client Credentials", "Client ID": "c""""),
        )
        assertEquals(
            "Auth 'a' needs a Client ID",
            failure(""""Type": "OAuth2", "Grant Type": "Client Credentials", "Token URL": "http://h/t""""),
        )
        assertEquals(
            "Auth 'a' needs a Username and a Password for the Password grant",
            failure(""""Type": "OAuth2", "Grant Type": "Password", "Token URL": "http://h/t", "Client ID": "c""""),
        )
        assertTrue(
            "must be an http or https URL" in
                failure(
                    """"Type": "OAuth2", "Grant Type": "Client Credentials", "Token URL": "ftp://h/t", "Client ID": "c"""",
                ).orEmpty(),
        )
    }

    @Test
    fun `lists unresolved variables`() {
        assertEquals(
            "Auth 'a' has unresolved variables: base, secret",
            failure(
                """
                "Type": "OAuth2", "Grant Type": "Client Credentials", "Token URL": "{{base}}/t",
                "Client ID": "c", "Client Secret": "{{secret}}"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `needs an environment with the auth id`() {
        assertEquals("There is no auth 'b' in Security.Auth of environment 'dev'", failure(""""Type": "OAuth2"""", "b"))
        assertTrue(
            "needs an environment" in assertFailsWith<OAuth2Exception> { OAuth2Config.of(null, "a") }.message.orEmpty(),
        )
    }
}
