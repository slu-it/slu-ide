package dev.sluide.pdf.view

import dev.sluide.fileviewers.zoom.ZoomSteps
import dev.sluide.pdf.engine.PageSize

/** How the pages are fitted to the viewport. */
internal enum class FitMode { PAGE, WIDTH }

/** What a fit is computed for: the page, the viewport and the width a vertical scroll bar takes. */
internal data class FitTarget(
    val page: PageSize,
    val viewWidth: Double,
    val viewHeight: Double,
    val scrollBar: Double,
)

/**
 * The zoom of a PDF view: a [scale] and the [fit] mode it follows, `null` for a zoom of its own. It opens at Fit Page;
 * zooming in or out, 100% and zooming by a factor leave the fit; a resize re-fits only in a fit mode ([refit]). Steps
 * and limits are those of the previews ([ZoomSteps]). FX-free.
 */
internal class ZoomModel {
    var scale = 1.0
        private set
    var fit: FitMode? = FitMode.PAGE
        private set

    fun zoomIn() = zoomTo(ZoomSteps.zoomIn(scale))

    fun zoomOut() = zoomTo(ZoomSteps.zoomOut(scale))

    fun actualSize() = zoomTo(1.0)

    /** Multiplies the scale by [factor] (Shortcut+wheel, pinch), within the limits; a factor that is no size is ignored. */
    fun zoomBy(factor: Double) {
        if (factor.isFinite() && factor > 0) zoomTo(ZoomSteps.clamp(scale * factor, scale))
    }

    /** Fits the pages in [mode] to [target] from now on. */
    fun fit(
        mode: FitMode,
        target: FitTarget,
    ) {
        fit = mode
        scale = fitScale(mode, target)
    }

    /** Fits again after the viewport changed, in a fit mode only; whether the scale changed. */
    fun refit(target: FitTarget): Boolean {
        val mode = fit ?: return false
        val before = scale
        scale = fitScale(mode, target)
        return scale != before
    }

    private fun zoomTo(scale: Double) {
        fit = null
        this.scale = scale
    }

    private fun fitScale(
        mode: FitMode,
        target: FitTarget,
    ): Double =
        when (mode) {
            FitMode.PAGE -> PageLayout.fitPage(target.page, target.viewWidth, target.viewHeight, target.scrollBar)
            FitMode.WIDTH -> PageLayout.fitWidth(target.page, target.viewWidth, target.scrollBar)
        }
}
