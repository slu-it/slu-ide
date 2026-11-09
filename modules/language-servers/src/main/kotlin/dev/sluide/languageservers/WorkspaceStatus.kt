package dev.sluide.languageservers

import java.nio.file.Path

/**
 * The status line of a file's workspace folder ([LanguageServerSpec.workspaceStatus], ADR 0151).
 *
 * @property text what the line says: `Python project: deployables/backend_one · .venv · Python 3.12`.
 * @property folder the folder a click on the line selects in the explorer; `null`: the line is not clickable.
 * @property tooltip the line's tooltip (absolute paths), or `null` for none.
 */
data class WorkspaceStatus(
    val text: String,
    val folder: Path?,
    val tooltip: String?,
)

/**
 * A problem of a file's environment the editor shows as a notice ([LanguageServerSpec.environmentProblem], ADR 0151).
 *
 * @property folder the project folder the problem belongs to; "Not Now" is remembered per folder.
 * @property message the notice's text.
 * @property action what a button of the notice runs, or `null` for none.
 */
data class EnvironmentProblem(
    val folder: Path,
    val message: String,
    val action: ProblemAction?,
)

/**
 * A command a notice's button runs in a terminal tab, in the problem's folder.
 *
 * @property label the button's text: `Run uv sync`.
 * @property terminalName the terminal tab's name: `uv sync (backend_one)`.
 * @property command the command line, a constant (no values from the project): `uv sync`.
 */
data class ProblemAction(
    val label: String,
    val terminalName: String,
    val command: String,
)
