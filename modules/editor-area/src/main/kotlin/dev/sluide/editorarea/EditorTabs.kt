package dev.sluide.editorarea

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.ViewActivity
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.Node
import java.nio.file.Path

/** Something shown in a tab of the editor area: an open file, a terminal, … */
interface EditorTab {
    /** The text on the tab header; it may change while the tab is open. */
    val title: ObservableValue<String>

    /** The tooltip of the tab header. */
    val tooltip: String

    /** The node shown below the tab strip while the tab is selected. */
    val content: Node

    /**
     * Why closing the tab from the UI should be confirmed first, e.g. "Terminal 1 is still running java.", or `null`
     * if it can simply be closed. Not asked when all tabs close with their window, which asks once for all of them.
     */
    val closeWarning: String? get() = null

    /**
     * Whether [content] shows file text in the editor font; the editor area then binds its style to the font setting
     * while the tab is open. Such a tab keeps the same [content] node and does not set its style itself.
     */
    val usesEditorFont: Boolean get() = false

    /**
     * Whether Shortcut+W (File → Close Tab) closes the tab while it is selected. It may change while the tab is
     * selected, e.g. a terminal running a single command becomes closable once the command has exited.
     */
    val closableByShortcut: ObservableValue<Boolean> get() = CLOSABLE

    /**
     * Whether the editor area focuses [content] when the user opens or chooses this tab, as it does for file tabs
     * (ADR 0050); [content] then hands the focus on to the node that takes the keys. Off by default: the other tabs
     * keep their own behaviour.
     */
    val focusOnShow: Boolean get() = false

    /** What the tab is doing, shown as a marker on its header (a reading running, ...); none by default. */
    val activity: ObservableValue<ViewActivity> get() = NO_ACTIVITY

    /** Releases the tab's resources. Called once, when the tab is closed. */
    fun dispose() {}
}

/** The tabs open in the editor area, in tab order, and the selected one. */
internal class EditorTabs {
    private val _tabs = FXCollections.observableArrayList<EditorTab>()
    private val _selected = ReadOnlyObjectWrapper<EditorTab?>()

    val tabs: ObservableList<EditorTab> = FXCollections.unmodifiableObservableList(_tabs)
    val selected: ReadOnlyObjectProperty<EditorTab?> = _selected.readOnlyProperty

    /** The tabs showing files, in tab order. */
    val files: List<OpenFile> get() = _tabs.filterIsInstance<OpenFile>()

    fun find(path: Path): OpenFile? = files.firstOrNull { it.path == path }

    /** The tabs of the files at or below [path], a file or a folder; `src2` is not below `src`. */
    fun filesUnder(path: Path): List<OpenFile> {
        val folder = path.toAbsolutePath().normalize()
        return files.filter { it.path.startsWith(folder) }
    }

    /**
     * Adds a tab for [path] showing [view], opened by [viewer], and selects it; with [select] `false` only if no tab
     * is selected (none is open), e.g. for a file another file's view edits (ADR 0057).
     */
    fun open(
        path: Path,
        view: FileView,
        showsDiffs: Boolean = false,
        viewer: FileViewer? = null,
        select: Boolean = true,
    ): OpenFile {
        require(find(path) == null) { "Already open: $path" }
        return OpenFile(path, view, showsDiffs, viewer).also { open(it, select) }
    }

    /**
     * Puts [replacement] in the place of [tab], selected if [tab] was, and disposes [tab]. The list reports [tab] as
     * removed and [replacement] as added.
     */
    fun replace(
        tab: EditorTab,
        replacement: EditorTab,
    ) {
        require(replacement !in _tabs) { "Already open: ${replacement.title.value}" }
        _tabs[indexOfOpen(tab)] = replacement
        if (_selected.get() === tab) _selected.set(replacement)
        tab.dispose()
    }

    /** Adds [tab] at the end and selects it; with [select] `false` only if no tab is selected. */
    fun open(
        tab: EditorTab,
        select: Boolean = true,
    ) {
        require(tab !in _tabs) { "Already open: ${tab.title.value}" }
        _tabs += tab
        if (select || _selected.get() == null) _selected.set(tab)
    }

    fun select(tab: EditorTab) {
        require(tab in _tabs) { "Not open: ${tab.title.value}" }
        _selected.set(tab)
    }

    /**
     * Moves [tab] to [toIndex], keeping the selection. The list reports a permutation, not a removal and an addition,
     * so listeners do not take the tab for closed and reopened.
     */
    fun move(
        tab: EditorTab,
        toIndex: Int,
    ) {
        val from = indexOfOpen(tab)
        require(toIndex in _tabs.indices) { "No tab position $toIndex" }
        if (from == toIndex) return
        val order = _tabs.toMutableList().apply { add(toIndex, removeAt(from)) }
        val positions = order.withIndex().associate { (index, it) -> it to index }
        _tabs.sortWith(compareBy { positions.getValue(it) })
    }

    /** Removes [tab]. If it was selected, its right neighbour is selected, otherwise its left one, if any. */
    fun close(tab: EditorTab) {
        val index = _tabs.indexOf(tab)
        if (index < 0) return
        _tabs.removeAt(index)
        if (_selected.get() === tab) {
            _selected.set(_tabs.getOrNull(index) ?: _tabs.getOrNull(index - 1))
        }
        tab.dispose()
    }

    fun closeAll() = closeAll(_tabs.toList(), keep = null)

    /** Closes every tab except [tab] and selects it. */
    fun closeOthers(tab: EditorTab) {
        closeAll(othersOf(tab), keep = tab)
        select(tab)
    }

    /** Closes the tabs left of [tab]; if the selected tab was among them, [tab] becomes selected. */
    fun closeLeftOf(tab: EditorTab) = closeAll(leftOf(tab), keep = tab)

    /** Closes the tabs right of [tab]; if the selected tab was among them, [tab] becomes selected. */
    fun closeRightOf(tab: EditorTab) = closeAll(rightOf(tab), keep = tab)

    fun othersOf(tab: EditorTab): List<EditorTab> = _tabs.filter { it !== tab }

    fun leftOf(tab: EditorTab): List<EditorTab> = _tabs.subList(0, indexOfOpen(tab)).toList()

    fun rightOf(tab: EditorTab): List<EditorTab> = _tabs.subList(indexOfOpen(tab) + 1, _tabs.size).toList()

    private fun indexOfOpen(tab: EditorTab): Int =
        _tabs.indexOf(tab).also { require(it >= 0) { "Not open: ${tab.title.value}" } }

    /** Removes [toClose] in one list change; if the selected tab is among them, [keep] becomes selected. */
    private fun closeAll(
        toClose: Collection<EditorTab>,
        keep: EditorTab?,
    ) {
        if (toClose.isEmpty()) return
        _tabs.removeAll(toClose.toSet())
        if (_selected.get() in toClose) _selected.set(keep)
        toClose.forEach { it.dispose() }
    }
}

/** The default [EditorTab.activity]. */
private val NO_ACTIVITY: ObservableValue<ViewActivity> = ReadOnlyObjectWrapper(ViewActivity.NONE).readOnlyProperty

/** The default [EditorTab.closableByShortcut]. */
private val CLOSABLE: ObservableValue<Boolean> = ReadOnlyBooleanWrapper(true).readOnlyProperty
