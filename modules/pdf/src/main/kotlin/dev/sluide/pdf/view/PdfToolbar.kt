package dev.sluide.pdf.view

import dev.sluide.fileviewers.WrappingToolbar
import javafx.beans.value.ObservableIntegerValue
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignO
import java.math.BigInteger

/**
 * The toolbar above the pages (ADR 0192, ADR 0135). Leading: the side panel's slot, the page field ("12" and "/ 340"),
 * the search's slot (both empty slots for other features). Trailing: Open in Default App and the [zoom] group. No
 * control is focus-traversable.
 */
internal class PdfToolbar(
    zoom: Node,
) {
    /** For the side panel's toggle. */
    val sidePanelSlot = slot()

    /** The current page, 1-based; editable to jump. */
    val pageField =
        TextField().apply {
            styleClass += "pdf-page-field"
            isFocusTraversable = false
        }

    /** "/ N", the page count. */
    val pageCount = Label().apply { styleClass += "pdf-page-count" }

    /** For the search's toggle. */
    val searchSlot = slot()

    val openExternally =
        Button(null, FontIcon(MaterialDesignO.OPEN_IN_NEW)).apply {
            styleClass += "pdf-button"
            isFocusTraversable = false
            tooltip = Tooltip("Open in Default App")
        }

    val node = WrappingToolbar()

    init {
        node.styleClass += "pdf-toolbar"
        val page = HBox(pageField, pageCount).apply { alignment = Pos.CENTER_LEFT }
        node.leading.setAll(sidePanelSlot, page, searchSlot)
        node.trailing.setAll(openExternally, zoom)
    }

    /**
     * Makes the page field show the [current] page (1-based) while it does not have the focus; Enter jumps to the page
     * typed ([jump], 0-based, clamped to the [pageCount]), Escape and leaving it restore the number; both hand the
     * focus back ([done]).
     */
    fun installPageField(
        current: ObservableIntegerValue,
        pageCount: () -> Int,
        jump: (Int) -> Unit,
        done: () -> Unit,
    ) {
        fun restore() {
            pageField.text = (current.get() + 1).toString()
        }
        current.subscribe { _ -> if (!pageField.isFocused) restore() }
        pageField.focusedProperty().subscribe { focused -> if (!focused) restore() }
        pageField.addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            when (event.code) {
                KeyCode.ENTER -> pageFieldTarget(pageField.text, pageCount())?.let(jump)
                KeyCode.ESCAPE -> Unit
                else -> return@addEventHandler
            }
            event.consume()
            restore()
            done()
        }
        restore()
    }

    /** Focuses the page field with its number selected (Shortcut+G). */
    fun focusPageField() {
        pageField.requestFocus()
        pageField.selectAll()
    }

    /** A slot for another feature's buttons; it centres them, as the toolbar row stretches it to the row's height. */
    private fun slot() =
        HBox().apply {
            styleClass += "pdf-slot"
            alignment = Pos.CENTER_LEFT
        }
}

/**
 * The page (0-based) that the page field's [text] asks for: the number it holds, kept within 1 and [pageCount]; `null`
 * if it holds no whole number.
 */
internal fun pageFieldTarget(
    text: String,
    pageCount: Int,
): Int? {
    val number = text.trim().toBigIntegerOrNull() ?: return null
    return number.coerceIn(BigInteger.ONE, pageCount.toBigInteger()).toInt() - 1
}
