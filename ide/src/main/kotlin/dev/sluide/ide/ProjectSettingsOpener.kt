package dev.sluide.ide

import dev.sluide.settings.ProjectSettings
import dev.sluide.settings.ProjectSettingsOpeners
import dev.sluide.settings.SettingsDialog
import javafx.beans.value.ObservableValue
import javafx.stage.Stage
import javafx.util.Subscription
import java.nio.file.Path

/**
 * Opens the Project Settings dialog of one window for its project folder: File → Project Settings… and
 * `ProjectSettings.show` (set by [install]) do nothing while the window has no folder or no module offers a page.
 */
internal class ProjectSettingsOpener(
    private val projectSettings: ProjectSettings,
    private val projectFolder: ObservableValue<Path?>,
    private val openers: ProjectSettingsOpeners,
) {
    /** Shows the dialog and waits until it is closed; tests replace it, since it blocks. */
    var showDialog: (SettingsDialog) -> Unit = { it.showAndWait() }

    /** Whether there is a folder and a page (`map` skips a null folder, so `orElse` gives false); enables the item. */
    val available: ObservableValue<Boolean> = projectFolder.map { projectSettings.pages.isNotEmpty() }.orElse(false)

    /** Lets `ProjectSettings.show` open the dialog owned by [stage]. */
    fun install(stage: Stage) {
        track()
        projectSettings.show = { path ->
            val folder = projectFolder.value
            if (folder != null && projectSettings.pages.isNotEmpty()) {
                showDialog(projectSettings.dialog(folder, stage, path))
            }
        }
    }

    private var registration: AutoCloseable? = null
    private var folderSubscription: Subscription? = null

    /**
     * Registers this window's opener with [ProjectSettingsOpeners] for its current folder, and again whenever the
     * folder changes, so that code outside the dialog (a language server's notice) can open it; once per window.
     */
    internal fun track() {
        // `subscribe` also calls the consumer with the folder that is set now.
        folderSubscription = projectFolder.subscribe { _ -> registerFolder() }
    }

    private fun registerFolder() {
        registration?.close()
        registration = projectFolder.value?.let { folder -> openers.register(folder) { projectSettings.show(it) } }
    }

    /** Ends the registration; called when the window closes. */
    fun close() {
        folderSubscription?.unsubscribe()
        registration?.close()
    }

    /** The File menu item's action: opens at the first page. */
    fun open() = projectSettings.show(null)
}
