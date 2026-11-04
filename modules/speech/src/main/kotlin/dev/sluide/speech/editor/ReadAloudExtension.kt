package dev.sluide.speech.editor

import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.speech.MAX_READ_BYTES
import dev.sluide.speech.isMarkdownName
import javafx.scene.control.MenuItem

/**
 * Adds "Read Selection Aloud" (the selection) or "Read Aloud" (without one: the whole text, unsaved edits included) to
 * the context menu of code editors (ADR 0124); [read] puts the text in the tool. Markdown files read as Markdown.
 */
internal class ReadAloudExtension(
    private val read: (text: String, markdown: Boolean) -> Unit,
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
        val tooLarge = size > MAX_READ_BYTES
        val label = if (selection) "Read Selection Aloud" else "Read Aloud"
        return listOf(
            MenuItem(if (tooLarge) "$label (larger than 1 MiB)" else label).apply {
                isDisable = tooLarge
                setOnAction {
                    val text = if (selection) target.selectedText else wholeText(editor)
                    read(
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
