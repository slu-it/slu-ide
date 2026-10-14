package dev.sluide.settings

import javafx.application.ColorScheme
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Windows follow the theme, including windows opened later. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ThemeUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage
    private val system = SimpleObjectProperty(ColorScheme.LIGHT)
    private val settings by lazy { ThemeSettings(SettingsStore(dir.resolve("settings.properties")), { system }) }

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(Label("main")), 200.0, 100.0)
        stage.show()
    }

    private val themed by lazy { ThemedWindows(settings) }

    private fun install(robot: FxRobot) {
        robot.interact { themed.install() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    @AfterEach
    fun uninstall(robot: FxRobot) {
        robot.interact { themed.uninstall() }
    }

    private fun Region.isDark(): Boolean {
        val fill = background.fills.first().fill as Color
        return fill.brightness < 0.3
    }

    @Test
    fun `the dark theme darkens open windows and windows opened later`(robot: FxRobot) {
        install(robot)
        val root = stage.scene.root as Region
        assertEquals(ColorScheme.LIGHT, stage.scene.preferences.colorScheme)
        assertTrue(!root.isDark(), "light by default")

        robot.interact { settings.apply(Theme.DARK) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(ColorScheme.DARK, stage.scene.preferences.colorScheme)
        assertTrue(root.isDark(), "dark after applying the dark theme")

        lateinit var second: Stage
        robot.interact {
            second = Stage().apply { scene = Scene(StackPane(Label("second")), 100.0, 50.0) }
            second.show()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue((second.scene.root as Region).isDark(), "a window opened later is dark, too")
        robot.interact { second.close() }
    }

    @Test
    fun `a window that was open before the install is themed when it gets a new scene`(robot: FxRobot) {
        install(robot)
        robot.interact { settings.apply(Theme.DARK) }

        robot.interact { stage.scene = Scene(StackPane(Label("new scene")), 200.0, 100.0) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(ColorScheme.DARK, stage.scene.preferences.colorScheme)
        assertTrue((stage.scene.root as Region).isDark())
    }

    @Test
    fun `the system theme follows the operating system`(robot: FxRobot) {
        install(robot)

        robot.interact { system.set(ColorScheme.DARK) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(ColorScheme.DARK, stage.scene.preferences.colorScheme)
        assertTrue((stage.scene.root as Region).isDark())
    }
}
