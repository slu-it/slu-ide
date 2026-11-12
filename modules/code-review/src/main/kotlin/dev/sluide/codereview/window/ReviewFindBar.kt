package dev.sluide.codereview.window

import dev.sluide.codereview.find.FindMatch
import dev.sluide.codereview.find.ReviewFind
import dev.sluide.codereview.list.ReviewDiffView
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewRow
import javafx.application.Platform
import javafx.collections.ListChangeListener
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.kordamp.ikonli.materialdesign2.MaterialDesignC

/**
 * The find bar of the review window, shown in [container] while open: searches the rows of [document] as the query is
 * typed, marks the matches in [diffView] and goes to the next or previous one (wrapping). The window's Shortcut+F opens
 * it, F3 and Shift+F3 go through the matches while it is open; Enter and Escape are handled in the query field.
 */
internal class ReviewFindBar(
    private val document: ReviewDocument,
    private val diffView: ReviewDiffView,
    private val container: Pane,
    private val dispatch: (() -> Unit) -> Unit = { Platform.runLater(it) },
) {
    private val find = ReviewFind(document)

    val query =
        TextField().apply {
            styleClass += "review-find-query"
            promptText = "Find in review"
        }
    val matchCase =
        ToggleButton("Aa").apply {
            styleClass += "review-find-option"
            tooltip = Tooltip("Match case")
            isFocusTraversable = false
        }
    val counter = Label().apply { styleClass += "review-find-counter" }

    val node =
        HBox(
            query,
            matchCase,
            counter,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            iconButton(MaterialDesignA.ARROW_UP, "Previous match (Shift+Enter, Shift+F3)", ::previous),
            iconButton(MaterialDesignA.ARROW_DOWN, "Next match (Enter, F3)", ::next),
            iconButton(MaterialDesignC.CLOSE, "Close (Escape)", ::close),
        ).apply {
            styleClass += "review-find-bar"
            alignment = Pos.CENTER_LEFT
        }

    private var searchPending = false

    private val rowsListener = ListChangeListener<ReviewRow> { rowsChanged() }

    val isOpen: Boolean get() = node in container.children

    init {
        query.textProperty().subscribe { _ -> if (isOpen) search() }
        matchCase.selectedProperty().subscribe { _ -> if (isOpen) search() }
        // A filter: the field's own handlers would take Enter and Escape.
        node.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            when {
                event.code == KeyCode.ENTER && event.target == query -> if (event.isShiftDown) previous() else next()
                event.code == KeyCode.ESCAPE && event.target == query -> close()
                else -> return@addEventFilter
            }
            event.consume()
        }
    }

    /** Shows the bar (if it is not) and puts the focus in the query with its text selected. */
    fun open() {
        if (!isOpen) {
            container.children += node
            document.rows.addListener(rowsListener)
            search()
        }
        query.requestFocus()
        query.selectAll()
    }

    /** Hides the bar and the marks; the focus goes to the diff. */
    fun close() {
        if (!isOpen) return
        container.children -= node
        document.rows.removeListener(rowsListener)
        diffView.showMatches(emptyList(), null)
        diffView.node.requestFocus()
    }

    /** Goes to the next match, the first after the last. */
    fun next() = go(find.next())

    /** Goes to the previous match, the last before the first. */
    fun previous() = go(find.previous())

    private fun go(match: FindMatch?) {
        show()
        match?.let { diffView.reveal(it.row) }
    }

    private fun search() {
        find.query = query.text
        find.matchCase = matchCase.isSelected
        find.search(diffView.firstVisible().first)
        show()
        // Typing never moves a match that is in view.
        find.current?.let { index ->
            val row = find.matches[index].row
            if (!diffView.isFullyVisible(row)) diffView.reveal(row)
        }
    }

    /**
     * The rows changed (a file expanded, collapsed, loaded, a filter): the matches follow without scrolling. Searched
     * once the change is through: several changes (the chunks of a load) are one search.
     */
    private fun rowsChanged() {
        if (searchPending) return
        searchPending = true
        dispatch {
            searchPending = false
            if (!isOpen) return@dispatch
            find.rowsChanged(diffView.firstVisible().first)
            show()
        }
    }

    private fun show() {
        counter.text = find.counterText()
        diffView.showMatches(find.matches, find.current)
    }

    private fun iconButton(
        icon: Ikon,
        tooltip: String,
        action: () -> Unit,
    ) = Button(null, FontIcon(icon)).apply {
        styleClass += "review-find-button"
        this.tooltip = Tooltip(tooltip)
        isFocusTraversable = false
        setOnAction { action() }
    }
}
