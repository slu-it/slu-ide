package dev.sluide.codeintelligence.workspace

import dev.sluide.languageservers.ServerState
import dev.sluide.languageservers.WorkspaceStatus
import javafx.beans.InvalidationListener
import javafx.beans.Observable
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import java.nio.file.Path

/**
 * What a bound editor's project status line says (ADR 0151): the spec's [WorkspaceStatus] of the file's workspace
 * folder, only while the server runs, the document is [served] and there is a project folder to show it for; `null`
 * otherwise (a document that is not served shows nothing: its banner says why, ADR 0160). Recomputed when
 * the server's [state], the file's [folder] or [changes] of the workspace folders fire. FX thread.
 *
 * @param folder the workspace folder of the file (`null`: native, served by the window folder).
 * @param changes fires when something about the file's project may have changed.
 * @param projectFolder the window's project folder, `null` for none.
 * @param statusOf the spec's `workspaceStatus` for a workspace folder.
 */
internal class WorkspaceStatusModel(
    private val state: ObservableValue<ServerState>,
    private val folder: ObservableValue<Path?>,
    private val changes: Observable,
    private val projectFolder: Path?,
    private val served: () -> Boolean = { true },
    private val statusOf: (folder: Path?, projectFolder: Path) -> WorkspaceStatus?,
) : AutoCloseable {
    private val wrapper = ReadOnlyObjectWrapper<WorkspaceStatus?>(compute())

    /** The line to show, `null` for none. */
    val status: ReadOnlyObjectProperty<WorkspaceStatus?> = wrapper.readOnlyProperty

    // One listener for all three: it recomputes from the current values, whichever fired.
    private val listener = InvalidationListener { wrapper.set(compute()) }

    init {
        state.addListener(listener)
        folder.addListener(listener)
        changes.addListener(listener)
    }

    private fun compute(): WorkspaceStatus? {
        val project = projectFolder ?: return null
        if (state.value != ServerState.Running || !served()) return null
        return statusOf(folder.value, project)
    }

    override fun close() {
        state.removeListener(listener)
        folder.removeListener(listener)
        changes.removeListener(listener)
    }
}
