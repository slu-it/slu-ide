package dev.sluide.codeintelligence.ui

import javafx.geometry.Bounds
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
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Subscription

/**
 * Where a search popup shows: in [owner], centred at the top of [area] (screen bounds of an editor's text or a
 * window's content); [returnFocus] gets the focus back when it hides.
 */
internal data class SearchAnchor(
    val owner: Window,
    val area: Bounds,
    val returnFocus: Node?,
)

/** A search popup as the flows (file structure, go to symbol) see it: a query field over a list. FX thread. */
internal interface SearchView<T> : AutoCloseable {
    val isShowing: Boolean

    /** The text typed into the queryField. */
    val query: String

    /** Shown while the list has no items ("Searching…", "No symbols"). */
    var placeholder: String

    /** Told the field's text after each change. */
    var onQuery: (String) -> Unit

    /** Gets the item taken (Enter, double-click), after the popup hid. */
    var onChoose: (T) -> Unit

    /** Told after the popup hid, by any cause. */
    var onHidden: () -> Unit

    /** Shows the popup with an empty field that has the focus, or keeps it shown; `false` if there is no place. */
    fun open(): Boolean

    /** Replaces the items and selects [selected] if it is among them, else keeps the selection or takes the first. */
    fun setItems(
        items: List<T>,
        selected: T? = null,
    )

    /** Shows only [text], without a field, until the next key, a click or the focus leaving; `false` without place. */
    fun note(text: String): Boolean

    fun hide()
}

/**
 * The search popup (ADR 0149): a `TextField` on top that takes the focus, unlike a [CaretPopup], and a list below, in a
 * plain [Popup] at the top centre of its [anchor] ([searchPopupPosition]). The field keeps the focus: Up/Down
 * (wrapping), PageUp/PageDown choose, Enter takes the selected item, Escape closes, all taken by the field before its
 * own keys; typing tells [onQuery]. A click selects, a double-click takes. It hides on Escape, a click outside, when
 * the field loses the focus, and when the owner window moves, is minimized or loses the focus; then [SearchAnchor.returnFocus]
 * gets the focus back. FX thread.
 *
 * @param anchor where to show it, asked on each [open]; `null` for nowhere (the editor is not in a shown window).
 * @param cell how an item shows; [PopupCell.indent] indents it.
 */
