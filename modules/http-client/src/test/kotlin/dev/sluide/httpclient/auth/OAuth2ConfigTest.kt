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

    private fun config(
        vararg members: String,
        tokenUrl: String = "http://h/t",
    ) = OAuth2Config.of(
        environment(
            """{"Security": {"Auth": {"a": {"Type": "OAuth2", "Grant Type": "Client Credentials", "Client ID": "c",
                |"Token URL": "$tokenUrl"${members.joinToString("") { ", $it" }}}}}}
            """.trimMargin(),
        ),
        "a",
    )

    @Test
    fun `names the config without secrets`() {
        assertEquals(
            "OAuth2Config(a, Client Credentials, http://h/t)",
            config(""""Client Secret": "s3cret"""").toString(),
        )
        assertEquals("http://h:0/t", OAuth2Config.display(URI("http://h:0/t")))
        assertEquals("http://h", OAuth2Config.display(URI("http://h?x=1")))
        // A Token URL may carry credentials in its user info or query: toString goes to logs.
        assertEquals(
            "OAuth2Config(a, Client Credentials, https://h:8443/t)",
            config(tokenUrl = "https://user:pw@h:8443/t?client_secret=s3cret#frag").toString(),
        )
    }

    @Test
    fun `trims the token URL and refuses an empty or blank one`() {
        assertEquals(URI("http://h/t"), config(tokenUrl = "  http://h/t ").tokenUrl)
        assertEquals("Auth 'a' needs a Token URL", assertFailsWith<OAuth2Exception> { config(tokenUrl = "") }.message)
        assertEquals("Auth 'a' needs a Token URL", assertFailsWith<OAuth2Exception> { config(tokenUrl = "  ") }.message)
    }

    @Test
    fun `refuses an empty client id and drops a blank scope`() {
        assertEquals(
            "Auth 'a' needs a Client ID",
            failure(
                """"Type": "OAuth2", "Grant Type": "Client Credentials", "Token URL": "http://h/t", "Client ID": """"",
            ),
        )
        assertEquals(null, config(""""Scope": """"").scope)
        assertEquals(null, config(""""Scope": "  """").scope)
        assertEquals("a b", config(""""Scope": "a b"""").scope)
    }

    @Test
    fun `the password grant needs both the username and the password`() {
        val base = """"Type": "OAuth2", "Grant Type": "Password", "Token URL": "http://h/t", "Client ID": "c""""
        val message = "Auth 'a' needs a Username and a Password for the Password grant"

        assertEquals(message, failure("""$base, "Username": "u""""))
        assertEquals(message, failure("""$base, "Password": "p""""))
    }

    @Test
    fun `names the supported grants when there is none and tells unknown from unsupported ones`() {
        assertEquals(
            "Auth 'a' has no Grant Type; supported are Client Credentials and Password",
            failure(""""Type": "OAuth2""""),
        )
        assertEquals(
            "Grant Type 'foo' of auth 'a' is unknown; supported are Client Credentials and Password",
            failure(""""Type": "OAuth2", "Grant Type": "foo""""),
        )
        assertEquals(
            "Grant Type 'implicit' of auth 'a' is not supported; supported are Client Credentials and Password",
            failure(""""Type": "OAuth2", "Grant Type": "implicit""""),
        )
    }

    @Test
    fun `accepts http and https token URLs in any case and refuses others`() {
        assertEquals("HTTPS://h/t", config(tokenUrl = "HTTPS://h/t").tokenUrl.toString())
        for (url in listOf("h/t", "http:/nohost/t", "file:///t")) {
            val message = assertFailsWith<OAuth2Exception> { config(tokenUrl = url) }.message.orEmpty()
            assertEquals("The Token URL of auth 'a' must be an http or https URL with a host", message)
        }
    }

    @Test
    fun `reports the syntax error of a token URL`() {
        // The text is not echoed: it may hold a password (`user:pw@`) or a secret in the query.
        val message =
            assertFailsWith<OAuth2Exception> { config(tokenUrl = "http://user:pw@h t?client_secret=s3cret") }.message

        assertEquals("The Token URL of auth 'a' is invalid: Illegal character in authority", message)
    }

    @Test
    fun `does not echo a token URL that is no http URL`() {
        val message = assertFailsWith<OAuth2Exception> { config(tokenUrl = "ftp://user:pw@h/t?secret=1") }.message

        assertEquals("The Token URL of auth 'a' must be an http or https URL with a host", message)
    }

    @Test
    fun `needs an environment with the auth id`() {
        assertEquals("There is no auth 'b' in Security.Auth of environment 'dev'", failure(""""Type": "OAuth2"""", "b"))
        assertTrue(
            "needs an environment" in assertFailsWith<OAuth2Exception> { OAuth2Config.of(null, "a") }.message.orEmpty(),
        )
    }
}
