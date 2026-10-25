package dev.sluide.commandruns

import javafx.beans.binding.Bindings
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

/**
 * The collapsible history of a run pane (ADR 0091): the arguments of the file's past runs, newest first, at most
 * [VISIBLE_ROWS] rows before it scrolls. A click on a row, or Enter on the selected one, puts it into [arguments]; ✕
 * removes it. Down in [arguments] goes into the list while it shows, Escape in the list back. Hidden until a history
 * is [attach]ed and while it is empty. FX thread.
 */
internal class ArgumentHistoryPane(
    private val arguments: TextField,
) {
    private var history: ArgumentHistory? = null

    /** Shows "▾ History" while expanded, "▸ History" while collapsed. */
    val header =
        ToggleButton().apply {
            styleClass += "run-history-header"
            textProperty().bind(Bindings.`when`(selectedProperty()).then("▾ History").otherwise("▸ History"))
        }

    val list =
        ListView<String>().apply {
            styleClass += "run-history-list"
            fixedCellSize = CELL_HEIGHT
            minHeight = 0.0
            maxHeight = Region.USE_PREF_SIZE
            visibleProperty().bind(header.selectedProperty())
            managedProperty().bind(visibleProperty())
            setCellFactory { Cell() }
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (!event.isPlain) return@addEventFilter
                when (event.code) {
                    KeyCode.ENTER -> fill(selectionModel.selectedItem ?: return@addEventFilter)
                    KeyCode.ESCAPE -> arguments.requestFocus()
                    else -> return@addEventFilter
                }
                event.consume()
            }
        }

    val node =
        VBox(header, list).apply {
            styleClass += "run-history"
            isVisible = false
            managedProperty().bind(visibleProperty())
        }

    init {
        arguments.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            // Shift+Down and the like keep their meaning in the field.
            if (event.code != KeyCode.DOWN || !event.isPlain || !node.isVisible ||
                !list.isVisible
            ) {
                return@addEventFilter
            }
            list.requestFocus()
            list.selectionModel.select(0)
            list.focusModel.focus(0)
            list.scrollTo(0)
            event.consume()
        }
    }

    /** Shows [history] and follows its entries and the global expanded state until [detach]. */
    fun attach(history: ArgumentHistory) {
        detach()
        this.history = history
        history.reload()
        list.items = history.entries
        list.prefHeightProperty().bind(
            Bindings.createDoubleBinding(
                { listHeight(history.entries.size, CELL_HEIGHT, list.insets.top + list.insets.bottom) },
                history.entries,
                list.insetsProperty(),
            ),
        )
        header.selectedProperty().bindBidirectional(history.expanded)
        node.visibleProperty().bind(Bindings.isNotEmpty(history.entries))
    }

    /** Lets go of the history (and the global expanded state, so a closed view is not kept) and hides the section. */
    fun detach() {
        val history = history ?: return
        this.history = null
        header.selectedProperty().unbindBidirectional(history.expanded)
        node.visibleProperty().unbind()
        node.isVisible = false
        list.prefHeightProperty().unbind()
        list.items = FXCollections.observableArrayList()
    }

    private fun fill(entry: String) {
        arguments.requestFocus()
        arguments.text = entry
        arguments.end()
    }

    /** A row: the arguments, cut with an ellipsis, and ✕ while hovered or selected. */
    private inner class Cell : ListCell<String>() {
        private val text = Label().apply { HBox.setHgrow(this, Priority.ALWAYS) }

        private val remove =
            Button("✕").apply {
                styleClass += "run-history-remove"
                tooltip = Tooltip("Remove from the history")
                isFocusTraversable = false
                visibleProperty().bind(this@Cell.hoverProperty().or(this@Cell.selectedProperty()))
                // The button keeps its clicks from the row, which would fill the arguments.
                setOnAction { item?.let { entry -> history?.remove(entry) } }
            }

        private val row =
            HBox(text, remove).apply {
                alignment = Pos.CENTER_LEFT
                spacing = 4.0
            }

        init {
            text.maxWidth = Double.MAX_VALUE
            text.minWidth = 0.0
            // As wide as the list, never wider: long arguments are cut, not scrolled sideways, and ✕ stays in view.
            prefWidth = 0.0
            setOnMouseClicked { event ->
                if (event.button == MouseButton.PRIMARY && !isEmpty) item?.let(::fill)
            }
        }

        override fun updateItem(
            item: String?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text.text = item
            graphic = if (empty || item == null) null else row
        }
    }

    companion object {
        /** The rows shown before the list scrolls. */
        const val VISIBLE_ROWS = 5

        const val CELL_HEIGHT = 24.0
    }
}

/**
 * The height of a history list of [rows] rows of [cellSize] with [insets] (top and bottom together): up to
 * [ArgumentHistoryPane.VISIBLE_ROWS] rows show without empty space below, more scroll.
 */
internal fun listHeight(
    rows: Int,
    cellSize: Double,
    insets: Double,
): Double = minOf(rows, ArgumentHistoryPane.VISIBLE_ROWS) * cellSize + insets

/** Without Shift, Ctrl, Alt or Meta. */
private val KeyEvent.isPlain: Boolean get() = !(isShiftDown || isControlDown || isAltDown || isMetaDown)
