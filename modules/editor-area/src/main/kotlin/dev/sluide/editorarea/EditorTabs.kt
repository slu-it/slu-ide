package dev.sluide.editorarea

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.ViewActivity
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableBooleanValue
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

    /**
     * The icon shown before the title in the tab header and in the overflow menu, or `null` for none. Returns a fresh
     * node on every call, as a node can have one parent only.
     */
    fun icon(): Node? = null

    /** Releases the tab's resources. Called once, when the tab is closed. */
    fun dispose() {}
}

/** The two sides of the editor area, each with its own row of tabs (ADR 0134). */
internal enum class Side { LEFT, RIGHT }

/**
 * The tabs open in the editor area, each on one [Side], and the selected one of each side (ADR 0134). New tabs open
 * on the left. The right side exists only while it has tabs ([split]); when the left side loses its last tab while
 * the right has some, the right side's tabs become the left side's. [current] is the side the user works in;
 * [selected] is its selected tab.
 *
 * Every operation first changes the lists ([tabs], then the sides'), then [split], the selections of the sides and
 * [current], and [selected] last, once, with its final value.
 */
internal class EditorTabs {
    private val _tabs = FXCollections.observableArrayList<EditorTab>()
    private val sides = Side.entries.associateWith { FXCollections.observableArrayList<EditorTab>() }
    private val sideViews = sides.mapValues { (_, tabs) -> FXCollections.unmodifiableObservableList(tabs) }
    private val selections = Side.entries.associateWith { ReadOnlyObjectWrapper<EditorTab?>() }
    private val _split = ReadOnlyBooleanWrapper(false)
    private val _current = ReadOnlyObjectWrapper(Side.LEFT)
    private val _selected = ReadOnlyObjectWrapper<EditorTab?>()

    /** What [publish] makes the sides' selections and the current side; every operation sets these, then publishes. */
    private val chosen = mutableMapOf<Side, EditorTab?>()
    private var chosenSide = Side.LEFT

    /**
     * Called after an operation has disposed the tabs it removed: the list change is reported before the disposal, so
     * what depends on both (the editor area reporting closed files) waits for this.
     */
    var afterDispose: () -> Unit = {}

    /** Whether [publish] is notifying the listeners; [makeCurrent] is ignored meanwhile. */
    private var publishing = false

    /**
     * All tabs: the left side's in order, then the right side's. A move, also to the other side, is a permutation,
     * so listeners do not take the tab for closed and reopened.
     */
    val tabs: ObservableList<EditorTab> = FXCollections.unmodifiableObservableList(_tabs)

    /** The tabs on [side], in order. */
    fun tabsOf(side: Side): ObservableList<EditorTab> = sideViews.getValue(side)

    /** The selected tab of [side]; `null` only while the side has no tabs. */
    fun selectedOf(side: Side): ReadOnlyObjectProperty<EditorTab?> = selections.getValue(side).readOnlyProperty

    /** The side the user works in; [Side.LEFT] while there is no [split]. */
    val current: ReadOnlyObjectProperty<Side> = _current.readOnlyProperty

    /** The selected tab of the [current] side. */
    val selected: ReadOnlyObjectProperty<EditorTab?> = _selected.readOnlyProperty

    /** Whether the right side has tabs. */
    val split: ObservableBooleanValue = _split.readOnlyProperty

    /** The side [tab] is on. */
    fun sideOf(tab: EditorTab): Side =
        Side.entries.firstOrNull { tab in sides.getValue(it) } ?: throw IllegalArgumentException(notOpen(tab))

    /**
     * Makes [side] the [current] one; ignored for the right side while there is no [split]. Also ignored, not deferred,
     * while an operation notifies its listeners: a side that takes the focus because its selection changed must not
     * undo the current side the operation chose (moving a tab makes the target side current).
     */
    fun makeCurrent(side: Side) {
        if (publishing || sides.getValue(side).isEmpty()) return
        chosenSide = side
        publish()
    }

    /** The tabs showing files, in tab order. */
    val files: List<OpenFile> get() = _tabs.filterIsInstance<OpenFile>()

    fun find(path: Path): OpenFile? = files.firstOrNull { it.path == path }

    /** The tabs of the files at or below [path], a file or a folder; `src2` is not below `src`. */
    fun filesUnder(path: Path): List<OpenFile> {
        val folder = path.toAbsolutePath().normalize()
        return files.filter { it.path.startsWith(folder) }
    }

