package dev.sluide.jshell.agent

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {
    @Test
    fun `strings are written with JSON escapes`() {
        assertEquals(
            """{"a":"q\"b\\s\n\r\t\u0001\u001f x"}""",
            Json.write(mapOf("a" to "q\"b\\s\n\r\t\u0001\u001f x")),
        )
    }

    @Test
    fun `values of every kind are written in order`() {
        assertEquals(
            """{"n":null,"t":true,"f":false,"i":5,"l":-7,"list":[{"x":"y"},[]],"other":"1.5"}""",
            Json.write(
                linkedMapOf(
                    "n" to null,
                    "t" to true,
                    "f" to false,
                    "i" to 5,
                    "l" to -7L,
                    "list" to listOf(mapOf("x" to "y"), emptyList<Any>()),
                    "other" to 1.5,
                ),
            ),
        )
    }

    @Test
    fun `astral characters are written as they are and a lone surrogate is escaped`() {
        assertEquals("{\"a\":\"ü😀\\ud800x\\udc00\"}", Json.write(mapOf("a" to "ü😀\uD800x\uDC00")))
        // at the ends of the text
        assertEquals("{\"a\":\"\\udc00x\\ud800\"}", Json.write(mapOf("a" to "\uDC00x\uD800")))
    }

    @Test
    fun `what is written parses back to the same values`() {
        val value =
            linkedMapOf<String, Any?>(
                "text" to "\"\\\n\t ü€😀 \u0000",
                "n" to 3L,
                "items" to listOf(mapOf("text" to "charAt(", "smart" to true)),
                "none" to null,
            )
        assertEquals(value, Json.parse(Json.write(value)))
    }

    @Test
    fun `numbers, literals, escapes and blanks are read`() {
        assertEquals(
            linkedMapOf<String, Any?>(
                "a" to 12L,
                "b" to -3L,
                "c" to 1.5e3,
                "d" to true,
                "e" to false,
                "f" to null,
                "g" to "x/\b\u000c\"\\\n\r\t😀é",
                "h" to listOf(1L, "two", emptyMap<String, Any?>()),
                "i" to emptyList<Any?>(),
                "j" to listOf(0.5, 90L, 9L, 19L),
            ),
            Json.parse(
                """ { "a" : 12 , "b":-3,"c":1.5e3,"d":true,"e":false,"f":null,""" +
                    """"g":"x\/\b\f\"\\\n\r\t\ud83d\ude00\u00E9","h":[ 1 , "two" , { } ],"i":[ ],""" +
                    """"j":[0.5,90,9,19] } """,
            ),
        )
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '`',
        textBlock = """
        ``                       | unexpected end
        `   `                    | unexpected end
        [1]                      | an object expected
        "text"                   | an object expected
        {                        | unexpected end
        {"a"                     | unexpected end
        {"a":                    | unexpected end
        {"a":1                   | unexpected end
        {"a" 1}                  | ':' expected
        {"a":1,}                 | '"' expected
        {"a":1 "b":2}            | ',' or '}' expected
        {a:1}                    | '"' expected
        {"a":"open}              | unexpected end
        {"a":"x\               | unexpected end
        {"a":"\x"}             | bad escape '\x'
        {"a":"\u12             | short \u escape
        {"a":"\u0041           | unexpected end
        {"a":"\u12zz"}         | For input string: "12zz" under radix 16
        {"a":tru}                | bad literal
        {"a":nul}                | bad literal
        {"a":[1,2}               | ',' or ']' expected
        {"a":[1 2]}              | ',' or ']' expected
        {"a":-}                  | For input string: "-"
        {"a":1.2.3}              | multiple points
        {"a":+1}                 | unexpected '+'
        {"a"::1}                 | unexpected ':'
        {"n":1:2}                | ',' or '}' expected
        {"a":1} x                | text after the object
        {"a":1}}                 | text after the object""",
    )
    fun `malformed text is rejected with what is wrong`(
        text: String,
        message: String,
    ) {
        assertEquals(message, assertFailsWith<IllegalArgumentException> { Json.parse(text) }.message)
    }

    @Test
    fun `a raw control character in a string is rejected`() {
        assertEquals(
            "control character in a string",
            assertFailsWith<IllegalArgumentException> { Json.parse("{\"a\":\"x\ny\"}") }.message,
        )
    }

    @Test
    fun `nesting up to the limit is read and one level deeper is rejected`() {
        fun nested(levels: Int): String = (1 until levels).fold("{}") { inner, _ -> "{\"a\":$inner}" }
        Json.parse(nested(Json.MAX_DEPTH))
        assertEquals(
            "nested deeper than 16",
            assertFailsWith<IllegalArgumentException> {
                Json.parse(
                    nested(Json.MAX_DEPTH + 1),
                )
            }.message,
        )

        fun arrays(levels: Int) = "{\"a\":" + "[".repeat(levels) + "]".repeat(levels) + "}"
        Json.parse(arrays(Json.MAX_DEPTH - 1))
        assertFailsWith<IllegalArgumentException> { Json.parse(arrays(Json.MAX_DEPTH)) }
    }

    @Test
    fun `very deep nesting is rejected without exhausting the stack`() {
        assertFailsWith<IllegalArgumentException> { Json.parse("{\"a\":" + "[".repeat(200_000)) }
    }
}
