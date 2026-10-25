package dev.sluide.fileexplorer.actions

import org.junit.jupiter.api.Assumptions.assumeFalse
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    private val file = Path.of("/work/my project/a,b.txt")

    @Test
    fun `macOS reveals a file with open -R`() {
        val manager = systemFileManager("Mac OS X")
        assertEquals("Reveal in Finder", manager.revealLabel)
        assertEquals(listOf(listOf("open", "-R", file.toString())), manager.reveal(file))
    }

    @Test
    fun `Windows reveals a file with one select argument`() {
        val manager = systemFileManager("Windows 11")
        assertEquals("Reveal in Explorer", manager.revealLabel)
        assertEquals(listOf(listOf("explorer.exe", "/select,$file")), manager.reveal(file))
    }

    @Test
    fun `other systems ask the file manager over D-Bus, then open the folder`() {
        val manager = systemFileManager("Linux")
        assertEquals("Reveal in File Manager", manager.revealLabel)
        val commands = manager.reveal(file)
        assertEquals(2, commands.size)
        assertEquals("dbus-send", commands[0].first())
        assertEquals("org.freedesktop.FileManager1.ShowItems", commands[0][7])
        assertEquals("--reply-timeout=5000", commands[0][3])
        assertEquals("array:string:file:///work/my%20project/a%2Cb.txt", commands[0][8])
        assertEquals("string:", commands[0][9])
        assertEquals(listOf("xdg-open", "/work/my project"), commands[1])
    }

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `launchFirstSuccess tries the next command after a failure and stops at the first success`() {
        assumeFalse(isWindows)
        assertNull(launchFirstSuccess(listOf(listOf("sh", "-c", "exit 1"), listOf("/no/such/program"), listOf("true"))))
        assertNull(launchFirstSuccess(listOf(listOf("true"), listOf("/no/such/program"))), "stops at the first success")
    }

    @Test
    fun `launchFirstSuccess reports the failure of the last command`() {
        assumeFalse(isWindows)
        val failure = launchFirstSuccess(listOf(listOf("sh", "-c", "exit 1"), listOf("/no/such/program")))
        assertEquals(true, failure is IOException)
        assertNotNull(launchFirstSuccess(listOf(listOf("/no/such/program"))))
    }
}
