package dev.sluide.fileviewers

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a view's file content comes from and goes to: the plain bytes of the file. A code view reads and writes its
 * file only through it, so a view can show a virtual path whose bytes come from elsewhere, e.g. decrypted in memory.
 * [read] and [write] may run on the FX thread, so they must be quick.
 */
interface FileContent {
    /** The file's bytes; a [java.nio.file.NoSuchFileException] when there is no such file (any more). */
    @Throws(IOException::class)
    fun read(): ByteArray

    /** Replaces the file's bytes with [bytes]. */
    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    /**
     * Called when the view takes the bytes of the last [read] as its saved state without writing them: a reload, the
     * conflict banner's Reload, or a change on disk whose text the view already counts as saved. Not called for the
     * first read, nor when the view keeps its own text over a change (Keep mine). Nothing by default; encrypted files
     * follow it to see whether the file still holds what the view stands for.
     */
    fun adopted() {}

    /** Whether the content must stay in the view: code-editor extensions do not attach to it unless they opt in. */
    val confidential: Boolean get() = false
}

/** The content of [file] on disk: read whole, written in place (create, truncate, write). */
class DiskContent(
    val file: Path,
) : FileContent {
    override fun read(): ByteArray = Files.readAllBytes(file)

    // In place, so the file keeps its permissions and symbolic links stay links.
    override fun write(bytes: ByteArray) {
        Files.write(file, bytes)
    }
}

/**
 * A viewer that can also show content that is not read from the shown path, e.g. a decrypted file. The path given to
 * [open] is virtual: it decides the type, highlighting, parent folder and `.editorconfig`, but is never read, written,
 * watched or checked for existence.
 */
interface ContentFileViewer {
    /** Whether [open] shows content of a file named [name], by its name alone. */
    fun opensContent(name: String): Boolean

    /** A view of [file] that reads and saves [content] instead of the file. Call on the FX thread. */
    fun open(
        file: Path,
        content: FileContent,
    ): FileView
}
