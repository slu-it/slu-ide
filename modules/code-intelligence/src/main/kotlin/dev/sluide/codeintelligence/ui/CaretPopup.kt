package dev.sluide.codeintelligence.ui

import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane

/** How a [CaretPopup] shows one item: its [label], a muted [secondary] text at the right, and an [icon] before it. */
internal data class PopupCell(
    val label: String,
    val secondary: String? = null,
    /** A new node for each call: a node shows in one cell only. */
    val icon: Node? = null,
)

/**
 * A list popup at a place in an editor's text (ADR 0145), for completion, choosers and usages: below the anchor, above
 * it when it does not fit the screen. The focus stays in the editor: while it shows, Up/Down (wrapping), PageUp/PageDown,
 * Home/End choose the item, Enter (and Tab if [acceptTab]) takes it, Escape closes, all through the editor's key
 * bindings, which come before the editor's own keys. A click selects, a double-click takes. [items][setItems] may change
 * while it shows (the selected item stays selected if it is still there); without items it shows [placeholder]
 * ("Searching…", "No results"). It hides on Escape, a click outside, when the editor loses the focus or scrolls, and
 * when its window moves or is minimized ([EditorPopupWindow]). FX thread.
 *
 * @param cell how an item shows.
 * @param onChoose gets the item taken, after the popup hid.
 * @param detail what shows beside the list for the selected item (completion docs), `null` for nothing.
 * @param visibleRows the list's height in rows, also a page for PageUp/PageDown.
 */
internal class CaretPopup<T>(
    private val editor: ExtensibleCodeEditor,
    private val cell: (T) -> PopupCell,
    private val onChoose: (T) -> Unit,
    private val acceptTab: Boolean = true,
    private val detail: ((T) -> Node?)? = null,
    private val visibleRows: Int = 10,
) : AutoCloseable {
    /** The items and the selection. */
    val model = PopupListModel<T>()

    /** Called after the popup hid, by any cause (also after a choice). */
    var onHidden: () -> Unit = {}

    /** Shown while there are no items. */
    var placeholder: String = "No results"
        set(value) {
            field = value
            placeholderLabel?.text = value
        }

    private val window = EditorPopupWindow(editor) { hidden() }
    private var keyBindings: List<AutoCloseable> = emptyList()
    private var syncing = false

    private var placeholderLabel: Label? = null
    private var listView: ListView<T>? = null
    private var detailPane: StackPane? = null
    private var root: HBox? = null

    /** Whether the popup shows. */
    val isShowing: Boolean get() = window.isShowing

    /** The selected item, `null` without items. */
    val selected: T? get() = model.selected

    /**
     * Shows the popup for the text [start, end) (the caret at [start] when equal), or moves it there; `false` if that
     * is not on screen. The key bindings are taken while it shows.
     */
    fun show(
        start: Int,
        end: Int = start,
    ): Boolean {
        val content = root ?: build()
        sync()
        val wasShowing = isShowing
        if (!window.show(content, start, end)) return false
        if (!wasShowing) keyBindings = bindKeys()
        return true
    }

    /** Replaces the items, keeping the selected one if it is still there, else selecting the first. */
    fun setItems(items: List<T>) {
        model.setItems(items)
        sync()
        window.relayout()
    }

    /** Hides the popup. */
    fun hide() = window.hide()

    /** Hides the popup and lets go of the editor. */
    override fun close() = window.close()

    private fun hidden() {
        keyBindings.forEach { it.close() }
        keyBindings = emptyList()
        onHidden()
    }

    private fun bindKeys(): List<AutoCloseable> {
        fun bind(
            code: KeyCode,
            action: () -> Boolean,
        ) = editor.addKeyBinding(KeyCodeCombination(code)) { isShowing && action() }

        fun moving(change: () -> Unit): () -> Boolean =
            {
                if (model.items.isEmpty()) {
                    false
                } else {
                    change()
                    sync()
                    true
                }
            }
        return listOfNotNull(
            bind(KeyCode.UP, moving { model.move(-1) }),
            bind(KeyCode.DOWN, moving { model.move(1) }),
            bind(KeyCode.PAGE_UP, moving { model.page(-1, visibleRows) }),
            bind(KeyCode.PAGE_DOWN, moving { model.page(1, visibleRows) }),
            bind(KeyCode.HOME, moving { model.first() }),
            bind(KeyCode.END, moving { model.last() }),
            bind(KeyCode.ENTER, ::choose),
            if (acceptTab) bind(KeyCode.TAB, ::choose) else null,
            bind(KeyCode.ESCAPE) {
                hide()
                true
            },
        )
    }

    /** Takes the selected item: hides, then tells [onChoose]; `false` without one. */
    private fun choose(): Boolean {
        val item = model.selected ?: return false
        hide()
        onChoose(item)
        return true
    }

    private fun build(): HBox {
        val placeholderNode = Label(placeholder).apply { styleClass += "caret-popup-placeholder" }
        placeholderLabel = placeholderNode
        val list =
            ListView<T>().apply {
                styleClass += "caret-popup-list"
                isFocusTraversable = false
                placeholder = placeholderNode
                setCellFactory { ItemCell() }
                fixedCellSize = ROW_HEIGHT
                prefWidth = LIST_WIDTH
                selectionModel.selectedIndexProperty().subscribe { _, index ->
                    if (!syncing && index.toInt() >= 0) {
                        model.select(index.toInt())
                        showDetail()
                    }
                }
                addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                    if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && onFilledCell(event)) choose()
                }
            }
        listView = list
        val detailNode =
            StackPane().apply {
                styleClass += "caret-popup-detail"
                isVisible = false
                isManaged = false
            }
        detailPane = detailNode
        return HBox(list, detailNode)
            .apply {
                styleClass += "caret-popup"
                alignment = Pos.TOP_LEFT
            }.also { root = it }
    }

    /** Shows the model in the list: items, selection scrolled into view, the detail. */
    private fun sync() {
        val list = listView ?: return
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
        showDetail()
    }

    private fun showDetail() {
        val pane = detailPane ?: return
        val node = model.selected?.let { item -> detail?.invoke(item) }
        if (node == null) {
            pane.children.clear()
        } else if (pane.children.singleOrNull() !== node) {
            pane.children.setAll(node)
        }
        pane.isVisible = node != null
        pane.isManaged = node != null
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
            graphic =
                if (empty || item == null) {
                    null
                } else {
                    val shown = cell(item)
                    val secondary =
                        shown.secondary?.let { Label(it).apply { styleClass += "caret-popup-secondary" } }
                    HBox(
                        StackPane().apply {
                            styleClass += "caret-popup-icon"
                            shown.icon?.let { children += it }
                        },
                        Label(shown.label).apply { styleClass += "caret-popup-label" },
                        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                        *listOfNotNull(secondary).toTypedArray(),
                    ).apply {
                        alignment = Pos.CENTER_LEFT
                        styleClass += "caret-popup-row"
                    }
                }
        }
    }

    private companion object {
        const val ROW_HEIGHT = 24.0
        const val LIST_WIDTH = 360.0
    }
}

/** Whether [event] hit a cell with an item, not the empty space of a list. */
private fun onFilledCell(event: MouseEvent): Boolean =
    generateSequence(event.target as? Node) { it.parent }.any { it is ListCell<*> && !it.isEmpty }
