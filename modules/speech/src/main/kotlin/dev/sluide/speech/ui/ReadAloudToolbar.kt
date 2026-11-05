package dev.sluide.speech.ui

import dev.sluide.media.PlaybackSpeeds
import dev.sluide.speech.engine.Gender
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.MenuButton
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignP
import org.kordamp.ikonli.materialdesign2.MaterialDesignS
import kotlin.math.abs

/**
 * The toolbar of the Read Aloud tab: Play/Pause and Stop, the accent and gender pickers, the speed menu, and the status
 * text at the right. It forwards the user's choices to [model] and shows what [show] is given.
 */
internal class ReadAloudToolbar(
    private val model: ReadAloudModel,
    private val caret: () -> Int,
) {
    private val play =
        Button().apply {
            id = "speech-play"
            styleClass += "speech-button"
            isFocusTraversable = false
        }
    private val stop =
        Button(null, FontIcon(MaterialDesignS.STOP)).apply {
            id = "speech-stop"
            styleClass += "speech-button"
            isFocusTraversable = false
            tooltip = Tooltip("Stop (Escape)")
            setOnAction { model.stop() }
        }
    private val accent =
        ComboBox<String>().apply {
            id = "speech-accent"
            isFocusTraversable = false
            tooltip = Tooltip("Accent")
            setCellFactory { NamedCell(VoiceChoice::accentName) }
            buttonCell = NamedCell(VoiceChoice::accentName)
            items.setAll(VoiceChoice.accents(model.voices))
        }
    private val gender =
        ComboBox<Gender>().apply {
            id = "speech-gender"
            isFocusTraversable = false
            tooltip = Tooltip("Voice gender")
            setCellFactory { NamedCell(VoiceChoice::genderName) }
            buttonCell = NamedCell(VoiceChoice::genderName)
        }
    private val speed =
        MenuButton().apply {
            id = "speech-speed"
            isFocusTraversable = false
            tooltip = Tooltip("Speed")
        }
    private val speedItems =
        ToggleGroup().let { group ->
            PlaybackSpeeds.ALL.map { rate ->
                RadioMenuItem(PlaybackSpeeds.label(rate)).apply {
                    toggleGroup = group
                    setOnAction { model.setSpeed(rate) }
                }
            }
        }
    private val status =
        Label().apply {
            id = "speech-status"
            styleClass += "speech-status"
        }
    private var mode = PlayMode.PLAY
    private var shownMode: PlayMode? = null
    private var updating = false

    val node: HBox =
        HBox(
            6.0,
            play,
            stop,
            accent,
            gender,
            speed,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            status,
        ).apply {
            styleClass += "speech-toolbar"
            alignment = Pos.CENTER_LEFT
        }

    init {
        speed.items.addAll(speedItems)
        accent.valueProperty().subscribe { _, language ->
            if (updating || language == null) return@subscribe
            val voice = VoiceChoice.voiceFor(model.voices, language, model.ui.voice?.gender ?: Gender.FEMALE)
            if (voice != null) model.setVoice(voice)
            show(model.ui)
        }
        gender.valueProperty().subscribe { _, chosen ->
            if (updating || chosen == null) return@subscribe
            val language = accent.value ?: model.ui.voice?.language
            val voice = language?.let { VoiceChoice.voiceFor(model.voices, it, chosen) }
            if (voice != null) model.setVoice(voice)
            show(model.ui)
        }
        play.setOnAction { if (mode == PlayMode.PLAY) model.play(caret()) else model.togglePause() }
        show(model.ui)
    }

    fun show(ui: ReadAloudUi) {
        mode = ui.playMode
        if (shownMode != ui.playMode) {
            shownMode = ui.playMode
            val (icon, tip) =
                when (ui.playMode) {
                    PlayMode.PLAY -> MaterialDesignP.PLAY to "Play (Shortcut+Enter)"
                    PlayMode.PAUSE -> MaterialDesignP.PAUSE to "Pause (Space)"
                    PlayMode.RESUME -> MaterialDesignP.PLAY to "Resume (Space)"
                }
            play.graphic = FontIcon(icon)
            play.tooltip = Tooltip(tip)
        }
        play.isDisable = !ui.playEnabled
        stop.isDisable = !ui.stopEnabled
        status.text = ui.status
        speed.text = PlaybackSpeeds.label(ui.speed)
        PlaybackSpeeds.ALL.forEachIndexed { i, rate -> speedItems[i].isSelected = abs(rate - ui.speed) < 1e-9 }
        updating = true
        try {
            val shown = ui.voice
            accent.value = shown?.language
            // Replaced only when they differ: show() runs at least once per sentence, and an open list would reset.
            val genders = shown?.let { VoiceChoice.genders(model.voices, it.language) }.orEmpty()
            if (gender.items != genders) gender.items.setAll(genders)
            gender.value = shown?.gender
        } finally {
            updating = false
        }
    }

    private class NamedCell<T>(
        private val name: (T) -> String,
    ) : ListCell<T>() {
        override fun updateItem(
            item: T?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else name(item)
        }
    }
}
