package dev.sluide.findinfiles

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuerySelectionTest {
    @ParameterizedTest
    @ValueSource(strings = [".", "*", "+", "?", "(", ")", "[", "]", "{", "}", "|", "^", "$", "\\", "a.*+?()[]{}|^$\\b"])
    fun `a regex escape matches exactly the text`(text: String) {
        val escaped = escapeRegex(text)

        assertTrue(Pattern.compile(escaped).matcher(text).matches())
        assertTrue(escaped.length > text.length)
    }

    @Test
    fun `escaping keeps plain text readable`() {
        assertEquals("foo bar_1", escapeRegex("foo bar_1"))
        assertEquals("a\\.b", escapeRegex("a.b"))
    }

    @Test
    fun `a single line selection is the query, escaped only for regex`() {
        assertEquals("a.b", selectionQuery("a.b", regex = false))
        assertEquals("a\\.b", selectionQuery("a.b", regex = true))
    }

    @Test
    fun `empty, multi-line and long selections are no query`() {
        assertNull(selectionQuery("", false))
        assertNull(selectionQuery("a\nb", false))
        assertNull(selectionQuery("a\rb", false))
        assertNull(selectionQuery("a".repeat(201), false))
        assertEquals("a".repeat(200), selectionQuery("a".repeat(200), false))
    }
}
