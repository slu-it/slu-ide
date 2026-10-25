package dev.sluide.fileexplorer.tree

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeDragAndDropTest {
    private fun p(path: String) = Path.of(path)

    @Test
    fun `entries can be dropped on another folder, also when only some are in it already`() {
        assertTrue(canDrop(listOf(p("/p/a.txt")), p("/p/src")))
        assertTrue(canDrop(listOf(p("/p/src/a.txt"), p("/p/b.txt")), p("/p")))
        assertTrue(canDrop(listOf(p("/p/a.txt")), p("/scratches")))
        assertTrue(canDrop(listOf(p("/scratches/x/a.txt")), p("/p/src/deep")))
    }

    @Test
    fun `nothing can be dropped on a dragged folder or a folder inside it`() {
        assertFalse(canDrop(listOf(p("/p/src")), p("/p/src")))
        assertFalse(canDrop(listOf(p("/p/a.txt"), p("/p/src")), p("/p/src/main/kotlin")))
    }

    @Test
    fun `entries cannot be dropped where all of them are already`() {
        assertFalse(canDrop(listOf(p("/p/a.txt"), p("/p/b.txt")), p("/p")))
        assertFalse(canDrop(emptyList(), p("/p")))
    }

    @Test
    fun `a folder whose name starts like a dragged one is another folder`() {
        assertTrue(canDrop(listOf(p("/p/src")), p("/p/src2")))
    }

    @Test
    fun `copies can be dropped into the folder they are in`() {
        assertTrue(canDrop(listOf(p("/p/a.txt"), p("/p/b.txt")), p("/p"), copy = true))
        assertFalse(canDrop(listOf(p("/p/a.txt")), p("/p"), copy = false))
    }

    @Test
    fun `copies still cannot go into a source or a folder inside one`() {
        assertFalse(canDrop(listOf(p("/p/src")), p("/p/src"), copy = true))
        assertFalse(canDrop(listOf(p("/p/a.txt"), p("/p/src")), p("/p/src/main"), copy = true))
        assertFalse(canDrop(emptyList(), p("/p"), copy = true))
    }

    @Test
    fun `the copy modifier is Option on macOS and Ctrl elsewhere`() {
        assertTrue(isCopyModifier(isMac = true, isAltDown = true, isControlDown = false))
        assertFalse(isCopyModifier(isMac = true, isAltDown = false, isControlDown = true))
        assertTrue(isCopyModifier(isMac = false, isAltDown = false, isControlDown = true))
        assertFalse(isCopyModifier(isMac = false, isAltDown = true, isControlDown = false))
    }

    @Test
    fun `outermost leaves out entries at or below another one and keeps the order`() {
        val paths = listOf(p("/p/b"), p("/p/a/x.txt"), p("/p/a"), p("/p/b/c/d"), p("/p/b"), p("/p/ab"))

        assertEquals(listOf(p("/p/b"), p("/p/a"), p("/p/ab")), outermost(paths) { it })
    }
}
