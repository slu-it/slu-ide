package dev.sluide.jshell.agent

import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

class OutputEventsTest {
    private val texts = mutableListOf<String>()
    private val events = OutputEvents { texts += it }

    @Test
    fun `each write is one text`() {
        events.write("ab".toByteArray())
        events.write('c'.code)
        assertEquals(listOf("ab", "c"), texts)
    }

    @Test
    fun `a character split between writes arrives whole with the second`() {
        val bytes = "ü😀".toByteArray(Charsets.UTF_8)
        events.write(bytes, 0, 1)
        events.write(bytes, 1, 3)
        events.write(bytes, 4, 2)
        assertEquals(listOf("ü", "😀"), texts)
    }

    @Test
    fun `malformed bytes become replacement characters`() {
        events.write(byteArrayOf('a'.code.toByte(), 0xff.toByte(), 'b'.code.toByte()))
        assertEquals(listOf("a�b"), texts)
    }

    @Test
    fun `a print stream's output arrives as written`() {
        PrintStream(events, true, "UTF-8").print("x€y")
        assertEquals("x€y", texts.joinToString(""))
    }

    @Test
    fun `the exit report is taken out of the text with its status`() {
        assertEquals(3 to "ab", exitReport("a${EXIT_MARK}Runtime.exit() called with status: 3\nb"))
        assertEquals(-1 to "", exitReport("${EXIT_MARK}Runtime.exit() called with status: -1\n"))
        assertEquals(255 to "", exitReport("${EXIT_MARK}Runtime.exit() called with status: 255"))
    }

    @Test
    fun `text without the report stays as it is`() {
        assertEquals(null to "status: 3\n", exitReport("status: 3\n"))
        assertEquals(null to "\u0000other 3\n", exitReport("\u0000other 3\n"))
    }
}
