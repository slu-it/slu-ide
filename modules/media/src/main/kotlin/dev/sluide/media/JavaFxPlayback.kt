package dev.sluide.media

import javafx.beans.property.BooleanProperty
import javafx.beans.property.DoubleProperty
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableMap
import javafx.geometry.Dimension2D
import javafx.scene.Node
import javafx.scene.media.Media
import javafx.scene.media.MediaException
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import java.nio.file.Path

private val log = System.getLogger(JavaFxPlayback::class.java.name)

/**
 * The [Playback] of [file] with JavaFX Media (ADR 0123): a `Media` from the file URI and its `MediaPlayer`. A file that
 * cannot be played never throws: the playback ends [PlaybackStatus.FAILED] with a readable [error]. Create and use it on
 * the FX thread.
 */
class JavaFxPlayback(
    private val file: Path,
) : Playback {
    private val statusWrapper = ReadOnlyObjectWrapper(PlaybackStatus.LOADING)
    private val currentTimeWrapper = ReadOnlyObjectWrapper(Duration.ZERO)
    private val totalDurationWrapper = ReadOnlyObjectWrapper(Duration.UNKNOWN)
    private val errorWrapper = ReadOnlyStringWrapper()
    private val videoSizeWrapper = ReadOnlyObjectWrapper<Dimension2D?>()

    override val status: ReadOnlyObjectProperty<PlaybackStatus> = statusWrapper.readOnlyProperty
    override val currentTime: ReadOnlyObjectProperty<Duration> = currentTimeWrapper.readOnlyProperty
    override val totalDuration: ReadOnlyObjectProperty<Duration> = totalDurationWrapper.readOnlyProperty
    override val error: ReadOnlyStringProperty = errorWrapper.readOnlyProperty
    override val videoSize: ReadOnlyObjectProperty<Dimension2D?> = videoSizeWrapper.readOnlyProperty
    override val volume: DoubleProperty = ClampedDoubleProperty(1.0, 0.0, 1.0)
    override val muted: BooleanProperty = SimpleBooleanProperty(false)
    override val rate: DoubleProperty = ClampedDoubleProperty(1.0, 0.0, 8.0)
    override val loop: BooleanProperty = SimpleBooleanProperty(false)
    override val metadata: ObservableMap<String, Any>

    private val media: Media?
    private val player: MediaPlayer?

    /**
     * Set at the end of the media until play or seek. The player is stopped there (it would stay PLAYING otherwise and
     * not play again), so its status and time (0) are not shown meanwhile.
     */
    private var ended = false

    /** A seek made after the end: a stopped player ignores seeks, so it is applied once the player has paused. */
    private var pendingSeek: Duration? = null
    private var disposed = false

    /** Whether the player was ready once: from then on, only a halted player counts as failed. */
    private var loaded = false

    /** FAILED by an error reported while loading, which may be another media's: undone if the player gets ready. */
    private var tentativeFailure = false

    init {
        var created: Pair<Media, MediaPlayer>? = null
        try {
            val newMedia = Media(file.toUri().toString())
            created = newMedia to MediaPlayer(newMedia)
        } catch (e: MediaException) {
            fail(e.type, e.message, e)
        } catch (e: RuntimeException) {
            // Media throws IllegalArgumentException and UnsupportedOperationException for URIs it cannot take.
            fail(null, e.message, e)
        }
        media = created?.first
        player = created?.second
        metadata = media?.metadata ?: FXCollections.observableHashMap()
        if (media != null && player != null) attach(media, player)
    }

    private fun attach(
        media: Media,
        player: MediaPlayer,
    ) {
        media.setOnError { media.error?.let { reported(it.type, it.message, it) } }
        player.setOnError { player.error?.let { reported(it.type, it.message, it) } }
        player.setOnReady {
            loaded = true
            totalDurationWrapper.set(media.duration ?: Duration.UNKNOWN)
            if (media.width > 0 && media.height > 0) {
                videoSizeWrapper.set(Dimension2D(media.width.toDouble(), media.height.toDouble()))
            }
        }
        media.durationProperty().addListener { _, _, duration ->
            if (player.status != MediaPlayer.Status.UNKNOWN) totalDurationWrapper.set(duration ?: Duration.UNKNOWN)
        }
        player.setOnEndOfMedia {
            // Also called before every repetition while looping, and the player rewinds by itself then.
            if (player.cycleCount == MediaPlayer.INDEFINITE) return@setOnEndOfMedia
            // A stopped player starts from the beginning on play.
            ended = true
            pendingSeek = null
            currentTimeWrapper.set(
                totalDurationWrapper.get().takeUnless { it.isUnknown || it.isIndefinite } ?: player.currentTime,
            )
            statusWrapper.set(PlaybackStatus.ENDED)
            player.stop()
        }
        // macOS: the rewind of a repetition plays anew, at rate 1, while the status stays PLAYING.
        player.setOnRepeat { if (player.status == MediaPlayer.Status.PLAYING) applyRate(player) }
        player.statusProperty().addListener { _, _, status ->
            if (status == MediaPlayer.Status.PLAYING) applyRate(player)
            updateStatus()
        }
        player.currentTimeProperty().addListener { _, _, time ->
            if (!ended && pendingSeek == null) currentTimeWrapper.set(time)
        }
        player.volumeProperty().bind(volume)
        player.muteProperty().bind(muted)
        // Not bound: see applyRate.
        rate.addListener { _, _, _ -> if (player.status == MediaPlayer.Status.PLAYING) applyRate(player) }
        player.cycleCount = cycles(loop.get())
        loop.addListener { _, _, on -> player.cycleCount = cycles(on) }
        updateStatus()
    }

    /**
     * Gives the player the speed, only while it plays. On macOS JavaFX hands a rate straight to AVPlayer, where any
     * rate above 0 starts playback JavaFX knows nothing of (it stays READY or PAUSED and reports no time): a bound rate
     * started every file on open. And AVPlayer's play resets the rate to 1 while the player's property keeps the old
     * value, so a speed other than 1 is set again at every start (via 1, as setting an unchanged value does nothing).
     * PLAYING arrives later than play: a pause in between would get the rate after it (on macOS, play on hidden), which
     * no caller does, as the controls and the coordinator see READY or PAUSED until then.
     */
    private fun applyRate(player: MediaPlayer) {
        val wanted = rate.get()
        if (player.rate == wanted && wanted != 1.0) player.rate = 1.0
        player.rate = wanted
    }

    private fun updateStatus() {
        val player = player ?: return
        if (disposed) return
        if (player.status == MediaPlayer.Status.HALTED) {
            fail(player.error?.type ?: MediaException.Type.PLAYBACK_HALTED, player.error?.message, player.error)
            return
        }
        if (statusWrapper.get() == PlaybackStatus.FAILED) {
            if (!tentativeFailure || player.status !in SEEKABLE) return
            log.log(System.Logger.Level.INFO, "$file plays after all: the error while loading was another media's")
            tentativeFailure = false
            errorWrapper.set(null)
        }
        if (ended) {
            statusWrapper.set(PlaybackStatus.ENDED)
            return
        }
        if (player.status in SEEKABLE) loaded = true
        val pending = pendingSeek
        if (pending != null && player.status in SEEKABLE) {
            pendingSeek = null
            player.seek(pending)
        }
        mapStatus(player.status)?.let(statusWrapper::set)
    }

    /**
     * An error JavaFX reported to this player. JavaFX sends the errors of every media being opened to all live players
     * (`MediaPlayer.init` registers each player's listener globally, `MediaUtils.nativeError` calls them all) and sets
     * them as their `error`, without stopping them. Which media an error belongs to cannot be told, so: an error of a
     * halted player fails it; one while loading fails it until the player gets ready after all; one after loading is
     * logged and ignored (also when it was this player's own). Internal: tests report an error at a chosen moment.
     */
    internal fun reported(
        type: MediaException.Type?,
        message: String?,
        cause: Throwable? = null,
    ) {
        if (disposed) return
        val halted = player?.status == MediaPlayer.Status.HALTED
        if (loaded && !halted) {
            log.log(System.Logger.Level.INFO, "Ignoring an error while $file plays on: $message")
            return
        }
        fail(type, message, cause, tentative = !halted)
    }

    private fun fail(
        type: MediaException.Type?,
        message: String?,
        cause: Throwable?,
        tentative: Boolean = false,
    ) {
        if (statusWrapper.get() == PlaybackStatus.FAILED) {
            if (!tentative) tentativeFailure = false
            return
        }
        log.log(System.Logger.Level.INFO, "Cannot play $file: $type $message", cause)
        tentativeFailure = tentative
        errorWrapper.set(describe(type, message))
        statusWrapper.set(PlaybackStatus.FAILED)
    }

    override fun play() {
        val player = player ?: return
        if (disposed || statusWrapper.get() == PlaybackStatus.FAILED) return
        // After the end the player is stopped, and a stopped player plays from the beginning.
        ended = false
        player.play()
    }

    override fun pause() {
        if (disposed || ended) return
        player?.pause()
    }

    override fun seek(to: Duration) {
        val player = player ?: return
        if (disposed || statusWrapper.get() == PlaybackStatus.FAILED) return
        // Clamped like JavaFX clamps a seek (start and stop time are never set: 0 and the length).
        val total = totalDurationWrapper.get()
        val target =
            (
                if (total.isUnknown ||
                    total.isIndefinite
                ) {
                    to
                } else {
                    to.coerceAtMost(total)
                }
            ).coerceAtLeast(Duration.ZERO)
        if (!ended && pendingSeek == null) {
            player.seek(target)
            // JavaFX reports the time only while playing: after a seek before the first play its currentTime moves
            // about a second later and no listener is told (seen in READY; PAUSED is inferred). Show the target.
            currentTimeWrapper.set(target)
            return
        }
        ended = false
        pendingSeek = target
        currentTimeWrapper.set(target)
        player.pause()
        updateStatus()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        // Nothing plays any more: a view listening must not stay on "playing".
        if (statusWrapper.get() != PlaybackStatus.FAILED) statusWrapper.set(PlaybackStatus.PAUSED)
        player?.let {
            it.volumeProperty().unbind()
            it.muteProperty().unbind()
            it.dispose()
        }
    }

    /** The wrapped player, for tests that check what reached it; `null` if the file could not be opened. */
    internal val mediaPlayer: MediaPlayer? get() = player

    override fun createVideoNode(): Node? =
        player?.takeUnless { disposed }?.let { MediaView(it).apply { isPreserveRatio = true } }

    /** A double property whose [set] keeps the value within [min] and [max]. */
    private class ClampedDoubleProperty(
        initial: Double,
        private val min: Double,
        private val max: Double,
    ) : SimpleDoubleProperty(initial) {
        override fun set(newValue: Double) = super.set(if (newValue.isNaN()) get() else newValue.coerceIn(min, max))
    }

    internal companion object {
        /** Our status for the player's; `null` keeps the current one (DISPOSED). HALTED is handled as a failure. */
        fun mapStatus(status: MediaPlayer.Status?): PlaybackStatus? =
            when (status) {
                null, MediaPlayer.Status.UNKNOWN -> PlaybackStatus.LOADING
                MediaPlayer.Status.READY -> PlaybackStatus.READY
                MediaPlayer.Status.PLAYING, MediaPlayer.Status.STALLED -> PlaybackStatus.PLAYING
                MediaPlayer.Status.PAUSED, MediaPlayer.Status.STOPPED -> PlaybackStatus.PAUSED
                MediaPlayer.Status.HALTED -> PlaybackStatus.FAILED
                MediaPlayer.Status.DISPOSED -> null
            }

        /** The reason shown after "Cannot play <name>: "; the technical message only when the type says nothing. */
        fun describe(
            type: MediaException.Type?,
            message: String?,
        ): String =
            when (type) {
                MediaException.Type.MEDIA_UNSUPPORTED -> "unsupported media format"
                MediaException.Type.MEDIA_UNAVAILABLE -> "media unavailable"
                MediaException.Type.MEDIA_INACCESSIBLE -> "media inaccessible"
                MediaException.Type.MEDIA_CORRUPTED -> "media corrupted"
                MediaException.Type.OPERATION_UNSUPPORTED -> "operation not supported"
                MediaException.Type.PLAYBACK_HALTED -> "playback halted"
                MediaException.Type.PLAYBACK_ERROR -> "playback error"
                MediaException.Type.MEDIA_UNSPECIFIED, MediaException.Type.UNKNOWN, null -> null
            } ?: message
                ?.replace(EXCEPTION_PREFIX, "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "unknown error"

        /** "com.sun.media.jfxmedia.MediaException: " before the message of a wrapped exception. */
        private val EXCEPTION_PREFIX = Regex("""^(?:[\w$]+\.)+\w*Exception:\s*""")

        private val SEEKABLE = setOf(MediaPlayer.Status.READY, MediaPlayer.Status.PAUSED, MediaPlayer.Status.PLAYING)

        private fun cycles(loop: Boolean) = if (loop) MediaPlayer.INDEFINITE else 1
    }
}
