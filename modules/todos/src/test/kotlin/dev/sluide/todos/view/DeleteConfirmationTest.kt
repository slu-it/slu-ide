package dev.sluide.todos.view

import kotlin.test.Test
import kotlin.test.assertEquals

class DeleteConfirmationTest {
    @Test
    fun `the delete header quotes the summary and shortens a long one`() {
        assertEquals("Delete TODO “Buy milk”?", deleteHeader("Buy milk"))
        val long = deleteHeader("x".repeat(100))
        assertEquals("Delete TODO “" + "x".repeat(59) + "…”?", long)
    }
}
