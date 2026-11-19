package dev.sluide.fileexplorer

import dev.sluide.settings.SettingsStore
import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExplorerSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy { SettingsStore(dir.resolve("settings.properties")) }

    @Test
    fun `middle folders are compacted by default, and a change is stored for the next start`() {
        val explorer = ExplorerSettings(settings)
        assertTrue(explorer.compactFolders.get())

        explorer.compactFolders.set(false)

        assertEquals("false", settings.load()[COMPACT_FOLDERS_KEY])
        assertFalse(ExplorerSettings(settings).compactFolders.get())
    }

    @Test
    fun `the setting is read on first access, a value that is no boolean counts as the default`() {
        val explorer = ExplorerSettings(settings)
        dir.resolve("settings.properties").writeText("$COMPACT_FOLDERS_KEY=false\n")
        assertFalse(explorer.compactFolders.get())

        dir.resolve("settings.properties").writeText("$COMPACT_FOLDERS_KEY=maybe\n")
        assertTrue(ExplorerSettings(settings).compactFolders.get())
    }

    @Test
    fun `a stored value is trimmed, and only exactly true or false counts`() {
        val file = dir.resolve("settings.properties")
        file.writeText("$COMPACT_FOLDERS_KEY=false\n$HIDE_GENERATED_FOLDERS_KEY=true\n")
        assertFalse(ExplorerSettings(settings).compactFolders.get())
        assertTrue(ExplorerSettings(settings).hideGeneratedFolders.get())
        file.writeText("$COMPACT_FOLDERS_KEY=true\n$HIDE_GENERATED_FOLDERS_KEY=false\n")
        assertTrue(ExplorerSettings(settings).compactFolders.get())
        assertFalse(ExplorerSettings(settings).hideGeneratedFolders.get())

        file.writeText("$COMPACT_FOLDERS_KEY=FALSE\n$HIDE_GENERATED_FOLDERS_KEY=false\n")
        assertTrue(ExplorerSettings(settings).compactFolders.get(), "FALSE is no boolean: the default")
        assertFalse(ExplorerSettings(settings).hideGeneratedFolders.get())

        file.writeText("$COMPACT_FOLDERS_KEY=  false \n")
        assertFalse(ExplorerSettings(settings).compactFolders.get())
    }

    @Test
    fun `settings that cannot be read give the default, a change that cannot be stored is logged and still applies`() {
        // A folder where the settings file should be: it cannot be read, so it is not replaced.
        val explorer = ExplorerSettings(SettingsStore(dir.resolve("settings").createDirectories()))
        val records =
            LogCapture(ExplorerSettings::class.java.name).use { log ->
                assertTrue(explorer.compactFolders.get())
                explorer.compactFolders.set(false)
                log.lines.map { (level, message) -> "$level $message" }
            }

        assertFalse(explorer.compactFolders.get())
        assertEquals(listOf("WARNING Cannot store whether the explorer compacts middle folders"), records)
    }

    @Test
    fun `generated folders are hidden by default, and a change is stored under its own key`() {
        val explorer = ExplorerSettings(settings)
        assertTrue(explorer.hideGeneratedFolders.get())

        explorer.hideGeneratedFolders.set(false)

        assertEquals("false", settings.load()[HIDE_GENERATED_FOLDERS_KEY])
        assertEquals("explorer.hideGeneratedFolders", HIDE_GENERATED_FOLDERS_KEY)
        assertFalse(ExplorerSettings(settings).hideGeneratedFolders.get())
        assertTrue(ExplorerSettings(settings).compactFolders.get())
    }
}
