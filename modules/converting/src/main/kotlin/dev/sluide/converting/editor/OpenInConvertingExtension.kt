package dev.sluide.converting.editor

import dev.sluide.converting.model.ConvertingModel
import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.scene.control.MenuItem

/** The longest text the tool takes, as for files (1 MiB), counted in chars here. */
private const val MAX_TEXT = ConvertingModel.MAX_FILE_SIZE

/**
 * Adds "Open Selection in Converting" (the selection) or "Open in Converting" (without one: the whole text) to the
 * context menu of code editors (ADR 0115); [open] shows the text in the tool with the extension of the editor's file.
 */
internal class OpenInConvertingExtension(
    private val open: (text: String, extension: String?) -> Unit,
) : CodeEditorExtension {
    override fun extend(editor: ExtensibleCodeEditor): AutoCloseable? {
        editor.addContextMenuItems { target -> menuItems(editor, target) }
        return null
    }

    private fun menuItems(
        editor: ExtensibleCodeEditor,
        target: EditorMenuTarget,
    ): List<MenuItem> {
        val selection = target.selectedText.isNotEmpty()
        val tooLarge = if (selection) target.selectedText.length > MAX_TEXT else textLength(editor) > MAX_TEXT
        val label = if (selection) "Open Selection in Converting" else "Open in Converting"
        return listOf(
            MenuItem(if (tooLarge) "$label (larger than 1 MiB)" else label).apply {
                isDisable = tooLarge
                setOnAction {
                    val text = if (selection) target.selectedText else wholeText(editor)
                    open(
                        text,
                        extensionOf(
                            editor.file.value
                                ?.fileName
                                ?.toString(),
                        ),
                    )
                }
            },
        )
    }

    /** The length of the whole text in chars, without building it. */
    private fun textLength(editor: ExtensibleCodeEditor): Int =
        (0 until editor.lineCount).sumOf { editor.lineText(it).length + 1 } - 1

    private fun wholeText(editor: ExtensibleCodeEditor): String =
        (0 until editor.lineCount).joinToString("\n") { editor.lineText(it) }
}

/** The extension of [fileName] without the dot; null without a name, a dot or an extension. */
internal fun extensionOf(fileName: String?): String? = fileName?.substringAfterLast('.', "")?.ifEmpty { null }
