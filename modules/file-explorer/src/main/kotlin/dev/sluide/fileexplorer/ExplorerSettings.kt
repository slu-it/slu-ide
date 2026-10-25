package dev.sluide.fileexplorer

import dev.sluide.settings.SettingsStore
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import org.springframework.stereotype.Component
import java.io.IOException

private val log = System.getLogger(ExplorerSettings::class.java.name)

/** The settings key of [ExplorerSettings.compactFolders]. */
internal const val COMPACT_FOLDERS_KEY = "explorer.compactFolders"

/**
 * The explorer's settings shared by all windows (a singleton bean), stored with [SettingsStore]. FX thread.
 */
@Component
class ExplorerSettings(
    private val settings: SettingsStore,
) {
    /**
     * Whether chains of folders that hold only one folder are shown as one row (`src/main/kotlin`, ADR 0096); stored as
     * `explorer.compactFolders` (default true). Read from the settings on first access; a change is stored, and a
     * failure to store it logged.
     */
    val compactFolders: BooleanProperty by lazy {
        val stored = settings.load()[COMPACT_FOLDERS_KEY]?.trim()?.toBooleanStrictOrNull() ?: true
        SimpleBooleanProperty(stored).apply {
            addListener { _, _, value -> store(value) }
        }
    }

    private fun store(compact: Boolean) {
        try {
            settings.update(mapOf(COMPACT_FOLDERS_KEY to compact.toString()))
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot store whether the explorer compacts middle folders", e)
        }
    }
}
