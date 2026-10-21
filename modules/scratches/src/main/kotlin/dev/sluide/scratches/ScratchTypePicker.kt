package dev.sluide.scratches

import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.fileicons.fileIconFor
import dev.sluide.fileicons.show
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Popup
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon

private val SCRATCHES_CSS: String =
    checkNotNull(ScratchTypePicker::class.java.getResource("/dev/sluide/scratches/scratches.css")).toExternalForm()

/**
 * The popup of File > New Scratch File, as in IntelliJ: a filter field above the list of [types]. Typing filters by name
 * or extension (ignoring case); Enter picks the selected type, Up and Down move the selection while the focus stays in
 * the field, a click picks a row, Escape or a click elsewhere closes.
 */
class ScratchTypePicker(
    private val types: List<ScratchType> = SCRATCH_TYPES,
) {
    private var onPick: (ScratchType) -> Unit = {}

    internal val field: TextField =
        TextField().apply {
            styleClass += "scratch-picker-field"
            promptText = "Type"
            textProperty().subscribe { _, _ -> filter() }
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

    internal val list: ListView<ScratchType> =
        ListView<ScratchType>().apply {
            styleClass += "scratch-picker-list"
            fixedCellSize = CELL_HEIGHT
            placeholder = Label("No matching type")
            setCellFactory { TypeCell() }
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                if (event.code == KeyCode.ENTER) {
                    pickSelected()
                    event.consume()
                }
            }
        }

    private val content: VBox =
        VBox(Label("New Scratch File").apply { styleClass += "scratch-picker-title" }, field, list).apply {
            styleClass += "scratch-picker"
            stylesheets.addAll(FILE_ICONS_CSS, SCRATCHES_CSS)
            prefWidth = WIDTH
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
            content += this@ScratchTypePicker.content
        }

    val isShowing: Boolean get() = popup.isShowing

    /**
     * Shows the popup centred horizontally near the top of [owner], the filter field focused and [preselected] (else
     * the first type) selected. A pick hides the popup and calls [onPick].
     */
    fun show(
        owner: Window,
        preselected: ScratchType?,
        onPick: (ScratchType) -> Unit,
    ) {
        this.onPick = onPick
        field.clear()
        filter()
        val index = types.indexOf(preselected).coerceAtLeast(0)
        list.selectionModel.select(index)
        list.scrollTo(index)
        popup.show(owner, owner.x + maxOf(0.0, (owner.width - WIDTH) / 2), owner.y + TOP_GAP)
        field.requestFocus()
    }

    fun hide() = popup.hide()

    private fun filter() {
        val wanted =
            field.text
                .orEmpty()
                .trim()
                .removePrefix(".")
        val shown =
            types.filter { wanted.isEmpty() || it.name.contains(wanted, true) || it.extension.contains(wanted, true) }
        list.items.setAll(shown)
        list.prefHeight = maxOf(1, minOf(shown.size, VISIBLE_ROWS)) * CELL_HEIGHT + LIST_INSETS
        if (shown.isNotEmpty()) list.selectionModel.selectFirst()
        popup.sizeToScene()
    }

    private fun move(delta: Int) {
        val size = list.items.size
        if (size == 0) return
        val next = (list.selectionModel.selectedIndex + delta).coerceIn(0, size - 1)
        list.selectionModel.select(next)
        list.scrollTo(next)
    }

    private fun pickSelected() = list.selectionModel.selectedItem?.let(::pick)

    private fun pick(type: ScratchType) {
        hide()
        onPick(type)
    }

    /** The type's icon, its name and the dimmed extension. */
    private inner class TypeCell : ListCell<ScratchType>() {
        private val icon = FontIcon().apply { iconSize = ICON_SIZE }
        private val name = Label()
        private val extension = Label().apply { styleClass += "scratch-picker-extension" }
        private val row = HBox(icon, name, extension).apply { styleClass += "scratch-picker-row" }

        init {
            setOnMouseClicked { event ->
                if (event.button == MouseButton.PRIMARY && event.clickCount == 1) item?.let(::pick)
            }
        }

        override fun updateItem(
            item: ScratchType?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            if (empty || item == null) {
                graphic = null
                return
            }
            icon.show(fileIconFor("scratch.${item.extension}", directory = false, expanded = false) { true })
            name.text = item.name
            extension.text = ".${item.extension}"
            graphic = row
        }
    }

    private companion object {
        const val WIDTH = 320.0
        const val VISIBLE_ROWS = 12
        const val CELL_HEIGHT = 24.0
        const val ICON_SIZE = 16
        const val LIST_INSETS = 2.0
        const val TOP_GAP = 40.0
    }
}
