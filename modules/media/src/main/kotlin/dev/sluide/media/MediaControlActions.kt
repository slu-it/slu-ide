package dev.sluide.media

import javafx.util.Duration

/**
 * What the control bar's buttons and keys do to a [Playback] (ADR 0123); no controls, so it needs no toolkit.
 * [onPlay] runs right before every [Playback.play] that a user action starts.
 */
internal class MediaControlActions(
    private val playback: Playback,
    private val onPlay: () -> Unit,
) {
    /** False while loading or failed: nothing reacts then. */
    val enabled: Boolean
        get() = playback.status.get().let { it != PlaybackStatus.LOADING && it != PlaybackStatus.FAILED }

    fun togglePlay() {
        if (!enabled) return
        if (playback.status.get() == PlaybackStatus.PLAYING) {
            playback.pause()
        } else {
            onPlay()
            playback.play()
        }
    }

    fun skip(seconds: Double) {
        if (!enabled) return
        val moved =
            PlaybackMath.skip(
                playback.currentTime.get(),
                Duration.seconds(seconds),
                playback.totalDuration.get(),
            )
        playback.seek(moved)
    }

    fun volumeStep(up: Boolean) {
        if (!enabled) return
        playback.volume.set(PlaybackMath.volumeStep(playback.volume.get(), up))
    }

    fun toggleMute() {
        if (!enabled) return
        playback.muted.set(!playback.muted.get())
    }

    /** Runs [action]; true if it was handled (the key is then consumed). */
    fun perform(action: MediaKeyAction): Boolean {
        if (!enabled) return false
        when (action) {
            MediaKeyAction.TOGGLE_PLAY -> togglePlay()
            MediaKeyAction.BACK -> skip(-SKIP_SECONDS)
            MediaKeyAction.AHEAD -> skip(SKIP_SECONDS)
            MediaKeyAction.VOLUME_UP -> volumeStep(true)
            MediaKeyAction.VOLUME_DOWN -> volumeStep(false)
            MediaKeyAction.TOGGLE_MUTE -> toggleMute()
        }
        return true
    }

    companion object {
        const val SKIP_SECONDS = 10.0

        /** `1×`, `0.75×`, `1.25×`. */
        fun speedLabel(rate: Double): String = PlaybackSpeeds.label(rate)
    }
}

/**
 * The progress slider's rules: it follows the playback unless dragged, seeks on release and on a click on the track,
 * and does not seek for its own updates, which includes the value a slider clamps when its maximum shrinks.
 * [setMax] and [setValue] move the slider, in seconds.
 */
internal class ProgressSync(
    private val playback: Playback,
    private val setMax: (Double) -> Unit,
    private val setValue: (Double) -> Unit,
) {
    private var updating = false
    private var dragging = false

    /** The playback's time or length changed. */
    fun playbackMoved() {
        updating = true
        try {
            setMax(maxSeconds(playback.totalDuration.get()))
            if (!dragging) setValue(seconds(playback.currentTime.get()))
        } finally {
            updating = false
        }
    }

    /** The slider's value changed to [seconds]: a click on the track when not dragging. */
    fun valueChanged(seconds: Double) {
        if (updating || dragging) return
        playback.seek(Duration.seconds(seconds))
    }

    /** The thumb was grabbed or released at [seconds]; a release seeks. */
    fun draggingChanged(
        changing: Boolean,
        seconds: Double,
    ) {
        dragging = changing
        if (!changing && !updating) playback.seek(Duration.seconds(seconds))
    }

    companion object {
        fun seconds(t: Duration): Double = if (known(t)) t.toSeconds() else 0.0

        /** The slider's upper end: the length in seconds; 1 while it is unknown. */
        fun maxSeconds(total: Duration): Double = if (known(total)) total.toSeconds().coerceAtLeast(0.001) else 1.0

        fun known(t: Duration): Boolean = !t.isUnknown && !t.isIndefinite && !t.toSeconds().isNaN()
    }
}
