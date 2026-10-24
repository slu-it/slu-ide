package dev.sluide.httpclient.envedit

import dev.sluide.fileviewers.FileViewHost
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Decides how an [EnvEdit] of the form is written for the view of the [side] file [file]: first to the [otherFile]
 * through its tab (creating it as `{}` if it is missing), and only if that worked, to the shown file. Nothing is
 * changed if the shown file's text changed since the form read it, if the shown file is read-only while the edit
 * changes it, or if the other file's tab differs from what the form read.
 */
internal class EnvFileWriter(
    private val side: FileSide,
    private val file: Path,
    private val otherFile: Path,
) {
    /** What to do with the shown file: write [ownText] (if not `null`) unless there is a [failure] to report. */
    data class Outcome(
        val ownText: String?,
        val failure: String?,
    )

    /**
     * Applies the part of [edit] for the other file through [host], and returns what is left for the shown file.
     * [document] is what the form read; [editorText] and [canReplace] tell the shown file's current text and whether
     * it can be replaced.
     */
    fun apply(
        edit: EnvEdit,
        document: EnvDocument,
        editorText: String,
        canReplace: Boolean,
        host: FileViewHost?,
    ): Outcome {
        val own = if (side == FileSide.PUBLIC) edit.public else edit.private
        val other = if (side == FileSide.PUBLIC) edit.private else edit.public
        val shown = if (side == FileSide.PUBLIC) document.publicText else document.privateText
        return when {
            // Typed since the form last read the text (its pause still running): the edit would drop what was typed.
            editorText != shown -> failed("${file.fileName} changed meanwhile; nothing was changed, try again")

            own != null && !canReplace -> failed("${file.fileName} is read-only")

            other == null -> Outcome(own, null)

            else -> applyToOther(other, document, host)?.let(::failed) ?: Outcome(own, null)
        }
    }

    /** Why [text] could not be written to the other file, `null` if it was. */
    private fun applyToOther(
        text: String,
        document: EnvDocument,
        host: FileViewHost?,
    ): String? {
        host ?: return "${otherFile.fileName} can only be changed in an IDE window"
        val expected = if (side == FileSide.PUBLIC) document.privateText else document.publicText
        if (!Files.exists(otherFile)) {
            try {
                Files.writeString(otherFile, EMPTY_FILE, StandardOpenOption.CREATE_NEW)
            } catch (e: IOException) {
                return "${otherFile.fileName} cannot be created: ${e.message}"
            }
        }
        var changedMeanwhile = false
        val edited =
            host.editText(otherFile) { current ->
                // The form showed another text of the file than its tab has now: nothing is changed then. A file that
                // was missing has to be the one just created.
                val unchanged =
                    expected?.let { normalize(current) == it } ?: (current.isBlank() || current.trim() == "{}")
                if (!unchanged) {
                    changedMeanwhile = true
                    current
                } else {
                    text
                }
            }
        return when {
            !edited -> "${otherFile.fileName} cannot be changed: it cannot be opened as text or is read-only"
            changedMeanwhile -> "${otherFile.fileName} changed meanwhile; nothing was changed, try again"
            else -> null
        }
    }

    private fun failed(message: String) = Outcome(null, message)

    companion object {
        private const val EMPTY_FILE = "{}\n"

        /** [text] of the other file with `\n` line breaks and without a byte order mark, which a tab or the disk may have. */
        fun normalize(text: String) = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    }
}
