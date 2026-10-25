package dev.sluide.fileexplorer

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
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
    fun `settings that cannot be read give the default, a change that cannot be stored is logged and still applies`() {
        // A folder where the settings file should be: it cannot be read, so it is not replaced.
        val explorer = ExplorerSettings(SettingsStore(dir.resolve("settings").createDirectories()))
        val records = CopyOnWriteArrayList<String>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += "${record.level} ${record.message}"
                }

                override fun flush() {}

                override fun close() {}
            }
        val logger = Logger.getLogger(ExplorerSettings::class.java.name).apply { addHandler(handler) }
        try {
            assertTrue(explorer.compactFolders.get())
            explorer.compactFolders.set(false)
        } finally {
            logger.removeHandler(handler)
        }

        assertFalse(explorer.compactFolders.get())
        assertEquals(listOf("WARNING Cannot store whether the explorer compacts middle folders"), records)
    }
}
