package dev.sluide.docker.settings

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("settings.properties")

    private fun settings() = DockerSettings(SettingsStore(file))

    @Test
    fun `without a stored value or with a blank one the executable is docker`() {
        assertEquals("docker", settings().executable.get())
        file.writeText("docker.executable=  \n")
        assertEquals("docker", settings().executable.get())
    }

    @Test
    fun `an applied executable is trimmed, notifies listeners and is stored for the next start`() {
        val settings = settings()
        val seen = mutableListOf<String>()
        settings.executable.subscribe { value -> seen += value }

        settings.apply("  /opt/podman/bin/podman ")

        assertEquals(listOf("docker", "/opt/podman/bin/podman"), seen)
        assertTrue("docker.executable=/opt/podman/bin/podman" in file.readText())
        assertEquals("/opt/podman/bin/podman", settings().executable.get())
        settings.apply("")
        assertEquals("docker", settings().executable.get(), "blank goes back to the default")
    }

    @Test
    fun `an unchanged executable is not written, and one that cannot be stored is not set`() {
        settings().apply("docker")
        assertFalse(file.exists())

        file.createDirectories()
        val settings = settings()
        assertFailsWith<IOException> { settings.apply("podman") }
        assertEquals("docker", settings.executable.get())
    }
}
