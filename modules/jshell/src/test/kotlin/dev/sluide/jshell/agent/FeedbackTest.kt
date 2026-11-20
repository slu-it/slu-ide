package dev.sluide.jshell.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [describe] and [throwingLine] on exceptions built here; AgentServerTest has the real snippets' exceptions. */
class FeedbackTest {
    @Test
    fun `frames outside the session show their class, method, file and line, causes follow`() {
        val text = describe(RuntimeException("outer", IllegalStateException()), emptyMap())
        val lines = text.lines()
        assertEquals("java.lang.RuntimeException: outer", lines.first())
        assertTrue(
            Regex(
                """\tat dev\.sluide\.jshell\.agent\.FeedbackTest\..+ \(FeedbackTest\.kt:[1-9]\d*\)""",
            ).matches(lines[1]),
            lines[1],
        )
        assertTrue("Caused by: java.lang.IllegalStateException" in lines, text)
        assertEquals(text.trimEnd(), text)
    }

    @Test
    fun `frames of session snippets show the document line and the method if there is one`() {
        val exception = RuntimeException("x")
        exception.stackTrace =
            arrayOf(
                StackTraceElement("REPL.\$JShell\$11", "f", "#3", 2),
                StackTraceElement("REPL.\$JShell\$12", "", "#4", 1),
            )
        val lines = mapOf("3" to 4, "4" to 9)
        assertEquals("java.lang.RuntimeException: x\n\tat f (line 6)\n\tat (line 10)", describe(exception, lines))
        assertEquals(5, throwingLine(exception, lines))
    }

    @Test
    fun `an exception without session frames has no line`() {
        assertNull(throwingLine(RuntimeException(), mapOf("1" to 0)))
    }
}
