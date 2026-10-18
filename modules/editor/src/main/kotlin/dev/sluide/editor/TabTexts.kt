package dev.sluide.editor

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.TextEditableView
import java.nio.file.Path

/**
 * Reads and edits the text of a file's tab for another file's view ([FileViewHost.currentText],
 * [FileViewHost.editText]), e.g. the HTTP client's environment editor changing the private file beside the one it
 * shows (ADR 0057). A file that is not open is opened by [openUnselected], which returns `null` if the file cannot be
 * shown in an [editable] view. Only views that can replace their text count, so a read-only view gives no
 * text and takes no edit.
 */
internal class TabTexts(
    private val tabs: EditorTabs,
    private val openUnselected: (Path) -> OpenFile?,
) {
    fun currentText(file: Path): String? = editable(tabs.find(normalized(file))?.editor)?.text

    fun editText(
        file: Path,
        edit: (String) -> String,
    ): Boolean {
        val path = normalized(file)
        val openFile = tabs.find(path) ?: openUnselected(path) ?: return false
        val view = editable(openFile.editor) ?: return false
        return view.replaceText(edit(view.text))
    }

    private fun normalized(file: Path): Path = file.toAbsolutePath().normalize()

    companion object {
        /** [view] if it is a [TextEditableView] that can replace its text, else `null`. */
        fun editable(view: FileView?): TextEditableView? = (view as? TextEditableView)?.takeIf { it.canReplaceText }
    }
}
