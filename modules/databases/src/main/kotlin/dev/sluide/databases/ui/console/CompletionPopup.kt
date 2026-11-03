package dev.sluide.databases.ui.console

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.sql.Completion
import dev.sluide.databases.sql.CompletionCatalog
import dev.sluide.databases.sql.CompletionKind
import dev.sluide.databases.sql.SqlCompletion
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Popup
import javafx.util.Subscription
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignK
import org.kordamp.ikonli.materialdesign2.MaterialDesignT

/**
 * The completion popup of a console (ADR 0121): opens with Ctrl+Space and by itself after a typed `.` or word, below
 * the caret. Up, Down, PageUp, PageDown choose, Enter or Tab insert, Escape closes; it follows every text and caret
 * change and closes when nothing fits, the caret leaves the word or the editor loses focus. [onLoaded] subscribes to
 * the catalog's loads, which refresh an open popup. Registers itself as the pane's key hook.
 */
internal class CompletionPopup(
    private val pane: ConsolePane,
    private val kind: DatabaseKind,
    private val catalog: CompletionCatalog,
    onLoaded: (() -> Unit) -> Subscription,
) {
    private val editor get() = pane.editor
    private val model = CompletionModel()

    /** The text before the last change, and whether that change was one inserted character not yet looked at. */
    private var before = editor.text
    private var current = editor.text
    private var typed = false
    private var inserting = false
    private var focusWatch: Subscription? = null

    val list: ListView<Completion> =
        ListView<Completion>().apply {
            styleClass += "completion-list"
            isFocusTraversable = false
            prefWidth = 320.0
            setCellFactory { CompletionCell() }
            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                if (event.button == MouseButton.PRIMARY && selectionModel.selectedIndex >= 0) {
                    model.select(selectionModel.selectedIndex)
                    insert()
                }
            }
        }

    val popup: Popup =
        Popup().apply {
            isAutoHide = true
            content += list
            setOnHidden { model.close() }
        }

    private val subscriptions: List<Subscription> =
        listOf(
            editor.textProperty.subscribe { new -> onText(new) },
            editor.caretOffsetProperty.subscribe { _ -> onCaret() },
            onLoaded { if (model.isOpen) recompute(opening = false) },
        )

    init {
        pane.keyHook = ::onKey
    }

    /** Whether the popup is showing, for tests. */
    val isShowing: Boolean get() = popup.isShowing

    /** The labels in the list, for tests. */
    val labels: List<String> get() = model.items.map { it.label }

    fun dispose() {
        subscriptions.forEach { it.unsubscribe() }
        hide()
    }

    private fun onKey(event: KeyEvent): Boolean {
        if (OPEN.match(event)) {
            recompute(opening = true)
            return true
        }
        val plain = !event.isShortcutDown && !event.isAltDown && !event.isControlDown && !event.isShiftDown
        if (!popup.isShowing || !plain) return false
        when (event.code) {
            KeyCode.UP -> {
                model.move(-1)
            }

            KeyCode.DOWN -> {
                model.move(1)
            }

            KeyCode.PAGE_UP -> {
                model.page(-ROWS)
            }

            KeyCode.PAGE_DOWN -> {
                model.page(ROWS)
            }

            KeyCode.ENTER, KeyCode.TAB -> {
                insert()
                return true
            }

            KeyCode.ESCAPE -> {
                hide()
                return true
            }

            else -> {
                return false
            }
        }
        syncSelection()
        return true
    }

    private fun onText(new: String) {
        before = current
        current = new
        typed = new.length == before.length + 1
        onCaret()
    }

    private fun onCaret() {
        if (inserting) return
        val caret = editor.caretOffset
        if (popup.isShowing) {
            recompute(opening = false)
        } else if (typed && CompletionModel.isTypedChar(before, current, caret)) {
            typed = false
            val auto = editor.selection.length == 0 && SqlCompletion.shouldAutoOpen(current, caret, kind)
            if (auto) recompute(opening = true)
        }
    }

    private fun recompute(opening: Boolean) {
        val text = editor.text
        val caret = editor.caretOffset
        val result = SqlCompletion.candidates(text, caret, kind, catalog)
        if (!model.update(result, caret, opening)) {
            hide()
            return
        }
        list.items.setAll(model.items)
        list.prefHeight = minOf(model.items.size, ROWS) * ROW + 2.0
        syncSelection()
        if (!popup.isShowing) show()
    }

    private fun show() {
        val bounds = editor.caretScreenBounds()
        val scene = pane.node.scene
        val window = scene?.window
        if (bounds == null || window == null) {
            model.close()
            return
        }
        popup.show(window, bounds.minX, bounds.maxY)
        focusWatch?.unsubscribe()
        focusWatch =
            scene.focusOwnerProperty().subscribe { _, owner ->
                if (owner == null || !insideEditor(owner, editor.node)) hide()
            }
    }

    private fun hide() {
        model.close()
        focusWatch?.unsubscribe()
        focusWatch = null
        popup.hide()
    }

    private fun syncSelection() {
        list.selectionModel.select(model.selected)
        list.scrollTo(maxOf(0, model.selected - ROWS / 2))
    }

    private fun insert() {
        val item = model.chosen ?: return
        val from = model.replaceFrom
        val to = model.replaceTo
        inserting = true
        try {
            hide()
            editor.replace(from, to, item.text)
        } finally {
            inserting = false
            before = current
            current = editor.text
            typed = false
        }
    }

    private class CompletionCell : ListCell<Completion>() {
        override fun updateItem(
            item: Completion?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = if (empty) null else item?.label
            graphic =
                if (empty || item == null) null else FontIcon(icon(item.kind)).apply { styleClass += "completion-icon" }
        }
    }

    private companion object {
        const val ROWS = 12
        const val ROW = 24
        val OPEN = KeyCodeCombination(KeyCode.SPACE, KeyCombination.CONTROL_DOWN)

        fun icon(kind: CompletionKind): Ikon =
            when (kind) {
                CompletionKind.KEYWORD -> MaterialDesignK.KEY_VARIANT
                CompletionKind.SCHEMA -> MaterialDesignF.FOLDER_OUTLINE
                CompletionKind.TABLE -> MaterialDesignT.TABLE
                CompletionKind.COLUMN -> MaterialDesignT.TABLE_COLUMN
            }
    }
}
