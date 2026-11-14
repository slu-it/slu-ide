package dev.sluide.barcodes.editor

import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.scene.control.MenuItem

/**
 * Adds "Show Selection as Barcode" to the context menu of code editors (ADR 0174, like ADR 0112); [open] puts the text
 * into the Barcodes tool. A selection longer than [MAX_SELECTION] characters is not offered: its item is disabled.
 */
internal class ShowSelectionExtension(
    private val open: (String) -> Unit,
) : CodeEditorExtension {
    override fun extend(editor: ExtensibleCodeEditor): AutoCloseable? {
        editor.addContextMenuItems(::menuItems)
        return null
    }

    private fun menuItems(target: EditorMenuTarget): List<MenuItem> {
        val selected = target.selectedText
        val tooLong = selected.length > MAX_SELECTION
        return listOf(
            MenuItem(if (tooLong) "Show Selection as Barcode (too long)" else "Show Selection as Barcode").apply {
                isDisable = selected.isEmpty() || tooLong
                setOnAction { open(selected) }
            },
        )
    }

    internal companion object {
        const val MAX_SELECTION = 4_096
    }
}
