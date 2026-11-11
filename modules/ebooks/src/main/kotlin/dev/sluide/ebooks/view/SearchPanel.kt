package dev.sluide.ebooks.view

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.ebooks.BookPage
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import javafx.util.Duration
import java.util.concurrent.ExecutorService

/**
 * The Search side panel of the reader: a field, a status line and the hits of [BookSearch] grouped by chapter. The
 * search runs on [executor] [debounce] after typing and at once on Enter; an older search is cancelled and a result
 * is applied only while the panel is in a scene. A click, or Enter, on a hit marks it and jumps there (the focus stays
 * in the list). [onClose] is called for Escape in an empty field. FX thread only.
 */
internal class SearchPanel(
    private val view: EpubFileView,
    private val onClose: () -> Unit,
    private val debounce: Duration = Duration.millis(300.0),
    private val executor: ExecutorService = NamedThreads.single("sluide-ebook-search"),
) {
    internal val field = TextField().apply { promptText = "Search in book" }
    internal val status = Label().apply { styleClass += "ebook-search-status" }
    internal val list = ListView<SearchRow>()
    private val root = VBox(4.0, field, status, list)
    private val timer = PauseTransition(debounce)
    private val runner = SearchRunner(executor) { Platform.runLater(it) }
    private var searched: BookPage? = null

    val node: Node get() = root

    init {
        root.styleClass += "ebook-search"
        root.setMinSize(0.0, 0.0)
        VBox.setVgrow(list, Priority.ALWAYS)
        list.isFocusTraversable = true
        list.setCellFactory { Cell() }
        timer.setOnFinished { search() }
        field.textProperty().subscribe { _, _ ->
            view.clearMark(MARK)
            clearResults()
            timer.playFromStart()
        }
        field.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            when (event.code) {
                KeyCode.ENTER -> {
                    timer.stop()
                    search()
                    event.consume()
                }

                KeyCode.ESCAPE -> {
                    if (field.text.isNotEmpty()) field.clear() else onClose()
                    event.consume()
                }

                else -> {}
            }
        }
        list.addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (event.target.let { cellOf(it) }?.item is SearchRow.Heading) event.consume()
        }
        list.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.button == MouseButton.PRIMARY && cellOf(event.target)?.item is SearchRow.Hit) activate()
        }
        list.addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ENTER) {
                activate()
                event.consume()
            }
        }
        list.selectionModel.selectedIndexProperty().subscribe { old, new ->
            if (list.items.getOrNull(new.toInt()) is SearchRow.Heading) skipHeading(old.toInt(), new.toInt())
        }
    }

    /** Selects the field's text and focuses it. */
    fun focusField() {
        field.requestFocus()
        field.selectAll()
    }

    /** Stops a running search, clears the results and removes the mark; called when the panel is closed. */
    fun closed() {
        timer.stop()
        clearResults()
        view.clearMark(MARK)
    }

    /** Searches the current query again; called when the panel is shown and when another book page was loaded. */
    fun refresh() {
        if (field.text.isNotBlank()) search()
    }

    /** Called when a page was loaded: the results are searched again if they are of another book text. */
    fun pageLoaded() {
        if (root.scene != null && view.page !== searched) refresh()
    }

    fun dispose() {
        closed()
        executor.shutdownNow()
    }

    private fun cellOf(target: Any?): ListCell<*>? {
        var n = target as? Node
        while (n != null && n !is ListCell<*>) n = n.parent
        return (n as? ListCell<*>)?.takeIf { !it.isEmpty }
    }

    private fun skipHeading(
        old: Int,
        new: Int,
    ) {
        val next = if (new > old) new + 1 else new - 1
        if (list.items.getOrNull(next) is SearchRow.Hit) {
            list.selectionModel.select(next)
        } else {
            list.selectionModel.clearSelection()
        }
    }

    private fun clearResults() {
        runner.cancel()
        list.items = FXCollections.observableArrayList()
        status.text = ""
    }

    private fun search() {
        val query = field.text
        val book = view.page
        clearResults()
        if (book == null || !BookSearch.searchable(query)) return
        searched = book
        status.text = "Searching…"
        runner.submit(
            work = { BookSearch.find(book.text, book.chapters, query) },
            attached = { root.scene != null },
            deliver = ::show,
        )
    }

    private fun show(result: SearchResult) {
        list.items = FXCollections.observableArrayList(searchRows(result))
        status.text = searchStatus(result)
    }

    private fun activate() {
        val hit = list.selectionModel.selectedItem as? SearchRow.Hit ?: return
        view.markRange(MARK, hit.match.start, hit.match.end)
        view.userJumpTo(hit.match.start)
    }

    private class Cell : ListCell<SearchRow>() {
        override fun updateItem(
            item: SearchRow?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = null
            graphic = null
            styleClass -= "ebook-search-heading"
            when (item) {
                is SearchRow.Heading -> {
                    text = item.title
                    styleClass += "ebook-search-heading"
                }

                is SearchRow.Hit -> {
                    graphic =
                        TextFlow(
                            snippetText(item.match.snippetBefore),
                            snippetText(item.match.matchText).apply { styleClass += "ebook-search-match" },
                            snippetText(item.match.snippetAfter),
                        ).apply {
                            styleClass += "ebook-search-snippet"
                            prefWidthProperty().bind(this@Cell.widthProperty().subtract(24))
                        }
                }

                null -> {}
            }
        }
    }

    companion object {
        /** A plain Text has no `text` style class (only a Labeled's inner text has), so it gets its own. */
        private fun snippetText(value: String) = Text(value).apply { styleClass += "ebook-search-text" }

        const val MARK = "search"
    }
}
