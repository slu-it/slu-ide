package dev.sluide.ebooks.view

import javafx.scene.Node
import javafx.scene.control.Label

/**
 * The text that stands in the page area when the first page cannot be built (a chapter too large, too deeply nested):
 * the toolbar and Contents stay usable, unlike under the opaque loading overlay. [page] is hidden while it shows.
 */
internal class PageNotice(
    private val page: Node,
) {
    val label = Label().apply { styleClass += listOf("ebook-overlay-text", "ebook-page-notice") }

    init {
        label.isVisible = false
    }

    fun show(text: String) {
        page.isVisible = false
        label.text = text
        label.isVisible = true
    }

    fun hide() {
        label.isVisible = false
        page.isVisible = true
    }
}
