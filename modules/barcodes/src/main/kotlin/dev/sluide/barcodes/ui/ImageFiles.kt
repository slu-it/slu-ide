package dev.sluide.barcodes.ui

import dev.sluide.barcodes.engine.ArgbImage
import dev.sluide.barcodes.engine.Reader
import dev.sluide.barcodes.engine.grouped
import javafx.scene.image.Image
import javafx.scene.image.PixelFormat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal const val UNREADABLE = "The image could not be read."
internal const val ANIMATED = "Animated GIFs cannot be read; save one frame as PNG."
internal const val TOO_LARGE = "The image is larger than 20 MiB."

private const val MAX_FILE_BYTES = 20 * 1024 * 1024

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

/**
 * Pixels above which an image is refused: JavaFX allocates the whole PNG, BMP or GIF frame before it scales it (a GIF
 * of every frame at full size), and a progressive JPEG buffers all its coefficients. Also the limit of a pasted or
 * dropped image, which is already decoded. 8K screenshots fit.
 */
internal const val MAX_PIXELS = 40_000_000L

/** Pixels above which a baseline JPEG is refused; JavaFX decodes that one scaled, row by row. */
internal const val MAX_JPEG_PIXELS = 100_000_000L

/** The message for an image of [width] × [height] pixels ([frames] of them when animated) over [limit] pixels. */
internal fun sizeMessage(
    width: Int,
    height: Int,
    limit: Long,
    frames: Int = 1,
): String {
    val size = "${grouped(width)} × ${grouped(height)} pixels"
    val what = if (frames > 1) "${grouped(frames)} frames of $size" else size
    return "The image is too large to read ($what; at most ${limit / 1_000_000} megapixels)."
}

/**
 * Reads the PNG, JPEG, GIF or BMP file [path] into pixels. Refused: above 20 MiB (at most that is read, so a pipe or
 * a growing file cannot fill the memory), anything but a regular file, and, from the header and before anything is
 * decoded, more than [MAX_PIXELS] pixels (all frames of a GIF together; a baseline JPEG: [MAX_JPEG_PIXELS]),
 * and an animated GIF (after the size check). What is decoded is no larger than
 * [MAX_DECODE_SIDE] on the longer side. JavaFX's `Image` decodes, which may happen on any thread. Blocks; call it off
 * the FX thread.
 */
internal fun loadImageFile(path: Path): ImageLoad {
    val bytes =
        try {
            if (!Files.isRegularFile(path)) return ImageLoad.Failed(UNREADABLE)
            Files.newInputStream(path).use { it.readNBytes(MAX_FILE_BYTES + 1) }
        } catch (_: IOException) {
            return ImageLoad.Failed(UNREADABLE)
        }
    if (bytes.size > MAX_FILE_BYTES) return ImageLoad.Failed(TOO_LARGE)
    val header = imageHeader(bytes) ?: return ImageLoad.Failed(UNREADABLE)
    val (width, height) = header.width to header.height
    val limit = if (header.baselineJpeg) MAX_JPEG_PIXELS else MAX_PIXELS
    if (header.frames * width.toLong() * height > limit) {
        return ImageLoad.Failed(sizeMessage(width, height, limit, header.frames))
    }
    // JavaFX gives no pixels for an animated image: refuse it now, not after decoding every frame.
    if (header.frames > 1) return ImageLoad.Failed(ANIMATED)
    val target = decodeSize(width, height)
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
    if (width.toLong() * height > MAX_PIXELS) return ImageLoad.Failed(sizeMessage(width, height, MAX_PIXELS))
    val argb = IntArray(width * height)
    reader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width)
    return ImageLoad.Loaded(ArgbImage(width, height, argb))
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

/** What the header of an image says: its size, and what decides how much memory decoding takes. */
internal class ImageHeader(
    val width: Int,
    val height: Int,
    /** The frames of a GIF that were counted (1 for the other formats). */
    val frames: Int = 1,
    /** A JPEG that is not progressive: JavaFX decodes it scaled. */
    val baselineJpeg: Boolean = false,
)

/** Width and height from the header of a PNG, GIF, BMP or JPEG in [b], or null (see [imageHeader]). */
internal fun imageDimensions(b: ByteArray): Pair<Int, Int>? = imageHeader(b)?.let { it.width to it.height }

/**
 * The header of the PNG, GIF, BMP or JPEG in [b], or null when it is none of them, cut short or malformed. For a GIF
 * the blocks are walked to count the frames.
 */
