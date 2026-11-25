package dev.sluide.previews.preview

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
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.input.ZoomEvent
import javafx.scene.layout.HBox
import javafx.scene.text.Text
import javafx.scene.web.WebView
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignM
import kotlin.math.ceil
import kotlin.math.pow

/**
 * The zoom of a [target] (ADR 0033, ADR 0083): a toolbar [node] with zoom out, the [level], zoom in, 100% and, if the
 * target [fits][ZoomTarget.fits], Fit ([fitButton]), and the keys, Shortcut+wheel and pinch on the web view
 * ([install]). An image's or diagram's page ([PageZoom]) reports every change ([zoomed]), also its own; a document
 * ([WebViewZoomTarget]) is shown at the scale asked for. Views that fit open fitted, others at 100%; the zoom is applied
 * again to every page loaded ([applyTo]). [onZoomed] is called after every change of [scale] or [fit].
 *
 * Everything runs on the FX thread.
 */
internal class ZoomControls(
    private val target: ZoomTarget,
    private val onZoomed: () -> Unit = {},
) {
    /** Whether the content is fitted to the view; else it is shown at [scale]. Never for a target that does not fit. */
    var fit = target.fits
        private set

    /** The scale in effect, as the page last reported it (or as last asked for). */
    var scale = 1.0
        private set

    val level =
        Label(ZoomSteps.label(scale)).apply {
            styleClass += "zoom-level"
            alignment = Pos.CENTER
            // Wheel and pinch values ("1250%") can be a pixel wider than the widest preset in proportional fonts.
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
    val zoomOutButton = button(MaterialDesignM.MAGNIFY_MINUS_OUTLINE, "Zoom out", ZOOM_OUT_KEY, ::zoomOut)
    val zoomInButton = button(MaterialDesignM.MAGNIFY_PLUS_OUTLINE, "Zoom in", ZOOM_IN_KEY, ::zoomIn)
    val actualSizeButton =
        Button("100%").apply {
            styleClass += "zoom-button"
            isFocusTraversable = false
            tooltip = Tooltip("Actual size (${ACTUAL_SIZE_KEY.displayText})")
            setOnAction { actualSize() }
        }

    /** The Fit button, selected while fitted; none if the target does not [fit][ZoomTarget.fits]. */
    val fitButton =
        if (target.fits) {
            ToggleButton("Fit").apply {
                styleClass += "zoom-button"
                isFocusTraversable = false
                tooltip = Tooltip("Fit to the view (${FIT_KEY.displayText})")
                isSelected = true
                // Clicking it while fitted would deselect it: it mirrors fit instead.
                setOnAction { fitToView() }
            }
        } else {
            null
        }

    val node: Node =
        HBox(*listOfNotNull(zoomOutButton, level, zoomInButton, actualSizeButton, fitButton).toTypedArray()).apply {
            styleClass += "zoom-controls"
            alignment = Pos.CENTER_LEFT
            // It carries its look into the toolbars of other modules (the ebook reader's, through DocumentZoom).
            stylesheets += ZOOM_CONTROLS_STYLESHEET
        }

    init {
        node.addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)
    }

    /** Applies the zoom to the page that just loaded. */
    fun applyTo() = target.applyTo(scale, fit)

    /**
     * Called by the page (`sluideBridge.zoomed`) after it applied the zoom; ignored if the target does not
     * [report][ZoomTarget.reports] its zoom (a document's page has no say in it) or the scale is no size.
     */
    fun zoomed(
        scale: Double,
        fitted: Boolean,
    ) {
        if (target.reports && scale.isFinite() && scale > 0) show(scale, fitted)
    }

    fun zoomIn() = zoomTo(ZoomSteps.zoomIn(scale))

    fun zoomOut() = zoomTo(ZoomSteps.zoomOut(scale))

    fun actualSize() = zoomTo(1.0)

    /** Fits the content to the view; nothing if the target does not [fit][ZoomTarget.fits]. */
    fun fitToView() {
        if (!target.fits) return
        show(scale, fitted = true)
        target.fit()
    }

    /**
     * Zooms with the keys, Shortcut+wheel and pinching on [webView], and only there (not in the source beside it). The
     * keys work on the [node] too; its buttons never take the focus from the web view.
     */
    fun install(webView: WebView) {
        webView.addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)
        webView.addEventFilter(ScrollEvent.SCROLL) { event ->
            if (event.isShortcutDown && event.deltaY != 0.0) {
                zoomBy(1.1.pow(event.deltaY / 40), event.x, event.y)
                event.consume()
            }
        }
        webView.addEventFilter(ZoomEvent.ZOOM) { event ->
            zoomBy(event.zoomFactor, event.x, event.y)
            event.consume()
        }
    }

    private fun onKey(event: KeyEvent) {
        if (!event.isShortcutDown) return
        val action: (() -> Unit)? =
            when (zoomKeyAction(event.code, target.fits)) {
                ZoomKeyAction.ZOOM_IN -> ::zoomIn
                ZoomKeyAction.ZOOM_OUT -> ::zoomOut
                ZoomKeyAction.ACTUAL_SIZE -> ::actualSize
                ZoomKeyAction.FIT -> ::fitToView
                null -> null
            }
        if (action != null) {
            action()
            event.consume()
        }
    }

    /** Zooms to [scale], keeping the middle of the view in place where the target can. */
    private fun zoomTo(scale: Double) {
        show(ZoomSteps.clamp(scale, this.scale), fitted = false)
        target.zoomTo(this.scale)
    }

    /** Zooms by [factor], keeping the point at [x], [y] (web view coordinates) in place where the target can. */
    private fun zoomBy(
        factor: Double,
        x: Double,
        y: Double,
    ) {
        if (factor.isFinite() && factor > 0) target.zoomBy(factor, x, y, scale)?.let { show(it, fitted = false) }
    }

    private fun show(
        scale: Double,
        fitted: Boolean,
    ) {
        val changed = scale != this.scale || fitted != fit
        this.scale = scale
        fit = fitted
        level.text = ZoomSteps.label(scale)
        fitButton?.isSelected = fitted
        if (changed) onZoomed()
    }

    private fun button(
        icon: Ikon,
        tooltip: String,
        key: KeyCombination,
        action: () -> Unit,
    ) = Button(null, FontIcon(icon)).apply {
        styleClass += "zoom-button"
        isFocusTraversable = false
        this.tooltip = Tooltip("$tooltip (${key.displayText})")
        setOnAction { action() }
    }
}
