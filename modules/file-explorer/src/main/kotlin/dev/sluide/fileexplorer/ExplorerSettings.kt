package dev.sluide.fileexplorer

import dev.sluide.settings.SettingsStore
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import org.springframework.stereotype.Component
import java.io.IOException

private val log = System.getLogger(ExplorerSettings::class.java.name)

/** The settings key of [ExplorerSettings.compactFolders]. */
internal const val COMPACT_FOLDERS_KEY = "explorer.compactFolders"

/** The settings key of [ExplorerSettings.hideGeneratedFolders]. */
internal const val HIDE_GENERATED_FOLDERS_KEY = "explorer.hideGeneratedFolders"

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
        flag(COMPACT_FOLDERS_KEY, "Cannot store whether the explorer compacts middle folders")
    }

    /**
     * Whether generated folders and files (`node_modules`, `build`, `.DS_Store`, ADR 0158, 0163) are left out of the tree; stored as
     * `explorer.hideGeneratedFolders` (default true), read and stored like [compactFolders].
     */
    val hideGeneratedFolders: BooleanProperty by lazy {
        flag(HIDE_GENERATED_FOLDERS_KEY, "Cannot store whether the explorer hides generated files")
    }

    private fun flag(
        key: String,
        failure: String,
    ): BooleanProperty {
        val stored = settings.load()[key]?.trim()?.toBooleanStrictOrNull() ?: true
        return SimpleBooleanProperty(stored).apply {
            addListener { _, _, value ->
                try {
                    settings.update(mapOf(key to value.toString()))
                } catch (e: IOException) {
                    log.log(System.Logger.Level.WARNING, failure, e)
                }
            }
        }
    }
}
