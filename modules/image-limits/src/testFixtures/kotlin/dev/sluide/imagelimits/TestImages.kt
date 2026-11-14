package dev.sluide.imagelimits

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Image files written by hand for tests: real files that decode, and headers that claim any size, so that a test can
 * hold a decompression bomb without its memory.
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

    private val PNG_SIGNATURE =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    private fun be32(value: Int) =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
