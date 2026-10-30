package dev.sluide.scratches.picker

import dev.sluide.fileicons.FILE_ICONS_CSS
import javafx.css.PseudoClass
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.VBox
import javafx.stage.Popup
import javafx.stage.Window

private val SCRATCHES_CSS: String =
    checkNotNull(FilterPicker::class.java.getResource("/dev/sluide/scratches/scratches.css")).toExternalForm()

private val HEADER: PseudoClass = PseudoClass.getPseudoClass("header")

/** The graphic of an entry row: its [node], reused for the items a cell shows, and how it [show]s one. */
internal class EntryGraphic<T>(
    val node: Node,
    val show: (T) -> Unit,
)

/**
 * The popup of the scratch pickers, as in IntelliJ: a [title], a filter field above the list of [items]. Typing filters
 * them by [filter]; with [headerOf], each run of items with the same title gets a header row that is never selected or
 * picked. Enter picks the selected entry, Up and Down move the selection over the entries while the focus stays in the
 * field, a click picks an entry, Escape or a click elsewhere closes. The list takes no focus, so only this code moves
 * its selection. Each entry row shows a graphic made by [entryGraphic].
 */
internal class FilterPicker<T>(
    title: String,
    prompt: String,
    noMatch: String,
    private val items: List<T>,
    private val filter: (List<T>, String?) -> List<T>,
    private val headerOf: ((T) -> String)? = null,
    private val popupWidth: Double = DEFAULT_WIDTH,
    private val entryGraphic: () -> EntryGraphic<T>,
) {
    private var onPick: (T) -> Unit = {}

    val field: TextField =
        TextField().apply {
            styleClass += "scratch-picker-field"
            promptText = prompt
            textProperty().subscribe { _, _ -> refilter() }
            setOnAction { pickSelected() }
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                when (event.code) {
                    KeyCode.DOWN -> move(1)
                    KeyCode.UP -> move(-1)
                    else -> return@addEventHandler
                }
                event.consume()
            }
        }

    val list: ListView<PickerRow<T>> =
        ListView<PickerRow<T>>().apply {
            styleClass += "scratch-picker-list"
            isFocusTraversable = false
            fixedCellSize = CELL_HEIGHT
            placeholder = Label(noMatch)
            setCellFactory { RowCell() }
        }

    private val content: VBox =
        VBox(Label(title).apply { styleClass += "scratch-picker-title" }, field, list).apply {
            styleClass += "scratch-picker"
            stylesheets.addAll(FILE_ICONS_CSS, SCRATCHES_CSS)
            prefWidth = popupWidth
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (event.code == KeyCode.ESCAPE) {
                    hide()
                    event.consume()
                }
            }
        }

    private val popup: Popup =
        Popup().apply {
            isAutoHide = true
            content += this@FilterPicker.content
        }

    val isShowing: Boolean get() = popup.isShowing

    /** The item of the selected row; `null` if none is selected. */
    val selected: T? get() = (list.selectionModel.selectedItem as? PickerRow.Entry)?.item

    /**
     * Shows the popup centred horizontally near the top of [owner], the filter field focused and [preselected] (else
     * the first entry) selected. A pick hides the popup and calls [onPick].
     */
    fun show(
        owner: Window,
        preselected: T?,
        onPick: (T) -> Unit,
    ) {
        this.onPick = onPick
        field.clear()
        refilter()
        select(entryIndex(list.items, preselected))
        popup.show(owner, owner.x + maxOf(0.0, (owner.width - popupWidth) / 2), owner.y + TOP_GAP)
        field.requestFocus()
    }

    fun hide() = popup.hide()

    private fun refilter() {
        val rows = pickerRows(filter(items, field.text), headerOf)
        list.items.setAll(rows)
        list.prefHeight = maxOf(1, minOf(rows.size, VISIBLE_ROWS)) * CELL_HEIGHT + LIST_INSETS
        select(firstEntry(rows))
        popup.sizeToScene()
    }

    private fun select(index: Int) {
        if (index < 0) return
        list.selectionModel.select(index)
        list.scrollTo(scrollTarget(list.items, index))
    }

    private fun move(step: Int) {
        val from = list.selectionModel.selectedIndex
        val next = nextEntry(list.items, from, step)
        if (next != from) select(next)
    }

    private fun pickSelected() = selected?.let(::pick)

    private fun pick(item: T) {
        hide()
        onPick(item)
    }

    /** A header's title, or an entry's graphic; headers swallow the mouse, so a click neither selects nor picks. */
    private inner class RowCell : ListCell<PickerRow<T>>() {
        private val entry = entryGraphic()
        private val header = Label().apply { styleClass += "scratch-picker-header" }

        init {
            addEventFilter(MouseEvent.ANY) { event -> if (item is PickerRow.Header) event.consume() }
            setOnMouseClicked { event ->
                val row = item
                if (event.button == MouseButton.PRIMARY && event.clickCount == 1 && row is PickerRow.Entry) {
                    pick(row.item)
                }
            }
        }

        override fun updateItem(
            item: PickerRow<T>?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            pseudoClassStateChanged(HEADER, item is PickerRow.Header)
            graphic =
                when (item) {
                    null -> null
                    is PickerRow.Header -> header.also { it.text = item.title }
                    is PickerRow.Entry -> entry.node.also { entry.show(item.item) }
                }
        }
    }

    private companion object {
        const val DEFAULT_WIDTH = 320.0
        const val VISIBLE_ROWS = 12
        const val CELL_HEIGHT = 24.0
        const val LIST_INSETS = 2.0
        const val TOP_GAP = 40.0
    }
}
