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
 * Central area showing open files read-only, one tab per file, and other [EditorTab]s such as terminals. Open files
 * are watched: a tab reloads when its file changes on disk and closes when the file is deleted or can no longer be
 * shown (e.g. it became binary).
 */
@Component
class EditorArea(
    private val fileViewers: FileViewers,
    private val fileWatcher: FileWatcher,
) : BorderPane() {
    internal val editorTabs = EditorTabs()
    internal val tabStrip = TabStrip(editorTabs)
    private val watches = mutableMapOf<EditorTab, AutoCloseable>()

    init {
        // Every way of closing a tab removes it from the list, so this ends all watches.
        editorTabs.tabs.addListener(
            ListChangeListener { change ->
                while (change.next()) change.removed.forEach { watches.remove(it)?.close() }
            },
        )
        styleClass += "editor-area"
        stylesheets += checkNotNull(EditorArea::class.java.getResource("editor.css")).toExternalForm()
        val content = StackPane().apply { styleClass += "editor-content" }
        top = tabStrip
        center = content
        editorTabs.selected.subscribe { selected -> content.children.setAll(listOfNotNull(selected?.content)) }
    }

    /**
     * Shows [file] in a tab: selects its tab if it is already open, otherwise opens a new one with the first
     * [dev.sluide.fileviewers.FileViewer] that can show it. Returns `false` if no viewer can show or read it.
     */
    fun open(file: Path): Boolean {
        val path = file.toAbsolutePath().normalize()
        editorTabs.find(path)?.let {
            editorTabs.select(it)
            return true
        }
        val viewer = fileViewers.viewerFor(path) ?: return false
        val view =
            try {
                viewer.open(path)
            } catch (_: IOException) {
                return false
            }
        watch(editorTabs.open(path, view))
        return true
    }

    /** Adds [tab] after the open tabs and selects it. Its [EditorTab.dispose] is called when it is closed. */
    fun open(tab: EditorTab) = editorTabs.open(tab)

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
        if (openFile !in editorTabs.tabs) return
        if (fileViewers.viewerFor(openFile.path) == null) {
            // Deleted, renamed away, or not viewable anymore.
            editorTabs.close(openFile)
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

    /** Closes all tabs, including the ones that do not show files. */
    fun closeAll() = editorTabs.closeAll()

    /** The paths of all open files in tab order. */
    val openPaths: List<Path> get() = editorTabs.files.map { it.path }

    /** All open tabs in tab order. */
    val tabs: List<EditorTab> get() = editorTabs.tabs

    /** Number of open files currently watched, for tests. */
    internal val watchCount: Int get() = watches.size

    /** The path of the file whose tab is selected, if a file tab is selected. */
    val selectedPath: Path? get() = (editorTabs.selected.get() as? OpenFile)?.path

    /** The selected tab, if any. */
    val selectedTab: EditorTab? get() = editorTabs.selected.get()
}
