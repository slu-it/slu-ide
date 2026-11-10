package dev.sluide.previews.image

/** How large an SVG is drawn: at a multiple of its own size or at a given number of pixels. */
internal sealed interface PngSize {
    /** The size in pixels for an image of [naturalWidth] × [naturalHeight]. */
    fun pixels(
        naturalWidth: Long,
        naturalHeight: Long,
    ): Pair<Long, Long>

    /** [factor] times the natural size (the preview's 100% is 1). */
    data class Scale(
        val factor: Int,
    ) : PngSize {
        init {
            require(factor >= 1) { "Scale $factor" }
        }

        override fun pixels(
            naturalWidth: Long,
            naturalHeight: Long,
        ) = naturalWidth * factor to naturalHeight * factor
    }

    /** Exactly [width] × [height] pixels. */
    data class Pixels(
        val width: Int,
        val height: Int,
    ) : PngSize {
        init {
            require(width >= 1 && height >= 1) { "Size $width × $height" }
        }

        override fun pixels(
            naturalWidth: Long,
            naturalHeight: Long,
        ) = width.toLong() to height.toLong()
    }
}
