package dev.sluide.codecs.editor

import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.scene.control.MenuItem

/** Adds "Open Selection in Codecs" to the context menu of code editors (ADR 0112); [open] shows the text in the tool. */
internal class OpenSelectionExtension(
    private val open: (String) -> Unit,
) : CodeEditorExtension {
    override fun extend(editor: ExtensibleCodeEditor): AutoCloseable? {
        editor.addContextMenuItems(::menuItems)
        return null
    }

    private fun menuItems(target: EditorMenuTarget): List<MenuItem> =
        listOf(
            MenuItem("Open Selection in Codecs").apply {
                isDisable = target.selectedText.isEmpty()
                setOnAction { open(target.selectedText) }
            },
        )
}
