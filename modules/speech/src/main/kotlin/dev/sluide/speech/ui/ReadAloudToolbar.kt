package dev.sluide.speech.ui

import dev.sluide.media.PlaybackSpeeds
import dev.sluide.speech.engine.Gender
import dev.sluide.speech.engine.Voice
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
import java.util.Locale
import kotlin.math.abs

/** A row of the voice picker: a language heading, which cannot be chosen, or a voice. */
internal sealed interface VoiceItem {
    data class Heading(
        val text: String,
    ) : VoiceItem

    data class Entry(
        val voice: Voice,
    ) : VoiceItem
}

/** The picker rows: [voices] under a heading per language, in their order. */
internal fun voiceItems(voices: List<Voice>): List<VoiceItem> =
    voices
        .groupBy { it.language }
        .flatMap { (language, group) ->
            listOf(VoiceItem.Heading(languageName(language))) + group.map(VoiceItem::Entry)
        }

internal fun languageName(language: String): String =
    when (language) {
        "en-US" -> "American English"
        "en-GB" -> "British English"
        else -> language
    }

/** `Heart · female · A`. */
internal fun voiceLabel(voice: Voice): String =
    listOfNotNull(voice.name, if (voice.gender == Gender.FEMALE) "female" else "male", voice.grade).joinToString(" · ")

/** `1×`, `0.75×`. */
internal fun speedLabel(speed: Double): String = "${"%.2f".format(Locale.ROOT, speed).trimEnd('0').trimEnd('.')}×"

/**
 * The toolbar of the Read Aloud tab: Play/Pause and Stop, the voice picker, the speed menu, and the status text at the
 * right. It forwards the user's choices to [model] and shows what [show] is given.
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
            tooltip = Tooltip("Stop (Esc)")
            setOnAction { model.stop() }
        }
    private val voice =
        ComboBox<VoiceItem>().apply {
            id = "speech-voice"
            isFocusTraversable = false
            items.setAll(voiceItems(model.voices))
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
                RadioMenuItem(speedLabel(rate)).apply {
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
    private var updating = false

    val node: HBox =
        HBox(
            6.0,
            play,
            stop,
            voice,
            speed,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            status,
        ).apply {
            styleClass += "speech-toolbar"
            alignment = Pos.CENTER_LEFT
        }

    init {
        speed.items.addAll(speedItems)
        voice.setCellFactory { VoiceCell() }
        voice.buttonCell = VoiceCell()
        voice.valueProperty().subscribe { _, item ->
            if (updating) return@subscribe
            when (item) {
                is VoiceItem.Entry -> model.setVoice(item.voice)
                else -> show(model.ui)
            }
        }
        play.setOnAction { if (mode == PlayMode.PLAY) model.play(caret()) else model.togglePause() }
        show(model.ui)
    }

    fun show(ui: ReadAloudUi) {
        mode = ui.playMode
        val (icon, tip) =
            when (ui.playMode) {
                PlayMode.PLAY -> MaterialDesignP.PLAY to "Play (Shortcut+Enter)"
                PlayMode.PAUSE -> MaterialDesignP.PAUSE to "Pause (Space)"
                PlayMode.RESUME -> MaterialDesignP.PLAY to "Resume (Space)"
            }
        play.graphic = FontIcon(icon)
        play.tooltip = Tooltip(tip)
        play.isDisable = !ui.playEnabled
        stop.isDisable = !ui.stopEnabled
        status.text = ui.status
        speed.text = speedLabel(ui.speed)
        PlaybackSpeeds.ALL.forEachIndexed { i, rate -> speedItems[i].isSelected = abs(rate - ui.speed) < 1e-9 }
        updating = true
        try {
            voice.value = voice.items.firstOrNull { it is VoiceItem.Entry && it.voice == ui.voice }
        } finally {
            updating = false
        }
    }

    private class VoiceCell : ListCell<VoiceItem>() {
        override fun updateItem(
            item: VoiceItem?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            val heading = item is VoiceItem.Heading
            isDisable = heading
            styleClass.remove("speech-voice-heading")
            if (heading) styleClass += "speech-voice-heading"
            text =
                when (item) {
                    is VoiceItem.Heading -> item.text
                    is VoiceItem.Entry -> voiceLabel(item.voice)
                    null -> null
                }
        }
    }
}
