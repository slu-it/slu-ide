package dev.sluide.localmodels

import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** The default cap of the bytes an archive may unpack to. */
const val DEFAULT_MAX_TOOL_BYTES: Long = 512L * 1024 * 1024

/** The default cap of the files and folders in an archive. */
const val DEFAULT_MAX_TOOL_ENTRIES: Int = 10_000

/** Headers a tar stream may have on top of its data; the decompressed stream is capped at the data cap plus this. */
internal const val TAR_HEADER_ALLOWANCE = 64L * 1024 * 1024

private const val BUFFER_SIZE = 64 * 1024

private val EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x")

/**
 * Unpacks a downloaded tool into a fresh folder (ADR 0143). Every entry path must stay inside the folder (no absolute
 * path, no `..`, no backslash), no path may come twice, and the archive may unpack to at most [maxBytes] in at most
 * [maxEntries] files and folders. Files are created new, never over something; links and special files are refused
 * (the tar reader refuses them; zip has none that Java creates). The named executable and tar entries with an
 * executable bit get mode 0755. A decompressed tar stream may hold [headerAllowance] bytes on top of [maxBytes] (headers,
 * skipped data). Blocking; off the FX thread.
 */
internal class ArchiveExtractor(
    private val maxBytes: Long = DEFAULT_MAX_TOOL_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_TOOL_ENTRIES,
    private val headerAllowance: Long = TAR_HEADER_ALLOWANCE,
) {
    /**
     * Unpacks [archive] in [format] into [target] (created if missing) and returns the [executable] in it.
     *
     * @throws ArchiveException when the archive is refused or damaged, or has no [executable]; readable message.
     * @throws CancellationException when [cancelled] returns true; what was unpacked stays for the caller to remove.
     */
    fun extract(
        archive: Path,
        format: ArchiveFormat,
        executable: String,
        target: Path,
        cancelled: () -> Boolean,
    ): Path {
        val exe = entryPath(executable) ?: throw ArchiveException("$executable is not a path inside the archive")
        Files.createDirectories(target)
        val unpack = Unpack(target, cancelled)
        when (format) {
            ArchiveFormat.TAR_GZ -> {
                extractTar(archive, unpack)
            }

            ArchiveFormat.ZIP -> {
                extractZip(archive, unpack)
            }

            ArchiveFormat.PLAIN -> {
                unpack.check(Files.size(archive))
                Files.newInputStream(archive).use { unpack.file(executable, it, false) }
            }
        }
        val file = target.resolve(exe)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw ArchiveException("the archive has no file $executable")
        }
        makeExecutable(file)
        return file
    }

    private fun extractTar(
        archive: Path,
        unpack: Unpack,
    ) {
        try {
            Files.newInputStream(archive).use { raw ->
                val tar = TarReader(Capped(GZIPInputStream(raw, BUFFER_SIZE), maxBytes + headerAllowance))
                while (true) {
                    val entry = tar.next() ?: break
                    if (entry.directory) {
                        unpack.directory(entry.path)
                    } else {
                        unpack.check(entry.size)
                        unpack.file(entry.path, tar.data, entry.mode and 0b001_001_001 != 0)
                    }
                }
            }
        } catch (e: ZipException) {
            throw ArchiveException("the archive is damaged or not gzip-compressed (${e.message})")
        } catch (_: EOFException) {
            throw ArchiveException("the archive is truncated")
        }
    }

    private fun extractZip(
        archive: Path,
        unpack: Unpack,
    ) {
        try {
            // The central directory, as unzip reads it; ZipInputStream would trust the local headers.
            ZipFile(archive.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) {
                        unpack.directory(entry.name)
                    } else {
                        if (entry.size >= 0) unpack.check(entry.size)
                        zip.getInputStream(entry).use { unpack.file(entry.name, it, false) }
                    }
                }
            }
        } catch (e: ZipException) {
            throw ArchiveException("the archive is damaged or not a zip archive (${e.message})")
        }
    }

    /** The unpacking of one archive: what it has written so far. */
    private inner class Unpack(
        private val target: Path,
        private val cancelled: () -> Boolean,
    ) {
        private val seen = HashSet<String>()
        private var bytes = 0L

        fun directory(name: String) {
            // `./` names the folder itself, as in archives made with `tar -C dir .`.
            val path = entryPath(name) ?: if (normalizes(name)) return else refuse(name)
            count(name, path)
            try {
                Files.createDirectories(target.resolve(path))
            } catch (e: FileSystemException) {
                throw ArchiveException("$name clashes with another entry (${e.reason ?: e.javaClass.simpleName})")
            }
        }

        /** Refuses an entry of [size] bytes before anything of it is written when it would pass the cap. */
        fun check(size: Long) {
            if (size > maxBytes - bytes) throw tooLarge()
        }

        fun file(
            name: String,
            data: InputStream,
            executable: Boolean,
        ) {
            val path = entryPath(name) ?: refuse(name)
            count(name, path)
            val file = target.resolve(path)
            try {
                Files.createDirectories(file.parent)
                Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelled()) throw CancellationException("Unpacking cancelled")
                        val n = data.read(buffer)
                        if (n < 0) break
                        if (n > maxBytes - bytes) throw tooLarge()
                        bytes += n
                        out.write(buffer, 0, n)
                    }
                }
            } catch (e: FileSystemException) {
                throw ArchiveException("$name clashes with another entry (${e.reason ?: e.javaClass.simpleName})")
            }
            if (executable) makeExecutable(file)
        }

        private fun count(
            name: String,
            path: String,
        ) {
            if (cancelled()) throw CancellationException("Unpacking cancelled")
            if (!seen.add(path)) throw ArchiveException("the archive has $name twice")
            if (seen.size > maxEntries) throw ArchiveException("the archive has more than $maxEntries entries")
            // The path rules already keep it inside; this guards against a rule that misses a case.
            if (!target.resolve(path).normalize().startsWith(target.normalize())) refuse(name)
        }

        private fun tooLarge() = ArchiveException("the archive unpacks to more than ${maxBytes / (1024 * 1024)} MiB")
    }

    private fun refuse(name: String): Nothing = throw ArchiveException("the archive has the unsafe path $name")

    /** Whether [name] is only `.` segments (the archive's own folder). */
    private fun normalizes(name: String) = name.isNotEmpty() && name.split('/').all { it == "." || it.isEmpty() }
}

/**
 * [name] as a relative path inside the target, `/`-separated with `.` and empty segments dropped; null for an
 * absolute path, a `..` segment, a backslash, a NUL, a drive letter, or nothing left.
 */
internal fun entryPath(name: String): String? {
    if (name.startsWith("/") || '\\' in name || '\u0000' in name || name.matches(Regex("^[A-Za-z]:.*"))) return null
    val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.isEmpty() || segments.any { it == ".." }) return null
    return segments.joinToString("/")
}

/** Gives [file] mode 0755, or sets its executable flag where the file system has no POSIX modes. */
internal fun makeExecutable(file: Path) {
    if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null) {
        Files.setPosixFilePermissions(file, EXECUTABLE)
    } else if (!file.toFile().setExecutable(true)) {
        throw IOException("Cannot make ${file.fileName} executable")
    }
}

/** Ends a stream with [ArchiveException] once more than [limit] bytes came out of it. */
private class Capped(
    input: InputStream,
    private val limit: Long,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) counted(1L)
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = super.read(b, off, len)
        if (n > 0) counted(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) counted(skipped)
        return skipped
    }

    private fun counted(n: Long) {
        count += n
        if (count > limit) throw ArchiveException("the archive unpacks to more than allowed")
    }
}
