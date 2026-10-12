package dev.sluide.editor

import dev.sluide.fileviewers.FileViewers
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Path

/** Central area showing open files read-only, one tab per file. */
@Component
class EditorArea(
    private val fileViewers: FileViewers,
) : BorderPane() {
    internal val openFiles = OpenFiles()
    internal val tabStrip = TabStrip(openFiles)

    init {
        styleClass += "editor-area"
        stylesheets += checkNotNull(EditorArea::class.java.getResource("editor.css")).toExternalForm()
        val content = StackPane().apply { styleClass += "editor-content" }
        top = tabStrip
        center = content
        openFiles.selected.subscribe { selected -> content.children.setAll(listOfNotNull(selected?.content)) }
    }

    /**
     * Shows [file] in a tab: selects its tab if it is already open, otherwise opens a new one with the first
     * [dev.sluide.fileviewers.FileViewer] that can show it. Returns `false` if no viewer can show or read it.
     */
    fun open(file: Path): Boolean {
        val path = file.toAbsolutePath().normalize()
        openFiles.find(path)?.let {
            openFiles.select(it)
            return true
        }
        val viewer = fileViewers.viewerFor(path) ?: return false
        val view =
            try {
                viewer.open(path)
            } catch (_: IOException) {
                return false
            }
        openFiles.open(path, view)
        return true
    }

    fun closeAll() = openFiles.closeAll()

    /** The paths of all open files in tab order. */
    val openPaths: List<Path> get() = openFiles.files.map { it.path }

    /** The path of the file whose tab is selected, if any. */
    val selectedPath: Path? get() = openFiles.selected.get()?.path
}
