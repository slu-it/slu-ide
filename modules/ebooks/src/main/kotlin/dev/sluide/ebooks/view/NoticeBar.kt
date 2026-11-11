package dev.sluide.ebooks.view

import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC

/** The closable bar above the toolbar with a one-line notice (fixed-layout book, a page that could not be built). */
internal class NoticeBar : HBox() {
    private val label = Label()

    init {
        val close =
            Button(null, FontIcon(MaterialDesignC.CLOSE)).apply {
                isFocusTraversable = false
                styleClass += "ebook-button"
                setOnAction { show(false) }
            }
        val spacer = Region().also { setHgrow(it, Priority.ALWAYS) }
        children.setAll(label, spacer, close)
        styleClass += "ebook-notice-bar"
        show(false)
    }

    fun show(text: String) {
        label.text = text
        show(true)
    }

    private fun show(visible: Boolean) {
        isVisible = visible
        isManaged = visible
    }
}
