package dev.sluide.localmodels

import java.io.ByteArrayOutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes tar archives header by header, so tests can build what real tools write and what they never would. */
internal class TarBuilder(
    /** GNU headers (`ustar  `, as GitHub release archives have), else POSIX ustar ones (`ustar\0` + `00`). */
    private val gnu: Boolean = false,
) {
    private val out = ByteArrayOutputStream()

    fun file(
        name: String,
        data: ByteArray,
        mode: Int = "644".toInt(8),
    ) = entry(name, '0', data, mode)

    fun dir(name: String) = entry(name, '5', ByteArray(0), "755".toInt(8))

    /** A GNU long-name header for the entry that follows. */
    fun longName(name: String) = entry("././@LongLink", 'L', (name + "\u0000").toByteArray())

    /** A PAX extended header (`x`) for the entry that follows, or a global one (`g`). */
    fun pax(
        vararg records: Pair<String, String>,
        global: Boolean = false,
    ): TarBuilder {
        val data = records.joinToString("") { record(it.first, it.second) }.toByteArray()
        return entry("PaxHeader", if (global) 'g' else 'x', data)
    }

    /** Any header; [size] may differ from the data written (a PAX size overrides it), [sizeField] replaces its field. */
    fun entry(
        name: String,
        type: Char,
        data: ByteArray,
        mode: Int = "644".toInt(8),
        size: Long = data.size.toLong(),
        prefix: String = "",
        filler: ByteArray? = null,
        sizeField: ByteArray? = null,
    ): TarBuilder {
        out.write(header(name, type, mode, size, prefix, filler, sizeField))
        out.write(data)
        out.write(ByteArray((512 - data.size % 512) % 512))
        return this
    }

    /** The archive with its end marker (two zero blocks). */
    fun bytes(): ByteArray = out.toByteArray() + ByteArray(1024)

    /** The archive without its end marker. */
    fun unterminated(): ByteArray = out.toByteArray()

    private fun header(
        name: String,
        type: Char,
        mode: Int,
        size: Long,
        prefix: String,
        filler: ByteArray?,
        sizeField: ByteArray?,
    ): ByteArray {
        val h = ByteArray(512)
        put(h, 0, name.toByteArray(), 100)
        put(h, 100, "%07o\u0000".format(mode).toByteArray(), 8)
        put(h, 108, "0001750\u0000".toByteArray(), 8)
        put(h, 116, "0001750\u0000".toByteArray(), 8)
        put(h, 124, sizeField ?: "%011o\u0000".format(size).toByteArray(), 12)
        put(h, 136, "15273322553\u0000".toByteArray(), 12)
        h[156] = type.code.toByte()
        if (gnu) {
            put(h, 257, "ustar  \u0000".toByteArray(), 8)
        } else {
            put(h, 257, "ustar\u000000".toByteArray(), 8)
        }
        put(h, 265, "runner".toByteArray(), 32)
        if (prefix.isNotEmpty()) put(h, 345, prefix.toByteArray(), 155)
        if (filler != null) put(h, 345, filler, 155)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xFF }
        put(h, 148, "%06o\u0000 ".format(sum).toByteArray(), 8)
        return h
    }

    private fun put(
        header: ByteArray,
        offset: Int,
        bytes: ByteArray,
        max: Int,
    ) {
        require(bytes.size <= max) { "field too long" }
        bytes.copyInto(header, offset)
    }

    private fun record(
        key: String,
        value: String,
    ): String {
        val body = " $key=$value\n"
        var length = body.toByteArray().size
        while ("$length$body".toByteArray().size != length) length = "$length$body".toByteArray().size
        return "$length$body"
    }
}

internal fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

internal fun zip(vararg entries: Pair<String, ByteArray?>): ByteArray =
    ByteArrayOutputStream()
        .also { out ->
            ZipOutputStream(out).use { zip ->
                for ((name, data) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    if (data != null) zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

/** Whether the default file system has POSIX modes; mode assertions are skipped without. */
internal val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

internal fun modeOf(file: Path): Set<PosixFilePermission> = Files.getPosixFilePermissions(file)

internal val mode755 =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE,
    )
