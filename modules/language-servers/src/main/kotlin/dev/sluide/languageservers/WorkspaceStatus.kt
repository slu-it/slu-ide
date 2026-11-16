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
 * @property actions what the notice's buttons do, in the order of the buttons; none for a notice without one.
 */
data class EnvironmentProblem(
    val folder: Path,
    val message: String,
    val actions: List<ProblemAction> = emptyList(),
)

/** A button of an environment notice (ADR 0179). */
sealed interface ProblemAction {
    /** The button's text: `Run uv sync`. */
    val label: String

    /**
     * Runs [command] in a terminal tab named [terminalName] in the problem's folder (ADR 0089: an explicit click
     * needs no trust question). The command is a constant: no values from the project.
     */
    data class Terminal(
        override val label: String,
        val terminalName: String,
        val command: String,
    ) : ProblemAction

    /** Opens [file] in an editor tab. */
    data class OpenFile(
        override val label: String,
        val file: Path,
    ) : ProblemAction

    /** Runs [perform] on the FX thread; one that throws is logged and the notice stays. */
    class Callback(
        override val label: String,
        val perform: () -> Unit,
    ) : ProblemAction
}
