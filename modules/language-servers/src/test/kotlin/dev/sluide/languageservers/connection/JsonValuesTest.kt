package dev.sluide.languageservers.connection

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The JSON a spec's options and a session's params become, built without Gson's reflection. */
class JsonValuesTest {
    private fun json(text: String) = JsonParser.parseString(text)

    @Test
    fun `scalars become JSON primitives and null JSON null`() {
        assertEquals(JsonNull.INSTANCE, jsonTree(null))
        assertEquals(JsonPrimitive("text"), jsonTree("text"))
        assertEquals(JsonPrimitive(""), jsonTree(""))
        assertEquals(JsonPrimitive(true), jsonTree(true))
        assertEquals(JsonPrimitive(false), jsonTree(false))
        assertEquals(json("7"), jsonTree(7))
        assertEquals(json("2.5"), jsonTree(2.5))
        assertEquals(json("9000000000"), jsonTree(9_000_000_000L))
    }

    @Test
    fun `a Gson element is handed on as it is`() {
        val element = JsonObject().apply { addProperty("kept", 1) }

        assertSame(element, jsonTree(element))
    }

    @Test
    fun `maps and collections nest, in the order they iterate`() {
        val tree =
            jsonTree(
                linkedMapOf(
                    "b" to listOf(1, "two", true, null),
                    "a" to setOf(linkedMapOf("deep" to mapOf("deeper" to emptyList<Any>()))),
                    "empty" to emptyMap<String, Any>(),
                ),
            )

        assertEquals(json("""{"b":[1,"two",true,null],"a":[{"deep":{"deeper":[]}}],"empty":{}}"""), tree)
        assertEquals(listOf("b", "a", "empty"), (tree as JsonObject).keySet().toList())
        assertEquals(JsonArray(), jsonTree(emptyList<Any>()))
    }

    @Test
    fun `a value of another type is refused with its class, also deep inside a list or a map`() {
        val message =
            "Not a JSON value: java.lang.StringBuilder " +
                "(use Map, List, String, Number, Boolean, null or a Gson JsonElement)"

        assertEquals(message, assertFailsWith<IllegalArgumentException> { jsonTree(StringBuilder()) }.message)
        assertEquals(
            message,
            assertFailsWith<IllegalArgumentException> { jsonTree(listOf(1, StringBuilder())) }.message,
        )
        assertEquals(
            message,
            assertFailsWith<IllegalArgumentException> { jsonTree(mapOf("k" to mapOf("n" to StringBuilder()))) }.message,
        )
    }

    @Test
    fun `a map key that is no string is refused with its class`() {
        assertEquals(
            "JSON object keys must be strings, not java.lang.Integer",
            assertFailsWith<IllegalArgumentException> { jsonTree(mapOf(1 to "x")) }.message,
        )
        assertEquals(
            "JSON object keys must be strings, not null",
            assertFailsWith<IllegalArgumentException> { jsonTree(mapOf(null to "x")) }.message,
        )
    }
}
