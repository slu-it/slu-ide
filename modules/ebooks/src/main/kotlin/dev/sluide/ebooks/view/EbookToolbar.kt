package dev.sluide.ebooks.view

import dev.sluide.fileviewers.WrappingToolbar
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignB
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignF

/**
 * The toolbar above the book (ADR 0165, ADR 0135). Leading: Contents, Previous and Next (large books only), the Read
 * Aloud slot. Trailing: progress, Book Style, document zoom, the Search slot. No control is focus-traversable.
 */
internal class EbookToolbar(
    zoom: Node,
) {
    val contents = toggle(MaterialDesignF.FORMAT_LIST_BULLETED, "Contents").apply { isSelected = true }
    val previous = button(MaterialDesignC.CHEVRON_LEFT, "Previous Chapter")
    val next = button(MaterialDesignC.CHEVRON_RIGHT, "Next Chapter")
    val readAloudSlot = HBox().apply { styleClass += "ebook-slot" }
    val progress = Label().apply { styleClass += "ebook-progress" }
    val bookStyle = toggle(MaterialDesignB.BOOK_OPEN_PAGE_VARIANT, "Book Style (the book's own formatting)")
    val searchSlot = HBox().apply { styleClass += "ebook-slot" }
    val node = WrappingToolbar()

    init {
        node.styleClass += "ebook-toolbar"
        previous.isVisible = false
        previous.isManaged = false
        next.isVisible = false
        next.isManaged = false
        node.leading.setAll(contents, previous, next, readAloudSlot)
        node.trailing.setAll(progress, bookStyle, zoom, searchSlot)
    }

    /** Shows Previous and Next for a large book, which is shown one chapter at a time. */
    fun showChapterButtons(show: Boolean) {
        for (button in listOf(previous, next)) {
            button.isVisible = show
            button.isManaged = show
        }
    }

    private fun toggle(
        icon: Ikon,
        tip: String,
    ) = ToggleButton(null, FontIcon(icon)).apply {
        isFocusTraversable = false
        tooltip = Tooltip(tip)
        styleClass += "ebook-button"
    }

    private fun button(
        icon: Ikon,
        tip: String,
    ) = Button(null, FontIcon(icon)).apply {
        isFocusTraversable = false
        tooltip = Tooltip(tip)
        styleClass += "ebook-button"
    }
}
