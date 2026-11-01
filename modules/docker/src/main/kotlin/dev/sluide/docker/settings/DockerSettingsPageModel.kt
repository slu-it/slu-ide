package dev.sluide.docker.settings

import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty

/** The edit on the Docker page: a draft executable, applied to [settings] only by [apply]. */
internal class DockerSettingsPageModel(
    private val settings: DockerSettings,
) {
    val executable: StringProperty = SimpleStringProperty(settings.executable.get())

    /** Drops the edit: the draft shows the current executable again. */
    fun load() = executable.set(settings.executable.get())

    /** Stores the draft; a blank draft means the default and shows it. */
    fun apply() {
        settings.apply(executable.get() ?: "")
        executable.set(settings.executable.get())
    }
}
