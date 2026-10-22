package dev.sluide.fileexplorer.actions

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemFileManagerTest {
    private val folder = Path.of("/work/my project")

    @Test
    fun `macOS opens the Finder with open`() {
        val manager = systemFileManager("Mac OS X")
        assertEquals("Open in Finder", manager.label)
        assertEquals("Finder", manager.name)
        assertEquals(listOf("open", folder.toString()), manager.command(folder))
    }

    @Test
    fun `Windows opens the Explorer with explorer exe`() {
        val manager = systemFileManager("Windows 11")
        assertEquals("Open in Explorer", manager.label)
        assertEquals(listOf("explorer.exe", folder.toString()), manager.command(folder))
    }

    @Test
    fun `other systems use xdg-open`() {
        val manager = systemFileManager("Linux")
        assertEquals("Open in File Manager", manager.label)
        assertEquals(listOf("xdg-open", folder.toString()), manager.command(folder))
    }
}
