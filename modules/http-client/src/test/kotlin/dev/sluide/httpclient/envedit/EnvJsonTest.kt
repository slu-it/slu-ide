package dev.sluide.httpclient.envedit

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvJsonTest {
    @Test
    fun `parses text with a leading byte order mark`() {
        assertEquals("1", EnvJson.parse("{\"a\": 1}")?.get("a")?.let(EnvJson::numberText))
    }

    @Test
    fun `reads numbers with their spelling`() {
        assertEquals("1.50", EnvJson.number("1.50")?.let(EnvJson::numberText))
        assertEquals("1e3", EnvJson.number(" 1e3 ")?.let(EnvJson::numberText))
        assertEquals("-7", EnvJson.number("-7")?.let(EnvJson::numberText))
        assertEquals("12345678901234567890", EnvJson.number("12345678901234567890")?.let(EnvJson::numberText))
    }

    @Test
    fun `reads nothing else as a number`() {
        assertNull(EnvJson.number("abc"))
        assertNull(EnvJson.number(""))
        assertNull(EnvJson.number("1 2"))
        assertNull(EnvJson.number("\"1\""))
        assertNull(EnvJson.number("1e9999999999"))
    }

    @Test
    fun `refuses numbers out of range`() {
        val error = assertFailsWith<EnvJsonException> { EnvJson.parse("""{"a": [1e9999999999]}""") }

        assertEquals("Number out of range: 1e9999999999", error.message)
    }

    @Test
    fun `writes a file back as it was read, with key order, number spellings and escapes`() {
        val text =
            """
            {
              "z": 1,
              "a": 3000000000,
              "n": -9223372036854775808,
              "big": 12345678901234567890,
              "d": 1.5,
              "d2": 1.50,
              "e": 1e3,
              "E": 1E+3,
              "neg": -0.5e-7,
              "list": [
                true,
                false,
                null,
                [],
                {}
              ],
              "s": "q\" b\\ \n é \u0001"
            }
            """.trimIndent() + "\n"

        assertEquals(text, EnvJson.print(EnvJson.parse(text)!!))
    }

    @Test
    fun `reads integers of each size as numbers with their value`() {
        val node = EnvJson.parse("""[7, 3000000000, -9223372036854775808, 18446744073709551616, 1.5, 1e3]""")!!

        assertEquals(
            listOf("7", "3000000000", "-9223372036854775808", "18446744073709551616", "1.5", "1e3"),
            node.values().map(EnvJson::numberText),
        )
        assertEquals(3000000000L, node[1].longValue())
        assertEquals(7, node[0].intValue())
    }

    @Test
    fun `a number read equals the number node of the same value, so that writing it again is no change`() {
        assertEquals(EnvJson.nodes.numberNode(80), EnvJson.number("80"))
        assertEquals(EnvJson.nodes.numberNode(3000000000L), EnvJson.number("3000000000"))
        assertEquals(
            EnvJson.nodes.numberNode(BigInteger("18446744073709551616")),
            EnvJson.number("18446744073709551616"),
        )
        assertEquals(EnvJson.nodes.numberNode(80), EnvJson.parse("[80]")?.get(0))
        assertEquals(EnvJson.nodes.numberNode(3000000000L), EnvJson.parse("[3000000000]")?.get(0))
    }

    @Test
    fun `refuses what follows the value and duplicate keys, and reads blank text as no value`() {
        val after = assertFailsWith<EnvJsonException> { EnvJson.parse("{} 1") }
        val duplicate = assertFailsWith<EnvJsonException> { EnvJson.parse("""{"a": 1, "a": 2}""") }

        assertEquals("Unexpected content after the end of the value", after.message)
        assertTrue(duplicate.message.orEmpty().startsWith("Duplicate Object property \"a\""), duplicate.message)
        assertNull(EnvJson.parse(""))
        assertNull(EnvJson.parse("  \n"))
    }
}
