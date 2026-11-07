package dev.sluide.localmodels

import java.io.IOException
import java.io.InputStream

private const val BLOCK = 512

/** GNU long names and PAX headers larger than this are refused: no tool needs them, and they are held in memory. */
private const val MAX_META_BYTES = 64 * 1024

/** Long-name and PAX headers in front of one entry; more are refused (a gzip bomb of headers would spin). */
private const val MAX_META_HEADERS = 8

/** One entry of a tar archive that [TarReader] hands out: a regular file or a directory. */
internal data class TarEntry(
    val path: String,
    val directory: Boolean,
    val mode: Int,
    val size: Long,
)

/**
 * A minimal reader of tar archives (ADR 0143): ustar and GNU headers with regular files and directories, GNU long
 * names (`L`) and PAX extended headers (`x`: `path`, `size`; global `g` headers are skipped). Links, devices, FIFOs,
 * sparse files and every other type are refused with an [ArchiveException], as are bad header checksums and an
 * archive that ends before its end marker. Paths come as written; checking them is the extractor's job.
 */
internal class TarReader(
    private val input: InputStream,
) {
    private val header = ByteArray(BLOCK)
    private var remaining = 0L
    private var padding = 0L
    private var ended = false

    /** The next entry, or null at the end marker; skips what the caller did not read of the previous entry's data. */
    fun next(): TarEntry? {
        if (ended) return null
        skipData()
        var longName: String? = null
        var pax: Map<String, String> = emptyMap()
        var metaHeaders = 0
        while (true) {
            if (!readBlock(header)) throw ArchiveException("the archive is truncated")
            if (header.all { it == 0.toByte() }) {
                ended = true
                return null
            }
            checkChecksum()
            val type = header[156].toInt().toChar()
            val size = number(124, 12, "size")
            if (type in "Lxg" && ++metaHeaders > MAX_META_HEADERS) {
                throw ArchiveException("the archive has too many extended headers in a row")
            }
            when (type) {
                '0', '\u0000', '7', '5' -> {
                    val path = pax["path"] ?: longName ?: headerName()
                    val directory = type == '5' || (type == '\u0000' && path.endsWith("/"))
                    val stored = paxSize(pax) ?: size
                    val data = if (directory) 0 else stored
                    startData(data, stored)
                    return TarEntry(path, directory, number(100, 8, "mode").toInt() and 0xFFF, data)
                }

                'L' -> {
                    longName = String(meta(size), Charsets.UTF_8).substringBefore('\u0000')
                }

                'x' -> {
                    pax = paxRecords(meta(size))
                }

                'g' -> {
                    meta(size)
                }

                '1' -> {
                    throw ArchiveException("${headerName()} is a hard link, which is not supported")
                }

                'K' -> {
                    throw ArchiveException("${headerName()} is a long link name, and links are not supported")
                }

                '2' -> {
                    throw ArchiveException("${headerName()} is a symbolic link, which is not supported")
                }

                '3', '4' -> {
                    throw ArchiveException("${headerName()} is a device, which is not supported")
                }

                '6' -> {
                    throw ArchiveException("${headerName()} is a FIFO, which is not supported")
                }

                else -> {
                    throw ArchiveException("${headerName()} has the unsupported entry type '$type'")
                }
            }
        }
    }

    /** The data of the entry [next] returned last; ends at its end. */
    val data: InputStream =
        object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (remaining <= 0) return -1
                val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
                if (n < 0) throw ArchiveException("the archive is truncated")
                remaining -= n
                return n
            }
        }

    private fun startData(
        data: Long,
        stored: Long,
    ) {
        // A directory's size is meant to be 0; whatever it holds is skipped.
        if (stored > Long.MAX_VALUE - BLOCK) throw ArchiveException("the archive has a size out of range")
        remaining = data
        padding = (stored - data) + (BLOCK - stored % BLOCK) % BLOCK
    }

    private fun skipData() {
        skip(remaining + padding)
        remaining = 0
        padding = 0
    }

    private fun meta(size: Long): ByteArray {
        if (size > MAX_META_BYTES) throw ArchiveException("the archive has an extended header of $size bytes")
        val bytes = ByteArray(size.toInt())
        if (input.readNBytes(bytes, 0, bytes.size) < bytes.size) throw ArchiveException("the archive is truncated")
        skip((BLOCK - size % BLOCK) % BLOCK)
        return bytes
    }

    private fun skip(bytes: Long) {
        var left = bytes
        val buffer = ByteArray(BLOCK * 16)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw ArchiveException("the archive is truncated")
            left -= n
        }
    }

    /** Fills [block]; false at the end of the input before its first byte, [ArchiveException] in its middle. */
    private fun readBlock(block: ByteArray): Boolean {
        val n = input.readNBytes(block, 0, BLOCK)
        if (n == 0) return false
        if (n < BLOCK) throw ArchiveException("the archive is truncated")
        return true
    }

    private fun checkChecksum() {
        val stored = number(148, 8, "checksum")
        var unsigned = 0L
        var signed = 0L
        for (i in 0 until BLOCK) {
            val b = if (i in 148 until 156) ' '.code.toByte() else header[i]
            unsigned += b.toInt() and 0xFF
            signed += b
        }
        if (stored != unsigned && stored != signed) {
            throw ArchiveException("the archive is damaged or not a tar archive")
        }
    }

    /** The name of the header, with the ustar prefix; GNU headers (`ustar  `) keep other data where the prefix is. */
    private fun headerName(): String {
        val name = text(0, 100)
        val posix = String(header, 257, 6, Charsets.ISO_8859_1) == "ustar\u0000"
        val prefix = if (posix) text(345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun text(
        offset: Int,
        length: Int,
    ): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    /** An octal field, or a GNU base-256 one (first byte with its high bit set) for large values. */
    private fun number(
        offset: Int,
        length: Int,
        field: String,
    ): Long {
        if (header[offset].toInt() and 0x80 != 0) {
            if (header[offset].toInt() and 0x40 != 0) throw ArchiveException("the archive has a negative $field")
            var value = (header[offset].toInt() and 0x3F).toLong()
            for (i in offset + 1 until offset + length) {
                if (value > Long.MAX_VALUE shr 8) throw ArchiveException("the archive has a $field out of range")
                value = (value shl 8) or (header[i].toLong() and 0xFF)
            }
            return value
        }
        val digits = String(header, offset, length, Charsets.ISO_8859_1).trim(' ', '\u0000')
        if (digits.isEmpty()) return 0
        if (digits.length > 21 || digits.any { it !in '0'..'7' }) {
            throw ArchiveException("the archive is damaged or not a tar archive")
        }
        return digits.toLong(8)
    }

    private fun paxSize(pax: Map<String, String>): Long? =
        pax["size"]?.let {
            it.toLongOrNull()?.takeIf { size -> size >= 0 } ?: throw ArchiveException("the archive has a bad size")
        }

    /** PAX records: `<length> <key>=<value>\n`, the length counting the whole record in bytes. */
    private fun paxRecords(bytes: ByteArray): Map<String, String> {
        val records = HashMap<String, String>()
        var at = 0
        while (at < bytes.size && bytes[at] != 0.toByte()) {
            var space = at
            while (space < bytes.size && bytes[space] != ' '.code.toByte()) space++
            val length = String(bytes, at, space - at, Charsets.ISO_8859_1).toIntOrNull()
            val fits = length != null && length > space - at + 1 && at + length <= bytes.size
            if (length == null || !fits || bytes[at + length - 1] != '\n'.code.toByte()) {
                throw ArchiveException("the archive has a damaged extended header")
            }
            val record = String(bytes, space + 1, at + length - space - 2, Charsets.UTF_8)
            val equals = record.indexOf('=')
            if (equals <= 0) throw ArchiveException("the archive has a damaged extended header")
            records[record.substring(0, equals)] = record.substring(equals + 1)
            at += length
        }
        return records
    }
}

/** An archive is refused or cannot be read; the message says why, readable for the user. */
internal class ArchiveException(
    message: String,
) : IOException(message)
