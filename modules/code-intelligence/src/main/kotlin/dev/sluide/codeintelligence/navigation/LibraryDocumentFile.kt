package dev.sluide.codeintelligence.navigation

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The largest document text a server may send for a library document (ADR 0179): 16 MiB as UTF-8. */
internal const val MAX_LIBRARY_DOCUMENT_BYTES = 16 * 1024 * 1024

/**
 * Writes [text], the answer of a server for a library document, to [file] exactly as received (UTF-8, line endings
 * kept), off the FX thread: parent folders are created, the text goes to a temporary file in the same folder that
 * replaces [file] in one step (`ATOMIC_MOVE`), so that a reader never sees half of it. The path is the spec's, nothing
 * of the server's text goes into it. Throws [IOException] for a text over [maxBytes] (nothing is written) or a failed
 * write (the earlier file stays).
 */
internal fun writeLibraryDocument(
    file: Path,
    text: String,
    maxBytes: Int = MAX_LIBRARY_DOCUMENT_BYTES,
) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size > maxBytes) throw IOException("The document is larger than $maxBytes bytes.")
    val absolute = file.toAbsolutePath()
    val folder = absolute.parent
    Files.createDirectories(folder)
    val temp = Files.createTempFile(folder, ".${absolute.fileName}-", ".tmp")
    try {
        Files.write(temp, bytes)
        Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        // Gone after a successful move; left behind only by a failed write or move.
        Files.deleteIfExists(temp)
    }
}
