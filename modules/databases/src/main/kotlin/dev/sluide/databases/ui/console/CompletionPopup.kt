package dev.sluide.databases.ui.console

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.sql.Completion
import dev.sluide.databases.sql.CompletionCatalog
import dev.sluide.databases.sql.CompletionKind
import dev.sluide.databases.sql.SqlCompletion
import javafx.scene.Node
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

/** Whether [event] hit a cell with an item, not the empty space of a list (copy of the guard in [StatementChooser]). */
internal fun onFilledCell(event: MouseEvent): Boolean =
    generateSequence(event.target as? Node) { it.parent }.any { it is ListCell<*> && !it.isEmpty }

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

    /** The text and caret the open popup was computed for, so an unchanged state is not computed twice. */
    private var version = 0
    private var computedVersion = -1
    private var computedCaret = -1

    /** The caret of an opening that found nothing; a finished catalog load there tries again. */
    private var pending: Int? = null
    private var focusWatch: Subscription? = null

    val list: ListView<Completion> =
        ListView<Completion>().apply {
            styleClass += "completion-list"
            isFocusTraversable = false
            prefWidth = 320.0
            setCellFactory { CompletionCell() }
            stylesheets += checkNotNull(CompletionPopup::class.java.getResource("console.css")).toExternalForm()
            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                if (event.button == MouseButton.PRIMARY && onFilledCell(event) && selectionModel.selectedIndex >= 0) {
                    model.select(selectionModel.selectedIndex)
                    insert()
                }
            }
        }

    val popup: Popup =
        Popup().apply {
            isAutoHide = true
            content += list
            setOnHidden { cleanup() }
        }

    private val subscriptions: List<Subscription> =
        listOf(
            editor.textProperty.subscribe { old, new -> onText(old, new) },
            editor.caretOffsetProperty.subscribe { _ -> onCaret() },
            onLoaded(::loaded),
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
        // A console key such as Shortcut+Enter (Run) closes the popup and goes on to the console.
        // Shift alone is typing (capitals), not a console key. AltGr (Ctrl+Alt on Windows) and macOS Option letters
        // also close it; the characters they type mostly end the word anyway.
        val consoleKey = event.isShortcutDown || event.isControlDown || event.isAltDown
        if (popup.isShowing && consoleKey && !event.code.isModifierKey) {
            hide()
            pending = null
        }
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
                pending = null
                return true
            }

            else -> {
                return false
            }
        }
        syncSelection()
        return true
    }

    private fun onText(
        old: String,
        new: String,
    ) {
        if (inserting) return
        before = old
        current = new
        version++
        // One character more also counts as typed when it is an undo of a one-character deletion; harmless.
        typed = new.length == old.length + 1
        pending = null
        changed()
    }

    private fun onCaret() {
        if (inserting) return
        if (pending != null && pending != editor.caretOffset) pending = null
        changed()
        // Whatever moves the caret later (Escape, arrows, clicks) must not count as the typing before it.
        typed = false
    }

    /** Looks at the editor after a text or caret change: refreshes an open popup, or opens it after a typed char. */
    private fun changed() {
        val caret = editor.caretOffset
        if (popup.isShowing) {
            if (version != computedVersion || caret != computedCaret) recompute(opening = false)
            if (popup.isShowing) {
                // Typing into an open popup is not a new typing for later caret moves (Escape, then Left and Right).
                typed = false
                return
            }
        }
        // Closed, or just closed by this change (a typed `.` ends the word): a typed character may open it.
        if (typed && CompletionModel.isTypedChar(before, current, caret, editor.selection.length)) {
            typed = false
            if (SqlCompletion.shouldAutoOpen(current, caret, kind)) recompute(opening = true)
        }
    }

    private fun loaded() {
        val waiting = pending
        if (model.isOpen) {
            recompute(opening = false)
        } else if (waiting != null && waiting == editor.caretOffset && editorHasFocus()) {
            recompute(opening = true)
        }
    }

    private fun editorHasFocus(): Boolean = insideEditor(pane.node.scene?.focusOwner, editor.node)

    private fun recompute(opening: Boolean) {
        val caret = editor.caretOffset
        computedVersion = version
        computedCaret = caret
        val result = SqlCompletion.candidates(editor.text, caret, kind, catalog)
        if (!model.update(result, caret, opening)) {
            hide()
            // Nothing yet, maybe because the catalog is still loading: open when it has loaded and the caret is here.
            pending = if (opening) caret else null
            return
        }
        pending = null
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
        cleanup()
        popup.hide()
    }

    /** What closing means, whether through [hide] or the popup's own auto-hide. */
    private fun cleanup() {
        model.close()
        focusWatch?.unsubscribe()
        focusWatch = null
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
