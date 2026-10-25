package dev.sluide.fileexplorer.actions

import kotlin.test.Test
import kotlin.test.assertEquals

class NameDialogTest {
    @Test
    fun `the base name ends before the last dot, but a leading dot does not start an extension`() {
        assertEquals(4, baseNameLength("Main.kt"))
        assertEquals(11, baseNameLength("archive.tar.gz"))
        assertEquals(10, baseNameLength(".gitignore"))
        assertEquals(8, baseNameLength("Makefile"))
        assertEquals(0, baseNameLength(""))
    }

    @Test
    fun `the selection is the base name of the last part of a path`() {
        assertEquals(0 until 4, selection("Main.kt"))
        assertEquals(9 until 15, selection("src/main/kotlin"))
        assertEquals(4 until 6, selection("src/my.dir"))
        assertEquals(0 until 0, selection(""))
    }
}
