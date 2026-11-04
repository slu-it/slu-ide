package dev.sluide.media

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.Slider
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignP
import org.kordamp.ikonli.materialdesign2.MaterialDesignR
import org.kordamp.ikonli.materialdesign2.MaterialDesignV
import kotlin.math.abs

/**
 * The control bar of the media view (ADR 0123): skip back and ahead, play and pause, times, progress, speed, loop,
 * mute and volume. FX thread only. [onPlay] runs right before every play that a user starts (button or key).
 * [install] adds the keys (Space, arrows, M) to the view.
 *
 * A bar lives exactly as long as its playback: it adds strong listeners to the playback and has no `dispose`, so a new
 * bar is built only together with a new playback.
 */
class MediaControlBar(
    private val playback: Playback,
    onPlay: () -> Unit,
) {
    private val actions = MediaControlActions(playback, onPlay)

    private val back =
        iconButton(MaterialDesignR.REWIND_10, "Back 10 s (←)") { actions.skip(-MediaControlActions.SKIP_SECONDS) }
    private val playPause = iconButton(MaterialDesignP.PLAY, "Play (Space)") { actions.togglePlay() }
    private val ahead =
        iconButton(MaterialDesignF.FAST_FORWARD_10, "Ahead 10 s (→)") { actions.skip(MediaControlActions.SKIP_SECONDS) }
    private val current = timeLabel()
    private val total = timeLabel()
    private val progress =
        Slider(0.0, 1.0, 0.0).apply {
            styleClass += "media-progress"
            isFocusTraversable = false
        }
    private val speed =
        MenuButton().apply {
            styleClass += "media-speed"
            isFocusTraversable = false
            tooltip = Tooltip("Speed")
        }
    private val loop =
        ToggleButton(null, FontIcon(MaterialDesignR.REPEAT)).apply {
            styleClass += "media-button"
            isFocusTraversable = false
            tooltip = Tooltip("Loop")
        }
    private val mute =
        ToggleButton().apply {
            styleClass += "media-button"
            isFocusTraversable = false
            tooltip = Tooltip("Mute (M)")
        }
    private val volume =
        Slider(0.0, 1.0, 1.0).apply {
            styleClass += "media-volume"
            isFocusTraversable = false
            prefWidth = 90.0
            tooltip = Tooltip("Volume (↑/↓)")
        }
    private val sync = ProgressSync(playback, { progress.max = it }, { progress.value = it })

    val node: Node =
        HBox(6.0, back, playPause, ahead, current, progress, total, speed, loop, mute, volume).apply {
            styleClass += "media-controls"
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(progress, Priority.ALWAYS)
            stylesheets += MediaControlBar::class.java.getResource("media-controls.css")!!.toExternalForm()
        }

    init {
        playback.status.addListener { _, _, _ -> showStatus() }
        showStatus()

        playback.currentTime.addListener { _, _, _ -> showTime() }
        playback.totalDuration.addListener { _, _, _ -> showTime() }
        progress.valueProperty().addListener { _, _, v -> sync.valueChanged(v.toDouble()) }
        progress.valueChangingProperty().addListener {
            _,
            _,
            changing,
            ->
            sync.draggingChanged(changing, progress.value)
        }
        showTime()

        buildSpeedMenu()
        loop.selectedProperty().bindBidirectional(playback.loop)
        mute.selectedProperty().bindBidirectional(playback.muted)
        playback.muted.addListener { _, _, _ -> showMute() }
        showMute()
        volume.valueProperty().bindBidirectional(playback.volume)
    }

    /** Space, arrows and M on [target] control the playback; an event handler, not a filter. Call once per bar. */
    fun install(target: Node) {
        // A click on a slider focuses it, and its own keys (Left, Right, Home, End) would then win over the media
        // keys: give the focus back to the view.
        for (slider in listOf(progress, volume)) {
            slider.focusedProperty().addListener { _, _, focused -> if (focused) target.requestFocus() }
        }
        target.addEventHandler(KeyEvent.KEY_PRESSED) { e ->
            val modifier = e.isShiftDown || e.isControlDown || e.isAltDown || e.isMetaDown
            val action = MediaKeys.actionFor(e.code, modifier) ?: return@addEventHandler
            if (actions.perform(action)) e.consume()
        }
    }

    private fun showStatus() {
        val status = playback.status.get()
        val playing = status == PlaybackStatus.PLAYING
        playPause.graphic = FontIcon(if (playing) MaterialDesignP.PAUSE else MaterialDesignP.PLAY)
        playPause.tooltip.text = if (playing) "Pause (Space)" else "Play (Space)"
        node.isDisable = !actions.enabled
    }

    private fun showTime() {
        val t = playback.totalDuration.get()
        progress.isDisable = !ProgressSync.known(t)
        current.text = PlaybackMath.format(playback.currentTime.get())
        total.text = PlaybackMath.format(t)
        sync.playbackMoved()
    }

    private fun showMute() {
        mute.graphic = FontIcon(if (playback.muted.get()) MaterialDesignV.VOLUME_OFF else MaterialDesignV.VOLUME_HIGH)
    }

    private fun buildSpeedMenu() {
        val group = ToggleGroup()
        val items =
            PlaybackSpeeds.ALL.map { rate ->
                RadioMenuItem(PlaybackSpeeds.label(rate)).apply {
                    toggleGroup = group
                    setOnAction { playback.rate.set(rate) }
                }
            }
        speed.items.addAll(items)

        fun show() {
            val rate = playback.rate.get()
            speed.text = PlaybackSpeeds.label(rate)
            items.forEachIndexed { i, item -> item.isSelected = abs(PlaybackSpeeds.ALL[i] - rate) < 1e-9 }
        }
        playback.rate.addListener { _, _, _ -> show() }
        show()
    }

    private fun iconButton(
        icon: Ikon,
        tip: String,
        onClick: () -> Unit,
    ) = Button(null, FontIcon(icon)).apply {
        styleClass += "media-button"
        isFocusTraversable = false
        tooltip = Tooltip(tip)
        setOnAction { onClick() }
    }

    private fun timeLabel() = Label("--:--").apply { styleClass += "media-time" }
}
