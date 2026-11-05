package dev.sluide.translation.editor

import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.translation.MAX_READ_BYTES
import dev.sluide.translation.isMarkdownName
import javafx.scene.control.MenuItem

/**
 * Adds "Translate Selection" (the selection) or "Translate" (without one: the whole text, unsaved edits included) to
 * the context menu of code editors (ADR 0126); [translate] puts the text in the tool. Markdown files translate as
 * Markdown.
 */
internal class TranslateExtension(
    private val translate: (text: String, markdown: Boolean) -> Unit,
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
        val size = if (selection) target.selectedText.length else textLength(editor)
        val label = if (selection) "Translate Selection" else "Translate"
        val tooLarge = size > MAX_READ_BYTES
        return listOf(
            MenuItem(if (tooLarge) "$label (larger than 1 MiB)" else label).apply {
                isDisable = tooLarge
                setOnAction {
                    val text = if (selection) target.selectedText else wholeText(editor)
                    translate(
                        text,
                        isMarkdownName(
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
