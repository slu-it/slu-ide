package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.CssColorParser
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.Clipboard
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The rows, the copy buttons and the clipboard need a scene. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FormatsSectionUiTest {
    private val state = ColoringState()
    private lateinit var formats: FormatsSection

    @Start
    fun start(stage: Stage) {
        formats = FormatsSection(state)
        state.current.set(CssColor.fromRgb8(59, 130, 246))
        stage.scene = Scene(VBox(formats.node), 340.0, 700.0)
        stage.show()
    }

    private fun labels(robot: FxRobot) =
        robot.lookup(".coloring-format-label").queryAllAs(Label::class.java).map { it.text }

    private fun visibleMarkers(robot: FxRobot) =
        robot.lookup(".coloring-approximate").queryAllAs(Label::class.java).count { it.isVisible }

    @Test
    fun `a format row's copy button puts its text on the clipboard`(robot: FxRobot) {
        val copy = robot.lookup(".coloring-copy").queryAllAs(Button::class.java).first()

        robot.interact { copy.fire() }

        var clip: String? = null
        robot.interact { clip = Clipboard.getSystemClipboard().string }
        assertEquals("#3b82f6", clip)
    }

    @Test
    fun `the name row appears only for an exact named color`(robot: FxRobot) {
        assertTrue("Name" !in labels(robot))

        robot.interact { state.current.set(CssColor.fromRgb8(255, 0, 0)) }
        assertTrue("Name" in labels(robot))

        robot.interact { state.current.set(CssColor.fromRgb8(59, 130, 246)) }
        assertTrue("Name" !in labels(robot))
    }

    @Test
    fun `the approximate marker shows for a color outside sRGB only`(robot: FxRobot) {
        assertEquals(0, visibleMarkers(robot))

        robot.interact { state.current.set(checkNotNull(CssColorParser.parse("oklch(0.7 0.35 150)"))) }
        assertTrue(visibleMarkers(robot) > 0)

        robot.interact { state.current.set(CssColor.fromRgb8(59, 130, 246)) }
        assertEquals(0, visibleMarkers(robot))
    }

    @Test
    fun `after dispose an external change no longer updates the rows`(robot: FxRobot) {
        robot.interact {
            formats.dispose()
            state.current.set(CssColor.fromRgb8(0, 255, 0))
        }

        assertEquals("#3b82f6", robot.lookup(".coloring-format-text").queryAs(TextField::class.java).text)
    }
}
