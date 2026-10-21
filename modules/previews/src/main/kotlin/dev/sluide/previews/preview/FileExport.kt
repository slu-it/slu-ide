package dev.sluide.previews.preview

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** One item of a preview's Export menu: writes a file made from the saved text beside the previewed file. */
internal interface FileExport {
    /** The menu item, e.g. "HTML" or "PNG 200%". */
    val label: String

    /** The file the export of [file] is written to. */
    fun target(file: Path): Path

    /**
     * Writes the export of [text], the saved content of [file], to [target]; blocking, never on the FX thread. Returns
     * lines for the user about what the export lacks (empty: nothing).
     */
    @Throws(IOException::class)
    fun write(
        file: Path,
        text: String,
        target: Path,
    ): List<String>
}

/**
 * Writes [target] by [write]ing a temporary file beside it and moving that over [target] (atomically where the file
 * system can), so that an export that fails leaves an existing target as it was. The temporary file is deleted on
 * failure. A target that is a symbolic link is written where the link points; an existing target keeps its POSIX
 * permissions.
 */
@Throws(IOException::class)
internal fun writeReplacing(
    target: Path,
    write: (Path) -> Unit,
) {
    val destination = if (Files.isSymbolicLink(target)) target.toRealPath() else target
    val temporary = destination.resolveSibling(".${destination.fileName}.${UUID.randomUUID()}.tmp")
    try {
        // Not Files.createTempFile, which would give the export owner-only permissions.
        Files.createFile(temporary)
        keepPermissions(destination, temporary)
        write(temporary)
        try {
            Files.move(temporary, destination, REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, REPLACE_EXISTING)
        }
    } finally {
        // A failure to clean up must not hide the export's own error.
        runCatching { Files.deleteIfExists(temporary) }
    }
}

/** Gives [temporary] the POSIX permissions of [target], if it exists and the file system has them. */
private fun keepPermissions(
    target: Path,
    temporary: Path,
) {
    if (!Files.exists(target)) return
    try {
        Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target))
    } catch (_: UnsupportedOperationException) {
        // No POSIX permissions (e.g. Windows): the file system's defaults apply.
    }
}
