package dev.sluide.httpclient.vars

import dev.sluide.testsupport.collidingWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicVariablesTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-14T08:15:30.123456Z"), ZoneOffset.UTC)
    private val variables = DynamicVariables(clock, Random(42), mapOf("HOME" to "/home/me")::get)

    private fun value(
        name: String,
        vararg args: String,
    ) = variables.value(name, args.toList())

    private fun text(
        name: String,
        vararg args: String,
    ) = assertNotNull(value(name, *args))

    @Test
    fun `gives the time`() {
        assertEquals("1791965730", value("\$timestamp"))
        assertEquals("2026-10-14T08:15:30.123Z", value("\$isoTimestamp"))
    }

    @Test
    fun `gives random version 4 UUIDs`() {
        val uuid = UUID.fromString(value("\$uuid"))
        assertEquals(4, uuid.version())
        assertEquals(2, uuid.variant())
        assertNotEquals(uuid.toString(), value("\$random.uuid"))
    }

    @Test
    fun `gives the same values for the same seed`() {
        val other = DynamicVariables(clock, Random(42)) { null }

        assertEquals(other.value("\$random.alphanumeric", listOf("20")), value("\$random.alphanumeric", "20"))
    }

    @Test
    fun `gives random integers in range`() {
        repeat(200) {
            assertTrue(text("\$randomInt").toInt() in 0..999)
            assertTrue(text("\$random.integer").toInt() in 0..999)
            assertTrue(text("\$random.integer", "5").toInt() in 0..4)
            assertTrue(text("\$random.integer", "-3", "-1").toInt() in -3..-2)
        }
        assertEquals("7", value("\$random.integer", "7", "8"))
    }

    @Test
    fun `gives random floats in range`() {
        repeat(200) {
            assertTrue(text("\$random.float").toDouble() in 0.0..<1000.0)
            assertTrue(text("\$random.float", "0.5").toDouble() in 0.0..<0.5)
            assertTrue(text("\$random.float", "1.5", "2").toDouble() in 1.5..<2.0)
        }
    }

    @Test
    fun `gives random strings of characters`() {
        assertTrue(Regex("[a-zA-Z]{12}").matches(text("\$random.alphabetic", "12")))
        assertTrue(Regex("\\w{30}").matches(text("\$random.alphanumeric", "30")))
        assertTrue(Regex("[0-9a-f]{16}").matches(text("\$random.hexadecimal", "16")))
        assertEquals("", value("\$random.hexadecimal", "0"))
        assertTrue(Regex("[a-z]{8}@[a-z]{6}\\.com").matches(text("\$random.email")))
    }

    @Test
    fun `reads the operating system environment`() {
        assertEquals("/home/me", value("\$env.HOME"))
        assertNull(value("\$env.MISSING"))
    }

    @Test
    fun `gives nothing for unknown names and invalid arguments`() {
        assertNull(value("\$unknown"))
        assertNull(value("\$uuid", "1"))
        assertNull(value("\$random.integer", "a"))
        assertNull(value("\$random.integer", "5", "5"))
        assertNull(value("\$random.integer", "1", "2", "3"))
        assertNull(value("\$random.alphabetic"))
        assertNull(value("\$random.alphabetic", "-1"))
        assertNotNull(value("\$random.float", "-1.5", "1e3"))
    }

    @Test
    fun `reads the real operating system environment by default`() {
        assertEquals(System.getenv("PATH"), DynamicVariables().value("\$env.PATH"))
    }

    @Test
    fun `gives no environment value when arguments are passed`() {
        assertNull(value("\$env.HOME", "x"))
    }

    @Test
    fun `draws the random integer and the characters from the random generator`() {
        assertEquals(Random(5).nextInt(0, 1000).toString(), DynamicVariables(clock, Random(5)).value("\$randomInt"))

        val generator = Random(6)
        val alphanumeric = ('a'..'z') + ('A'..'Z') + ('0'..'9') + '_'
        assertEquals(
            String(CharArray(30) { alphanumeric[generator.nextInt(alphanumeric.size)] }),
            DynamicVariables(clock, Random(6)).value("\$random.alphanumeric", listOf("30")),
        )
        val hexGenerator = Random(7)
        val hex = "0123456789abcdef"
        assertEquals(
            String(CharArray(20) { hex[hexGenerator.nextInt(hex.length)] }),
            DynamicVariables(clock, Random(7)).value("\$random.hexadecimal", listOf("20")),
        )
    }

    @Test
    fun `gives nothing for invalid float and character arguments`() {
        assertNull(value("\$random.float", "a"))
        assertNull(value("\$random.float", "1", "2", "3"))
        assertNull(value("\$random.float", "5", "5"))
        assertNull(value("\$random.alphabetic", "x"))
        assertNull(value("\$random.alphabetic", "1", "2"))
    }

    @Test
    fun `compares the names, not only their hash codes`() {
        val names =
            mapOf(
                "\$uuid" to emptyList(),
                "\$random.uuid" to emptyList(),
                "\$timestamp" to emptyList(),
                "\$isoTimestamp" to emptyList(),
                "\$randomInt" to emptyList(),
                "\$random.integer" to emptyList(),
                "\$random.float" to emptyList(),
                "\$random.alphabetic" to listOf("5"),
                "\$random.alphanumeric" to listOf("5"),
                "\$random.hexadecimal" to listOf("5"),
                "\$random.email" to emptyList(),
            )
        for ((name, args) in names) {
            assertNotNull(variables.value(name, args), name)
            // Same String.hashCode as the real name, which a compiled `when` over strings checks first.
            val twin = collidingWith(name)
            assertNull(variables.value(twin, args), twin)
        }
    }

    @Test
    fun `refuses random strings longer than 10000 characters`() {
        assertEquals(10_000, text("\$random.alphanumeric", "10000").length)
        assertFailsWith<IllegalArgumentException> { value("\$random.hexadecimal", "10001") }
    }

    @Test
    fun `resolves every listed name`() {
        // The arguments the names that need one are called with; the others take none.
        val arguments =
            mapOf(
                "\$random.alphabetic" to "5",
                "\$random.alphanumeric" to "5",
                "\$random.hexadecimal" to "5",
            )

        for (entry in DYNAMIC_VARIABLE_NAMES) {
            val args = listOfNotNull(arguments[entry.name])
            assertNotNull(variables.value(entry.name, args), "${entry.name} does not resolve")
        }
    }

    @Test
    fun `lists each name once with a hint for those taking arguments`() {
        val names = DYNAMIC_VARIABLE_NAMES.associate { it.name to it.hint }

        assertEquals(DYNAMIC_VARIABLE_NAMES.size, names.size)
        assertEquals(
            setOf(
                "\$uuid",
                "\$random.uuid",
                "\$timestamp",
                "\$isoTimestamp",
                "\$randomInt",
                "\$random.integer",
                "\$random.float",
                "\$random.alphabetic",
                "\$random.alphanumeric",
                "\$random.hexadecimal",
                "\$random.email",
            ),
            names.keys,
        )
        assertEquals("(from, to)", names["\$random.integer"])
        assertEquals("(from, to)", names["\$random.float"])
        assertEquals("(length)", names["\$random.alphabetic"])
        assertNull(names["\$uuid"])
    }
}
