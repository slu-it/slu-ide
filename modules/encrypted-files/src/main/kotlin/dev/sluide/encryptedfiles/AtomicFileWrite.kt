package dev.sluide.encryptedfiles

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView

/**
 * Writes files so that a crash never leaves half of one (ADR 0162, a deviation from ADR 0020's save in place): the
 * bytes go to a hidden temp file in the target's folder, are forced to the disk, and the temp file is moved over the
 * target in one step; then the folder is forced too, so the move itself survives a crash, where the platform lets a
 * folder be opened (not on Windows; skipped quietly there). A replaced regular file keeps its POSIX permissions; a new
 * file is owner-only (600). A symbolic link at the target is replaced, not written through. On a failure the target is
 * as it was and the temp file is gone.
 */
object AtomicFileWrite {
    /** Throws IOException. */
    fun write(
        target: Path,
        bytes: ByteArray,
    ) {
        val absolute = target.toAbsolutePath()
        // Files.createTempFile creates the file owner-only on POSIX file systems.
        val temp = Files.createTempFile(absolute.parent, ".sluenc-", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            keepPermissions(absolute, temp)
            Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            removeQuietly(temp, e)
            throw e
        } catch (e: RuntimeException) {
            removeQuietly(temp, e)
            throw e
        }
        forceFolder(absolute.parent)
    }

    private fun forceFolder(folder: Path) {
        try {
            FileChannel.open(folder, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Folders cannot be opened or forced here (Windows); the file itself is already on the disk.
        }
    }

    private fun keepPermissions(
        target: Path,
        temp: Path,
    ) {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.getFileStore(temp).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
            Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS))
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
}
