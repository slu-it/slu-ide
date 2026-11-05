package dev.sluide.diagnostics

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val log = System.getLogger("dev.sluide.diagnostics.CaptureFolders")

private val FOLDER_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

/** The names of capture folders: a time stamp, with `-2`, `-3`… when that second was taken. */
private val CAPTURE_FOLDER = Regex("""(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})(?:-(\d+))?""")

private val posix get() = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

/** Owner-only permissions for a folder that is created, where the file system has POSIX permissions. */
internal fun ownerOnly(): Array<FileAttribute<*>> =
    if (posix) {
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else {
        emptyArray()
    }

/** Makes an existing [folder] owner-only, where the file system has POSIX permissions. */
internal fun restrictToOwner(folder: Path) {
    if (posix) Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"))
}

/** Creates the folder of a capture taken at [time] in [base] (owner-only where POSIX), never one that exists. */
internal fun createCaptureFolder(
    base: Path,
    time: ZonedDateTime,
): Path {
    Files.createDirectories(base, *ownerOnly())
    val name = FOLDER_NAME.format(time)
    var number = 1
    while (true) {
        val folder = base.resolve(if (number == 1) name else "$name-$number")
        try {
            return Files.createDirectory(folder, *ownerOnly())
        } catch (_: FileAlreadyExistsException) {
            number++
        }
    }
}

/** Writes [text] to [target] through a temp file in its folder, so a reader never sees half of it. */
internal fun writeAtomically(
    target: Path,
    text: String,
) {
    val temp = Files.createTempFile(target.parent, "${target.name}.", ".tmp")
    try {
        Files.writeString(temp, text)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temp)
    }
}

/**
 * Keeps the newest [keep] capture folders in [base] and deletes the older ones. Only folders named like a capture
 * count; other files and folders are left alone. Never throws: what cannot be listed or deleted is a WARNING.
 */
internal fun applyRetention(
    base: Path,
    keep: Int,
) {
    try {
        deleteOldCaptures(base, keep)
    } catch (e: IOException) {
        log.log(System.Logger.Level.WARNING, "Old diagnostics folders in $base could not be deleted", e)
    } catch (e: UncheckedIOException) {
        log.log(System.Logger.Level.WARNING, "Old diagnostics folders in $base could not be deleted", e)
    }
}

private fun deleteOldCaptures(
    base: Path,
    keep: Int,
) {
    val captures =
        base
            .listDirectoryEntries()
            .mapNotNull { path ->
                val match = CAPTURE_FOLDER.matchEntire(path.name) ?: return@mapNotNull null
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
                Triple(path, match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 1)
            }.sortedWith(compareByDescending<Triple<Path, String, Int>> { it.second }.thenByDescending { it.third })
    for ((folder) in captures.drop(keep)) {
        try {
            deleteRecursively(folder)
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Old diagnostics folder $folder could not be deleted", e)
        } catch (e: UncheckedIOException) {
            log.log(System.Logger.Level.WARNING, "Old diagnostics folder $folder could not be deleted", e)
        }
    }
}

/** Deletes [folder] and what is in it. */
internal fun deleteRecursively(folder: Path) {
    // walk does not follow links: a link inside is deleted, not what it points to.
    Files.walk(folder).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}

/** Bytes read at most from the end of a log file; lines beyond it are left out. */
private const val TAIL_BYTES = 1L * 1024 * 1024

/**
 * The last [count] lines of [file], reading backwards from its end in blocks so a large log is not read whole; at
 * most [TAIL_BYTES] bytes. Malformed UTF-8 shows as replacement characters.
 */
internal fun tailLines(
    file: Path,
    count: Int,
): List<String> {
    FileChannel.open(file, StandardOpenOption.READ).use { channel ->
        val size = channel.size()
        val block = 64 * 1024
        var start = size
        var bytes = ByteArray(0)
        while (start > 0 && size - start < TAIL_BYTES && bytes.count { it == '\n'.code.toByte() } <= count) {
            val length = minOf(block.toLong(), start).toInt()
            start -= length
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining() && channel.read(buffer, start + buffer.position()) >= 0) {
                // Read until the block is full.
            }
            bytes = buffer.array().copyOf(buffer.position()) + bytes
        }
        val text =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        if (text.isEmpty()) return emptyList()
        val lines = text.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }
        // Unless the file was read from its start, the first line is a part of one.
        return (if (start > 0) lines.drop(1) else lines).takeLast(count)
    }
}
