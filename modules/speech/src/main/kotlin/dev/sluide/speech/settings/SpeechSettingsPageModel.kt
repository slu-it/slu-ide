package dev.sluide.speech.settings

import dev.sluide.localmodels.InstallState
import dev.sluide.speech.install.KokoroVariant
import dev.sluide.speech.install.ModelInstaller
import dev.sluide.speech.install.formatMegabytes
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty

/** The questions the page asks; tests replace them (ADR 0084). */
internal interface SpeechSettingsDialogs {
    /** Asks [question] with a Delete button and Cancel, which is the default; true for Delete. */
    fun confirmDelete(question: String): Boolean
}

/**
 * The edit on the Speech page: a draft path and variant, applied to [settings] only by [apply]. Download, Cancel and
 * Delete act at once through the [installer].
 */
internal class SpeechSettingsPageModel(
    private val settings: SpeechSettings,
    private val installer: ModelInstaller,
    private val dialogs: SpeechSettingsDialogs,
) {
    val espeak: StringProperty = SimpleStringProperty(settings.espeakPath.get())
    val variant: ObjectProperty<KokoroVariant> = SimpleObjectProperty(settings.variant.get())

    fun load() {
        espeak.set(settings.espeakPath.get())
        variant.set(settings.variant.get())
    }

    fun apply() {
        settings.apply(espeak.get() ?: "", variant.get())
        load()
    }

    fun download(v: KokoroVariant) = installer.download(v, null)

    fun cancel(v: KokoroVariant) = installer.cancel(v)

    /** Deletes [v] after the question; nothing when it is answered with Cancel. */
    fun delete(v: KokoroVariant) {
        if (dialogs.confirmDelete(deleteQuestion(v))) installer.delete(v)
    }

    companion object {
        fun rowLabel(v: KokoroVariant) = "${v.label} · ${formatMegabytes(v.bytes)}"

        fun deleteQuestion(v: KokoroVariant) =
            "Delete the ${v.label} voice model? It must be downloaded again (${formatMegabytes(v.bytes)}) " +
                "to read aloud with it."

        fun statusText(state: InstallState): String =
            when (state) {
                InstallState.NotInstalled -> "Not installed"
                InstallState.Installed -> "Installed"
                is InstallState.Downloading -> "${(state.progress * 100).toInt()} %"
                is InstallState.Failed -> state.message
            }
    }
}
