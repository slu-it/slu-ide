package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IndentationDetectTest {
    @Test
    fun `detects the size of space indentation`() {
        val text = "fun a() {\n    if (b) {\n        c()\n    }\n}\n"
        assertEquals(IndentStyle(useTabs = false, size = 4), Indentation.detect(text))
        assertEquals(IndentStyle(useTabs = false, size = 2), Indentation.detect("a:\n  b:\n    c: 1\n  d: 2\n"))
    }

    @Test
    fun `detects tab indentation`() {
        val text = "fun a() {\n\tif (b) {\n\t\tc()\n\t}\n}\n"
        assertEquals(IndentStyle(useTabs = true, size = 4), Indentation.detect(text))
    }

    @Test
    fun `decides tabs or spaces by the majority of indented lines`() {
        assertEquals(true, Indentation.detect("a\n\tb\n\tc\n    d\n")?.useTabs)
        assertEquals(false, Indentation.detect("a\n\tb\n    c\n    d\n")?.useTabs)
    }

    @Test
    fun `takes the most common step and the smaller one on ties`() {
        assertEquals(4, Indentation.detect("a\n    b\n    c\n        d\nx\n    y\n      z(\n")?.size)
        assertEquals(2, Indentation.detect("a\n    b\nc\n  d\n")?.size)
    }

    @Test
    fun `ignores blank lines and single-space continuations`() {
        val text = "/**\n * doc\n */\nclass A {\n\n  val b = 1\n}\n"
        assertEquals(IndentStyle(useTabs = false, size = 2), Indentation.detect(text))
    }

    @Test
    fun `returns null when the indentation cannot be told`() {
        assertNull(Indentation.detect(""))
        assertNull(Indentation.detect("a\nb\n"))
        assertNull(Indentation.detect("a\n\tb\n    c\n"))
        assertNull(Indentation.detect("a\n                    b\n"))
    }

    @Test
    fun `has the indent unit of a style`() {
        assertEquals("\t", IndentStyle(useTabs = true, size = 4).unit)
        assertEquals("  ", IndentStyle(useTabs = false, size = 2).unit)
    }

    @Test
    fun `rejects sizes that are not positive`() {
        assertFailsWith<IllegalArgumentException> { IndentStyle(useTabs = false, size = 0) }
        assertFailsWith<IllegalArgumentException> { IndentStyle(useTabs = true, size = 4, tabWidth = -1) }
    }
}
