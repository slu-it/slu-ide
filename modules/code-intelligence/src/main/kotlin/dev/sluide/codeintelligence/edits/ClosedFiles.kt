package dev.sluide.codeintelligence.edits

import dev.sluide.librarycode.LibraryCode
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID

/** Closed files larger than this are not edited: a workspace edit reads them whole. */
internal const val MAX_CLOSED_FILE_BYTES = 10L * 1024 * 1024

private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/** A workspace edit refused for a file, with the reason shown to the user. */
internal class EditRefusedException(
    reason: String,
) : Exception(reason)

/** A closed file as read for a workspace edit: its [bytes] on disk and their [text] (UTF-8, without a BOM). */
internal class ClosedFileText(
    val file: Path,
    val bytes: ByteArray,
    val text: String,
    val bom: Boolean,
)

/**
 * Reads [file] for a workspace edit, off the FX thread; throws [EditRefusedException] for a file that is missing,
 * outside [projectFolder] (by real paths; `null` is no project), library code while [libraryReadOnly] (ADR 0150),
 * read-only, larger than [MAX_CLOSED_FILE_BYTES], not
 * UTF-8 or unreadable.
 */
internal fun readClosedFile(
    file: Path,
    projectFolder: Path?,
    libraryReadOnly: Boolean = false,
): ClosedFileText {
    try {
        if (!Files.isRegularFile(file)) throw EditRefusedException("does not exist")
        val project = projectFolder?.takeIf { Files.isDirectory(it) }?.toRealPath()
        val real = file.toRealPath()
        if (project == null || !real.startsWith(project)) throw EditRefusedException("outside the project")
        if (libraryReadOnly) {
            // Real paths, as the write goes through them: a link into a library is library code.
            LibraryCode.locate(real, project)?.let {
                throw EditRefusedException("library code (${it.description})")
            }
        }
        if (!Files.isWritable(file)) throw EditRefusedException("read-only")
        if (Files.size(file) > MAX_CLOSED_FILE_BYTES) throw EditRefusedException("larger than 10 MiB")
        val bytes = Files.readAllBytes(file)
        val bom = hasBom(bytes)
        val text = decodeUtf8(if (bom) bytes.copyOfRange(BOM.size, bytes.size) else bytes)
        return ClosedFileText(file, bytes, text ?: throw EditRefusedException("not UTF-8"), bom)
    } catch (e: IOException) {
        throw EditRefusedException(ioReason(e))
    }
}

/**
 * The text of [file] on disk as an editor holds it (UTF-8 without a BOM, LF line breaks), off the FX thread, to see
 * whether an open editor's text is what the server read; `null` if it cannot be read as such.
 */
internal fun readDiskText(file: Path): String? =
    try {
        if (Files.size(file) > MAX_CLOSED_FILE_BYTES) {
            null
        } else {
            val bytes = Files.readAllBytes(file)
            decodeUtf8(if (hasBom(bytes)) bytes.copyOfRange(BOM.size, bytes.size) else bytes)?.let { text ->
                withLineEnding(text, LF)
            }
        }
    } catch (_: IOException) {
        null
    }

private const val LF = "\n"

private fun hasBom(bytes: ByteArray) = bytes.size >= BOM.size && bytes.copyOf(BOM.size).contentEquals(BOM)

/** [bytes] decoded as UTF-8, `null` if they are not. */
private fun decodeUtf8(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

/**
 * Writes [text] over the closed file [original] was read from, off the FX thread, as UTF-8 with its BOM if it had one:
 * to a temporary file beside it, moved over it in one step (`ATOMIC_MOVE`), keeping its permissions; through a
 * symbolic link to its target. Throws [EditRefusedException] if the file changed on disk since it was read or cannot
 * be written; the file is as it was then.
 */
internal fun writeClosedFile(
    original: ClosedFileText,
    text: String,
) {
    try {
        val target = original.file.toRealPath()
        if (!Files.isWritable(target)) throw EditRefusedException("read-only")
        if (!Files.readAllBytes(target).contentEquals(original.bytes)) {
            throw EditRefusedException("changed on disk meanwhile")
        }
        val content = text.toByteArray(Charsets.UTF_8)
        writeAtomically(target, if (original.bom) BOM + content else content)
    } catch (e: IOException) {
        throw EditRefusedException(ioReason(e))
    }
}

/**
 * Writes [bytes] to a temporary file beside [path] and moves it over [path] in one step; a regular file keeps its
 * permissions where the file system has POSIX ones, and a failure leaves [path] as it was. Throws IOException. After
 * `writeAtomically` in `ide`'s conversions (modules never depend on `ide`).
 */
private fun writeAtomically(
    path: Path,
    bytes: ByteArray,
) {
    val temp = path.toAbsolutePath().resolveSibling(".${path.fileName}-${UUID.randomUUID()}.tmp")
    try {
        // Not Files.createTempFile, which creates files only the owner can read.
        Files.newByteChannel(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
        }
        keepPermissions(path, temp)
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: IOException) {
        removeQuietly(temp, e)
        throw e
    } catch (e: RuntimeException) {
        removeQuietly(temp, e)
        throw e
    }
}

private fun keepPermissions(
    old: Path,
    temp: Path,
) {
    if (!Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS)) return
    if (Files.getFileStore(old).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
        Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(old, LinkOption.NOFOLLOW_LINKS))
    }
}

private fun removeQuietly(
    temp: Path,
    cause: Exception,
) {
    try {
        Files.deleteIfExists(temp)
    } catch (e: IOException) {
        cause.addSuppressed(e)
    }
}

private fun ioReason(e: IOException): String =
    when (e) {
        is AccessDeniedException -> "access denied"
        is NoSuchFileException -> "does not exist"
        else -> e.message ?: e.javaClass.simpleName
    }
