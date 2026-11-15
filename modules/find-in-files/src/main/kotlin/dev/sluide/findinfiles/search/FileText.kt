package dev.sluide.findinfiles.search

import dev.sluide.textediting.LineEnding
import dev.sluide.textediting.decodeText
import dev.sluide.textediting.isTextSample
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A file's text as the search sees it. */
internal sealed interface FileText {
    /** The text with `\n` line breaks, as an editor holds it. */
    data class Text(
        val text: String,
    ) : FileText

    /** Larger than the limit: skipped and counted. */
    data object TooLarge : FileText

    /** Binary, undecodable or unreadable: skipped silently. */
    data object Unreadable : FileText
}

/**
 * The text of [file] (absolute, normalised): its unsaved editor buffer from [unsaved] if there is one, else the file
 * on disk, read up to [maxBytes] + 1 bytes and decoded as the editors decode it (UTF-8, else Windows-1252).
 */
internal fun readText(
    file: Path,
    unsaved: Map<Path, String>,
    maxBytes: Int,
): FileText {
    unsaved[file]?.let { buffer ->
        return if (buffer.length > maxBytes) FileText.TooLarge else FileText.Text(LineEnding.normalize(buffer))
    }
    val bytes =
        try {
            Files.newInputStream(file).use { it.readNBytes(maxBytes + 1) }
        } catch (_: IOException) {
            return FileText.Unreadable
        }
    if (bytes.size > maxBytes) return FileText.TooLarge
    val complete = bytes.size <= TEXT_SAMPLE_BYTES
    val sample = if (complete) bytes else bytes.copyOf(TEXT_SAMPLE_BYTES)
    if (!isTextSample(sample, complete)) return FileText.Unreadable
    val decoded = decodeText(bytes) ?: return FileText.Unreadable
    return FileText.Text(LineEnding.normalize(decoded.text))
}