internal fun imageHeader(b: ByteArray): ImageHeader? {
    val header =
        when {
            b.size >= 24 && u8(
                b,
                0,
            ) == 0x89 && u8(b, 1) == 'P'.code && u8(b, 2) == 'N'.code && u8(b, 3) == 'G'.code -> {
                ImageHeader(be32(b, 16), be32(b, 20))
            }

            b.size >= 13 && u8(b, 0) == 'G'.code && u8(b, 1) == 'I'.code && u8(b, 2) == 'F'.code -> {
                gifHeader(b)
            }

            b.size >= 26 && u8(b, 0) == 'B'.code && u8(b, 1) == 'M'.code -> {
                if (le32(b, 14) == 12) {
                    ImageHeader(le16(b, 18), le16(b, 20))
                } else {
                    ImageHeader(le32(b, 18), abs(le32(b, 22)))
                }
            }

            u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 -> {
                jpegHeader(b)
            }

            else -> {
                null
            }
        }
    return header?.takeIf { it.width > 0 && it.height > 0 }
}

/**
 * The screen size of the GIF in [b] and its number of frames, found by walking the blocks: colour tables,
 * extensions and their sub-blocks, image descriptors with their data. Null when a block is cut short or unknown, or
 * there is no trailer. Every step moves forward, so the walk ends within the data.
 */
private fun gifHeader(b: ByteArray): ImageHeader? {
    var at = 13 + colorTableBytes(u8(b, 10))
    var frames = 0
    while (at < b.size) {
        when (u8(b, at)) {
            0x3B -> {
                return if (frames > 0) ImageHeader(le16(b, 6), le16(b, 8), frames) else null
            }

            0x21 -> {
                at = skipSubBlocks(b, at + 2) ?: return null
            }

            0x2C -> {
                if (at + 10 > b.size) return null
                at += 10 + colorTableBytes(u8(b, at + 9))
                // The minimum code size byte, then the data.
                at = skipSubBlocks(b, at + 1) ?: return null
                frames++
            }

            else -> {
                return null
            }
        }
    }
    return null
}

/** The bytes of a colour table whose flags byte is [packed]: none without the table flag (0x80). */
private fun colorTableBytes(packed: Int) = if (packed >= 0 && packed and 0x80 != 0) 3 shl ((packed and 7) + 1) else 0

/** The position after the chain of sub-blocks at [start] (lengths, 0 ends it), or null when the data ends first. */
private fun skipSubBlocks(
    b: ByteArray,
    start: Int,
): Int? {
    var at = start
    while (true) {
        val length = u8(b, at)
        if (length < 0) return null
        at += 1 + length
        if (length == 0) return at
    }
}

private fun u8(
    b: ByteArray,
    i: Int,
) = if (i in b.indices) b[i].toInt() and 0xFF else -1

private fun be16(
    b: ByteArray,
    i: Int,
) = (u8(b, i) shl 8) or u8(b, i + 1)

private fun be32(
    b: ByteArray,
    i: Int,
) = (be16(b, i) shl 16) or be16(b, i + 2)

private fun le16(
    b: ByteArray,
    i: Int,
) = u8(b, i) or (u8(b, i + 1) shl 8)

private fun le32(
    b: ByteArray,
    i: Int,
) = le16(b, i) or (le16(b, i + 2) shl 16)

/** Walks the JPEG's segments to the first start-of-frame marker, which holds the size and says if it is progressive. */
private fun jpegHeader(b: ByteArray): ImageHeader? {
    var at = 2
    while (at + 3 < b.size) {
        if (u8(b, at) != 0xFF) {
            at++
            continue
        }
        val marker = u8(b, at + 1)
        when {
            marker == 0xFF -> {
                at++
            }

            marker == 0x01 || marker in 0xD0..0xD8 -> {
                at += 2
            }

            marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                return if (u8(b, at + 8) < 0) {
                    null
                } else {
                    ImageHeader(be16(b, at + 7), be16(b, at + 5), baselineJpeg = marker !in PROGRESSIVE)
                }
            }

            else -> {
                at += 2 + be16(b, at + 2)
            }
        }
    }
    return null
}

/** The start-of-frame markers of progressive JPEGs (Huffman, then arithmetic coding). */
private val PROGRESSIVE = setOf(0xC2, 0xC6, 0xCA, 0xCE)
