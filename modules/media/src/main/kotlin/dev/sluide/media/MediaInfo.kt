package dev.sluide.media

import javafx.geometry.Dimension2D
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension

/** The one-line description of a media file for the status line: type, size and, for a video, its dimensions. */
internal object MediaInfo {
    /** "MP3 · 4.2 MB", "MP4 · 12.0 MB · 1920 × 1080"; the type is the upper-cased extension. */
    fun describe(
        file: Path,
        sizeBytes: Long,
        videoSize: Dimension2D?,
    ): String {
        val type = file.extension.uppercase(Locale.ROOT).ifEmpty { "FILE" }
        val parts = mutableListOf(type, humanSize(sizeBytes))
        if (videoSize != null) parts += "${videoSize.width.toInt()} × ${videoSize.height.toInt()}"
        return parts.joinToString(" · ")
    }
}

/** Same format as the image preview's status line (copied, `media` does not depend on `previews`). */
internal fun humanSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(Locale.ROOT, bytes / 1024.0)
        else -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
    }
