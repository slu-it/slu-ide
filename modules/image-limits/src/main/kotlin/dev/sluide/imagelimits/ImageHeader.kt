package dev.sluide.imagelimits

/** The image formats whose header [ImageHeaders] reads. */
enum class ImageFormat { PNG, JPEG, GIF, BMP, WEBP, ICO }

/**
 * What the header of an image says: its size, and what decides how much memory decoding takes.
 *
 * @property width the width in pixels, always positive.
 * @property height the height in pixels, always positive.
 * @property frames the frames of a GIF that were counted (1 for the other formats).
 * @property format the format the header was read as.
 * @property baselineJpeg a JPEG that is not progressive: JavaFX decodes it scaled.
 */
data class ImageHeader(
    val width: Int,
    val height: Int,
    val frames: Int,
    val format: ImageFormat,
    val baselineJpeg: Boolean,
)
