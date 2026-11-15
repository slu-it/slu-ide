package dev.sluide.ide

import dev.sluide.settings.ProjectSettings
import dev.sluide.settings.SettingsDialog
import javafx.beans.value.ObservableValue
import javafx.stage.Stage
import java.nio.file.Path

/**
 * Opens the Project Settings dialog of one window for its project folder: File → Project Settings… and
 * `ProjectSettings.show` (set by [install]) do nothing while the window has no folder or no module offers a page.
 */
internal class ProjectSettingsOpener(
    private val projectSettings: ProjectSettings,
    private val projectFolder: ObservableValue<Path?>,
) {
    /** Shows the dialog and waits until it is closed; tests replace it, since it blocks. */
    var showDialog: (SettingsDialog) -> Unit = { it.showAndWait() }

    /** Whether there is a folder and a page (`map` skips a null folder, so `orElse` gives false); enables the item. */
    val available: ObservableValue<Boolean> = projectFolder.map { projectSettings.pages.isNotEmpty() }.orElse(false)

    /** Lets `ProjectSettings.show` open the dialog owned by [stage]. */
    fun install(stage: Stage) {
        projectSettings.show = { path ->
            val folder = projectFolder.value
            if (folder != null && projectSettings.pages.isNotEmpty()) {
                showDialog(projectSettings.dialog(folder, stage, path))
            }
        }
    }

    /** The File menu item's action: opens at the first page. */
    fun open() = projectSettings.show(null)
}
