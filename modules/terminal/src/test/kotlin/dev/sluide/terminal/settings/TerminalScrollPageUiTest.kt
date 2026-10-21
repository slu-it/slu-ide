package dev.sluide.terminal.settings

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

/** Shows the real Terminal → Scrolling page. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TerminalScrollPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy { TerminalScrollSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val page by lazy { TerminalScrollPage(settings) }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    @Test
    fun `the page is Terminal Scrolling and shows the default speeds`(robot: FxRobot) {
        assertEquals(listOf("Terminal", "Scrolling"), page.path)
        robot.interact {
            page.node
            page.load()
        }
        assertEquals(100.0, page.trackpad.value)
        assertEquals("100 %", page.wheelValue.text)
    }

    @Test
    fun `the scroll speeds change on apply, in steps of 5 percent`(robot: FxRobot) {
        robot.interact {
            page.node
            page.load()
        }

        robot.interact {
            page.trackpad.value = 36.0
            page.wheel.decrement()
        }
        assertEquals(35.0, page.trackpad.value, "whole multiples of 5")
        assertEquals("35 %", page.trackpadValue.text)
        assertEquals("95 %", page.wheelValue.text)
        assertEquals(100, settings.trackpad.get(), "not applied yet")

        robot.interact { page.apply() }
        assertEquals(35, settings.trackpad.get())
        assertEquals(95, settings.wheel.get())
    }
}
