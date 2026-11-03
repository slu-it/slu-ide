package dev.sluide.databases.ui.console

import dev.sluide.databases.sql.Completion
import dev.sluide.databases.sql.CompletionKind
import dev.sluide.databases.sql.CompletionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletionModelTest {
    private val model = CompletionModel()

    private fun result(
        from: Int,
        to: Int,
        vararg labels: String,
    ) = CompletionResult(from, to, labels.map { Completion(it, it, CompletionKind.TABLE) })

    private fun open(vararg labels: String) = model.update(result(4, 6, *labels), 6, opening = true)

    @Test
    fun `opens with the first item chosen and the insert range`() {
        assertTrue(open("a", "b", "c"))
        assertEquals("a", model.chosen?.label)
        assertEquals(4, model.replaceFrom)
        assertEquals(6, model.replaceTo)
    }

    @Test
    fun `an empty list does not open and closes an open one`() {
        assertFalse(open())
        assertFalse(model.isOpen)
        open("a")
        assertFalse(model.update(result(4, 7), 7, opening = false))
        assertFalse(model.isOpen)
        assertNull(model.chosen)
    }

    @Test
    fun `moving wraps at both ends and paging stops at them`() {
        open("a", "b", "c")
        model.move(-1)
        assertEquals("c", model.chosen?.label)
        model.move(1)
        assertEquals("a", model.chosen?.label)
        model.page(20)
        assertEquals("c", model.chosen?.label)
        model.page(-20)
        assertEquals("a", model.chosen?.label)
    }

    @Test
    fun `a refresh keeps the chosen label when it is still there`() {
        open("a", "b", "c")
        model.move(1)
        assertTrue(model.update(result(4, 7, "x", "c", "b"), 7, opening = false))
        assertEquals("b", model.chosen?.label)
        assertEquals(7, model.replaceTo)
        model.update(result(4, 7, "x", "y"), 7, opening = false)
        assertEquals("x", model.chosen?.label)
    }

    @Test
    fun `closes when the caret leaves the word or the word start moves`() {
        open("a")
        assertFalse(model.update(result(2, 3, "a"), 3, opening = false))
        open("a")
        assertFalse(model.update(result(7, 7, "a"), 7, opening = false))
        open("a")
        assertTrue(model.update(result(4, 5, "a"), 5, opening = false))
    }

    @Test
    fun `an opening call accepts another word start`() {
        open("a")
        assertTrue(model.update(result(7, 7, "a"), 7, opening = true))
        assertEquals(7, model.replaceFrom)
    }

    @Test
    fun `a click chooses a row`() {
        open("a", "b")
        model.select(1)
        assertEquals("b", model.chosen?.label)
        model.select(5)
        assertEquals("b", model.chosen?.label)
    }

    @Test
    fun `typed means one inserted character right before the caret`() {
        assertTrue(CompletionModel.isTypedChar("ab", "a.b", 2))
        assertTrue(CompletionModel.isTypedChar("aa", "aaa", 3))
        assertFalse(CompletionModel.isTypedChar("ab", "a.b", 3))
        assertFalse(CompletionModel.isTypedChar("ab", "axyb", 3))
        assertFalse(CompletionModel.isTypedChar("abc", "ab", 2))
        assertFalse(CompletionModel.isTypedChar("ab", "a.b", 0))
    }
}