    /**
     * Adds a tab for [path] showing [view], opened by [viewer], like [open] of a tab: with [select] `false` it is
     * selected only if the left side has no selected tab, e.g. for a file another file's view edits (ADR 0057).
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
     * Puts [replacement] in the place of [tab], on its side, selected there if [tab] was, and disposes [tab]. The list
     * reports [tab] as removed and [replacement] as added.
     */
    fun replace(
        tab: EditorTab,
        replacement: EditorTab,
    ) {
        require(replacement !in _tabs) { "Already open: ${replacement.title.value}" }
        val side = sideOf(tab)
        _tabs[_tabs.indexOf(tab)] = replacement
        sides.getValue(side).let { it[it.indexOf(tab)] = replacement }
        if (chosen[side] === tab) chosen[side] = replacement
        publish()
        try {
            tab.dispose()
        } finally {
            afterDispose()
        }
    }

    /**
     * Adds [tab] at the end of the left side. With [select] it is selected there and the left side becomes current;
     * otherwise it is selected only if the left side has no selected tab, and the current side stays.
     */
    fun open(
        tab: EditorTab,
        select: Boolean = true,
    ) {
        require(tab !in _tabs) { "Already open: ${tab.title.value}" }
        val left = sides.getValue(Side.LEFT)
        _tabs.add(left.size, tab)
        left += tab
        if (select) chosenSide = Side.LEFT
        if (select || chosen[Side.LEFT] == null) chosen[Side.LEFT] = tab
        publish()
    }

    /** Selects [tab] on its side and makes that side current. */
    fun select(tab: EditorTab) {
        val side = sideOf(tab)
        chosen[side] = tab
        chosenSide = side
        publish()
    }

    /**
     * Moves [tab] to [toIndex] on its side (an index in [tabsOf]), keeping the selections. The lists report a
     * permutation, not a removal and an addition, so listeners do not take the tab for closed and reopened.
     */
    fun move(
        tab: EditorTab,
        toIndex: Int,
    ) {
        val side = sideOf(tab)
        val list = sides.getValue(side)
        require(toIndex in list.indices) { "No tab position $toIndex" }
        val from = list.indexOf(tab)
        if (from == toIndex) return
        val order = list.toMutableList().apply { add(toIndex, removeAt(from)) }
        reorder(_tabs, if (side == Side.LEFT) order + tabsOf(Side.RIGHT) else tabsOf(Side.LEFT) + order)
        reorder(list, order)
    }

    /**
     * Moves [tab] to the other [side], at [index] there, selects it there and makes that side current. On its old side
     * the selection goes to its right neighbour, else its left one, if it was selected there. [tabs] reports a
     * permutation: the tab is not closed, not disposed. [side] must not be the tab's own; [move] moves it within that.
     * The only tab, without a split, stays where it is: the left side would at once take the right side's tabs again.
     */
    fun moveTo(
        tab: EditorTab,
        side: Side,
        index: Int = tabsOf(side).size,
    ) {
        val from = sideOf(tab)
        require(from != side) { "Already on the $side side: ${tab.title.value}" }
        val source = sides.getValue(from)
        val target = sides.getValue(side)
        require(index in 0..target.size) { "No tab position $index" }
        if (source.size == 1 && target.isEmpty()) return
        val sourceIndex = source.indexOf(tab)
        val sourceOrder = source.filter { it !== tab }
        val targetOrder = target.toMutableList().apply { add(index, tab) }
        reorder(_tabs, if (side == Side.RIGHT) sourceOrder + targetOrder else targetOrder + sourceOrder)
        source.removeAt(sourceIndex)
        target.add(index, tab)
        if (chosen[from] === tab) chosen[from] = neighbour(source, sourceIndex)
        chosen[side] = tab
        chosenSide = side
        publish()
    }

    /** Removes [tab]. If it was selected on its side, its right neighbour there is selected, else its left one. */
    fun close(tab: EditorTab) {
        if (tab !in _tabs) return
        val side = sideOf(tab)
        val list = sides.getValue(side)
        val index = list.indexOf(tab)
        _tabs.remove(tab)
        list.removeAt(index)
        if (chosen[side] === tab) chosen[side] = neighbour(list, index)
        publish()
        try {
            tab.dispose()
        } finally {
            afterDispose()
        }
    }

    /** Closes every tab of both sides, e.g. with the window. */
    fun closeAll() = closeAll(_tabs.toList(), keep = null)

