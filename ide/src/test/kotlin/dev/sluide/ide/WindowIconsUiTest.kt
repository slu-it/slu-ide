package dev.sluide.ide

import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.image.WritableImage
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stages get the Thoreau logo, including stages opened later. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class WindowIconsUiTest {
    private lateinit var stage: Stage
    private val windowIcons = WindowIcons()
    private val opened = mutableListOf<Stage>()

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(Label("main")), 200.0, 100.0)
        stage.show()
    }

    @AfterEach
    fun cleanUp(robot: FxRobot) {
        robot.interact {
            windowIcons.uninstall()
            // The primary stage is shared by all UI test classes.
            stage.icons.clear()
            opened.forEach(Stage::hide)
        }
    }

    private fun open(
        robot: FxRobot,
        configure: Stage.() -> Unit = {},
    ): Stage {
        lateinit var other: Stage
        robot.interact {
            other = Stage().apply(configure)
            other.scene = Scene(StackPane(Label("other")), 100.0, 50.0)
            other.show()
        }
        WaitForAsyncUtils.waitForFxEvents()
        return other.also(opened::add)
    }

    @Test
    fun `open stages and stages opened later get the logo in every size`(robot: FxRobot) {
        robot.interact { windowIcons.install() }
        assertEquals(listOf(16.0, 32.0, 64.0, 128.0, 256.0), stage.icons.map { it.width })
        assertTrue(stage.icons.none { it.isError }, "all images load")

        assertEquals(stage.icons.toList(), open(robot).icons.toList())
    }

    @Test
    fun `a stage with icons of its own keeps them`(robot: FxRobot) {
        robot.interact { windowIcons.install() }
        val own = WritableImage(8, 8)

        val other = open(robot) { icons += own }

        assertEquals(listOf(own), other.icons.toList())
    }
}
