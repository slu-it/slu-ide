package dev.sluide.imagelimits

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Image files written by hand for tests (PNG, GIF, BMP, JPEG, WebP, ICO): real files that decode, and headers that
 * claim any size, so that a test can hold a decompression bomb without its memory.
 */
object TestImages {
    /** An 8-bit grayscale PNG of one flat grey that decodes: tiny however large it is (a real bomb when large). */
    fun grayPng(
        width: Int,
        height: Int,
    ): ByteArray {
        val raw = ByteArray((width + 1) * height) { 0x80.toByte() }
        for (row in 0 until height) raw[row * (width + 1)] = 0
        val deflated = ByteArrayOutputStream()
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) deflated.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)

        fun chunk(
            type: String,
            data: ByteArray,
        ) {
            out.write(be32(data.size))
            val body = type.toByteArray() + data
            out.write(body)
            out.write(be32(CRC32().also { it.update(body) }.value.toInt()))
        }
        chunk("IHDR", be32(width) + be32(height) + byteArrayOf(8, 0, 0, 0, 0))
        chunk("IDAT", deflated.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** The signature and IHDR of a PNG of any claimed size, nothing else. */
    fun pngHeader(
        width: Int,
        height: Int,
    ) = PNG_SIGNATURE + be32(13) + "IHDR".toByteArray() + be32(width) + be32(height) + byteArrayOf(8, 0, 0, 0, 0)

    /**
     * A GIF with a [width] × [height] screen and [frames] tiny frames (each a valid 1 × 1 image at the corner), so
     * its block chain is real: a global colour table, one image descriptor with its data per frame, the trailer.
     */
    fun gif(
        width: Int,
        height: Int,
        frames: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.write(byteArrayOf(width.toByte(), (width shr 8).toByte(), height.toByte(), (height shr 8).toByte()))
        out.write(byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, -1, -1, -1))
        repeat(frames) {
            // A graphic control extension, then the image descriptor and its data.
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))
            out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0))
            out.write(byteArrayOf(2, 2, 0x44, 1, 0))
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    /** The file header and BITMAPINFOHEADER of a BMP of any claimed size, nothing else. */
    fun bmpHeader(
        width: Int,
        height: Int,
    ) = ByteArray(30).also {
        it[0] = 'B'.code.toByte()
        it[1] = 'M'.code.toByte()
        it[14] = 40
        for (i in 0..3) {
            it[18 + i] = (width shr (8 * i)).toByte()
            it[22 + i] = (height shr (8 * i)).toByte()
        }
    }

    /** The start of a JPEG: SOI, then a start-of-frame segment with [marker] (0xC0 baseline, 0xC2 progressive). */
    fun jpeg(
        width: Int,
        height: Int,
        marker: Int = 0xC0,
    ) = intArrayOf(0xFF, 0xD8, 0xFF, marker, 0, 11, 8, height shr 8, height and 0xFF, width shr 8, width and 0xFF, 1)
        .map { it.toByte() }
        .toByteArray()

    /** A RIFF chunk: its [tag], the little-endian size of [data], the data and a pad byte when the size is odd. */
    fun riffChunk(
        tag: String,
        data: ByteArray,
    ) = tag.toByteArray() + le32(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf()

    /** A WebP file of [chunks], with the RIFF size of what follows it. */
    fun webp(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold("WEBP".toByteArray()) { all, chunk -> all + chunk }
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    /** A lossy WebP's `VP8 ` chunk: a key frame tag, the start code and 14 bits each of width and height. */
    fun vp8(
        width: Int,
        height: Int,
    ) = riffChunk(
        "VP8 ",
        byteArrayOf(0x10, 0x02, 0x00, 0x9D.toByte(), 0x01, 0x2A) + le16(width) + le16(height) + byteArrayOf(0, 0),
    )

    /** A lossless WebP's `VP8L` chunk: the signature, then 14 bits each of width − 1 and height − 1. */
    fun vp8l(
        width: Int,
        height: Int,
    ) = riffChunk("VP8L", byteArrayOf(0x2F) + le32((width - 1) or ((height - 1) shl 14)) + byteArrayOf(0))

    /**
     * An extended WebP's `VP8X` chunk with a canvas of [width] × [height] (24 bits each) and the animation flag when
     * [animated].
     */
    fun vp8x(
        width: Int,
        height: Int,
        animated: Boolean,
    ) = riffChunk(
        "VP8X",
        byteArrayOf(if (animated) 0x02 else 0x00, 0, 0, 0) + le32(width - 1).copyOf(3) + le32(height - 1).copyOf(3),
    )

    /** An animated WebP with a [width] × [height] canvas and [frames] `ANMF` chunks of 1 × 1 lossless frames. */
    fun animatedWebp(
        width: Int,
        height: Int,
        frames: Int,
    ): ByteArray {
        val anim = riffChunk("ANIM", ByteArray(6))
        // Frame position and size (− 1, 24 bits each), duration and flags, then the frame's image chunk.
        val frame = riffChunk("ANMF", ByteArray(16) + vp8l(1, 1))
        return webp(vp8x(width, height, animated = true), anim, *Array(frames) { frame })
    }

    /** An entry of an icon file: the size its directory gives (256 is written as 0) and its image data. */
    class IcoEntry(
        val width: Int,
        val height: Int,
        val data: ByteArray,
    )

    /** An icon file: the directory of [entries], then their data in order. */
    fun ico(vararg entries: IcoEntry): ByteArray {
        var offset = 6 + 16 * entries.size
        var directory = byteArrayOf(0, 0, 1, 0) + le16(entries.size)
        for (entry in entries) {
            directory += byteArrayOf(entry.width.toByte(), entry.height.toByte(), 0, 0, 1, 0, 32, 0) +
                le32(entry.data.size) + le32(offset)
            offset += entry.data.size
        }
        return entries.fold(directory) { all, entry -> all + entry.data }
    }

    /** The BITMAPINFOHEADER of an icon's BMP entry: its height counts the mask rows too, so it is doubled. */
    fun dib(
        width: Int,
        height: Int,
    ) = le32(40) + le32(width) + le32(height * 2) + byteArrayOf(1, 0, 32, 0) + ByteArray(24)

    /** The older BITMAPCOREHEADER of an icon's BMP entry: 16-bit width and doubled height. */
    fun coreDib(
        width: Int,
        height: Int,
    ) = le32(12) + le16(width) + le16(height * 2) + byteArrayOf(1, 0, 32, 0)

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun le32(value: Int) =
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private val PNG_SIGNATURE =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    private fun be32(value: Int) =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
