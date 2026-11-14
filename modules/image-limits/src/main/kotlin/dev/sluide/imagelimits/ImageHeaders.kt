package dev.sluide.imagelimits

import kotlin.math.abs

/**
 * Reads the size of an image from its header, without decoding anything, so that [ImageLimits] can refuse an image
 * before JavaFX allocates it (ADR 0175).
 */
object ImageHeaders {
    /**
     * The header of the PNG, GIF, BMP, JPEG, WebP or ICO in [bytes], or null when it is none of them, cut short or
     * malformed. The blocks of a GIF and the chunks of a WebP are walked to count the frames, the directory of an ICO
     * to sum its entries. Never throws; reads nothing outside [bytes] and takes time bounded by its size.
     */
    fun read(bytes: ByteArray): ImageHeader? {
        val b = bytes
        val header =
            when {
                b.size >= 24 && u8(b, 0) == 0x89 && u8(b, 1) == 'P'.code && u8(b, 2) == 'N'.code &&
                    u8(b, 3) == 'G'.code -> {
                    ImageHeader(be32(b, 16), be32(b, 20), 1, ImageFormat.PNG, false)
                }

                b.size >= 13 && u8(b, 0) == 'G'.code && u8(b, 1) == 'I'.code && u8(b, 2) == 'F'.code -> {
                    gifHeader(b)
                }

                b.size >= 26 && u8(b, 0) == 'B'.code && u8(b, 1) == 'M'.code -> {
                    if (le32(b, 14) == 12) {
                        ImageHeader(le16(b, 18), le16(b, 20), 1, ImageFormat.BMP, false)
                    } else {
                        ImageHeader(le32(b, 18), abs(le32(b, 22)), 1, ImageFormat.BMP, false)
                    }
                }

                u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 -> {
                    jpegHeader(b)
                }

                isWebp(b) -> {
                    webpHeader(b)
                }

                isIco(b) -> {
                    icoHeader(b)
                }

                else -> {
                    null
                }
            }
        return header?.takeIf { it.width > 0 && it.height > 0 }
    }
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
                return if (frames > 0) ImageHeader(le16(b, 6), le16(b, 8), frames, ImageFormat.GIF, false) else null
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
                    ImageHeader(be16(b, at + 7), be16(b, at + 5), 1, ImageFormat.JPEG, marker !in PROGRESSIVE)
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
