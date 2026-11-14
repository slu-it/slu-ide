package dev.sluide.imagelimits

/** The image formats whose header [ImageHeaders] reads. */
enum class ImageFormat { PNG, JPEG, GIF, BMP, WEBP, ICO }

/**
 * What the header of an image says: its size, and what decides how much memory decoding takes.
 *
 * @property width the width in pixels, always positive; of an ICO, the width of its largest entry.
 * @property height the height in pixels, always positive; of an ICO, the height of its largest entry.
 * @property frames the frames of an animated GIF or WebP that were counted, the entries of an ICO, else 1.
 * @property format the format the header was read as.
 * @property baselineJpeg a JPEG that is not progressive: JavaFX decodes it scaled.
 * @property pixels the pixels decoding may allocate, which [ImageLimits.check] caps: frames × width × height, but of
 *   an ICO the sum of all its entries' pixels, since a decoder may decode any of them. [Long.MAX_VALUE] when larger.
 */
data class ImageHeader(
    val width: Int,
    val height: Int,
    val frames: Int,
    val format: ImageFormat,
    val baselineJpeg: Boolean,
    val pixels: Long = timesOrMax(timesOrMax(frames.toLong(), width.toLong()), height.toLong()),
)

/** [a] × [b] for values of at least 0, or [Long.MAX_VALUE] when that does not fit. */
internal fun timesOrMax(
    a: Long,
    b: Long,
): Long = if (a != 0L && b > Long.MAX_VALUE / a) Long.MAX_VALUE else a * b

/** [a] + [b] for values of at least 0, or [Long.MAX_VALUE] when that does not fit. */
internal fun plusOrMax(
    a: Long,
    b: Long,
): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b
