package dev.sluide.gitlog

import dev.sluide.gitlog.filter.FilterItem
import dev.sluide.gitlog.ui.GIT_LOG_CSS
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Popup

/**
 * Go to Hash/Branch/Tag (ADR 0045), as in IntelliJ: a popup at the top of the commit table with a text field and below
 * it the branches and tags of [items] whose short name has the typed text (ignoring case), at most [VISIBLE_ROWS] rows
 * in view, hidden while nothing matches or nothing is typed. Enter in the field hands the typed text (or the one
 * completion named exactly) to [go], Enter or a click in the list that item; Down moves into the list, Up from its
 * first item back to the field; Escape closes. [say] shows why the log cannot go there, with a Clear Filters button
 * that calls [clearFilters] if wanted; the popup stays open, the text selected. [closed] is told whenever it closes.
 */
internal class GoToPopup(
    private val items: () -> List<FilterItem>,
) {
    /** Goes to a revision (a full refname for an item, else the typed text), called by its name. */
    var go: (revision: String, name: String) -> Unit = { _, _ -> }

    /** Clears the filters and goes on to the commit they hide. */
    var clearFilters: () -> Unit = {}

    /** Told when the popup closes, however. */
    var closed: () -> Unit = {}

    /** The branches and tags as read when the popup was shown. */
    private var known: List<FilterItem> = emptyList()

    internal val field =
        TextField().apply {
            styleClass += "git-goto-field"
            promptText = "Hash, branch or tag"
            textProperty().subscribe { _, _ ->
                say(null)
                rebuild()
            }
            setOnAction { enter() }
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                if (event.code == KeyCode.DOWN && list.isVisible) {
                    list.requestFocus()
                    list.selectionModel.select(0)
                    list.scrollTo(0)
                    event.consume()
                }
            }
        }

    internal val message = Label().apply { styleClass += "git-goto-message" }

    internal val clearFiltersButton =
        Button("Clear Filters").apply {
            styleClass += "git-goto-clear-filters"
            minWidth = Region.USE_PREF_SIZE
            setOnAction { clearFilters() }
        }

    private val messageBar =
        HBox(message, clearFiltersButton).apply {
            styleClass += "git-goto-message-bar"
            HBox.setHgrow(message, Priority.ALWAYS)
            message.maxWidth = Double.MAX_VALUE
            message.isWrapText = true
        }

    internal val list: ListView<FilterItem> =
        ListView<FilterItem>().apply {
            styleClass += "git-goto-list"
            fixedCellSize = CELL_HEIGHT
            setCellFactory { CompletionCell() }
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                when {
                    event.code == KeyCode.ENTER -> {
                        selectionModel.selectedItem?.let(::take)
                        event.consume()
                    }

                    event.code == KeyCode.UP && selectionModel.selectedIndex <= 0 -> {
                        selectionModel.clearSelection()
                        field.requestFocus()
                        field.end()
                        event.consume()
                    }
                }
            }
        }

    internal val content =
        VBox(field, messageBar, list).apply {
            // .git-log gives the looked-up colours of the ref icons: the popup is a window of its own.
            styleClass.addAll("git-log", "git-goto")
            stylesheets += GIT_LOG_CSS
            prefWidth = WIDTH
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
            popup.setOnHidden { closed() }
        }

    val isShowing: Boolean get() = popup.isShowing

    init {
        say(null)
        rebuild()
    }

    /** Shows the popup at the top of [table], centred, with the current [items] and the field empty and focused. */
    fun show(table: Node) {
        known = items()
        field.clear()
        say(null)
        rebuild()
        val bounds = table.localToScreen(table.boundsInLocal) ?: return
        popup.show(table, bounds.minX + maxOf(0.0, (bounds.width - WIDTH) / 2), bounds.minY + TOP_GAP)
        field.requestFocus()
    }

    fun hide() = popup.hide()

    /** Says [text] below the field (`null` says nothing), with Clear Filters if [offerClearFilters]; the text selected. */
    fun say(
        text: String?,
        offerClearFilters: Boolean = false,
    ) {
        message.text = text.orEmpty()
        messageBar.isVisible = text != null
        messageBar.isManaged = text != null
        clearFiltersButton.isVisible = text != null && offerClearFilters
        clearFiltersButton.isManaged = clearFiltersButton.isVisible
        if (text != null) {
            field.requestFocus()
            field.selectAll()
        }
        popup.sizeToScene()
    }

    private fun rebuild() {
        val shown = goToCompletions(known, field.text.orEmpty())
        list.items.setAll(shown)
        list.isVisible = shown.isNotEmpty()
        list.isManaged = list.isVisible
        list.prefHeight = minOf(shown.size, VISIBLE_ROWS) * CELL_HEIGHT + LIST_INSETS
        popup.sizeToScene()
    }

    private fun enter() {
        val text = field.text.orEmpty().trim()
        if (text.isEmpty()) return
        val exact = exactCompletion(list.items, text)
        if (exact != null) take(exact) else go(text, text)
    }

    private fun take(item: FilterItem) = go(item.value, item.text)

    /** The item's icon and short name; a click goes to it. */
    private inner class CompletionCell : ListCell<FilterItem>() {
        init {
            styleClass += "git-goto-item"
            setOnMouseClicked { event ->
                if (event.button == MouseButton.PRIMARY && event.clickCount == 1) item?.let(::take)
            }
        }

        override fun updateItem(
            item: FilterItem?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else item.text
            graphic = if (empty || item == null) null else item.icon?.invoke()
        }
    }

    private companion object {
        const val WIDTH = 360.0
        const val VISIBLE_ROWS = 10
        const val CELL_HEIGHT = 24.0
        const val LIST_INSETS = 2.0
        const val TOP_GAP = 2.0
    }
}

/** The items whose text (a short name) has [text], ignoring case and the text's surrounding blanks; none for none. */
internal fun goToCompletions(
    items: List<FilterItem>,
    text: String,
): List<FilterItem> {
    val wanted = text.trim()
    if (wanted.isEmpty()) return emptyList()
    return items.filter { it.text.contains(wanted, ignoreCase = true) }
}

/** The one item of [items] whose text is exactly [text]; `null` for none or several. */
internal fun exactCompletion(
    items: List<FilterItem>,
    text: String,
): FilterItem? = items.filter { it.text == text }.singleOrNull()
