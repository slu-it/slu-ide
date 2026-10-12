package dev.sluide.editor

import dev.sluide.fileviewers.FileView
import javafx.scene.Group
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class OpenFilesTest {
    private val openFiles = OpenFiles()

    private fun open(name: String) =
        openFiles.open(
            Path.of("/project", name),
            object : FileView {
                override val node = Group()

                override fun reload() {}
            },
        )

    private fun names() = openFiles.files.map { it.name }

    @Test
    fun `opening appends a tab and selects it`() {
        open("a.txt")
        val b = open("b.txt")

        assertEquals(listOf("a.txt", "b.txt"), names())
        assertSame(b, openFiles.selected.get())
    }

    @Test
    fun `finds open files by path and refuses to open them twice`() {
        val a = open("a.txt")

        assertSame(a, openFiles.find(Path.of("/project", "a.txt")))
        assertNull(openFiles.find(Path.of("/project", "other.txt")))
        assertFailsWith<IllegalArgumentException> { open("a.txt") }
    }

    @Test
    fun `closing the selected tab selects its right neighbour`() {
        val a = open("a.txt")
        val b = open("b.txt")
        val c = open("c.txt")
        openFiles.select(b)

        openFiles.close(b)

        assertEquals(listOf("a.txt", "c.txt"), names())
        assertSame(c, openFiles.selected.get())
        assertSame(a, openFiles.files.first())
    }

    @Test
    fun `closing the selected last tab selects its left neighbour, closing the only tab selects nothing`() {
        val a = open("a.txt")
        val b = open("b.txt")

        openFiles.close(b)
        assertSame(a, openFiles.selected.get())

        openFiles.close(a)
        assertNull(openFiles.selected.get())
        assertEquals(emptyList(), names())
    }

    @Test
    fun `closing another tab keeps the selection`() {
        val a = open("a.txt")
        val b = open("b.txt")

        openFiles.close(a)

        assertSame(b, openFiles.selected.get())
    }

    @Test
    fun `closing all tabs clears the selection`() {
        open("a.txt")
        open("b.txt")

        openFiles.closeAll()

        assertEquals(emptyList(), names())
        assertNull(openFiles.selected.get())
    }

    @Test
    fun `closing others keeps and selects the given tab`() {
        open("a.txt")
        val b = open("b.txt")
        open("c.txt")

        openFiles.closeOthers(b)

        assertEquals(listOf("b.txt"), names())
        assertSame(b, openFiles.selected.get())
    }

    @Test
    fun `closing tabs to the right keeps a selection left of them`() {
        val a = open("a.txt")
        val b = open("b.txt")
        open("c.txt")
        open("d.txt")
        openFiles.select(a)

        openFiles.closeRightOf(b)

        assertEquals(listOf("a.txt", "b.txt"), names())
        assertSame(a, openFiles.selected.get())
    }

    @Test
    fun `closing tabs to the left selects the given tab if the selected one was closed`() {
        val a = open("a.txt")
        open("b.txt")
        val c = open("c.txt")
        open("d.txt")
        openFiles.select(a)

        openFiles.closeLeftOf(c)

        assertEquals(listOf("c.txt", "d.txt"), names())
        assertSame(c, openFiles.selected.get())
    }

    @Test
    fun `nothing to close left of the first or right of the last tab`() {
        val a = open("a.txt")
        val b = open("b.txt")

        openFiles.closeLeftOf(a)
        openFiles.closeRightOf(b)

        assertEquals(listOf("a.txt", "b.txt"), names())
        assertSame(b, openFiles.selected.get())
    }
}
