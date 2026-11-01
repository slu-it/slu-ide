package dev.sluide.docker.compose

import dev.sluide.docker.settings.DockerSettings
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which files [ComposeFileViewer] opens: exactly its names and name patterns, ignoring case, as `FileViewers` ranks
 * them. That it wins over the YAML preview among all viewers is SpringWiringTest's (the `ide` has both).
 */
class ComposeFileViewerTest {
    @TempDir
    lateinit var dir: Path

    private val viewer by lazy {
        ComposeFileViewer(
            ComposeStore(ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())),
            DockerSettings(SettingsStore(dir.resolve("settings.properties"))),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml", "compose.override.yaml",
            "docker-compose.override.yml", "docker-compose.prod.yml", "compose.dev.yaml", "Docker-Compose.YML",
        ],
    )
    fun `opens compose files by name and pattern, ignoring case`(name: String) {
        assertTrue(viewer.canOpen(dir.resolve(name).apply { writeText("services: {}\n") }))
    }

    @ParameterizedTest
    @ValueSource(strings = ["other.yml", "my-compose.yml", "compose.a.b.yaml", "compose.yaml.bak", "compose.json"])
    fun `leaves other files to other viewers`(name: String) {
        assertFalse(viewer.canOpen(dir.resolve(name).apply { writeText("services: {}\n") }))
    }

    @Test
    fun `does not open a missing or binary compose file`() {
        assertFalse(viewer.canOpen(dir.resolve("compose.yaml")))
        assertFalse(viewer.canOpen(dir.resolve("compose.yml").apply { writeBytes(byteArrayOf(0, 1, 2, 0)) }))
    }
}
