package dev.sluide.pdf.view

import dev.sluide.fileviewers.zoom.ACTUAL_SIZE_KEY
import dev.sluide.fileviewers.zoom.FIT_KEY
import dev.sluide.fileviewers.zoom.ZOOM_CONTROLS_STYLESHEET
import dev.sluide.fileviewers.zoom.ZOOM_IN_KEY
import dev.sluide.fileviewers.zoom.ZOOM_OUT_KEY
import dev.sluide.fileviewers.zoom.ZoomKeyAction
import dev.sluide.fileviewers.zoom.ZoomSteps
import dev.sluide.fileviewers.zoom.zoomKeyAction
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.OverrunStyle
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.text.Text
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignM
import kotlin.math.ceil

/**
 * The zoom group of the PDF view (the previews' look and keys, ADR 0192): zoom out, the level, zoom in, 100%, and the
 * Fit Width and Fit Page toggles, which mirror the fit mode of the [column]'s zoom. [install] adds the keys
 * (Shortcut+Plus/Minus, Shortcut+0 for 100%, Shortcut+9 for Fit Page). FX thread only.
 */
internal class PdfZoom(
    private val column: PageColumn,
) {
    val level =
        Label().apply {
            styleClass += "zoom-level"
            alignment = Pos.CENTER
            textOverrun = OverrunStyle.CLIP

            // One width for every text, so that the buttons beside it stay in place: that of the widest, in any font.
            fun fixWidth() {
                val widest = Text(ZoomSteps.label(ZoomSteps.MAX)).also { it.font = font }
                val width = ceil(widest.layoutBounds.width + insets.left + insets.right)
                minWidth = width
                prefWidth = width
                maxWidth = width
            }
            fontProperty().subscribe { _ -> fixWidth() }
            insetsProperty().subscribe { _ -> fixWidth() }
        }
    val zoomOutButton =
        button(FontIcon(MaterialDesignM.MAGNIFY_MINUS_OUTLINE), "Zoom Out (${ZOOM_OUT_KEY.displayText})")
    val zoomInButton = button(FontIcon(MaterialDesignM.MAGNIFY_PLUS_OUTLINE), "Zoom In (${ZOOM_IN_KEY.displayText})")
    val actualSizeButton =
        Button("100%").apply {
            styleClass += "zoom-button"
            isFocusTraversable = false
            tooltip = Tooltip("Actual Size (${ACTUAL_SIZE_KEY.displayText})")
        }
    val fitWidthButton = toggle("Fit Width", "Fit Width")
    val fitPageButton = toggle("Fit Page", "Fit Page (${FIT_KEY.displayText})")

    val node: Node =
        HBox(zoomOutButton, level, zoomInButton, actualSizeButton, fitWidthButton, fitPageButton).apply {
            styleClass += "zoom-controls"
            alignment = Pos.CENTER_LEFT
            // It carries its look from file-viewers, whose stylesheet the view does not load.
            stylesheets += ZOOM_CONTROLS_STYLESHEET
        }

    init {
        zoomOutButton.setOnAction { column.zoomOut() }
        zoomInButton.setOnAction { column.zoomIn() }
        actualSizeButton.setOnAction { column.actualSize() }
        // Clicking a selected toggle would deselect it: they mirror the fit mode instead.
        fitWidthButton.setOnAction { fit(FitMode.WIDTH) }
        fitPageButton.setOnAction { fit(FitMode.PAGE) }
        column.zoomed += ::sync
        sync()
    }

    /** Zooms with the keys anywhere in [target], the view. */
    fun install(target: Node) = target.addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)

    private fun onKey(event: KeyEvent) {
        if (!event.isShortcutDown) return
        when (zoomKeyAction(event.code, fits = true)) {
            ZoomKeyAction.ZOOM_IN -> column.zoomIn()
            ZoomKeyAction.ZOOM_OUT -> column.zoomOut()
            ZoomKeyAction.ACTUAL_SIZE -> column.actualSize()
            ZoomKeyAction.FIT -> column.fit(FitMode.PAGE)
            null -> return
        }
        event.consume()
    }

    private fun fit(mode: FitMode) {
        column.fit(mode)
        sync()
    }

    /** Shows the scale and the fit mode in effect. */
    private fun sync() {
        level.text = ZoomSteps.label(column.zoom.scale)
        fitWidthButton.isSelected = column.zoom.fit == FitMode.WIDTH
        fitPageButton.isSelected = column.zoom.fit == FitMode.PAGE
    }

    private fun button(
        icon: Node,
        tip: String,
    ) = Button(null, icon).apply {
        styleClass += "zoom-button"
        isFocusTraversable = false
        tooltip = Tooltip(tip)
    }

    private fun toggle(
        text: String,
        tip: String,
    ) = ToggleButton(text).apply {
        styleClass += "zoom-button"
        isFocusTraversable = false
        tooltip = Tooltip(tip)
    }
}
