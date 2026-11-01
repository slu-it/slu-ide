package dev.sluide.docker.settings

import dev.sluide.settings.SettingsStore
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real Docker page: its field is bound to the page's draft both ways, which only a created page shows; the draft's
 * rules are in [DockerSettingsPageModelTest]. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DockerSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy { DockerSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val page by lazy { DockerSettingsPage(settings) }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    @Test
    fun `the field shows the setting and what is typed into it is applied`(robot: FxRobot) {
        assertEquals(listOf("Docker"), page.path)
        var shown = ""
        robot.interact {
            page.node
            page.load()
            shown = page.executable.text
        }
        assertEquals("docker", shown)

        robot.interact {
            page.executable.text = "podman"
            page.apply()
        }
        assertEquals("podman", settings.executable.get())

        robot.interact {
            page.executable.text = ""
            page.load()
            shown = page.executable.text
        }
        assertEquals("podman", shown, "loading shows the stored value again")
    }
}
