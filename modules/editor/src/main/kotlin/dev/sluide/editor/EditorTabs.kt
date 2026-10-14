package dev.sluide.editor

import dev.sluide.fileviewers.FileView
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.Node
import javafx.scene.layout.StackPane
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

    /** Releases the tab's resources. Called once, when the tab is closed. */
    fun dispose() {}
}

/**
 * A file shown in a tab together with the view that displays it. The view can be replaced (e.g. by a diff view) while
 * the tab and its [content] stay the same; the replaced view is disposed, and so is the view when the tab closes.
 */
internal class OpenFile(
    val path: Path,
    view: FileView,
) : EditorTab {
    val name: String get() = (path.fileName ?: path).toString()

    override val title: ObservableValue<String> = ReadOnlyStringWrapper(name).readOnlyProperty

    override val tooltip: String get() = path.toString()

    override val content = StackPane(view.node)

    var view: FileView = view
        set(value) {
            val old = field
            field = value
            content.children.setAll(value.node)
            if (old !== value) old.dispose()
        }

    override fun dispose() = view.dispose()
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

    /** Adds a tab for [path] showing [view] and selects it. */
    fun open(
        path: Path,
        view: FileView,
    ): OpenFile {
        require(find(path) == null) { "Already open: $path" }
        return OpenFile(path, view).also(::open)
    }

    /** Adds [tab] at the end and selects it. */
    fun open(tab: EditorTab) {
        require(tab !in _tabs) { "Already open: ${tab.title.value}" }
        _tabs += tab
        _selected.set(tab)
    }

    fun select(tab: EditorTab) {
        require(tab in _tabs) { "Not open: ${tab.title.value}" }
        _selected.set(tab)
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
