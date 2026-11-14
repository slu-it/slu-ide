package dev.sluide.imagelimits

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** The outcome of [ImageLimits.check]. */
sealed interface ImageCheck {
    /** The image is within its cap and may be decoded. */
    data class Fits(
        val header: ImageHeader,
    ) : ImageCheck

    /** The image claims more pixels than [limitPixels], the cap of its kind; it must not be decoded. */
    data class TooLarge(
        val header: ImageHeader,
        val limitPixels: Long,
    ) : ImageCheck

    /** The header could not be read: the image is refused, as its size is unknown (fail closed). */
    data object Unreadable : ImageCheck
}

/**
 * The size caps of images from outside the app (files, downloads), checked from the header before anything is decoded
 * (ADR 0175, which amends ADR 0174).
 *
 * The caps exist because JavaFX allocates the whole image before it scales it: a PNG, a BMP, every frame of a GIF
 * and a progressive JPEG (it buffers all its coefficients) take their full pixel count in memory whatever size is
 * requested, and only a baseline JPEG decodes scaled, row by row. A file of a few hundred KB can claim 30 000 × 30 000
 * pixels and fill the heap. The caps limit what is attempted; they are no guarantee about memory.
 */
object ImageLimits {
    /**
     * Pixels above which an image is refused: PNG, BMP, GIF (all frames together) and progressive JPEG. 8K
     * screenshots fit.
     */
    const val MAX_PIXELS = 40_000_000L

    /** Pixels above which a baseline JPEG is refused; JavaFX decodes that one scaled, row by row. */
    const val MAX_JPEG_PIXELS = 100_000_000L

    /** The most bytes [readFile] reads by default: 20 MiB. */
    const val MAX_FILE_BYTES = 20 * 1024 * 1024

    /**
     * Whether the image in [bytes] may be decoded: frames × width × height from its header against
     * [MAX_JPEG_PIXELS] for a baseline JPEG, else [MAX_PIXELS]. A header that cannot be read is [ImageCheck.Unreadable].
     * At the cap fits, one pixel more does not. Reads only the header; cheap enough for the FX thread.
     */
    fun check(bytes: ByteArray): ImageCheck {
        val header = ImageHeaders.read(bytes) ?: return ImageCheck.Unreadable
        val limit = if (header.baselineJpeg) MAX_JPEG_PIXELS else MAX_PIXELS
        val pixels = header.frames.toLong() * header.width * header.height
        return if (pixels > limit) ImageCheck.TooLarge(header, limit) else ImageCheck.Fits(header)
    }

    /**
     * The size of [header] in words, thousands grouped with a space: "30 000 × 30 000 pixels", or for an animated
     * image "60 frames of 4 000 × 4 000 pixels". [unit] replaces "pixels" (e.g. "px").
     */
    fun sizeText(
        header: ImageHeader,
        unit: String = "pixels",
    ): String {
        val size = "${grouped(header.width)} × ${grouped(header.height)} $unit"
        return if (header.frames > 1) "${grouped(header.frames)} frames of $size" else size
    }

    /**
     * The bytes of the regular file [path], or null when it holds more than [maxBytes], is no regular file (a folder,
     * a pipe, a device) or cannot be read. At most [maxBytes] + 1 bytes are read, never the whole file, so a growing
     * or huge file cannot fill the memory. Blocks; call it off the FX thread.
     *
     * @throws IllegalArgumentException when [maxBytes] is negative or [Int.MAX_VALUE], as one byte more is read.
     */
    fun readFile(
        path: Path,
        maxBytes: Int = MAX_FILE_BYTES,
    ): ByteArray? {
        require(maxBytes in 0 until Int.MAX_VALUE) { "maxBytes must be at least 0 and below Int.MAX_VALUE" }
        return try {
            if (!Files.isRegularFile(path)) return null
            Files.newInputStream(path).use { it.readNBytes(maxBytes + 1) }.takeIf { it.size <= maxBytes }
        } catch (_: IOException) {
            null
        }
    }
}

private fun grouped(count: Number): String = "%,d".format(Locale.ROOT, count.toLong()).replace(',', ' ')
