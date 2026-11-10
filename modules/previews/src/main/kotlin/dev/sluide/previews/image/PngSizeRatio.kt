package dev.sluide.previews.image

import kotlin.math.max
import kotlin.math.roundToInt

/** Keeps the aspect ratio of an image of [naturalWidth] × [naturalHeight] while one side is chosen. */
internal class PngSizeRatio(
    private val naturalWidth: Int,
    private val naturalHeight: Int,
) {
    init {
        require(naturalWidth >= 1 && naturalHeight >= 1) { "Size $naturalWidth × $naturalHeight" }
    }

    /** The height that goes with [width], at least 1; always computed from the natural ratio, so it does not drift. */
    fun heightFor(width: Int): Int = max(1, (width.toDouble() * naturalHeight / naturalWidth).roundToInt())

    /** The width that goes with [height], at least 1. */
    fun widthFor(height: Int): Int = max(1, (height.toDouble() * naturalWidth / naturalHeight).roundToInt())

    /** Whether [width] × [height] exceeds the rasteriser's limits ([exceedsLimits]). */
    fun tooLarge(
        width: Int,
        height: Int,
    ): Boolean = exceedsLimits(width.toLong(), height.toLong())
}
