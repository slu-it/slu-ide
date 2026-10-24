package dev.sluide.previews.preview

import javafx.scene.web.WebEngine
import javafx.scene.web.WebView

/** What [ZoomControls] scale: an image or diagram in its page ([PageZoom]) or a whole document ([DocumentZoom]). */
internal sealed interface ZoomTarget {
    /** Whether the content can be fitted to the view (Fit, Shortcut+9); views that fit open fitted. */
    val fits: Boolean

    /**
     * Whether the page reports every change of the zoom itself ([ZoomControls.zoomed]); otherwise the controls show
     * the scale they asked for and ignore such reports.
     */
    val reports: Boolean

    /** Shows the content fitted to the view; only asked if it [fits]. */
    fun fit()

    /** Shows the content at [scale], keeping the middle of the view in place where the target can. */
    fun zoomTo(scale: Double)

    /**
     * Zooms by [factor] from [scale], around [x], [y] (web view coordinates) where the target can: the new scale, or
     * null when the target [reports] it itself ([ZoomControls.zoomed]).
     */
    fun zoomBy(
        factor: Double,
        x: Double,
        y: Double,
        scale: Double,
    ): Double?

    /** Applies the zoom ([scale], or fitted if [fit]) to a page that just loaded. */
    fun applyTo(
        scale: Double,
        fit: Boolean,
    )
}

/**
 * The zoom of a page with an image or a diagram (`sluide.setZoom` in `preview.js`, ADR 0033): the page sizes the
 * image itself, keeps the point under the pointer in place and reports every change ([ZoomControls.zoomed]), also its
 * own (a click, a resize while fitted, a diagram drawn again). It loses the zoom with every page loaded, so
 * [applyTo] sets it again.
 *
 * Scripts run only while [isLoaded] says the [engine] shows the view's own page.
 */
internal class PageZoom(
    private val engine: WebEngine,
    private val isLoaded: () -> Boolean,
) : ZoomTarget {
    override val fits get() = true

    override val reports get() = true

    override fun fit() = setZoom("null")

    override fun zoomTo(scale: Double) = setZoom("$scale, window.innerWidth / 2, window.innerHeight / 2")

    override fun zoomBy(
        factor: Double,
        x: Double,
        y: Double,
        scale: Double,
    ): Double? {
        run("sluide.zoomBy($factor, $x, $y)")
        return null
    }

    override fun applyTo(
        scale: Double,
        fit: Boolean,
    ) = setZoom(if (fit) "null" else "$scale")

    private fun setZoom(arguments: String) = run("sluide.setZoom($arguments)")

    /** Runs [call] on the view's own page, if it is loaded and has the zoom. */
    private fun run(call: String) {
        if (isLoaded()) engine.executeScript("if (window.sluide && sluide.setZoom) $call")
    }
}

/**
 * The zoom of a document (ADR 0080): [WebView.zoom] scales the whole page, which reflows, like a browser's zoom. It
 * belongs to the [webView], so it outlasts every page loaded, also one whose scripts are off. It does not fit, and
 * wheel and pinch zoom without keeping the point under the pointer in place.
 */
internal class DocumentZoom(
    private val webView: WebView,
) : ZoomTarget {
    override val fits get() = false

    override val reports get() = false

    /** Never asked: a document does not [fit][fits]. */
    override fun fit() = Unit

    override fun zoomTo(scale: Double) {
        webView.zoom = scale
    }

    override fun zoomBy(
        factor: Double,
        x: Double,
        y: Double,
        scale: Double,
    ): Double = ZoomSteps.clamp(scale * factor, scale).also(::zoomTo)

    /** Nothing to do: the zoom is the web view's, not the page's. */
    override fun applyTo(
        scale: Double,
        fit: Boolean,
    ) = Unit
}
