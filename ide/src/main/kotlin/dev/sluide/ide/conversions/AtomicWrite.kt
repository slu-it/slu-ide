package dev.sluide.ide.conversions

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID

/**
 * Writes [text] as UTF-8 to a temporary file beside [path] and moves it over [path] in one step: an existing file is
 * replaced, a symbolic link at [path] is replaced and not written through, and a failure leaves [path] as it was. A new
 * file gets the usual mode (the umask's); a replaced regular file keeps its permissions where the file system has POSIX
 * ones. Throws IOException.
 */
internal fun writeAtomically(
    path: Path,
    text: String,
) {
    val temp = path.toAbsolutePath().resolveSibling(".convert-${UUID.randomUUID()}.tmp")
    try {
        // Not Files.createTempFile, which creates files only the owner can read.
        Files.newByteChannel(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) it.write(bytes)
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

/** Why writing failed, for the message to the user. */
internal fun writeFailureReason(e: IOException): String =
    when (e) {
        is AccessDeniedException -> "access denied"
        is NoSuchFileException -> "the folder is missing"
        else -> e.message ?: e.javaClass.simpleName
    }
