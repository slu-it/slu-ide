package dev.sluide.imagelimits

import kotlin.math.abs
import kotlin.math.max

/** Whether [b] starts as an icon file: reserved 0, then type 1 (`00 00 01 00`). */
internal fun isIco(b: ByteArray) = b.size >= 6 && u8(b, 0) == 0 && u8(b, 1) == 0 && u8(b, 2) == 1 && u8(b, 3) == 0

/**
 * The header of the icon file in [b]: its directory holds a count, then 16-byte entries with width and height (0 for
 * 256), data size and offset. An entry whose data is a PNG has that PNG's IHDR size; a BMP entry (a DIB without file
 * header) the larger of its directory size and its DIB size, the DIB's height halved as it counts the mask rows too.
 *
 * The header gives the largest entry as width × height, the entries as frames, and the sum of all entries' pixels as
 * [ImageHeader.pixels], since a decoder may decode any of them. Null when there is no entry, the directory is cut
 * short (so the count is capped by the data), or an entry's data or the size in it lies past the end.
 */
internal fun icoHeader(b: ByteArray): ImageHeader? {
    val count = le16(b, 4)
    if (count == 0 || 6L + 16L * count > b.size) return null
    var width = 0
    var height = 0
    var pixels = 0L
    for (index in 0 until count) {
        val entry = 6 + 16 * index
        val size = u32(b, entry + 8)
        val offset = u32(b, entry + 12)
        if (offset + size > b.size) return null
        val (w, h) = entrySize(b, entry, offset.toInt(), size) ?: return null
        val entryPixels = w.toLong() * h
        if (entryPixels > width.toLong() * height) {
            width = w
            height = h
        }
        pixels = plusOrMax(pixels, entryPixels)
    }
    return ImageHeader(width, height, count, ImageFormat.ICO, false, pixels)
}

/** The size of the entry at [entry] whose [size] bytes of data start at [data], or null when it cannot be read. */
private fun entrySize(
    b: ByteArray,
    entry: Int,
    data: Int,
    size: Long,
): Pair<Int, Int>? {
    if (size >= 4 && u8(b, data) == 0x89 && isTag(b, data + 1, "PNG")) {
        if (size < 24) return null
        val width = be32(b, data + 16)
        val height = be32(b, data + 20)
        return if (width > 0 && height > 0) width to height else null
    }
    if (size < 12) return null
    // A BITMAPCOREHEADER (12 bytes) has 16-bit width and height, the larger DIB headers 32-bit ones.
    val core = le32(b, data) == 12
    val dibWidth = if (core) le16(b, data + 4) else le32(b, data + 4)
    if (dibWidth <= 0) return null
    val dibHeight = if (core) le16(b, data + 6).toLong() else abs(le32(b, data + 8).toLong())
    val dibRows = (dibHeight + 1) / 2
    val width = max(u8(b, entry).takeIf { it > 0 } ?: 256, dibWidth)
    val height = max((u8(b, entry + 1).takeIf { it > 0 } ?: 256).toLong(), dibRows).toInt()
    return width to height
}
