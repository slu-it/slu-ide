package dev.sluide.editorarea

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import java.net.URI
import java.nio.file.Path

/**
 * Lets the file views of [area] open files in it (also at a line or a line and column), web pages through
 * [EditorArea.openWebPage], show a file's changes ([showChangesAt]), look up the window's services, read and edit other
 * tabs' texts ([tabTexts]) and save a tab's file ([EditorArea.save]).
 */
internal class ViewHost(
    private val area: EditorArea,
    private val showChangesAt: (Path, Int) -> Unit,
    private val tabTexts: TabTexts,
) : FileViewHost {
    override fun openFile(file: Path): Boolean = area.open(file)

    /** Opens [file] like [openFile], then lets its view go to [line] (0-based). */
    override fun openFile(
        file: Path,
        line: Int,
    ): Boolean = openThen(file) { it.showLine(line) }

    /** Opens [file] like [openFile], then lets its view go to [column] of [line] (0-based). */
    override fun openFile(
        file: Path,
        line: Int,
        column: Int,
    ): Boolean = openThen(file) { it.showPosition(line, column) }

    /** Opens [file] like [openFile], then lets [go] move in its view. */
    private fun openThen(
        file: Path,
        go: (FileView) -> Unit,
    ): Boolean {
        if (!area.open(file)) return false
        area.editorTabs
            .find(file.toAbsolutePath().normalize())
            ?.editor
            ?.let(go)
        return true
    }

    override fun openWebPage(uri: URI) = area.openWebPage(uri)

    override val projectFolder get() = area.projectFolder()

    override fun showChanges(
        file: Path,
        line: Int,
    ) = showChangesAt(file, line)

    override fun <T : Any> windowService(type: Class<T>): T? = area.windowServices.get(type)

    override fun currentText(file: Path): String? = tabTexts.currentText(file)

    override fun editText(
        file: Path,
        edit: (String) -> String,
    ): Boolean = tabTexts.editText(file, edit)

    override fun save(file: Path): Boolean = area.save(file)
}
