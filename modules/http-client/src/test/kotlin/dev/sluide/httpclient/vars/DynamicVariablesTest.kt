package dev.sluide.httpclient.vars

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
    fun `refuses random strings longer than 10000 characters`() {
        assertEquals(10_000, text("\$random.alphanumeric", "10000").length)
        assertFailsWith<IllegalArgumentException> { value("\$random.hexadecimal", "10001") }
    }
}
