package dev.sluide.editorarea

import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.TextDiff
import dev.sluide.fileviewers.WindowServices
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.value.ObservableBooleanValue
import javafx.beans.value.ObservableValue
import javafx.collections.ListChangeListener
import javafx.scene.Node
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.nio.file.Path

private val log = System.getLogger(EditorArea::class.java.name)

/**
 * Central area showing open files, one tab per file, and other [EditorTab]s such as terminals. Open files are
 * watched: a tab reloads when its file changes on disk and closes when the file is deleted or can no longer be shown
 * (e.g. it became binary), unless it has unsaved changes. The lines changed against an original ([diffOf]) are marked
 * in the editor ([EditableFileView.showChanges]); [toggleDiff] shows them side by side over it. Edited files are saved
 * with [save] and [saveAll]; closing them asks to save first ([confirmSave]).
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
    internal val tabStrip = TabStrip(editorTabs, ::confirmClosing, ::selectChosen)

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

    /**
     * Asked before a git operation, named by its action ("Check Out", "Cherry-Pick"), changes files with unsaved changes
     * on disk ([saveBeforeGitChange]), with their paths as for [confirmSave]: whether to save them first, go on anyway
     * ([SaveChoice.DISCARD], which keeps the changes in their tabs) or not go on. Shows a dialog
     * ([askToSaveBeforeGitChange]); tests replace it.
     */
    var confirmSaveBeforeGitChange: (files: List<String>, action: String) -> SaveChoice =
        { files, action -> askToSaveBeforeGitChange(scene?.window, files, action) }

    /** Shows an error message, e.g. that a file could not be saved, and waits until it is closed; tests replace it. */
    var showError: (message: String) -> Unit = { showErrorAlert(scene?.window, it) }
    private val watches = mutableMapOf<EditorTab, AutoCloseable>()

    /**
     * Gives the callback the diff of a file against its original (e.g. the last commit), `null` if it has none (ADR
     * 0021), on the JavaFX thread, maybe later (ADR 0062). Its changes are marked in the file's editor. Asked when a
     * file is opened, when it changes on disk and on [refreshDiffs]; only the answer to the newest request counts.
     */
    var diffOf: (Path, (TextDiff?) -> Unit) -> Unit = { _, done -> done(null) }

    /** Shows a web page outside the IDE when a file view asks for it, e.g. for a clicked link; set by the window. */
    var openWebPage: (URI) -> Unit = {}

    /** The project folder of the window, whose files HTML previews may use; set by the window. */
    var projectFolder: () -> Path? = { null }

    /**
     * Shows the history of a file, e.g. in the git log area (ADR 0046): Show History in the context menu of the tabs
     * that show a file on disk, only while set. Set by the window.
     */
    var showHistory: ((Path) -> Unit)?
        get() = tabStrip.showHistory
        set(value) {
            tabStrip.showHistory = value
        }

    /** Whether Show History is enabled for a tab's file; asked when the tab's context menu opens. */
    var canShowHistory: (Path) -> Boolean
        get() = tabStrip.canShowHistory
        set(value) {
            tabStrip.canShowHistory = value
        }

    /**
     * Called on the FX thread with the path of each file tab removed from the tabs in any way (closed from the UI,
     * [closeAll], [closeUnder], after a delete, or a move its tab cannot follow) when no tab of that path is open in this
     * area anymore, e.g. not when a tab is replaced by a new view of the same file; a move followed in place reports
     * nothing. Called once the change of the tabs is processed; set by the window.
     */
    var onFileClosed: (Path) -> Unit = {}

    /** The services of the window that file views look up by exact type ([FileViewHost.windowService], ADR 0070). */
    val windowServices = WindowServices()

    /** Another file's tab for views that edit it; opened without being selected or focused (ADR 0057). */
    private val tabTexts =
        TabTexts(editorTabs) { path -> openTab(path, select = false) { view -> TabTexts.editable(view) != null } }

    /** Lets the file views open files in this area and web pages through [openWebPage]. */
    private val viewHost = ViewHost(this, ::showChangesAt, tabTexts)

    /** The editor font as CSS; file views and diffs inherit it from their tab's node. */
    private val editorCss = fontSettings.font(FontArea.EDITOR).map(FontSpec::toCss)

    init {
        // Every way of opening or closing a tab changes the list: file views and diffs get the editor font, closed
        // tabs lose their watch and font binding.
        editorTabs.tabs.addListener(
            ListChangeListener { change ->
                val closed = mutableSetOf<Path>()
                while (change.next()) {
                    change.removed.forEach { tab ->
                        watches.remove(tab)?.close()
                        // Unbinding also lets the shared font binding forget the closed view.
                        if (tab.usesEditorFont) tab.content.styleProperty().unbind()
                        if (tab is OpenFile) closed.add(tab.path)
                    }
                    change.addedSubList.filter { it.usesEditorFont }.forEach {
                        it.content.styleProperty().bind(
                            editorCss,
                        )
                    }
                }
                closed.filter { editorTabs.find(it) == null }.forEach(onFileClosed)
            },
        )
        styleClass += "editor-area"
        stylesheets += checkNotNull(EditorArea::class.java.getResource("editor.css")).toExternalForm()
        val content = StackPane().apply { styleClass += "editor-content" }
        top = tabStrip
        center = content
        editorTabs.selected.subscribe { selected ->
            // Asked before the content changes: until then, a focus owner in the old content is still in the scene.
            val owner = scene?.focusOwner
            val focusHere = owner == null || owner.scene == null || owner.isInside(this@EditorArea)
            content.children.setAll(listOfNotNull(selected?.content))
            // A file tab shown in place of another (closed, replaced) takes the focus only if the area had it, e.g. not
            // after its file was deleted from the file tree (ADR 0050). Opening and choosing a tab always focus it.
            if (selected is OpenFile && focusHere) focus(selected)
        }
    }

    /** Whether a [dev.sluide.fileviewers.FileViewer] can show [file], i.e. whether [open] would open it. */
    fun canOpen(file: Path): Boolean = fileViewers.viewerFor(file) != null

    /**
     * Shows [file] in a tab: selects its tab if it is already open, otherwise opens a new one with the first
     * [dev.sluide.fileviewers.FileViewer] that can show it. Either way the file's view takes the focus
     * ([FileView.focus]). Returns `false` if no viewer can show or read it.
     */
    fun open(file: Path): Boolean {
        val path = file.toAbsolutePath().normalize()
        editorTabs.find(path)?.let {
            editorTabs.select(it)
            focus(it)
            return true
        }
        val openFile = openTab(path, select = true) ?: return false
        focus(openFile)
        return true
    }

    /**
     * Opens a new tab for [path], which is not open, with the first viewer that can show it, selected if [select];
     * `null` if no viewer can show or read it, or [accept] rejects its view, which is then disposed.
     */
    private fun openTab(
        path: Path,
        select: Boolean,
        accept: (FileView) -> Boolean = { true },
    ): OpenFile? {
        val viewer = fileViewers.viewerFor(path) ?: return null
        val view =
            try {
                viewer.open(path)
            } catch (e: IOException) {
                warn("Cannot open", path, e)
                return null
            }
        if (!accept(view)) return null.also { view.dispose() }
        val openFile = editorTabs.open(path, hosted(view), fileViewers.showsDiffs(viewer), viewer, select)
        showChanges(openFile)
        watch(openFile)
        return openFile
    }

    /**
     * Lets the tabs of files at or below [from] follow them to [to], where they were moved, e.g. a renamed file or
     * folder. Call it right after the move: the watch of a renamed file reports its old path as deleted, which would
     * close a clean tab, and the watches of the files in a renamed folder follow the folder without reporting
     * anything, which would leave their tabs at paths that no longer exist.
     *
     * A tab keeps its view if the view can follow its file ([FileView.moved]) and the same viewer shows the new path;
     * otherwise it shows the file in a new view in its place. A tab with unsaved changes always keeps its view and
     * changes, also if a new name would be shown by another viewer. If a tab is open at the new path already (one with
     * unsaved changes whose file was deleted), it stays the file's tab and shows the file now there: the moved tab
     * closes, or keeps its unsaved changes at the old path, where it shows its file deleted.
     */
    fun moved(
        from: Path,
        to: Path,
    ) {
        val source = from.toAbsolutePath().normalize()
        val target = to.toAbsolutePath().normalize()
        for (openFile in filesUnder(source)) {
            follow(openFile, target.resolve(source.relativize(openFile.path)))
        }
    }

    private fun follow(
        openFile: OpenFile,
        path: Path,
    ) {
        editorTabs.find(path)?.let { existing ->
            // Its file was deleted; now there is one again, maybe with other text than the tab kept.
            onChangedOnDisk(existing)
            if (openFile.isDirty) onChangedOnDisk(openFile) else editorTabs.close(openFile)
            return
        }
        val viewer = fileViewers.viewerFor(path)
        if ((viewer === openFile.viewer || openFile.isDirty) && openFile.editor.moved(path)) {
            openFile.path = path
            // The diff shown, and the one to show until the new path's arrives, is of the old path.
            hideDiff(openFile)
            openFile.diff.set(null)
            watch(openFile)
            showChanges(openFile)
            return
        }
        // Unsaved changes stay with the old path, where the file is gone: the view says so, saving recreates it.
        if (openFile.isDirty) {
            onChangedOnDisk(openFile)
            return
        }
        val view =
            viewer?.let {
                try {
                    hosted(it.open(path))
                } catch (e: IOException) {
                    warn("Cannot reopen", path, e)
                    null
                }
            }
        if (viewer == null || view == null) {
            editorTabs.close(openFile)
            return
        }
        val replacement = OpenFile(path, view, fileViewers.showsDiffs(viewer), viewer)
        editorTabs.replace(openFile, replacement)
        showChanges(replacement)
        watch(replacement)
    }

    /** Asks [diffOf] again for every open file and marks its changes. Call it when the answers may have changed. */
    fun refreshDiffs() = editorTabs.files.forEach(::showChanges)

    /**
     * Asks [diffOf] for the diff of [openFile] and, once it answers, marks its changes in the editor; a diff shown
     * follows it, or gives way to the editor once there is no diff anymore. Until then the old markers stay. An answer
     * is dropped if the tab was closed or asked again meanwhile. [diffOf] (git) is only asked for files shown as text.
     */
    private fun showChanges(openFile: OpenFile) {
        val editor = openFile.editor as? EditableFileView ?: return
        if (!openFile.showsDiffs) return
        val request = ++openFile.diffRequests
        diffOf(openFile.path) answer@{ diff ->
            if (request != openFile.diffRequests || openFile !in editorTabs.tabs) return@answer
            openFile.diff.set(diff)
            editor.showChanges(diff)
            val diffView = openFile.diffView ?: return@answer
            if (diff == null) return@answer hideDiff(openFile)
            try {
                diffView.show(titled(openFile, diff))
            } catch (e: IOException) {
                // Keep showing the last diff; the next change will try again.
                warn("Cannot update the diff of", openFile.path, e)
            }
        }
    }

    /** Whether the selected tab is a file with a diff, or shows it: whether [toggleDiff] does something. */
    val canToggleDiff: ObservableValue<Boolean> =
        editorTabs.selected.flatMap { (it as? OpenFile)?.hasDiff ?: NO_DIFF }.orElse(false)

    /** Whether [closeSelected] does something. */
    val canCloseSelected: ObservableValue<Boolean> =
        editorTabs.selected.map { it?.closableByShortcut ?: false }.orElse(false)

    /**
     * Shows the diff of the selected file ([diffOf]) side by side over its editor, or the editor again while the diff
     * is shown. The diff is of the file as saved; the editor keeps its unsaved changes, caret and undo history
     * meanwhile. Does nothing unless [canToggleDiff], e.g. for files without changes.
     */
    fun toggleDiff() {
        val openFile = editorTabs.selected.get() as? OpenFile ?: return
        if (openFile.diffView != null) hideDiff(openFile) else showDiff(openFile)
    }

    private fun showDiff(openFile: OpenFile) {
        val diff = openFile.diff.get() ?: return
        val diffView =
            try {
                fileViewers.openDiff(openFile.path, titled(openFile, diff))
            } catch (e: IOException) {
                warn("Cannot show the diff of", openFile.path, e)
                null
            } ?: return
        diffView.onBack = { hideDiff(openFile) }
        val focused = scene?.focusOwner
        openFile.focusInEditor = focused?.takeIf { it.isInside(openFile.editor.node) }
        openFile.diffView = diffView
        diffView.node.requestFocus()
    }

    /** Shows the diff of the open [file] in its tab, at the change at its [line], e.g. for a clicked change marker. */
    private fun showChangesAt(
        file: Path,
        line: Int,
    ) {
        val openFile = editorTabs.find(file.toAbsolutePath().normalize()) ?: return
        editorTabs.select(openFile)
        if (openFile.diffView == null) showDiff(openFile)
        openFile.diffView?.jumpToChangeAt(line)
    }

    /**
     * Shows the editor of [openFile] again. If the diff had the focus, the editor gets it: where it was when the diff
     * was shown, or its text.
     */
    private fun hideDiff(openFile: OpenFile) {
        val diffView = openFile.diffView ?: return
        val diffFocused = scene?.focusOwner?.isInside(diffView.node) == true
        openFile.diffView = null
        if (!diffFocused) return
        val focus = openFile.focusInEditor?.takeIf { it.scene != null && it.isInside(openFile.editor.node) }
        if (focus != null) focus.requestFocus() else openFile.editor.focusText()
    }

    /** [diff], whose current side is the file as saved, saying so while the editor has unsaved changes. */
    private fun titled(
        openFile: OpenFile,
        diff: TextDiff,
    ) = if (openFile.isDirty) diff.copy(currentTitle = "${diff.currentTitle} (saved)") else diff

    /**
     * Gives the focus to the selected tab: a file's view as when its tab is shown ([FileView.focus]), or its diff while
     * that is shown; the content of any other tab; the area itself without tabs.
     */
    fun focusSelected() {
        when (val tab = editorTabs.selected.get()) {
            null -> requestFocus()
            is OpenFile -> focus(tab)
            else -> tab.content.requestFocus()
        }
    }

    /** Selects [tab], which the user chose (e.g. clicked its header), and focuses it if it shows a file. */
    private fun selectChosen(tab: EditorTab) {
        editorTabs.select(tab)
        if (tab is OpenFile) focus(tab)
    }

    private fun focus(openFile: OpenFile) {
        val diffView = openFile.diffView
        if (diffView != null) diffView.node.requestFocus() else openFile.editor.focus()
    }

    /** Adds [tab] after the open tabs and selects it. Its [EditorTab.dispose] is called when it is closed. */
    fun open(tab: EditorTab) = editorTabs.open(tab)

    /** Selects [tab], one of the open [tabs]. */
    fun select(tab: EditorTab) = editorTabs.select(tab)

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

    /** Shows the file's current content and marks its changes. */
    private fun updateView(openFile: OpenFile) {
        try {
            openFile.editor.reload()
        } catch (e: IOException) {
            // Keep showing the last content; the next change will try again.
            warn("Cannot reload", openFile.path, e)
            return
        }
        showChanges(openFile)
    }

    private fun warn(
        what: String,
        path: Path,
        e: IOException,
    ) = log.log(System.Logger.Level.WARNING, "$what $path", e)

    private fun hosted(view: FileView): FileView = view.apply { host = viewHost }

    /** Closes the selected tab if it is closable by shortcut, asking as the tab strip does. FX thread. */
    fun closeSelected() {
        val tab = editorTabs.selected.get()?.takeIf { it.closableByShortcut } ?: return
        if (confirmClosing(listOf(tab))) editorTabs.close(tab)
    }

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

    /**
     * Asks to save the files at or below [folder] (a repository) with unsaved changes before a git operation, named by
     * its [action] ("Check Out", "Cherry-Pick", "Reset"), changes files there ([confirmSaveBeforeGitChange]): Save saves
     * them, "<action> Anyway" keeps the changes in their tabs, which then offer to reload the changed file or keep them,
     * Cancel does nothing. Returns whether to go on: `false` if the user cancelled or saving failed.
     */
    fun saveBeforeGitChange(
        folder: Path,
        action: String,
    ): Boolean {
        val dirty = filesUnder(folder).filter { it.isDirty }
        if (dirty.isEmpty()) return true
        return when (confirmSaveBeforeGitChange(dirty.map(::shownPath), action)) {
            SaveChoice.SAVE -> dirty.map(::save).all { it }
            SaveChoice.DISCARD -> true
            SaveChoice.CANCEL -> false
        }
    }

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
        val view = openFile.editor as? EditableFileView ?: return true
        return try {
            view.save()
            true
        } catch (e: IOException) {
            showError("${openFile.name} could not be saved. ${saveFailureReason(e)}")
            false
        }
    }

    /** Closes all tabs without asking, including the ones that do not show files. */
    fun closeAll() = editorTabs.closeAll()

    /** The open files at or below [path] with unsaved changes, e.g. to warn before [path] is deleted. */
    fun dirtyUnder(path: Path): List<Path> = filesUnder(path).filter { it.isDirty }.map { it.path }

    /**
     * Closes the tabs of the files at or below [path] without asking, after [path] was deleted: those without unsaved
     * changes and those whose changes were [discarded] (e.g. counted as lost when the delete was confirmed). The other
     * tabs, edited since, keep their changes and show their file deleted; saving recreates it.
     */
    fun closeUnder(
        path: Path,
        discarded: List<Path>,
    ) {
        for (openFile in filesUnder(path)) {
            if (!openFile.isDirty ||
                openFile.path in discarded
            ) {
                editorTabs.close(openFile)
            } else {
                onChangedOnDisk(openFile)
            }
        }
    }

    private fun filesUnder(path: Path): List<OpenFile> {
        val folder = path.toAbsolutePath().normalize()
        return editorTabs.files.filter { it.path.startsWith(folder) }
    }

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

/** The [OpenFile.hasDiff] of tabs that do not show files. */
private val NO_DIFF: ObservableBooleanValue = ReadOnlyBooleanWrapper(false).readOnlyProperty

/** Whether this node is [ancestor] or within it. */
internal fun Node.isInside(ancestor: Node): Boolean = generateSequence(this) { it.parent }.any { it === ancestor }
