package dev.sluide.editor

import dev.sluide.fileviewers.DiffFileView
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.TextDiff
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import javafx.collections.ListChangeListener
import javafx.scene.control.Alert
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Central area showing open files, one tab per file, and other [EditorTab]s such as terminals. Open files are
 * watched: a tab reloads when its file changes on disk and closes when the file is deleted or can no longer be shown
 * (e.g. it became binary), unless it has unsaved changes. Files with a diff ([diffOf]) open side by side with their
 * original instead, read-only. Edited files are saved with [save] and [saveAll]; closing them asks to save first
 * ([confirmSave]).
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class EditorArea(
    private val fileViewers: FileViewers,
    private val fileWatcher: FileWatcher,
    fontSettings: FontSettings,
) : BorderPane() {
    internal val editorTabs = EditorTabs()
    internal val tabStrip = TabStrip(editorTabs, ::confirmClosing)

    /**
     * Asked before tabs are closed from the UI if some of them have a [EditorTab.closeWarning]; returns whether to
     * close them. Shows a confirmation dialog; tests replace it.
     */
    var confirmClose: (warnings: List<String>) -> Boolean = ::showCloseDialog

    /**
     * Asked before files with unsaved changes are closed, with their paths in the [projectFolder] (full paths outside of
     * it): whether to save them first, close them without saving or close nothing. Shows a dialog ([askToSave]); tests
     * replace it.
     */
    var confirmSave: (files: List<String>) -> SaveChoice = { files -> askToSave(scene?.window, files) }

    /** Shows an error message, e.g. that a file could not be saved, and waits until it is closed; tests replace it. */
    var showError: (message: String) -> Unit = ::showErrorDialog
    private val watches = mutableMapOf<EditorTab, AutoCloseable>()

    /**
     * The diff of a file against its original (e.g. the last commit) if it is to be shown as a diff, `null` to show it
     * normally (ADR 0015). Asked when a file is opened, and for diff views when their file changes on disk and on
     * [refreshDiffs]. Until diffs are shown on demand, an open editor is never swapped for its diff (ADR 0020).
     */
    var diffOf: (Path) -> TextDiff? = { null }

    /** Shows a web page outside the IDE when a file view asks for it, e.g. for a clicked link; set by the window. */
    var openWebPage: (URI) -> Unit = {}

    /** The project folder of the window, whose files HTML previews may use; set by the window. */
    var projectFolder: () -> Path? = { null }

    /** Lets the file views open files in this area and web pages through [openWebPage]. */
    private val viewHost =
        object : FileViewHost {
            override fun openFile(file: Path): Boolean = open(file)

            override fun openWebPage(uri: URI) = this@EditorArea.openWebPage(uri)

            override val projectFolder get() = this@EditorArea.projectFolder()
        }

    /** The editor font as CSS; file views inherit it from their node. */
    private val editorCss = fontSettings.font(FontArea.EDITOR).map(FontSpec::toCss)

    init {
        // Every way of opening or closing a tab changes the list: file views get the editor font, closed ones lose
        // their watch and font binding.
        editorTabs.tabs.addListener(
            ListChangeListener { change ->
                while (change.next()) {
                    change.removed.forEach { tab ->
                        watches.remove(tab)?.close()
                        // Unbinding also lets the shared font binding forget the closed view.
                        if (tab is OpenFile) tab.content.styleProperty().unbind()
                    }
                    change.addedSubList.filterIsInstance<OpenFile>().forEach {
                        it.content.styleProperty().bind(
                            editorCss,
                        )
                    }
                }
            },
        )
        styleClass += "editor-area"
        stylesheets += checkNotNull(EditorArea::class.java.getResource("editor.css")).toExternalForm()
        val content = StackPane().apply { styleClass += "editor-content" }
        top = tabStrip
        center = content
        editorTabs.selected.subscribe { selected -> content.children.setAll(listOfNotNull(selected?.content)) }
    }

    /** Whether a [dev.sluide.fileviewers.FileViewer] can show [file], i.e. whether [open] would open it. */
    fun canOpen(file: Path): Boolean = fileViewers.viewerFor(file) != null

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
                hosted(diffOf(path)?.let { fileViewers.openDiff(path, it) } ?: viewer.open(path))
            } catch (_: IOException) {
                return false
            }
        watch(editorTabs.open(path, view))
        return true
    }

    /**
     * Asks [diffOf] again for every open diff view: it shows the new diff, or the normal view if there is none anymore;
     * other views reload. Call it when the answers may have changed (e.g. a commit).
     */
    fun refreshDiffs() {
        editorTabs.files.forEach { if (fileViewers.viewerFor(it.path) != null) updateView(it) }
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
        if (fileViewers.viewerFor(openFile.path) == null && !openFile.isDirty) {
            // Deleted, renamed away, or not viewable anymore. With unsaved changes, the tab stays and its view tells.
            editorTabs.close(openFile)
            return
        }
        updateView(openFile)
        // Renew the watch: the folder may have been deleted and recreated, which ends the old one.
        watch(openFile)
    }

    /** Shows the file's current content; a diff view is swapped for the normal view once [diffOf] has no diff. */
    private fun updateView(openFile: OpenFile) {
        val path = openFile.path
        try {
            when (val view = openFile.view) {
                is DiffFileView -> {
                    val diff = diffOf(path)
                    if (diff != null) view.show(diff) else openFile.view = normalView(path)?.let(::hosted) ?: return
                }

                // Until diffs are shown on demand, an editor is never swapped for its diff: it may have unsaved
                // changes, and saving must not take it away.
                else -> {
                    view.reload()
                }
            }
        } catch (_: IOException) {
            // Keep showing the last content; the next change will try again.
        }
    }

    private fun normalView(path: Path): FileView? = fileViewers.viewerFor(path)?.open(path)

    private fun hosted(view: FileView): FileView = view.apply { host = viewHost }

    /**
     * Whether to close [tabs] from the UI: files with unsaved changes are saved or discarded as [confirmSave] says,
     * then tabs that still run something are confirmed ([confirmClose]). Nothing closes when either is cancelled or
     * saving fails.
     */
    private fun confirmClosing(tabs: List<EditorTab>): Boolean {
        if (!saveBeforeClosing(tabs)) return false
        val warnings = tabs.mapNotNull { it.closeWarning }
        return warnings.isEmpty() || confirmClose(warnings)
    }

    /**
     * Asks to save the files with unsaved changes before all tabs close with the window ([confirmSave]); call it
     * before [closeAll]. Returns whether to go on closing: `false` if the user cancelled or saving failed.
     */
    fun confirmCloseAll(): Boolean = saveBeforeClosing(editorTabs.tabs)

    /** Saves or discards the unsaved changes of the files among [tabs] as [confirmSave] says; `false` to close none. */
    private fun saveBeforeClosing(tabs: List<EditorTab>): Boolean {
        val dirty = tabs.filterIsInstance<OpenFile>().filter { it.isDirty }
        if (dirty.isEmpty()) return true
        return when (confirmSave(dirty.map(::shownPath))) {
            SaveChoice.SAVE -> dirty.map(::save).all { it }
            SaveChoice.DISCARD -> true
            SaveChoice.CANCEL -> false
        }
    }

    /** The path of [openFile] as questions show it: within the project folder, or in full outside of it. */
    private fun shownPath(openFile: OpenFile): String {
        val folder = projectFolder()?.toAbsolutePath()?.normalize()
        val path = openFile.path
        return if (folder != null && path.startsWith(folder)) folder.relativize(path).toString() else path.toString()
    }

    private fun showCloseDialog(warnings: List<String>): Boolean {
        val single = warnings.size == 1
        return askToClose(
            scene?.window,
            if (single) "Close Tab" else "Close Tabs",
            if (single) "Close this tab anyway?" else "Close these tabs anyway?",
            warnings,
        )
    }

    /**
     * Saves the file of the selected tab if it has unsaved changes. Returns `false` if saving failed, after showing
     * why; the tab keeps its unsaved changes then.
     */
    fun save(): Boolean = (editorTabs.selected.get() as? OpenFile)?.let(::save) ?: true

    /** Saves every open file with unsaved changes, like [save]; `false` if one of them could not be saved. */
    fun saveAll(): Boolean = editorTabs.files.map(::save).all { it }

    private fun save(openFile: OpenFile): Boolean {
        if (!openFile.isDirty) return true
        val view = openFile.view as? EditableFileView ?: return true
        return try {
            view.save()
            true
        } catch (e: IOException) {
            showError("${openFile.name} could not be saved. ${reason(e)}")
            false
        }
    }

    private fun reason(e: IOException): String =
        when (e) {
            is NoSuchFileException -> "Its folder does not exist."
            is AccessDeniedException -> "Access denied."
            is FileSystemException -> e.reason ?: e.toString()
            else -> e.message ?: e.toString()
        }

    private fun showErrorDialog(message: String) {
        Alert(Alert.AlertType.ERROR, message)
            .apply {
                scene?.window?.let(::initOwner)
                headerText = null
            }.showAndWait()
    }

    /** Closes all tabs without asking, including the ones that do not show files. */
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
