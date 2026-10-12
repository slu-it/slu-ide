package dev.sluide.editor

import dev.sluide.fileviewers.FileViewers
import dev.sluide.filewatcher.FileWatcher
import javafx.collections.ListChangeListener
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Path

/**
 * Central area showing open files read-only, one tab per file. Open files are watched: a tab reloads when its file
 * changes on disk and closes when the file is deleted or can no longer be shown (e.g. it became binary).
 */
@Component
class EditorArea(
    private val fileViewers: FileViewers,
    private val fileWatcher: FileWatcher,
) : BorderPane() {
    internal val openFiles = OpenFiles()
    internal val tabStrip = TabStrip(openFiles)
    private val watches = mutableMapOf<OpenFile, AutoCloseable>()

    init {
        // Every way of closing a tab removes it from the list, so this ends all watches.
        openFiles.files.addListener(
            ListChangeListener { change ->
                while (change.next()) change.removed.forEach { watches.remove(it)?.close() }
            },
        )
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
        watch(openFiles.open(path, view))
        return true
    }

    private fun watch(openFile: OpenFile) {
        val path = openFile.path
        watches.remove(openFile)?.close()
        watches[openFile] =
            fileWatcher.watch(path.parent) { changed ->
                // The folder itself is reported when events were lost or it was deleted: check the file anyway.
                if (path in changed || path.parent in changed) onChangedOnDisk(openFile)
            }
    }

    private fun onChangedOnDisk(openFile: OpenFile) {
        if (openFile !in openFiles.files) return
        if (fileViewers.viewerFor(openFile.path) == null) {
            // Deleted, renamed away, or not viewable anymore.
            openFiles.close(openFile)
            return
        }
        try {
            openFile.view.reload()
        } catch (_: IOException) {
            // Keep showing the last content; the next change will try again.
        }
        // Renew the watch: the folder may have been deleted and recreated, which ends the old one.
        watch(openFile)
    }

    fun closeAll() = openFiles.closeAll()

    /** The paths of all open files in tab order. */
    val openPaths: List<Path> get() = openFiles.files.map { it.path }

    /** Number of open files currently watched, for tests. */
    internal val watchCount: Int get() = watches.size

    /** The path of the file whose tab is selected, if any. */
    val selectedPath: Path? get() = openFiles.selected.get()?.path
}
