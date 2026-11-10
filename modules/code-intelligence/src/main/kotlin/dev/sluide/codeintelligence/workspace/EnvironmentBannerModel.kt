package dev.sluide.codeintelligence.workspace

import dev.sluide.codeintelligence.InstallPrompts
import dev.sluide.languageservers.DocumentGate
import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.ServerState
import javafx.beans.InvalidationListener
import javafx.beans.Observable
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import javafx.collections.ObservableSet
import java.nio.file.Path

/**
 * What a bound editor's environment banner says (ADR 0151, 0160): for a document the server does not serve, the
 * [reason][DocumentGate.reason] of its gate whatever the server's state (nothing while the gate has not answered);
 * otherwise the spec's [EnvironmentProblem] of the file, only while the server runs. Shown only if the user has not
 * put it off (Not Now for the problem's folder in this window, Don't Ask Again for the spec in every window); `null`
 * otherwise. Recomputed when the server's [state], the file's [folder], [changes] of the workspace folders, [notNow]
 * or the declined ids fire. FX thread.
 *
 * @param declineId the id Don't Ask Again stores in [prompts] (`python.environment`).
 * @param notNow the problem folders put off in this window until the app restarts.
 * @param projectFolder the window's project folder, `null` for none.
 * @param problemOf the spec's `environmentProblem` for the file in the project folder.
 * @param gateOf the session's gate answer of the file (`null`: not answered); served by default.
 * @param runInTerminal opens a terminal tab named `name` in `folder` and runs `command` there.
 */
internal class EnvironmentBannerModel(
    private val state: ObservableValue<ServerState>,
    private val folder: ObservableValue<Path?>,
    private val changes: Observable,
    private val projectFolder: Path?,
    private val declineId: String,
    private val notNow: ObservableSet<Path>,
    private val prompts: InstallPrompts,
    private val problemOf: (project: Path) -> EnvironmentProblem?,
    private val gateOf: () -> DocumentGate? = { DocumentGate.SERVED },
    private val runInTerminal: (folder: Path, name: String, command: String) -> Unit,
) : AutoCloseable {
    private val wrapper = ReadOnlyObjectWrapper<EnvironmentProblem?>(compute())

    /** The problem to show, `null` for none. */
    val problem: ReadOnlyObjectProperty<EnvironmentProblem?> = wrapper.readOnlyProperty

    private val listener = InvalidationListener { wrapper.set(compute()) }
    private val observed: List<Observable> = listOf(state, folder, changes, notNow, prompts.declined)

    init {
        observed.forEach { it.addListener(listener) }
    }

    /** The action's button: runs its constant command in a new terminal tab in the problem's folder. */
    fun run() {
        val shown = problem.value ?: return
        val action = shown.action ?: return
        runInTerminal(shown.folder, action.terminalName, action.command)
    }

    /** Not Now: hides the notice of the problem's folder in this window until the app restarts. */
    fun notNow() {
        problem.value?.let { notNow.add(it.folder) }
    }

    /** Don't Ask Again: hides the notice in every window, also after a restart. */
    fun dontAskAgain() = prompts.decline(declineId)

    private fun compute(): EnvironmentProblem? {
        val project = projectFolder ?: return null
        if (declineId in prompts.declined) return null
        val gate = gateOf() ?: return null
        val found: EnvironmentProblem?
        if (gate.served) {
            if (state.value != ServerState.Running) return null
            found = problemOf(project)
        } else {
            found = gate.reason
        }
        if (found == null) return null
        return found.takeUnless { it.folder in notNow }
    }

    override fun close() = observed.forEach { it.removeListener(listener) }
}
