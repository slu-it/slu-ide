package dev.sluide.fileexplorer

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
}
