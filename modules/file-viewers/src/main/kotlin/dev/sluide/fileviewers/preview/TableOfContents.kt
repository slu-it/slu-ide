package dev.sluide.fileviewers.preview

import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleDoubleProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.OverrunStyle
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.Text
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignT
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The table of contents of a Markdown view: a toolbar toggle [button] and an [overlay] that floats over the top left of
 * the view (it belongs in a `StackPane`) while it is open. It lists the [headings] of the document, indented by their
 * rank among the levels in use ([HeadingOutline.depths]), and selects the one whose section contains
 * [currentLine]. Choosing an entry (click, or Enter) closes the overlay and [navigate]s to it; Escape and the button
 * close it and [restoreFocus]. The view closes it on a click outside ([contains]). Everything runs on the FX thread.
 */
internal class TableOfContents(
    private val headings: () -> List<Heading>,
    private val currentLine: () -> Int,
    private val navigate: (Heading) -> Unit,
    private val restoreFocus: () -> Unit,
) {
    val button =
        ToggleButton(null, FontIcon(MaterialDesignT.TABLE_OF_CONTENTS).apply { iconSize = ICON_SIZE }).apply {
            styleClass += "toc-button"
            tooltip = Tooltip("Table of Contents")
            // A click leaves the focus where it is; the list takes it when it opens.
            isFocusTraversable = false
        }

    private var depths = emptyList<Int>()

    val list =
        ListView<Heading>().apply {
            fixedCellSize = DEFAULT_CELL_HEIGHT
            placeholder = Label(PLACEHOLDER)
            setCellFactory { HeadingCell() }
            addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)
        }

    /** Height of the list's content, which the overlay is no taller than (it is shorter than the view). */
    private val contentHeight = SimpleDoubleProperty(DEFAULT_CELL_HEIGHT)

    /** A cell that is never shown: it takes the font and the height the real cells get from the stylesheets. */
    private val probe =
        ListCell<Heading>().apply {
            text = "Xg"
            isVisible = false
            isManaged = false
        }

    val overlay =
        VBox(list, probe).apply {
            styleClass += "toc-overlay"
            isVisible = false
            managedProperty().bind(visibleProperty())
            VBox.setVgrow(list, Priority.ALWAYS)
            StackPane.setAlignment(this, Pos.TOP_LEFT)
            StackPane.setMargin(this, Insets(MARGIN))
            // Sized to its content; the StackPane would stretch it up to the maximum.
            maxWidth = Region.USE_PREF_SIZE
            val container = parentProperty().flatMap { (it as? Region)?.heightProperty() }
            maxHeightProperty().bind(
                Bindings.createDoubleBinding(
                    { min(contentHeight.get(), (container.value?.toDouble() ?: Double.MAX_VALUE) * MAX_SHARE) },
                    contentHeight,
                    container,
                ),
            )
        }

    val isOpen: Boolean get() = overlay.isVisible

    init {
        // Sized for the placeholder until there are entries: the first refresh finds the list empty and sizes nothing.
        size(emptyList())
        button.selectedProperty().subscribe { selected -> if (selected) open() else close(restore = true) }
    }

    /** Shows the overlay with the section of the current line selected and scrolled into view, and focuses the list. */
    fun open() {
        if (isOpen) return
        overlay.isVisible = true
        button.isSelected = true
        refresh(scroll = true)
        list.requestFocus()
    }

    /** Hides the overlay, and puts the focus back where it was if [restore]. */
    fun close(restore: Boolean = false) {
        if (!isOpen) return
        overlay.isVisible = false
        button.isSelected = false
        if (restore) restoreFocus()
    }

    /** Updates the entries and the selected one, e.g. after the caret moved or the text changed; only while open. */
    fun refresh() = refresh(scroll = false)

    /** Whether [target], the target of an event in the view, is in the overlay or is the button. */
    fun contains(target: Any?): Boolean {
        var node = target as? Node
        while (node != null) {
            if (node === overlay || node === button) return true
            node = node.parent
        }
        return false
    }

    private fun refresh(scroll: Boolean) {
        if (!isOpen) return
        val all = headings()
        val previous = list.items.toList()
        if (all != previous) {
            depths = HeadingOutline.depths(all)
            list.items.setAll(all)
        }
        // Also when nothing changed: the font may have, and the view's size.
        if (scroll || all != previous) size(all)
        val section = HeadingOutline.sectionAt(all, currentLine())
        val changed = list.selectionModel.selectedIndex != (section ?: -1)
        if (section == null) list.selectionModel.clearSelection() else list.selectionModel.select(section)
        if (section != null && (scroll || changed)) {
            list.scrollTo(max(0, section - SCROLL_CONTEXT))
        }
    }

    /**
     * Sizes the overlay to its entries as they render (the editor font setting can change): wide enough for the longest
     * up to [MAX_WIDTH] or [MAX_VIEW_SHARE] of the view, tall enough for all without a scroll bar up to [MAX_SHARE].
     */
    private fun size(all: List<Heading>) {
        overlay.applyCss()
        probe.applyCss()
        list.applyCss()
        val cellHeight = ceil(probe.prefHeight(-1.0)).takeIf { it > 0 } ?: DEFAULT_CELL_HEIGHT
        list.fixedCellSize = cellHeight
        val font = probe.font
        val bold = Font.font(font.family, FontWeight.BOLD, font.size)

        fun width(
            text: String,
            font: Font,
        ) = Text(text).also { it.font = font }.layoutBounds.width
        val widest =
            all.indices.maxOfOrNull { i ->
                width(all[i].text, if (all[i].level == 1) bold else font) + depths[i] * INDENT
            } ?: width(PLACEHOLDER, font)
        val extra = overlay.insets.left + overlay.insets.right + list.insets.left + list.insets.right
        val view = (overlay.parent as? Region)?.width?.takeIf { it > 0 } ?: Double.MAX_VALUE
        val cap = max(MIN_WIDTH, min(MAX_WIDTH, view * MAX_VIEW_SHARE))
        overlay.prefWidth = ceil(min(cap, max(MIN_WIDTH, widest + CELL_PADDING * 2 + SCROLL_BAR + extra)))
        val height = max(1, all.size) * cellHeight + list.insets.top + list.insets.bottom
        list.prefHeight = height
        contentHeight.set(height + overlay.insets.top + overlay.insets.bottom)
    }

    private fun choose(heading: Heading?) {
        if (heading == null) return
        close()
        navigate(heading)
    }

    private fun onKey(event: KeyEvent) {
        when (event.code) {
            KeyCode.ENTER -> {
                choose(list.selectionModel.selectedItem)
                event.consume()
            }

            KeyCode.ESCAPE -> {
                close(restore = true)
                event.consume()
            }

            else -> {}
        }
    }

    /** An entry: the heading's text, indented by its depth, with its level as style class `toc-level-N`. */
    private inner class HeadingCell : ListCell<Heading>() {
        init {
            textOverrun = OverrunStyle.ELLIPSIS
            // The list gives the cell its width, so that long entries end in an ellipsis, not a scroll bar.
            prefWidth = 0.0
            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                if (event.button == MouseButton.PRIMARY && !isEmpty) choose(item)
            }
        }

        override fun updateItem(
            item: Heading?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            styleClass.removeIf { it.startsWith("toc-level-") }
            if (item == null || empty) {
                text = null
                return
            }
            text = item.text
            styleClass += "toc-level-${item.level}"
            val depth = depths.getOrElse(index) { 0 }
            padding = Insets(0.0, CELL_PADDING, 0.0, CELL_PADDING + depth * INDENT)
        }
    }

    private companion object {
        const val DEFAULT_CELL_HEIGHT = 24.0
        const val ICON_SIZE = 14
        const val PLACEHOLDER = "No headings"
        const val MAX_VIEW_SHARE = 0.45
        const val CELL_PADDING = 8.0
        const val INDENT = 14.0
        const val MARGIN = 6.0
        const val MIN_WIDTH = 200.0
        const val MAX_WIDTH = 480.0
        const val SCROLL_BAR = 16.0
        const val MAX_SHARE = 0.7
        const val SCROLL_CONTEXT = 2
    }
}
