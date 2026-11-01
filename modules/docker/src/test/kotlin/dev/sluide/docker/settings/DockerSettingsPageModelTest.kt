package dev.sluide.docker.settings

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DockerSettingsPageModelTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy { DockerSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val model by lazy { DockerSettingsPageModel(settings) }

    @Test
    fun `the edit stays a draft until applied`() {
        model.executable.set("podman")
        assertEquals("docker", settings.executable.get())

        model.apply()

        assertEquals("podman", settings.executable.get())
    }

    @Test
    fun `loading drops the edit, and applying a blank draft shows the default`() {
        model.executable.set("podman")
        model.load()
        assertEquals("docker", model.executable.get())

        model.executable.set(" ")
        model.apply()
        assertEquals("docker", model.executable.get())
    }
}
