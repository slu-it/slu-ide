package dev.sluide.fileviewers.settings

import dev.sluide.librarycode.LibraryCodeSettings
import dev.sluide.settings.SettingsStore
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real Editor page: its check box reaches the draft of the model and OK or Apply the setting, which only a created
 * page shows; the model's rules are in [EditorSettingsPageModelTest]. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy { LibraryCodeSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val page by lazy { EditorSettingsPage(settings) }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(page.node as Parent, 500.0, 200.0)
        stage.show()
    }

    @Test
    fun `the check box shows the setting, and apply stores what it says`(robot: FxRobot) {
        robot.interact { page.load() }
        assertTrue(page.libraryCheckBox.isSelected, "on by default")

        robot.interact { page.libraryCheckBox.fire() }
        assertTrue(settings.readOnly.get(), "a draft changes nothing")
        robot.interact { page.apply() }
        assertFalse(settings.readOnly.get())

        robot.interact { page.libraryCheckBox.fire() }
        robot.interact { page.load() }
        assertFalse(page.libraryCheckBox.isSelected, "load drops the unapplied edit")
    }
}
