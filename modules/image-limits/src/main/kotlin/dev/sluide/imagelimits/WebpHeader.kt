package dev.sluide.imagelimits

import kotlin.math.min

/** Whether [b] starts as a WebP file: "RIFF", the size of what follows, "WEBP". */
internal fun isWebp(b: ByteArray) = b.size >= 12 && isTag(b, 0, "RIFF") && isTag(b, 8, "WEBP")

/**
 * The header of the WebP in [b], from its first chunk: `VP8 ` (lossy) and `VP8L` (lossless) hold the image size,
 * `VP8X` the canvas size and whether it is animated, in which case its `ANMF` chunks are counted as frames. A `VP8 `
 * or `VP8L` chunk after the first (the image of an extended still WebP) widens the size to the larger of both, as a
 * decoder may allocate the image's own size whatever the canvas says.
 *
 * All chunks are walked, up to the end of the data or the RIFF size, whichever comes first. Chunk sizes are
 * little-endian and padded to even; every step moves forward by at least the 8 bytes of a chunk header. Null when a
 * chunk is cut short or claims to reach past the end, the first chunk is none of the three, an image chunk is
 * malformed, or an animation has no frame.
 */
internal fun webpHeader(b: ByteArray): ImageHeader? {
    val end = min(b.size.toLong(), 8 + u32(b, 4)).toInt()
    var at = 12
    var width = 0
    var height = 0
    var animated = false
    var frames = 0
    while (at < end) {
        if (at + 8 > end) return null
        val size = u32(b, at + 4)
        val data = at + 8
        if (data + size > end) return null
        val image =
            when {
                isTag(b, at, "VP8 ") -> lossySize(b, data, size) ?: return null
                isTag(b, at, "VP8L") -> losslessSize(b, data, size) ?: return null
                at == 12 && isTag(b, at, "VP8X") -> canvasSize(b, data, size) ?: return null
                at == 12 -> return null
                else -> null
            }
        if (image != null) {
            width = maxOf(width, image.first)
            height = maxOf(height, image.second)
        }
        if (at == 12) animated = isTag(b, at, "VP8X") && u8(b, data) and ANIMATION_FLAG != 0
        if (isTag(b, at, "ANMF")) frames++
        // Within the data, so it fits an Int; a missing pad byte at the very end ends the walk.
        at = (data + size + (size and 1)).toInt()
    }
    // No chunk at all, or an animation without frames.
    if (at == 12 || (animated && frames == 0)) return null
    return ImageHeader(width, height, if (animated) frames else 1, ImageFormat.WEBP, false)
}

/** The flag of an animated image in the `VP8X` flags byte. */
private const val ANIMATION_FLAG = 0x02

/** A lossy frame: a 3-byte frame tag, the start code `9d 01 2a`, then 14 bits each of width and height. */
private fun lossySize(
    b: ByteArray,
    data: Int,
    size: Long,
): Pair<Int, Int>? {
    if (size < 10 || u8(b, data + 3) != 0x9D || u8(b, data + 4) != 0x01 || u8(b, data + 5) != 0x2A) return null
    return (le16(b, data + 6) and 0x3FFF) to (le16(b, data + 8) and 0x3FFF)
}

/** A lossless image: the signature 0x2f, then 14 bits each of width − 1 and height − 1. */
private fun losslessSize(
    b: ByteArray,
    data: Int,
    size: Long,
): Pair<Int, Int>? {
    if (size < 5 || u8(b, data) != 0x2F) return null
    val bits = le32(b, data + 1)
    return (bits and 0x3FFF) + 1 to ((bits ushr 14) and 0x3FFF) + 1
}

/** The extended header: flags, 3 reserved bytes, then 24 bits each of canvas width − 1 and height − 1. */
private fun canvasSize(
    b: ByteArray,
    data: Int,
    size: Long,
): Pair<Int, Int>? {
    if (size < 10) return null
    return le24(b, data + 4) + 1 to le24(b, data + 7) + 1
}

private fun le24(
    b: ByteArray,
    i: Int,
) = le16(b, i) or (u8(b, i + 2) shl 16)
