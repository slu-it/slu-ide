package dev.sluide.codeintelligence.workspace

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.WorkspaceStatus
import javafx.scene.Node
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import java.nio.file.Path

/** The stylesheet of the status line. */
private val WORKSPACE_STATUS_CSS: String =
    checkNotNull(WorkspaceStatusFeature::class.java.getResource("workspace-status.css")).toExternalForm()

/**
 * The project status line of a bound editor (ADR 0151), a status item below the text ([WorkspaceStatusModel]):
 * `Python project: deployables/backend_one · .venv · Python 3.12`. A line with a folder is a link; a click calls
 * [revealInExplorer] with it. The tooltip is the status's. FX thread.
 *
 * @param revealInExplorer selects a folder in the explorer.
 */
internal class WorkspaceStatusFeature(
    private val revealInExplorer: (Path) -> Unit,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val session = binding.session
        val file = binding.file
        val model =
            WorkspaceStatusModel(
                session.state,
                session.workspaceFolderOf(file),
                session.workspaceChanges,
                binding.editor.host.projectFolder,
            ) { folder, project -> binding.spec.workspaceStatus(folder, project) }
        return WorkspaceStatusItem(binding.editor, model, revealInExplorer)
    }
}

/** Shows [model]'s status in [editor]'s status bar while there is one. */
private class WorkspaceStatusItem(
    private val editor: ExtensibleCodeEditor,
    private val model: WorkspaceStatusModel,
    private val revealInExplorer: (Path) -> Unit,
) : AutoCloseable {
    private val stylesheet = editor.addStylesheet(WORKSPACE_STATUS_CSS)
    private var handle: AutoCloseable? = null
    private val subscription = model.status.subscribe { status -> render(status) }

    private fun render(status: WorkspaceStatus?) {
        handle?.close()
        handle = null
        if (status != null) handle = editor.addStatusItem(nodeOf(status))
    }

    private fun nodeOf(status: WorkspaceStatus): Node {
        val folder = status.folder
        val node: Node =
            if (folder == null) {
                Label(status.text)
            } else {
                Hyperlink(status.text).apply {
                    isFocusTraversable = false
                    setOnAction {
                        isVisited = false
                        revealInExplorer(folder)
                    }
                }
            }
        node.styleClass += "workspace-status"
        node.id = "workspace-status"
        status.tooltip?.let { Tooltip.install(node, Tooltip(it)) }
        return node
    }

    override fun close() {
        subscription.unsubscribe()
        handle?.close()
        handle = null
        stylesheet.close()
        model.close()
    }
}
