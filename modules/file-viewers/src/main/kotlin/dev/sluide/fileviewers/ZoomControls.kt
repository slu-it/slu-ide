package dev.sluide.fileviewers

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.input.ZoomEvent
import javafx.scene.layout.HBox
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignM
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** The zoom levels of images and diagrams (ADR 0033), as scales: 1.0 is 100%. */
internal object ZoomSteps {
    const val MIN = 0.1
    const val MAX = 16.0

    /** The levels zooming in and out steps through. */
    val presets = listOf(0.1, 0.25, 0.33, 0.5, 0.67, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0)

    /** How far apart (relative) a scale and a preset may be and still count as the same. */
    private const val TOLERANCE = 1e-3

    /**
     * [scale] as a fixed scale: within [MIN] and [MAX], but not above [current] when that is below [MIN] (a fit of huge
     * content), so that zooming out never enlarges. As `sluide.zoomBy` in `preview.js` does.
     */
    fun clamp(
        scale: Double,
        current: Double = MAX,
    ): Double = scale.coerceIn(min(MIN, current), MAX)

    /** The next preset above [scale], or [MAX]. */
    fun zoomIn(scale: Double): Double = presets.firstOrNull { it > scale * (1 + TOLERANCE) } ?: MAX

    /**
     * The next preset below [scale], or [MIN]; [scale] itself when it is below [MIN] already (a fit of huge content).
     */
    fun zoomOut(scale: Double): Double = presets.lastOrNull { it < scale * (1 - TOLERANCE) } ?: min(scale, MIN)

    /** [scale] in percent, e.g. `67%`. */
    fun label(scale: Double): String = "${(scale * 100).roundToInt()}%"
}

/**
 * The zoom of a page with an image or a diagram (`sluide.setZoom` in `preview.js`, ADR 0033): a toolbar [node] with
 * zoom out, the [level], zoom in, 100% and Fit, and the keys, Shortcut+wheel and pinch on the web view ([install]).
 * The page sizes the image itself and reports every change ([zoomed]), also its own (a click, a resize while fitted,
 * a diagram drawn again). Views open fitted; the zoom is applied again to every page loaded ([applyTo]).
 *
 * Scripts run only while [isLoaded] says the [engine] shows the view's own page. Everything runs on the FX thread.
 */
internal class ZoomControls(
    private val engine: WebEngine,
    private val isLoaded: () -> Boolean,
) {
    /** Whether the image is fitted to the view; else it is shown at [scale]. */
    var fit = true
        private set

    /** The scale in effect, as the page last reported it (or as last asked for). */
    var scale = 1.0
        private set

    val level = Label(ZoomSteps.label(scale)).apply { styleClass += "zoom-level" }
    val zoomOutButton = button(MaterialDesignM.MAGNIFY_MINUS_OUTLINE, "Zoom out", ZOOM_OUT_KEY, ::zoomOut)
    val zoomInButton = button(MaterialDesignM.MAGNIFY_PLUS_OUTLINE, "Zoom in", ZOOM_IN_KEY, ::zoomIn)
    val actualSizeButton =
        Button("100%").apply {
            styleClass += "zoom-button"
            isFocusTraversable = false
            tooltip = Tooltip("Actual size (${ACTUAL_SIZE_KEY.displayText})")
            setOnAction { actualSize() }
        }
    val fitButton =
        ToggleButton("Fit").apply {
            styleClass += "zoom-button"
            isFocusTraversable = false
            tooltip = Tooltip("Fit to the view (${FIT_KEY.displayText})")
            isSelected = true
            // Clicking it while fitted would deselect it: it mirrors fit instead.
            setOnAction { fitToView() }
        }

    val node: Node =
        HBox(zoomOutButton, level, zoomInButton, actualSizeButton, fitButton).apply {
            styleClass += "zoom-controls"
            alignment = Pos.CENTER_LEFT
        }

    init {
        node.addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)
    }

    /** Applies the zoom to the page that just loaded. */
    fun applyTo() = setZoom(if (fit) "null" else "$scale")

    /** Called by the page (`sluideBridge.zoomed`) after it applied the zoom; a scale that is no size is ignored. */
    fun zoomed(
        scale: Double,
        fitted: Boolean,
    ) {
        if (scale.isFinite() && scale > 0) show(scale, fitted)
    }

    fun zoomIn() = zoomTo(ZoomSteps.zoomIn(scale))

    fun zoomOut() = zoomTo(ZoomSteps.zoomOut(scale))

    fun actualSize() = zoomTo(1.0)

    fun fitToView() {
        show(scale, fitted = true)
        setZoom("null")
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
            when (event.code) {
                KeyCode.PLUS, KeyCode.EQUALS, KeyCode.ADD -> ::zoomIn
                KeyCode.MINUS, KeyCode.SUBTRACT -> ::zoomOut
                KeyCode.DIGIT0, KeyCode.NUMPAD0 -> ::actualSize
                KeyCode.DIGIT9, KeyCode.NUMPAD9 -> ::fitToView
                else -> null
            }
        if (action != null) {
            action()
            event.consume()
        }
    }

    /** Zooms to [target], keeping the middle of the view in place. */
    private fun zoomTo(target: Double) {
        show(ZoomSteps.clamp(target, scale), fitted = false)
        setZoom("$scale, window.innerWidth / 2, window.innerHeight / 2")
    }

    /** Zooms by [factor], keeping the point at [x], [y] (web view coordinates, the page's viewport) in place. */
    private fun zoomBy(
        factor: Double,
        x: Double,
        y: Double,
    ) {
        if (factor.isFinite() && factor > 0) run("sluide.zoomBy($factor, $x, $y)")
    }

    private fun setZoom(arguments: String) = run("sluide.setZoom($arguments)")

    /** Runs [call] on the view's own page, if it is loaded and has the zoom. */
    private fun run(call: String) {
        if (isLoaded()) engine.executeScript("if (window.sluide && sluide.setZoom) $call")
    }

    private fun show(
        scale: Double,
        fitted: Boolean,
    ) {
        this.scale = scale
        fit = fitted
        level.text = ZoomSteps.label(scale)
        fitButton.isSelected = fitted
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

    private companion object {
        val ZOOM_IN_KEY = KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN)
        val ZOOM_OUT_KEY = KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN)
        val ACTUAL_SIZE_KEY = KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN)
        val FIT_KEY = KeyCodeCombination(KeyCode.DIGIT9, KeyCombination.SHORTCUT_DOWN)
    }
}
