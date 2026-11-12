package dev.sluide.codereview.window

import dev.sluide.codereview.ResolvedReview
import dev.sluide.codereview.ReviewBase
import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.model.ReviewSelection
import javafx.beans.property.ObjectProperty
import javafx.beans.value.ObservableValue
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.geometry.Bounds
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.stage.Popup
import java.time.Clock

/**
 * The toolbar's leading nodes (ADR 0168): the bold title, the base picker (branch scopes) and the commit picker. The
 * pickers only set what the window owns: [changeBase] and [selection]; the loader loads.
 */
internal class ReviewPickers(
    private val scope: ReviewScope,
    private val resolved: ObservableValue<ResolvedReview?>,
    private val bases: ObservableList<ReviewBase>,
    private val selection: ObjectProperty<ReviewSelection>,
    private val changeBase: (String) -> Unit,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    internal val title = Label().apply { styleClass += "review-title" }

    internal val baseButton =
        MenuButton().apply {
            styleClass += "review-picker"
            isFocusTraversable = false
        }

    internal val commitButton =
        Button().apply {
            styleClass += "review-picker"
            isFocusTraversable = false
            setOnAction { showCommitPopup() }
        }

    private val entries = ListView<PickerEntry>().apply { styleClass += "review-commit-list" }
    private val popup =
        Popup().apply {
            isAutoHide = true
            isHideOnEscape = true
            content += entries
        }

    /** The commit last clicked or moved to; where a Shift range starts. */
    private var anchor: Int? = null

    /** The commit rows a Shift+Up/Down range covers, shown highlighted; `null` without one. */
    private var rangeRows: IntRange? = null

    val nodes: List<Node> = listOf(title, baseButton, commitButton)

    init {
        entries.stylesheets += REVIEW_WINDOW_CSS
        entries.setCellFactory { EntryCell() }
        entries.addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)
        entries.setPrefSize(POPUP_WIDTH, POPUP_HEIGHT)
        popup.setOnHidden { rangeRows = null }

        baseButton.isVisible = scope is ReviewScope.Branch
        baseButton.isManaged = baseButton.isVisible
        bases.addListener(ListChangeListener { fillBases() })
        resolved.subscribe { _ -> update() }
        selection.subscribe { _ -> update() }
        update()
    }

    private fun update() {
        val review = resolved.value
        title.text = review?.title.orEmpty()
        baseButton.text = baseButtonText(review)
        fillBases()
        val shown = review != null && !(scope is ReviewScope.Commits && review.commits.size <= 1)
        commitButton.isVisible = shown
        commitButton.isManaged = shown
        commitButton.text = review?.let { selectionLabel(it, selection.get()) + " ▾" }.orEmpty()
        anchor = null
    }

    private fun fillBases() {
        val (defaults, rest) = baseGroups(bases)
        val items = ArrayList<MenuItem>()
        defaults.mapTo(items) { baseItem(it, "${it.label} (default)") }
        if (defaults.isNotEmpty() && rest.isNotEmpty()) items += SeparatorMenuItem()
        rest.mapTo(items) { baseItem(it, it.label) }
        baseButton.items.setAll(items)
    }

    private fun baseItem(
        base: ReviewBase,
        text: String,
    ) = MenuItem(text).apply { setOnAction { changeBase(base.ref) } }

    /** Opens the commit list under the button, on the current selection. */
    internal fun showCommitPopup() {
        val review = resolved.value ?: return
        val owner = commitButton.scene?.window ?: return
        val rows = pickerEntries(review, clock)
        entries.items.setAll(rows)
        rangeRows = null
        val picked = selection.get()
        val current = rows.indexOfFirst { it.selection == picked }.coerceAtLeast(0)
        val bounds: Bounds = commitButton.localToScreen(commitButton.boundsInLocal)
        popup.show(owner, bounds.minX, bounds.maxY)
        entries.selectionModel.select(current)
        entries.scrollTo(current)
        entries.requestFocus()
    }

    internal val commitList: ListView<PickerEntry> get() = entries

    internal val commitPopup: Popup get() = popup

    /** A click on [entry]; with [extend] a commit extends the range from the last clicked commit. */
    internal fun pick(
        entry: PickerEntry,
        extend: Boolean,
    ) {
        val index = entry.commitIndex
        if (index == null) {
            anchor = null
            selection.set(entry.selection)
        } else {
            val from = if (extend) anchor ?: anchorOf(selection.get()) else null
            selection.set(if (from == null) entry.selection else commitRange(from, index))
            anchor = index
        }
        popup.hide()
    }

    private fun onKey(event: KeyEvent) {
        when (event.code) {
            KeyCode.ENTER -> {
                pickFocused()
                event.consume()
            }

            KeyCode.ESCAPE -> {
                popup.hide()
                event.consume()
            }

            KeyCode.UP, KeyCode.DOWN -> {
                if (event.isShiftDown) extendRange(if (event.code == KeyCode.UP) -1 else 1) else rangeRows = null
                if (event.isShiftDown) event.consume()
                entries.refresh()
            }

            else -> {}
        }
    }

    private fun pickFocused() {
        val range = rangeRows
        if (range != null) {
            val first = entries.items[range.first].commitIndex
            val last = entries.items[range.last].commitIndex
            if (first != null && last != null) {
                selection.set(commitRange(first, last))
                popup.hide()
                return
            }
        }
        entries.selectionModel.selectedItem?.let { pick(it, extend = false) }
    }

    /** Moves the selected row by [delta] over the commits and covers the rows from where the range started. */
    private fun extendRange(delta: Int) {
        val first = entries.items.indexOfFirst { it.commitIndex != null }
        val row = entries.selectionModel.selectedIndex
        if (first < 0 || row < first) return
        val target = (row + delta).coerceIn(first, entries.items.lastIndex)
        val start = rangeRows?.let { if (row == it.first) it.last else it.first } ?: row
        rangeRows = minOf(start, target)..maxOf(start, target)
        entries.selectionModel.select(target)
        entries.scrollTo(target)
    }

    internal val pickedRange: IntRange? get() = rangeRows

    private inner class EntryCell : ListCell<PickerEntry>() {
        init {
            setOnMouseClicked { event ->
                val entry = item
                if (event.button == MouseButton.PRIMARY && entry != null) {
                    pick(entry, event.isShiftDown)
                    event.consume()
                }
            }
        }

        override fun updateItem(
            item: PickerEntry?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = if (empty) null else item?.text
            val inRange = rangeRows?.contains(index) == true
            if (inRange) {
                if ("review-pick-range" !in styleClass) styleClass += "review-pick-range"
            } else {
                styleClass -= "review-pick-range"
            }
        }
    }

    private companion object {
        const val POPUP_WIDTH = 520.0
        const val POPUP_HEIGHT = 300.0
    }
}
