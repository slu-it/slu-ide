package dev.sluide.gitlog.filter

import dev.sluide.gitlog.ui.GIT_LOG_CSS
import javafx.scene.Node
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Popup

/**
 * The popup of a [FilterButton] (ADR 0039): a search field above a list of [items] with check boxes, the checked ones
 * first. Typing narrows the list; clicking an item or Space toggles it and tells [changed] right away, the popup stays
 * open. Enter in the search field toggles the one item shown (or the one named exactly), else adds the text as a
 * value of its own if [freeItem] makes one of it. Down moves into the list, Escape closes.
 */
internal class FilterPopup(
    private val items: () -> List<FilterItem>,
    private val freeItem: ((String) -> FilterItem?)?,
    private val changed: () -> Unit,
) {
    /** The selected values with their items, also those no longer among [items] (a deleted branch, a typed value). */
    private val chosen = LinkedHashMap<String, FilterItem>()

    /** The items as read when the popup was last shown or refreshed (there may be 100 000 paths). */
    private var known: List<FilterItem> = emptyList()
        set(value) {
            field = value
            knownIndex =
                HashMap<String, Int>(value.size * 2).apply { value.forEachIndexed { i, it -> put(it.value, i) } }
            knownTexts = value.map { it.text.lowercase() }
        }

    /** The index of every value in [known], for the list order. */
    private var knownIndex: Map<String, Int> = emptyMap()

    /** The text of every item in [known] in lower case, for the search. */
    private var knownTexts: List<String> = emptyList()

    internal val search =
        TextField().apply {
            styleClass += "git-filter-popup-search"
            textProperty().subscribe { _, _ -> rebuild() }
            setOnAction { enter() }
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                if (event.code == KeyCode.DOWN) {
                    list.requestFocus()
                    if (list.selectionModel.isEmpty && list.items.isNotEmpty()) list.selectionModel.select(0)
                    event.consume()
                }
            }
        }

    internal val list: ListView<FilterItem> =
        ListView<FilterItem>().apply {
            styleClass += "git-filter-list"
            VBox.setVgrow(this, Priority.ALWAYS)
            setCellFactory { FilterCell() }
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                if (event.code == KeyCode.SPACE) {
                    selectionModel.selectedItem?.let(::toggle)
                    event.consume()
                }
            }
        }

    internal val content =
        VBox(search, list).apply {
            // .git-log gives the looked-up colours of the ref icons: the popup is a window of its own.
            styleClass.addAll("git-log", "git-filter-popup")
            stylesheets += GIT_LOG_CSS
            prefWidth = 320.0
            prefHeight = 300.0
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (event.code == KeyCode.ESCAPE) {
                    hide()
                    event.consume()
                }
            }
        }

    internal val popup: Popup =
        Popup().also { popup ->
            popup.isAutoHide = true
            popup.content += content
        }

    /** The selected items, in list order: the known ones, then the others in the order they were chosen. */
    val selected: List<FilterItem>
        get() = chosen.values.sortedBy { knownIndex[it.value] ?: Int.MAX_VALUE }

    /** Shows the popup below [owner] with the current [items], the search field empty and focused. */
    fun show(owner: Node) {
        known = items()
        search.clear()
        rebuild()
        val bounds = owner.localToScreen(owner.boundsInLocal) ?: return
        popup.show(owner, bounds.minX, bounds.maxY)
        search.requestFocus()
    }

    fun hide() = popup.hide()

    /** Reads the [items] again if the popup is showing, e.g. once they are loaded. */
    fun refresh() {
        if (!popup.isShowing) return
        known = items()
        rebuild()
    }

    /** Selects nothing, without telling. */
    fun clear() {
        chosen.clear()
        rebuild()
    }

    /** Checks exactly the items of [values] (with their known items, read again), without telling. */
    fun choose(values: List<String>) {
        known = items()
        chosen.clear()
        for (value in values) chosen[value] = knownIndex[value]?.let(known::get) ?: FilterItem(value, value)
        rebuild()
    }

    /** Checks or unchecks [item] and tells [changed]. */
    fun toggle(item: FilterItem) {
        if (chosen.remove(item.value) == null) chosen[item.value] = item
        list.refresh()
        changed()
    }

    /** The chosen items that are not among the [known] ones (a deleted branch, a typed value). */
    private fun others(): List<FilterItem> = chosen.values.filter { it.value !in knownIndex }

    /** Fills the list with the items whose text has the search text, the checked ones first. */
    private fun rebuild() {
        val text =
            search.text
                .orEmpty()
                .trim()
                .lowercase()
        val checked = ArrayList<FilterItem>()
        val unchecked = ArrayList<FilterItem>()
        for (index in known.indices) {
            if (text.isNotEmpty() && text !in knownTexts[index]) continue
            val item = known[index]
            if (item.value in chosen) checked += item else unchecked += item
        }
        // Chosen items that are not known are checked.
        checked += others().filter { it.text.lowercase().contains(text) }
        list.items.setAll(checked + unchecked)
    }

    private fun enter() {
        val text = search.text.orEmpty().trim()
        if (text.isEmpty()) return
        val shown = list.items
        val named =
            known.firstOrNull { it.value.equals(text, ignoreCase = true) || it.text.equals(text, ignoreCase = true) }
                ?: others().firstOrNull { it.value.equals(text, ignoreCase = true) }
        when {
            shown.size == 1 -> {
                toggle(shown.single())
            }

            // Several shown, one of them named exactly.
            named != null -> {
                toggle(named)
            }

            freeItem != null -> {
                val item = freeItem(text) ?: return
                if (item.value !in chosen) {
                    chosen[item.value] = item
                    changed()
                }
                search.clear()
            }
        }
    }

    /** A check box, the item's icon and its text; clicking it toggles the item. */
    private inner class FilterCell : ListCell<FilterItem>() {
        private val check =
            CheckBox().apply {
                isFocusTraversable = false
                // The cell toggles the item, not the check box itself.
                isMouseTransparent = true
            }
        private val name = Label().apply { styleClass += "git-filter-item-text" }
        private val box = HBox(check, name).apply { styleClass += "git-filter-item" }

        init {
            setOnMouseClicked { event ->
                // Once for a double click too.
                if (event.button == MouseButton.PRIMARY && event.clickCount == 1) item?.let(::toggle)
            }
        }

        override fun updateItem(
            item: FilterItem?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            if (empty || item == null) {
                graphic = null
            } else {
                check.isSelected = item.value in chosen
                name.text = item.text
                name.graphic = item.icon?.invoke()
                graphic = box
            }
        }
    }
}
