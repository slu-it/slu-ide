package dev.sluide.ide

import dev.sluide.git.GitStatusService
import dev.sluide.java.OutputIgnoreChecks
import dev.sluide.settings.ProjectSettings
import dev.sluide.settings.ProjectSettingsOpeners
import javafx.stage.Stage
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * What one window registers with the app-wide registries for its project folder, so that modules reach the window
 * without depending on `ide`: its Project Settings dialog ([ProjectSettingsOpeners]) and its git's answer which of
 * jdtls's output folders git does not ignore ([OutputIgnoreChecks], ADR 0179). A window bean; FX thread.
 */
@Component
@Scope(WINDOW_SCOPE)
class FolderRegistrations(
    projectSettings: ProjectSettings,
    projectService: ProjectService,
    openers: ProjectSettingsOpeners,
    checks: OutputIgnoreChecks,
    gitStatus: GitStatusService,
) {
    internal val projectSettingsOpener = ProjectSettingsOpener(projectSettings, projectService.projectFolder, openers)
    private val outputIgnore =
        OutputIgnoreRegistration(projectService.projectFolder, checks, gitStatus::unignoredFoldersAsync)

    /** Registers for the current folder and follows its changes; once, when the window is shown on [stage]. */
    fun install(stage: Stage) {
        projectSettingsOpener.install(stage)
        outputIgnore.track()
    }

    /** Ends the registrations; called when the window closes. */
    fun close() {
        projectSettingsOpener.close()
        outputIgnore.close()
    }
}
