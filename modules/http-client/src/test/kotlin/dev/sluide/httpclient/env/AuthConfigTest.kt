package dev.sluide.httpclient.env

import dev.sluide.httpclient.env.AuthConfig.ClientCredentials
import dev.sluide.httpclient.env.AuthConfig.CustomParameter
import dev.sluide.httpclient.env.AuthConfig.ParameterUse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

/** [AuthConfig.parse]: one `Security.Auth.<id>` object, value by value, and what it reports for the rest. */
internal class AuthConfigTest {
    private val problems = mutableListOf<String>()

    private fun parse(json: String) = AuthConfig.parse("a", JsonMapper.builder().build().readTree(json), problems)

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "basic,BASIC",
        "BASIC,BASIC",
        "' Basic ',BASIC",
        "in body,IN_BODY",
        "' IN Body ',IN_BODY",
        "none,NONE",
        "NONE,NONE",
    )
    fun `reads the client credentials ignoring case and blanks around them`(
        text: String,
        expected: ClientCredentials,
    ) {
        assertEquals(expected, parse("""{"Client Credentials": "$text"}""").clientCredentials)
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `client credentials that are not given are basic without a problem`() {
        assertEquals(ClientCredentials.BASIC, parse("{}").clientCredentials)
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `unknown client credentials give basic and a problem with the text as written`() {
        assertEquals(ClientCredentials.BASIC, parse("""{"Client Credentials": "Header"}""").clientCredentials)
        assertEquals(
            listOf("Auth \"a\": unknown Client Credentials \"Header\" (basic, in body or none); basic is used"),
            problems,
        )
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("true,true", "TRUE,true", "false,false", "False,false")
    fun `reads flags given as the strings true and false in any case`(
        text: String,
        expected: Boolean,
    ) {
        val config = parse("""{"Use ID Token": "$text", "Acquire Automatically": "$text"}""")

        assertEquals(expected, config.useIdToken)
        assertEquals(expected, config.acquireAutomatically)
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `reads flags given as booleans`() {
        val on = parse("""{"Use ID Token": true, "Acquire Automatically": true}""")
        val off = parse("""{"Use ID Token": false, "Acquire Automatically": false}""")

        assertEquals(
            listOf(true, true, false, false),
            listOf(on.useIdToken, on.acquireAutomatically, off.useIdToken, off.acquireAutomatically),
        )
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `a flag that is neither keeps its default and is reported`() {
        val config = parse("""{"Use ID Token": "yes", "Acquire Automatically": 0}""")

        assertEquals(false, config.useIdToken)
        assertEquals(true, config.acquireAutomatically)
        assertEquals(
            listOf(
                "Auth \"a\": \"Use ID Token\" must be true or false",
                "Auth \"a\": \"Acquire Automatically\" must be true or false",
            ),
            problems,
        )
    }

    @Test
    fun `values that are no strings are read as their JSON text`() {
        val config =
            parse(
                """
                {"Client ID": 42, "Scope": ["a", "b"], "Username": null, "Token URL": "https://t",
                 "Custom Request Parameters": {"n": 5, "t": true, "o": {"Value": {"k": 1}}}}
                """.trimIndent(),
            )

        assertEquals("42", config.clientId)
        assertEquals("[\"a\",\"b\"]", config.scope)
        assertEquals("null", config.username)
        assertEquals("https://t", config.tokenUrl)
        assertEquals(
            listOf(
                CustomParameter("n", "5", ParameterUse.EVERYWHERE),
                CustomParameter("t", "true", ParameterUse.EVERYWHERE),
                CustomParameter("o", "{\"k\":1}", ParameterUse.EVERYWHERE),
            ),
            config.customRequestParameters,
        )
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "Everywhere,EVERYWHERE",
        "EVERYWHERE,EVERYWHERE",
        "In Auth Request,IN_AUTH_REQUEST",
        "' in auth request ',IN_AUTH_REQUEST",
        "In Token Request,IN_TOKEN_REQUEST",
        "' IN TOKEN REQUEST ',IN_TOKEN_REQUEST",
    )
    fun `reads where a custom parameter is used, ignoring case and blanks`(
        text: String,
        expected: ParameterUse,
    ) {
        val config = parse("""{"Custom Request Parameters": {"p": {"Value": "v", "Use": "$text"}}}""")

        assertEquals(listOf(CustomParameter("p", "v", expected)), config.customRequestParameters)
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `a custom parameter without a use is used everywhere, an unknown use is reported`() {
        val config =
            parse(
                """{"Custom Request Parameters": {"p": {"Value": "v"}, "q": {"Value": "w", "Use": "Sometimes"}}}""",
            )

        assertEquals(
            listOf(
                CustomParameter("p", "v", ParameterUse.EVERYWHERE),
                CustomParameter("q", "w", ParameterUse.EVERYWHERE),
            ),
            config.customRequestParameters,
        )
        assertEquals(listOf("Auth \"a\": custom parameter \"q\" has an unknown Use; Everywhere is used"), problems)
    }

    @Test
    fun `a custom parameter object without a value is dropped and reported, the others stay`() {
        val config =
            parse("""{"Custom Request Parameters": {"p": {"Use": "Everywhere"}, "q": ["x", {"Use": "x"}, "y"]}}""")

        assertEquals(
            listOf(
                CustomParameter("q", "x", ParameterUse.EVERYWHERE),
                CustomParameter("q", "y", ParameterUse.EVERYWHERE),
            ),
            config.customRequestParameters,
        )
        assertEquals(
            listOf(
                "Auth \"a\": custom parameter \"p\" has no Value",
                "Auth \"a\": custom parameter \"q\" has no Value",
            ),
            problems,
        )
    }

    @Test
    fun `custom request parameters that are no object are reported and give none`() {
        for (json in listOf("[]", "\"x\"", "5")) {
            problems.clear()

            assertEquals(emptyList(), parse("""{"Custom Request Parameters": $json}""").customRequestParameters, json)
            assertEquals(listOf("Auth \"a\": Custom Request Parameters must be an object"), problems, json)
        }
        problems.clear()
        assertEquals(emptyList(), parse("{}").customRequestParameters)
        assertEquals(emptyList(), problems)
    }
}
