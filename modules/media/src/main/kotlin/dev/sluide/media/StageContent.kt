package dev.sluide.media

/** What the stage shows (decided without a toolkit, ADR 0084). */
internal enum class StageKind { VIDEO, AUDIO, FAILED }

/** A failed player shows the notice; a video file without a video node falls back to the audio layout. */
internal fun stageKind(
    status: PlaybackStatus,
    video: Boolean,
    hasVideoNode: Boolean,
): StageKind =
    when {
        status == PlaybackStatus.FAILED -> StageKind.FAILED
        video && hasVideoNode -> StageKind.VIDEO
        else -> StageKind.AUDIO
    }

/** The text lines under the cover art; [artist] and [album] are `null` when absent or blank. */
internal data class AudioLines(
    val title: String,
    val artist: String?,
    val album: String?,
)

internal fun audioLines(
    metadata: Map<String, Any>,
    fileName: String,
): AudioLines {
    fun text(key: String) = (metadata[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    return AudioLines(text("title") ?: fileName, text("artist"), text("album"))
}

/** The centered notice of a failed file: file name, size and the reason. */
internal data class FailedNotice(
    val name: String,
    val size: String,
    val message: String,
)

internal fun failedNotice(
    fileName: String,
    sizeBytes: Long,
    error: String?,
): FailedNotice =
    FailedNotice(
        fileName,
        humanSize(sizeBytes),
        "Cannot play $fileName: ${error?.takeIf { it.isNotBlank() } ?: "unknown error"}",
    )

/** Padding of the stage's column in `media-stage.css` on both sides (2 x 16). */
internal const val COLUMN_PADDING = 32.0

/** Height kept for the title, artist and album lines under the cover. */
internal const val TEXT_ROOM = 130.0

internal const val COVER_SIZE = 240.0

/** Fit size of the cover art in a stage of [width] x [height]: at most [COVER_SIZE], never 0 (0 means "no fit"). */
internal fun coverFit(
    width: Double,
    height: Double,
): Pair<Double, Double> =
    maxOf(1.0, minOf(COVER_SIZE, width - COLUMN_PADDING)) to maxOf(1.0, minOf(COVER_SIZE, height - TEXT_ROOM))
