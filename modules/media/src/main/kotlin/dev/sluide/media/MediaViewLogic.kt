package dev.sluide.media

import dev.sluide.fileviewers.ViewActivity
import javafx.util.Duration
import java.nio.file.attribute.FileTime

/** The tab marker for a playback in [status]: RUNNING while playing, FAILED when it failed, else none. */
internal fun activityFor(status: PlaybackStatus): ViewActivity =
    when (status) {
        PlaybackStatus.PLAYING -> ViewActivity.RUNNING
        PlaybackStatus.FAILED -> ViewActivity.FAILED
        else -> ViewActivity.NONE
    }

/** Where a new player continues after the file changed: [position] and whether it [play]s again. */
internal data class ResumePlan(
    val position: Duration,
    val play: Boolean,
)

/** What a reload keeps of the old playback: its position and whether it was playing (an ended one is not). */
internal fun resumePlan(
    status: PlaybackStatus,
    position: Duration,
): ResumePlan = ResumePlan(position, status == PlaybackStatus.PLAYING)

/** [position] within 0 and [total]; only the lower bound where the total is unknown or indefinite. */
internal fun clampPosition(
    position: Duration,
    total: Duration,
): Duration {
    val atLeastZero = if (position.isUnknown || position.lessThan(Duration.ZERO)) Duration.ZERO else position
    val known = !total.isUnknown && !total.isIndefinite
    return if (known && atLeastZero.greaterThan(total)) total else atLeastZero
}

/** A file's size and modification time: what tells a reload that nothing changed. */
internal data class FileStamp(
    val size: Long,
    val modified: FileTime,
)

/** Whether the file changed since [before]; unknown (`null`) counts as changed. */
internal fun needsReload(
    before: FileStamp?,
    now: FileStamp,
): Boolean = before != now
