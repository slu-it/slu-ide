package dev.sluide.findinfiles.ui

import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.findinfiles.search.FileHits
import dev.sluide.findinfiles.search.LineHit
import dev.sluide.textediting.SearchOptions
import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.Node
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
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Popup
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Subscription
import java.nio.file.Path

/**
 * The Find in Files popup (ADR 0177): a query field with the Aa / W / .* toggles and a file mask on top, a status line
 * (with the scope chip), the grouped result list and a preview of the selected line, in a plain focus-taking [Popup]
 * that hides as `SearchPopup` of code-intelligence does (Escape, a click outside, the owner moving, minimizing or
 * losing the focus). [editorFont] is the editor's font as CSS (`FontSpec.toCss()`), for the snippets and the preview.
 * Up/Down/PageUp/PageDown move between line rows only and stop at the ends (no wrap); Enter and a double-click tell
 * [onOpen]; Tab switches between the two fields. FX thread.
 */
internal class FindInFilesPopup(
    highlighter: (Path, String) -> SyntaxHighlighter?,
    icon: (Path) -> Node?,
    previewTexts: PreviewTexts,
    editorFont: ObservableValue<String>,
) : FindView {
    override var onChanged: () -> Unit = {}
    override var onWiden: () -> Unit = {}
    override var onOpen: (FileHits, LineHit) -> Unit = { _, _ -> }
    override var onHidden: () -> Unit = {}

    /** Set while the program changes the fields or toggles, so that [onChanged] tells only the user's edits. */
    private var programmatic = false

    internal val queryField =
        TextField().apply {
            styleClass += "find-in-files-field"
            promptText = "Find in files"
        }

    internal val maskField =
        TextField().apply {
            styleClass += "find-in-files-field"
            promptText = "File mask, e.g. *.kt, !*Test.kt"
            prefWidth = MASK_WIDTH
            minWidth = MASK_WIDTH
        }

    internal val caseToggle = toggle("Aa", "Match Case")
    internal val wordToggle = toggle("W", "Whole Words")
    internal val regexToggle = toggle(".*", "Regular Expression")

    private val scopeTip = Tooltip()
    internal val scopeText =
        Label().apply {
            styleClass += "find-in-files-dim"
            tooltip = scopeTip
        }

    internal val widenButton =
        Button("×").apply {
            styleClass += "find-in-files-widen"
            isFocusTraversable = false
            tooltip = Tooltip("Search the whole window")
            setOnAction { onWiden() }
        }

    private val scopeChip =
        HBox(2.0, scopeText, widenButton).apply {
            alignment = Pos.CENTER_LEFT
            isVisible = false
            isManaged = false
        }

    internal val statusLabel =
        Label().apply {
            styleClass += "find-in-files-dim"
            maxWidth = Double.MAX_VALUE
        }

    internal val list =
        ListView<ResultRow>().apply {
            styleClass += "find-in-files-list"
            // A click on the list must not take the focus from the fields.
            isFocusTraversable = false
            fixedCellSize = ROW_HEIGHT
            prefHeight = VISIBLE_ROWS * ROW_HEIGHT + 2
            minHeight = prefHeight
            maxHeight = prefHeight
            setCellFactory { ResultCell(icon, { severalRoots }, editorFont) }
            selectionModel.selectedItemProperty().subscribe { _, row -> selected(row) }
            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                if (event.button == MouseButton.PRIMARY && event.clickCount == 2) openAtCell(event)
            }
        }

    internal val preview =
        FindPreview(highlighter, previewTexts, { queryField.text }, { options }, editorFont) {
            // The editor must not take the focus: the fields keep it.
            queryField.requestFocus()
        }.also {
            it.node.prefHeight = PREVIEW_HEIGHT
            it.node.minHeight = PREVIEW_HEIGHT
            it.node.maxHeight = PREVIEW_HEIGHT
        }

    private val root =
        VBox(
            HBox(4.0, queryField, caseToggle, wordToggle, regexToggle, maskField).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(queryField, Priority.ALWAYS)
            },
            HBox(8.0, scopeChip, statusLabel).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(statusLabel, Priority.ALWAYS)
            },
            list,
            preview.node,
        ).apply {
            styleClass += "find-in-files-popup"
            stylesheets += STYLESHEET
            // Keys the fields leave would go on to the editor or the window beneath.
            addEventHandler(KeyEvent.ANY) { event -> event.consume() }
        }

    private val popup =
        Popup().apply {
            content.setAll(root)
            isAutoHide = true
            // A click into the editor hides the popup and still moves the caret there.
            consumeAutoHidingEvents = false
            // Escape is the fields' own key, handled in fieldKey.
            isHideOnEscape = false
            isAutoFix = false
            setOnHidden { hidden() }
        }

    private var watches: List<Subscription> = emptyList()
    private var returnFocusTo: Node? = null
    private var returnFocusOnHide = true

    init {
        for (field in listOf(queryField, maskField)) {
            field.addEventFilter(KeyEvent.KEY_PRESSED, ::fieldKey)
            field.textProperty().subscribe { _, _ -> changedByUser() }
        }
        for (toggle in listOf(caseToggle, wordToggle, regexToggle)) {
            toggle.selectedProperty().subscribe { _, _ -> changedByUser() }
        }
    }

    override val isShowing: Boolean get() = popup.isShowing

    override var query: String
        get() = queryField.text
        set(value) = programmatically { queryField.text = value }

    override var mask: String
        get() = maskField.text
        set(value) = programmatically { maskField.text = value }

    override var options: SearchOptions
        get() = SearchOptions(caseToggle.isSelected, wordToggle.isSelected, regexToggle.isSelected)
        set(value) =
            programmatically {
                caseToggle.isSelected = value.matchCase
                wordToggle.isSelected = value.wholeWord
                regexToggle.isSelected = value.regex
            }

    override var scopeLabel: String? = null
        set(value) {
            field = value
            scopeText.text = value?.let { "In: ${scopeName(it)}" }.orEmpty()
            scopeTip.text = value.orEmpty()
            scopeChip.isVisible = value != null
            scopeChip.isManaged = value != null
        }

    override var severalRoots: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            list.refresh()
        }

    override fun open(anchor: FindAnchor) {
        if (!anchor.owner.isShowing) return
        val width = findPopupWidth(anchor.area.width)
        root.prefWidth = width
        root.minWidth = width
        root.maxWidth = width
        if (!popup.isShowing) {
            returnFocusTo = anchor.returnFocus
            returnFocusOnHide = true
            popup.show(anchor.owner, anchor.area.minX, anchor.area.minY)
            watches = watch(anchor.owner)
        }
        popup.sizeToScene()
        val screen =
            Screen
                .getScreensForRectangle(anchor.area.minX, anchor.area.minY, anchor.area.width, anchor.area.height)
                .firstOrNull() ?: Screen.getPrimary()
        val position = findPopupPosition(anchor.area, popup.width, screen.visualBounds)
        popup.x = position.x
        popup.y = position.y
        queryField.requestFocus()
        queryField.selectAll()
    }

    override fun clear() {
        list.items.clear()
        preview.clear()
    }

    override fun add(files: List<FileHits>) {
        val rows = rowsOf(files)
        if (rows.isEmpty()) return
        list.items.addAll(rows)
        if (list.selectionModel.selectedIndex < 0) select(moveLine(list.items, -1, 1))
    }

    override fun status(text: String) {
        statusLabel.text = text
    }

    override fun hide() = popup.hide()

    override fun hideForOpen() {
        returnFocusOnHide = false
        popup.hide()
    }

    private fun toggle(
        label: String,
        tip: String,
    ) = ToggleButton(label).apply {
        isFocusTraversable = false
        tooltip = Tooltip(tip)
    }

    private fun programmatically(change: () -> Unit) {
        val before = programmatic
        programmatic = true
        try {
            change()
        } finally {
            programmatic = before
        }
    }

    private fun changedByUser() {
        if (!programmatic) onChanged()
    }

    private fun watch(owner: Window): List<Subscription> {
        // Changes only: a window that is not focused when the popup opens (no window manager) keeps it.
        val watches =
            mutableListOf(
                owner.xProperty().subscribe { _, _ -> hide() },
                owner.yProperty().subscribe { _, _ -> hide() },
                owner.focusedProperty().subscribe { _, focused -> if (!focused) hide() },
            )
        (owner as? Stage)?.let { stage ->
            watches += stage.iconifiedProperty().subscribe { _, iconified -> if (iconified) hide() }
        }
        return watches
    }

    private fun hidden() {
        watches.forEach { it.unsubscribe() }
        watches = emptyList()
        preview.cancel()
        val focus = if (returnFocusOnHide) returnFocusTo else null
        returnFocusTo = null
        returnFocusOnHide = true
        focus?.requestFocus()
        onHidden()
    }

    /** The fields' keys that move the selection, take it, switch fields or close the popup; consumed. */
    private fun fieldKey(event: KeyEvent) {
        val handled =
            when (event.code) {
                KeyCode.UP -> {
                    select(moveLine(list.items, list.selectionModel.selectedIndex, -1)).let { true }
                }

                KeyCode.DOWN -> {
                    select(moveLine(list.items, list.selectionModel.selectedIndex, 1)).let { true }
                }

                KeyCode.PAGE_UP -> {
                    select(pageLine(list.items, list.selectionModel.selectedIndex, -1, VISIBLE_ROWS)).let { true }
                }

                KeyCode.PAGE_DOWN -> {
                    select(pageLine(list.items, list.selectionModel.selectedIndex, 1, VISIBLE_ROWS)).let { true }
                }

                KeyCode.ENTER -> {
                    openSelected().let { true }
                }

                KeyCode.ESCAPE -> {
                    hide().let { true }
                }

                KeyCode.TAB -> {
                    switchField().let { true }
                }

                else -> {
                    false
                }
            }
        if (handled) event.consume()
    }

    private fun switchField() {
        val to = if (maskField.isFocused) queryField else maskField
        to.requestFocus()
        to.selectAll()
    }

    private fun select(index: Int) {
        if (index < 0) return
        list.selectionModel.select(index)
        list.scrollTo((index - VISIBLE_ROWS / 2).coerceAtLeast(0))
    }

    /** A header is no selection: it goes to its first line row. Then the preview follows the line row. */
    private fun selected(row: ResultRow?) {
        when (row) {
            is ResultRow.Header -> {
                val line = lineRowFor(list.items, list.selectionModel.selectedIndex)
                if (line >= 0) list.selectionModel.select(line) else list.selectionModel.clearSelection()
            }

            is ResultRow.Line -> {
                preview.show(row)
            }

            null -> {
                preview.show(null)
            }
        }
    }

    private fun openSelected() {
        val row = list.selectionModel.selectedItem as? ResultRow.Line ?: return
        onOpen(row.hits, row.hit)
    }

    private fun openAtCell(event: MouseEvent) {
        val cell = generateSequence(event.target as? Node) { it.parent }.filterIsInstance<ListCell<*>>().firstOrNull()
        (cell?.item as? ResultRow.Line)?.let { onOpen(it.hits, it.hit) }
    }

    private companion object {
        const val ROW_HEIGHT = 24.0
        const val VISIBLE_ROWS = 12
        const val PREVIEW_HEIGHT = 232.0
        const val MASK_WIDTH = 160.0
        val STYLESHEET: String =
            checkNotNull(FindInFilesPopup::class.java.getResource("/dev/sluide/findinfiles/ui/find-in-files.css"))
                .toExternalForm()
    }
}
