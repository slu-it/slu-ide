package dev.sluide.media

import javafx.beans.property.BooleanProperty
import javafx.beans.property.DoubleProperty
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyStringProperty
import javafx.collections.ObservableMap
import javafx.geometry.Dimension2D
import javafx.scene.Node
import javafx.util.Duration

/** Where a [Playback] stands (ADR 0123). */
enum class PlaybackStatus { LOADING, READY, PLAYING, PAUSED, ENDED, FAILED }

/**
 * Something playing sound that [PlaybackCoordinator] can pause when another playback starts: a media [Playback], or a
 * reading of the Read Aloud tool (ADR 0123, ADR 0124). [pause] of something paused, ended or failed does nothing.
 */
interface Pausable {
    fun pause()
}

/**
 * One file's player (ADR 0123); FX thread only. The views build on this interface, so tests drive them with a fake;
 * [JavaFxPlayback] is the real one.
 */
interface Playback : Pausable {
    val status: ReadOnlyObjectProperty<PlaybackStatus>
    val currentTime: ReadOnlyObjectProperty<Duration>

    /** `Duration.UNKNOWN` until [PlaybackStatus.READY]; may stay unknown or indefinite for a stream. */
    val totalDuration: ReadOnlyObjectProperty<Duration>

    /** A readable reason while [PlaybackStatus.FAILED] ("unsupported media format"), else `null`. */
    val error: ReadOnlyStringProperty

    /** The media's tags as JavaFX Media reads them: "title", "artist", "album", "image" (a JavaFX `Image`). */
    val metadata: ObservableMap<String, Any>

    /** Width and height of a video once known; `null` for audio and until then. */
    val videoSize: ReadOnlyObjectProperty<Dimension2D?>

    /** 0.0 to 1.0. */
    val volume: DoubleProperty
    val muted: BooleanProperty

    /** Playback speed, 0.0 to 8.0 as in `MediaPlayer`. */
    val rate: DoubleProperty

    /** Starts again from the beginning at the end, instead of ending. */
    val loop: BooleanProperty

    /** Plays; from the beginning if [PlaybackStatus.ENDED]. */
    fun play()

    fun seek(to: Duration)

    /** Releases the player; idempotent. Nothing plays afterwards. */
    fun dispose()

    /** A new node showing the video (a `MediaView`); `null` when there is no player (fakes, a failed file). */
    fun createVideoNode(): Node?
}