internal class SearchPopup<T>(
    private val anchor: () -> SearchAnchor?,
    private val cell: (T) -> PopupCell,
    private val visibleRows: Int = 14,
) : SearchView<T> {
    override var onQuery: (String) -> Unit = {}
    override var onChoose: (T) -> Unit = {}

    override var onHidden: () -> Unit = {}

    /** The items and the selection. */
    val model = PopupListModel<T>()

    override var placeholder: String = "No results"
        set(value) {
            field = value
            placeholderLabel.text = value
        }

    private val placeholderLabel = Label(placeholder).apply { styleClass += "caret-popup-placeholder" }

    /** The query field; internal for tests. */
    internal val queryField =
        TextField().apply {
            styleClass += "search-popup-field"
            addEventFilter(KeyEvent.KEY_PRESSED, ::fieldKey)
        }

    private val list =
        ListView<T>().apply {
            styleClass += "caret-popup-list"
            // A click on the list must not take the focus from the queryField.
            isFocusTraversable = false
            placeholder = placeholderLabel
            fixedCellSize = ROW_HEIGHT
            setCellFactory { ItemCell() }
            selectionModel.selectedIndexProperty().subscribe { _, index ->
                if (!syncing && index.toInt() >= 0) model.select(index.toInt())
            }
            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && onFilledCell(event)) choose()
            }
        }

    private val root =
        VBox(queryField, list).apply {
            styleClass += "search-popup"
            prefWidth = WIDTH
            stylesheets += POPUP_CSS
            // Keys the field leaves would go on to the editor or the window beneath.
            addEventHandler(KeyEvent.ANY) { event -> if (!noteMode) event.consume() }
        }

    private val popup =
        Popup().apply {
            content.setAll(root)
            isAutoHide = true
            // A click into the editor hides the popup and still moves the caret there.
            consumeAutoHidingEvents = false
            // Escape is the field's (or the note's) own key, so it closes the popup and goes no further.
            isHideOnEscape = false
            isAutoFix = false
            scene.addEventFilter(KeyEvent.KEY_PRESSED, ::noteKey)
            setOnHidden { hidden() }
        }

    private var noteMode = false
    private var syncing = false
    private var settingText = false
    private var watches: List<Subscription> = emptyList()
    private var returnFocus: Node? = null

    init {
        queryField.textProperty().subscribe { _, text -> if (!settingText) onQuery(text) }
    }

    override val isShowing: Boolean get() = popup.isShowing

    override val query: String get() = queryField.text

    /** The selected item, `null` without items. */
    val selected: T? get() = model.selected

    override fun open(): Boolean {
        val wasNote = noteMode
        noteMode = false
        queryField.isVisible = true
        queryField.isManaged = true
        if (!isShowing || wasNote) {
            settingText = true
            queryField.text = ""
            settingText = false
        }
        if (!showAt()) return false
        queryField.requestFocus()
        return true
    }

    override fun note(text: String): Boolean {
        noteMode = true
        queryField.isVisible = false
        queryField.isManaged = false
        placeholder = text
        model.setItems(emptyList())
        sync()
        return showAt()
    }

    override fun setItems(
        items: List<T>,
        selected: T?,
    ) {
        model.setItems(items)
        if (selected != null) items.indexOf(selected).takeIf { it >= 0 }?.let(model::select)
        sync()
        if (isShowing) popup.sizeToScene()
    }

    override fun hide() = popup.hide()

    override fun close() = hide()

    private fun showAt(): Boolean {
        val at = anchor() ?: return false
        if (!at.owner.isShowing) return false
        sync()
        if (!isShowing) {
            returnFocus = at.returnFocus
            popup.show(at.owner, at.area.minX, at.area.minY)
            watches = watch(at.owner)
        }
        popup.sizeToScene()
        val screen =
            Screen.getScreensForRectangle(at.area.minX, at.area.minY, at.area.width, at.area.height).firstOrNull()
                ?: Screen.getPrimary()
        val position = searchPopupPosition(at.area, popup.width, screen.visualBounds)
        popup.x = position.x
        popup.y = position.y
        return true
    }

    private fun watch(owner: Window): List<Subscription> {
        // Changes only: a window that is not focused when the popup opens (no window manager) keeps it.
        val watches =
            mutableListOf(
                owner.xProperty().subscribe { _, _ -> hide() },
                owner.yProperty().subscribe { _, _ -> hide() },
                owner.focusedProperty().subscribe { _, focused -> if (!focused) hide() },
                queryField.focusedProperty().subscribe { _, focused -> if (!focused && !noteMode) hide() },
            )
        (owner as? Stage)?.let { stage ->
            watches += stage.iconifiedProperty().subscribe { _, iconified -> if (iconified) hide() }
        }
        return watches
    }

    private fun hidden() {
        watches.forEach { it.unsubscribe() }
        watches = emptyList()
        val focus = returnFocus
        returnFocus = null
        focus?.requestFocus()
        onHidden()
    }

    /** The field's keys that move the selection, take it or close the popup; consumed. */
    private fun fieldKey(event: KeyEvent) {
        val handled =
            when (event.code) {
                KeyCode.UP -> moving { model.move(-1) }
                KeyCode.DOWN -> moving { model.move(1) }
                KeyCode.PAGE_UP -> moving { model.page(-1, visibleRows) }
                KeyCode.PAGE_DOWN -> moving { model.page(1, visibleRows) }
                KeyCode.ENTER -> choose().let { true }
                KeyCode.ESCAPE -> hide().let { true }
                else -> false
            }
        if (handled) event.consume()
    }

    /** A note hides on the next key; Escape goes no further, any other key still acts where the focus is. */
    private fun noteKey(event: KeyEvent) {
        if (!noteMode || event.code.isModifierKey) return
        if (event.code == KeyCode.ESCAPE) event.consume()
        hide()
    }

    private fun moving(change: () -> Unit): Boolean {
        if (model.items.isNotEmpty()) {
            change()
            sync()
        }
        return true
    }

    private fun choose() {
        val item = model.selected ?: return
        hide()
        onChoose(item)
    }

    private fun sync() {
        syncing = true
        try {
            if (list.items != model.items) list.items.setAll(model.items)
            list.prefHeight = model.items.size.coerceIn(1, visibleRows) * ROW_HEIGHT + 2
            val index = model.selectedIndex
            if (index < 0) {
                list.selectionModel.clearSelection()
            } else {
                list.selectionModel.select(index)
                list.scrollTo((index - visibleRows / 2).coerceAtLeast(0))
            }
        } finally {
            syncing = false
        }
    }

    private inner class ItemCell : ListCell<T>() {
        init {
            styleClass += "caret-popup-cell"
        }

        override fun updateItem(
            item: T?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = null
            graphic = if (empty || item == null) null else popupRow(cell(item))
        }
    }

    private companion object {
        const val ROW_HEIGHT = 24.0
        const val WIDTH = 520.0
    }
}
