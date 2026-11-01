package dev.sluide.docker.tab.logs

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogTextTest {
    @Test
    fun `colours, cursor codes and titles are stripped`() {
        assertEquals("red plain", cleanLogText("\u001B[31mred\u001B[0m plain"))
        assertEquals("ab", cleanLogText("a\u001B[2K\u001B[1Gb"))
        assertEquals("text", cleanLogText("\u001B]0;title\u0007text"))
        assertEquals("text", cleanLogText("\u001B]8;;http://x\u001B\\text"))
    }

    @Test
    fun `only the text after the last carriage return stays`() {
        assertEquals("100%", cleanLogText("10%\r50%\r100%"))
        assertEquals("done", cleanLogText("done\r"))
    }

    @Test
    fun `other control characters go, tabs stay`() {
        assertEquals("a\tb", cleanLogText("a\u0000\tb\u0007"))
    }

    @Test
    fun `the timestamp is split off once`() {
        val (time, text) = splitTimestamp("2026-11-01T13:44:03.123456789Z hello world")
        assertEquals(Instant.parse("2026-11-01T13:44:03.123456789Z"), time)
        assertEquals("hello world", text)
    }

    @Test
    fun `a line without a timestamp is kept whole`() {
        assertEquals(null to "plain 2026", splitTimestamp("plain 2026"))
        assertEquals(null to "2026-11-01Tnope text", splitTimestamp("2026-11-01Tnope text"))
    }

    @Test
    fun `a line of only a timestamp has empty text`() {
        assertEquals("", splitTimestamp("2026-11-01T13:44:03Z").second)
    }

    @Test
    fun `times are shown as local hours to milliseconds`() {
        val time = Instant.parse("2026-11-01T13:44:03.123456789Z")
        assertEquals("13:44:03.123", formatLogTime(time, ZoneOffset.UTC))
        assertEquals("15:44:03.123", formatLogTime(time, ZoneOffset.ofHours(2)))
    }

    @ParameterizedTest
    @CsvSource("ERROR,Error: boom,true", "error,Error: boom,true", "warn,Error: boom,false", "'',anything,true")
    fun `the filter is a case-insensitive substring`(
        filter: String,
        text: String,
        expected: Boolean,
    ) {
        assertEquals(expected, matchesFilter(text, filter))
    }

    @Test
    fun `follow is on only at the end of the scroll bar`() {
        assertTrue(followsAfterScroll(1.0, 1.0))
        assertFalse(followsAfterScroll(0.8, 1.0))
    }

    @Test
    fun `the buffer drops the oldest lines beyond its capacity`() {
        val buffer = LogBuffer(3)
        repeat(5) { buffer.append(null, "line $it") }
        assertEquals(3, buffer.size)
        assertEquals(2, buffer.firstSeq)
        assertEquals(listOf("line 2", "line 3", "line 4"), buffer.filtered("").map { it.text })
    }

    @Test
    fun `filtering keeps notes`() {
        val buffer = LogBuffer(10)
        buffer.append(null, "alpha")
        buffer.append(null, "beta")
        buffer.append(null, "stopped", note = true)
        assertEquals(listOf("beta", "stopped"), buffer.filtered("BET").map { it.text })
        assertEquals(listOf("alpha", "stopped"), buffer.filtered("alp").map { it.text })
    }

    @Test
    fun `the buffer holds 50 000 lines by default`() {
        val buffer = LogBuffer()
        repeat(MAX_LOG_LINES + 10) { buffer.append(null, "x") }
        assertEquals(MAX_LOG_LINES, buffer.size)
        assertNull(buffer.filtered("").first().time)
        assertEquals(10, buffer.firstSeq)
    }
}
