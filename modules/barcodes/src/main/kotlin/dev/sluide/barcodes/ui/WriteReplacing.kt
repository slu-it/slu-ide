package dev.sluide.barcodes.ui

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/**
 * Writes [target] through a temporary file in its folder that [write] fills and that then replaces the target, so that
 * a failed write leaves the old file whole. A copy of `writeReplacing` in `previews`' `FileExport.kt` (modules do not
 * depend on each other), without its permission handling: the target is a file the user just chose in the dialog,
 * which asked before replacing it. A symbolic link is followed, so that it is not replaced by a file.
 */
@Throws(IOException::class)
internal fun writeReplacing(
    target: Path,
    write: (Path) -> Unit,
) {
    val destination = if (Files.isSymbolicLink(target)) target.toRealPath() else target
    val temporary = destination.resolveSibling(".${destination.fileName}.${UUID.randomUUID()}.tmp")
    try {
        Files.createFile(temporary)
        write(temporary)
        try {
            Files.move(temporary, destination, REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, REPLACE_EXISTING)
        }
    } finally {
        // A failure to clean up must not hide the write's own error.
        runCatching { Files.deleteIfExists(temporary) }
    }
}
