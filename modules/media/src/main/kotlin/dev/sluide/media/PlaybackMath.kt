package dev.sluide.media

import javafx.util.Duration
import java.util.Locale
import kotlin.math.roundToLong

/** Times and steps of the media controls (ADR 0123). */
object PlaybackMath {
    private const val VOLUME_STEP = 0.1

    /** `m:ss` below one hour, `h:mm:ss` from one hour on; `--:--` for an unknown, indefinite or NaN time. */
    fun format(t: Duration): String {
        if (!known(t)) return "--:--"
        val total = (t.toSeconds().coerceAtLeast(0.0)).toLong()
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.ROOT, minutes, seconds)
        }
    }

    /** [current] moved [by], within 0 and [total]; with an unknown [total] only the lower bound applies. */
    fun skip(
        current: Duration,
        by: Duration,
        total: Duration,
    ): Duration {
        val from = if (known(current)) current.toMillis() else 0.0
        val moved = (from + by.toMillis()).coerceAtLeast(0.0)
        return Duration.millis(if (known(total)) moved.coerceAtMost(total.toMillis()) else moved)
    }

    /** [v] one step (10 %) louder or quieter, within 0 and 1, rounded to hundredths so that steps do not drift. */
    fun volumeStep(
        v: Double,
        up: Boolean,
    ): Double {
        val start = if (v.isNaN()) 0.0 else v
        val stepped = (start + if (up) VOLUME_STEP else -VOLUME_STEP).coerceIn(0.0, 1.0)
        return (stepped * 100).roundToLong() / 100.0
    }

    private fun known(t: Duration?) = t != null && !t.isUnknown && !t.isIndefinite && !t.toMillis().isNaN()
}
