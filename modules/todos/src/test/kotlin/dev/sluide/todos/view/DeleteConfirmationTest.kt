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

    @Test
    fun `the folder header counts the TODOs and shortens a long name`() {
        assertEquals("Delete folder “Release”?", deleteFolderHeader("Release", 0))
        assertEquals("Delete folder “Release” with its 1 TODO?", deleteFolderHeader("Release", 1))
        assertEquals("Delete folder “Release” with its 3 TODOs?", deleteFolderHeader("Release", 3))
        assertEquals("Delete folder “" + "x".repeat(59) + "…”?", deleteFolderHeader("x".repeat(100), 0))
    }
}
