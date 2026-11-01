package dev.sluide.docker.settings

import dev.sluide.docker.cli.DEFAULT_DOCKER_EXECUTABLE
import dev.sluide.settings.SettingsStore
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import org.springframework.stereotype.Component

private const val EXECUTABLE_KEY = "docker.executable"

/**
 * The Docker settings (ADR 0117): which CLI Thoreau runs, [executable]: `docker` (the default), `podman`, or the full
 * path of either. Windows' `DockerSession`s follow it; the settings dialog changes it with [apply], which also stores
 * it in the [SettingsStore] under `docker.executable`.
 */
@Component
class DockerSettings(
    private val store: SettingsStore,
) {
    private val executableWrapper = ReadOnlyStringWrapper(read(store.load()))

    /** The executable: a program name found through the login shell, or an absolute path; never blank. */
    val executable: ReadOnlyStringProperty = executableWrapper.readOnlyProperty

    /**
     * Stores [value], trimmed (blank: the default `docker`), and then sets it. Must be called on the FX thread, which
     * waits for the (small) settings file to be written.
     *
     * @throws java.io.IOException if it cannot be stored; it is then not changed either.
     */
    fun apply(value: String) {
        val executable = normalized(value)
        if (executable == executableWrapper.get()) return
        store.update(mapOf(EXECUTABLE_KEY to executable))
        executableWrapper.set(executable)
    }

    internal companion object {
        fun read(stored: Map<String, String>): String = normalized(stored[EXECUTABLE_KEY] ?: "")

        fun normalized(value: String): String = value.trim().ifEmpty { DEFAULT_DOCKER_EXECUTABLE }
    }
}
