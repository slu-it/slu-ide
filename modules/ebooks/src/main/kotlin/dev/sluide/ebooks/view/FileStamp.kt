package dev.sluide.ebooks.view

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize

/** Size and modification time of a file, to tell whether it changed since the last [update]; null when unreadable. */
internal class FileStamp(
    private val file: () -> Path,
) {
    private var last: Pair<Long, Any?>? = read()

    private fun read(): Pair<Long, Any?>? =
        try {
            file().fileSize() to Files.getLastModifiedTime(file())
        } catch (_: IOException) {
            null
        }

    /** Takes the current stamp and says whether it differs from the one before. */
    fun update(): Boolean {
        val now = read()
        return (now != last).also { last = now }
    }
}
