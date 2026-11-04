package dev.sluide.media

import javafx.beans.property.BooleanProperty
import javafx.beans.property.DoubleProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableMap
import javafx.geometry.Dimension2D
import javafx.scene.Node
import javafx.util.Duration

/**
 * A [Playback] for tests without a player: every property is settable, and play, pause, seek and dispose are recorded
 * in [calls] (`"play"`, `"pause"`, `"seek 10000.0 ms"`, `"dispose"`). [play] and [pause] also set [status], as a
 * player would; [seek] sets [currentTime]. Needs no toolkit.
 */
class FakePlayback(
    initialStatus: PlaybackStatus = PlaybackStatus.READY,
    total: Duration = Duration.minutes(3.0),
) : Playback {
    override val status: ObjectProperty<PlaybackStatus> = SimpleObjectProperty(initialStatus)
    override val currentTime: ObjectProperty<Duration> = SimpleObjectProperty(Duration.ZERO)
    override val totalDuration: ObjectProperty<Duration> = SimpleObjectProperty(total)
    override val error: StringProperty = SimpleStringProperty()
    override val metadata: ObservableMap<String, Any> = FXCollections.observableHashMap()
    override val videoSize: ObjectProperty<Dimension2D?> = SimpleObjectProperty()
    override val volume: DoubleProperty = SimpleDoubleProperty(1.0)
    override val muted: BooleanProperty = SimpleBooleanProperty(false)
    override val rate: DoubleProperty = SimpleDoubleProperty(1.0)
    override val loop: BooleanProperty = SimpleBooleanProperty(false)

    val calls = mutableListOf<String>()

    override fun play() {
        calls += "play"
        status.set(PlaybackStatus.PLAYING)
    }

    override fun pause() {
        calls += "pause"
        status.set(PlaybackStatus.PAUSED)
    }

    override fun seek(to: Duration) {
        calls += "seek $to"
        currentTime.set(to)
    }

    override fun dispose() {
        calls += "dispose"
    }

    override fun createVideoNode(): Node? = null
}
