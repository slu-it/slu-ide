package dev.sluide.codeintelligence.rename

import dev.sluide.codeintelligence.RENAME_KEYS
import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.edits.WorkspaceEditTarget
import dev.sluide.codeintelligence.edits.WorkspaceEdits
import dev.sluide.codeintelligence.navigation.navigationBlocker
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.textediting.TextPosition
import javafx.scene.control.MenuItem

/**
 * Rename in a bound editor (ADR 0149): Shift+F6 at the caret, the editor's own binding, always taken in a bound editor
 * (a note says why when it cannot act), and "Rename…" in the context menu at the right-click, disabled with the
 * reason while the server cannot rename. The flow is [Rename].
 */
internal class RenameFeature(
    private val view: (ExtensibleCodeEditor) -> RenameView = ::RenamePopupView,
    private val edits: (WorkspaceEditTarget) -> WorkspaceEdits = { WorkspaceEdits(it) },
    private val states: (DocumentBinding) -> RenameFileStates = { BindingRenameFileStates(it) },
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val editor = binding.editor
        val rename = Rename(binding, view(editor), edits, states(binding))
        val handles =
            listOf(
                rename,
                editor.addKeyBinding(RENAME_KEYS) {
                    rename.start(editor.caretOffset)
                    true
                },
                binding.addContextMenuItems { target ->
                    val offset = editor.offsetOf(TextPosition(target.line, target.column))
                    val blocker = navigationBlocker(binding, RENAME)
                    listOf(
                        MenuItem(if (blocker == null) "Rename…" else "Rename… ($blocker)").apply {
                            // Shown only: the editor's binding above takes the key.
                            accelerator = RENAME_KEYS
                            isDisable = blocker != null
                            setOnAction { rename.start(offset) }
                        },
                    )
                },
            )
        return AutoCloseable { handles.asReversed().forEach { it.close() } }
    }
}
