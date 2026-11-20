package dev.sluide.previews.preview

import javafx.scene.control.Label
import javafx.scene.layout.HBox

/**
 * The banner above a preview's page that tells about a known limitation of that preview ([Preview.limitation]); it
 * takes no room and is not shown while there is no message. Styled by `preview-view.css` like the editor's banners.
 */
internal class LimitationBanner : HBox() {
    private val message = Label().apply { isWrapText = true }

    init {
        styleClass += "preview-banner"
        children += message
        managedProperty().bind(visibleProperty())
        isVisible = false
    }

    /** Shows [text], or hides the banner for null. */
    fun update(text: String?) {
        message.text = text
        isVisible = text != null
    }
}
