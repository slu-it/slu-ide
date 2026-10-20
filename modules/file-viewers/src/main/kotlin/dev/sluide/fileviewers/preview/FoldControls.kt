package dev.sluide.fileviewers.preview

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.web.WebEngine
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignE

/**
 * Collapse All and Expand All for a page with foldable `<details>` elements (`sluide.setAllOpen` in `preview.js`): a
 * toolbar [node] with the two buttons. Nothing happens unless [isLoaded] says the [engine] shows the view's own page.
 * Everything runs on the FX thread.
 */
internal class FoldControls(
    private val engine: WebEngine,
    private val isLoaded: () -> Boolean,
) {
    val collapseAllButton =
        button(MaterialDesignC.COLLAPSE_ALL_OUTLINE, "Collapse All", "preview-collapse-all", false)
    val expandAllButton = button(MaterialDesignE.EXPAND_ALL_OUTLINE, "Expand All", "preview-expand-all", true)

    val node: Node =
        HBox(collapseAllButton, expandAllButton).apply {
            styleClass += "fold-controls"
            alignment = Pos.CENTER_LEFT
        }

    /** Opens ([open]) or closes every foldable element of the page, if it is loaded. */
    fun setAllOpen(open: Boolean) {
        if (isLoaded()) engine.executeScript("if (window.sluide && sluide.setAllOpen) sluide.setAllOpen($open)")
    }

    private fun button(
        icon: Ikon,
        tooltip: String,
        styleClass: String,
        open: Boolean,
    ) = Button(null, FontIcon(icon)).apply {
        this.styleClass += listOf("fold-button", styleClass)
        isFocusTraversable = false
        this.tooltip = Tooltip(tooltip)
        setOnAction { setAllOpen(open) }
    }
}
