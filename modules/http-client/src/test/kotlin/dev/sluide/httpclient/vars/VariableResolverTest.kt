package dev.sluide.httpclient.vars

import dev.sluide.httpclient.env.Environment
import dev.sluide.httpclient.parse.FileVariable
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VariableResolverTest {
    private val dynamic =
        DynamicVariables(
            Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC),
            Random(1),
            mapOf("USER" to "me")::get,
        )

    private fun environment(json: String) =
        Environment("dev", JsonMapper.builder().build().readTree(json) as ObjectNode, emptyMap(), null, emptyList())

    private fun resolver(
        environment: String? = null,
        vararg variables: Pair<String, String>,
        auth: (String, Boolean) -> String? = { _, _ -> null },
    ) = VariableResolver(
        environment?.let(::environment),
        variables.mapIndexed { line, (name, value) -> FileVariable(name, value, line) },
        dynamic,
        auth,
    )

    @Test
    fun `replaces environment values, also nested and non-text ones`() {
        val resolver =
            resolver("""{"host": "example.com", "api": {"version": 2, "key": "k"}, "ids": [1, 2], "on": true}""")

        assertEquals(
            "https://example.com/v2?key=k&ids=[1,2]&on=true&api={\"version\":2,\"key\":\"k\"}",
            resolver.resolve("https://{{host}}/v{{api.version}}?key={{api.key}}&ids={{ids}}&on={{on}}&api={{api}}"),
        )
    }

    @Test
    fun `prefers a flat key with dots over a path`() {
        assertEquals("flat", resolver("""{"a.b": "flat", "a": {"b": "nested"}}""").resolve("{{a.b}}"))
    }

    @Test
    fun `replaces in-file variables that use the environment and other variables`() {
        val resolver =
            resolver(
                """{"host": "example.com"}""",
                "base" to "https://{{host}}/{{version}}",
                "version" to "v1",
                "url" to "{{base}}/users",
            )

        assertEquals("https://example.com/v1/users", resolver.resolve("{{url}}"))
    }

    @Test
    fun `takes the environment before in-file variables and dynamic names first`() {
        val resolver = resolver("""{"host": "env", "${'$'}timestamp": "env"}""", "host" to "file")

        assertEquals("env 1000", resolver.resolve("{{host}} {{\$timestamp}}"))
    }

    @Test
    fun `takes the last definition of an in-file variable`() {
        assertEquals("2", resolver(null, "a" to "1", "a" to "2").resolve("{{a}}"))
    }

    @Test
    fun `replaces dynamic variables and calls`() {
        val resolver = resolver()

        assertEquals("me 1000 7", resolver.resolve("{{\$env.USER}} {{\$timestamp}} {{\$random.integer(7, 8)}}"))
    }

    @Test
    fun `asks for auth tokens`() {
        val resolver = resolver(auth = { id, idToken -> if (idToken) "id-$id" else "access-$id" })

        assertEquals(
            "Bearer access-api id-api",
            resolver.resolve("Bearer {{\$auth.token(\"api\")}} {{\$auth.idToken(\"api\")}}"),
        )
    }

    @Test
    fun `lists all unresolved variables`() {
        val error =
            assertFailsWith<UnresolvedVariablesException> {
                resolver(null, "a" to "{{b}}").resolve("{{x}}/{{a}}/{{x}}/{{\$auth.token(\"none\")}}/{{\$nope}}")
            }

        assertEquals(listOf("x", "b", "\$auth.token(none)", "\$nope"), error.names)
    }

    @Test
    fun `detects cycles of in-file variables`() {
        val error =
            assertFailsWith<UnresolvedVariablesException> {
                resolver(null, "a" to "x{{b}}", "b" to "y{{a}}").resolve("{{a}}")
            }

        assertEquals(listOf("a -> b -> a"), error.names)
    }

    @Test
    fun `keeps text without variables`() {
        assertEquals("{\"json\": {\"a\": 1}}", resolver().resolve("{\"json\": {\"a\": 1}}"))
    }

    @Test
    fun `resolves variables inside environment values`() {
        val resolver =
            resolver(
                """{"base": "https://{{host}}/{{api.version}}", "host": "{{fileHost}}", "api": {"version": "v{{n}}"}, "n": 2}""",
                "fileHost" to "example.com",
                "url" to "{{base}}/{{stamp}}",
                "stamp" to "{{\$timestamp}}",
            )

        assertEquals("https://example.com/v2/1000", resolver.resolve("{{url}}"))
    }

    @Test
    fun `reports unresolved variables and cycles inside environment values`() {
        val resolver = resolver("""{"a": "{{b}}", "b": "x{{a}}", "c": "{{missing}}"}""")

        assertEquals(
            listOf("a -> b -> a"),
            assertFailsWith<UnresolvedVariablesException> { resolver.resolve("{{a}}") }.names,
        )
        assertEquals(
            listOf("missing"),
            assertFailsWith<UnresolvedVariablesException> { resolver.resolve("{{c}}") }.names,
        )
    }

    @Test
    fun `leaves unresolved variables as written when resolving partially`() {
        val partial = resolver("""{"host": "h"}""").resolvePartially("{{ x }} {{\$auth.token(\"id\")}} {{host}}")

        assertEquals(
            VariableResolver.Partial("{{ x }} {{\$auth.token(\"id\")}} h", listOf("x", "\$auth.token(id)")),
            partial,
        )
    }

    @Test
    fun `says why a dynamic variable has no value`() {
        val error =
            assertFailsWith<UnresolvedVariablesException> { resolver().resolve("{{\$random.alphabetic(20000)}}") }

        assertEquals(listOf("\$random.alphabetic(20000) (at most 10000 characters)"), error.names)
    }
}
