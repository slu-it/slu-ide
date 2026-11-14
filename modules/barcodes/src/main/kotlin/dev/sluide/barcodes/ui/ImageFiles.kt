package dev.sluide.barcodes.ui

import dev.sluide.barcodes.engine.ArgbImage
import dev.sluide.barcodes.engine.Reader
import dev.sluide.imagelimits.ImageCheck
import dev.sluide.imagelimits.ImageFormat
import dev.sluide.imagelimits.ImageHeader
import dev.sluide.imagelimits.ImageLimits
import dev.sluide.imagelimits.ImageLimits.MAX_FILE_BYTES
import dev.sluide.imagelimits.ImageLimits.MAX_PIXELS
import javafx.scene.image.Image
import javafx.scene.image.PixelFormat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt

internal const val UNREADABLE = "The image could not be read."
internal const val ANIMATED = "Animated GIFs cannot be read; save one frame as PNG."
internal const val TOO_LARGE = "The image is larger than 20 MiB."

/** The longest side an image file is decoded to: the side the reader scales down to anyway, so one constant decides. */
private const val MAX_DECODE_SIDE = Reader.MAX_SIDE

/** The result of reading an image file: its pixels, or the message to show. */
internal sealed interface ImageLoad {
    data class Loaded(
        val image: ArgbImage,
    ) : ImageLoad

    data class Failed(
        val message: String,
    ) : ImageLoad
}

/**
 * The size an image of [width] × [height] is decoded at: null when it is at most [MAX_DECODE_SIDE] on its longer side,
 * else scaled down to fit that, with the aspect ratio kept. Never upscales.
 */
internal fun decodeSize(
    width: Int,
    height: Int,
): Pair<Int, Int>? {
    val longer = max(width, height)
    if (longer <= MAX_DECODE_SIDE) return null
    val scale = MAX_DECODE_SIDE.toDouble() / longer
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/** The message for an image of the size in [header] over [limit] pixels. */
internal fun sizeMessage(
    header: ImageHeader,
    limit: Long,
): String = "The image is too large to read (${ImageLimits.sizeText(header)}; at most ${limit / 1_000_000} megapixels)."

/**
 * Reads the PNG, JPEG, GIF or BMP file [path] into pixels. Refused: above 20 MiB (at most that is read, so a pipe or
 * a growing file cannot fill the memory), anything but a regular file, and, from the header and before anything is
 * decoded, more than the caps of [ImageLimits] (all frames of a GIF together; a baseline JPEG: 100 megapixels), and
 * an animated GIF (after the size check). What is decoded is no larger than [MAX_DECODE_SIDE] on the longer side.
 * JavaFX's `Image` decodes, which may happen on any thread. Blocks; call it off the FX thread.
 */
internal fun loadImageFile(path: Path): ImageLoad {
    val bytes = ImageLimits.readFile(path) ?: return ImageLoad.Failed(if (isOverLimit(path)) TOO_LARGE else UNREADABLE)
    val header =
        when (val check = ImageLimits.check(bytes)) {
            is ImageCheck.Fits -> check.header
            is ImageCheck.TooLarge -> return ImageLoad.Failed(sizeMessage(check.header, check.limitPixels))
            ImageCheck.Unreadable -> return ImageLoad.Failed(UNREADABLE)
        }
    // JavaFX gives no pixels for an animated image: refuse it now, not after decoding every frame.
    if (header.frames > 1) return ImageLoad.Failed(ANIMATED)
    val target = decodeSize(header.width, header.height)
    val image =
        try {
            if (target == null) {
                Image(ByteArrayInputStream(bytes))
            } else {
                Image(ByteArrayInputStream(bytes), target.first.toDouble(), target.second.toDouble(), true, true)
            }
        } catch (e: Throwable) {
            // Also an OutOfMemoryError: the user sees the failure, not a status stuck on "Reading…".
            logReadFailure(log, e)
            return ImageLoad.Failed(UNREADABLE)
        }
    return argbOf(image)
}

/** The pixels of [image]; failed when it did not load, has no size or pixel reader, or has over [MAX_PIXELS] pixels. */
internal fun argbOf(image: Image?): ImageLoad {
    if (image == null || image.isError) return ImageLoad.Failed(UNREADABLE)
    val width = image.width.toInt()
    val height = image.height.toInt()
    val reader = image.pixelReader
    if (width <= 0 || height <= 0 || reader == null) return ImageLoad.Failed(UNREADABLE)
    if (width.toLong() * height > MAX_PIXELS) {
        // Decoded pixels: the format no longer matters, PNG gives the plain wording.
        return ImageLoad.Failed(sizeMessage(ImageHeader(width, height, 1, ImageFormat.PNG, false), MAX_PIXELS))
    }
    val argb = IntArray(width * height)
    reader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width)
    return ImageLoad.Loaded(ArgbImage(width, height, argb))
}

/**
 * Whether [path] is a regular file over [MAX_FILE_BYTES], to tell "too large" from "unreadable" after a refused read.
 */
private fun isOverLimit(path: Path) =
    try {
        Files.isRegularFile(path) && Files.size(path) > MAX_FILE_BYTES
    } catch (_: IOException) {
        false
    }

private val log = System.getLogger("dev.sluide.barcodes.ui.ImageFiles")

/** Logs why a read failed (ADR 0060): an out-of-memory as a warning, anything else as an error with its stack trace. */
internal fun logReadFailure(
    logger: System.Logger,
    error: Throwable,
) {
    if (error is OutOfMemoryError) {
        logger.log(System.Logger.Level.WARNING, "Not enough memory to read an image")
    } else {
        logger.log(System.Logger.Level.ERROR, "Reading an image failed", error)
    }
}