    /** Closes the tabs of [side]. */
    fun closeAll(side: Side) = closeAll(tabsOf(side).toList(), keep = null)

    /** Closes every other tab of the side of [tab] and selects [tab] there; its side becomes current. */
    fun closeOthers(tab: EditorTab) {
        val side = sideOf(tab)
        chosen[side] = tab
        chosenSide = side
        closeAll(othersOf(tab), keep = tab)
    }

    /**
     * Closes the tabs left of [tab] on its side, which becomes current; if its selected tab was among them, [tab] is
     * selected there.
     */
    fun closeLeftOf(tab: EditorTab) {
        chosenSide = sideOf(tab)
        closeAll(leftOf(tab), keep = tab)
    }

    /**
     * Closes the tabs right of [tab] on its side, which becomes current; if its selected tab was among them, [tab] is
     * selected there.
     */
    fun closeRightOf(tab: EditorTab) {
        chosenSide = sideOf(tab)
        closeAll(rightOf(tab), keep = tab)
    }

    /** The other tabs on the side of [tab]. */
    fun othersOf(tab: EditorTab): List<EditorTab> = sides.getValue(sideOf(tab)).filter { it !== tab }

    /** The tabs left of [tab] on its side. */
    fun leftOf(tab: EditorTab): List<EditorTab> =
        sides.getValue(sideOf(tab)).let { it.subList(0, it.indexOf(tab)).toList() }

    /** The tabs right of [tab] on its side. */
    fun rightOf(tab: EditorTab): List<EditorTab> =
        sides.getValue(sideOf(tab)).let { it.subList(it.indexOf(tab) + 1, it.size).toList() }

    /**
     * Removes [toClose] with one change of each list; a side whose selected tab is among them selects [keep], which is
     * on that side, or nothing.
     */
    private fun closeAll(
        toClose: Collection<EditorTab>,
        keep: EditorTab?,
    ) {
        val closing = toClose.toSet()
        if (closing.isNotEmpty()) {
            _tabs.removeAll(closing)
            sides.values.filter { list -> list.any { it in closing } }.forEach { it.removeAll(closing) }
            Side.entries.filter { chosen[it] in closing }.forEach { chosen[it] = keep }
        }
        publish()
        try {
            closing.forEach { it.dispose() }
        } finally {
            afterDispose()
        }
    }

    /**
     * Applies the rules for empty sides, then publishes the [chosen] selections and side: the right side's tabs become
     * the left side's when the left has none (in the same order, so [tabs] does not change), and an empty right side
     * is never current. [split] goes first, so a side that appears has its selection when that is set. What is
     * published is taken before the first listener runs, so a listener's call cannot change it halfway.
     */
    private fun publish() {
        val left = sides.getValue(Side.LEFT)
        val right = sides.getValue(Side.RIGHT)
        if (left.isEmpty() && right.isNotEmpty()) {
            val moved = right.toList()
            right.clear()
            left.setAll(moved)
            chosen[Side.LEFT] = chosen[Side.RIGHT]
        }
        if (right.isEmpty()) {
            chosen[Side.RIGHT] = null
            chosenSide = Side.LEFT
        }
        val selection = Side.entries.associateWith { chosen[it] }
        val side = chosenSide
        val outer = publishing
        publishing = true
        try {
            _split.set(right.isNotEmpty())
            Side.entries.forEach { selections.getValue(it).set(selection[it]) }
            _current.set(side)
            _selected.set(selection[side])
        } finally {
            publishing = outer
        }
    }

    private fun neighbour(
        list: List<EditorTab>,
        index: Int,
    ): EditorTab? = list.getOrNull(index) ?: list.getOrNull(index - 1)

    /** Brings [list] into [order], the same tabs, with one permutation; nothing if it is in that order already. */
    private fun reorder(
        list: ObservableList<EditorTab>,
        order: List<EditorTab>,
    ) {
        if (list == order) return
        val positions = order.withIndex().associate { (index, it) -> it to index }
        list.sortWith(compareBy { positions.getValue(it) })
    }

    private fun notOpen(tab: EditorTab) = "Not open: ${tab.title.value}"
}

/** The default [EditorTab.activity]. */
private val NO_ACTIVITY: ObservableValue<ViewActivity> = ReadOnlyObjectWrapper(ViewActivity.NONE).readOnlyProperty

/** The default [EditorTab.closableByShortcut]. */
private val CLOSABLE: ObservableValue<Boolean> = ReadOnlyBooleanWrapper(true).readOnlyProperty
