package dev.sluide.jshell.engine

import dev.sluide.jshell.ChunkedInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StderrTailTest {
    @Test
    fun `a short stream is kept whole`() {
        val tail = StderrTail()
        tail.drain("Picked up JAVA_TOOL_OPTIONS: x\nboom\n".byteInputStream())
        assertEquals("Picked up JAVA_TOOL_OPTIONS: x\nboom\n", tail.text())
    }

    @Test
    fun `only the last bytes are kept, across many reads`() {
        val input = ChunkedInput()
        val text = (0 until 5000).joinToString("") { "$it\n" }
        text.chunked(700).forEach(input::feed)
        input.end()
        val tail = StderrTail(limit = 1000)
        tail.drain(input)
        assertEquals(text.takeLast(1000), tail.text())
    }

    @Test
    fun `a character cut at the start becomes a replacement character`() {
        val tail = StderrTail(limit = 4)
        tail.drain("xxéabc".byteInputStream())
        assertEquals("�abc", tail.text())
    }

    @Test
    fun `the end is awaited`() {
        val input = ChunkedInput()
        val tail = StderrTail()
        assertFalse(tail.awaitEnd(10))
        val drainer = Thread { tail.drain(input) }.apply { start() }
        input.feed("a")
        input.end()
        assertTrue(tail.awaitEnd(10_000))
        assertEquals("a", tail.text())
        drainer.join(10_000)
    }
}
