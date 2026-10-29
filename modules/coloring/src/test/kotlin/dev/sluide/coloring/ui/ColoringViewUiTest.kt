package dev.sluide.coloring.ui

import dev.sluide.coloring.SavedColors
import dev.sluide.coloring.color.CssColor
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Coloring view needs a scene for its tabs and the swatch's mouse handling. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ColoringViewUiTest {
    private val state = ColoringState()
    private lateinit var view: ColoringView

    @Start
    fun start(stage: Stage) {
        view =
            ColoringView(state, savedColors = SavedColors(unusedStore()), projectFolder = { null })
        stage.scene = Scene(view.node as Parent, 900.0, 600.0)
        stage.show()
    }

    @Test
    fun `builds with the five tool tabs in order`() {
        assertEquals(
            listOf("Contrast", "Shades & Harmonies", "Mix", "Gradient", "Vision"),
            view.toolTabs.tabs.map { it.text },
        )
    }

    @Test
    fun `a primary click on a swatch makes its color the current color`(robot: FxRobot) {
        val red = CssColor.fromRgb8(255, 0, 0)
        val swatch = Swatch.of(red, state)

        robot.interact { click(swatch, MouseButton.SECONDARY) }
        assertEquals(CssColor.fromRgb8(59, 130, 246), state.current.get())

        robot.interact { click(swatch, MouseButton.PRIMARY) }
        assertEquals(red, state.current.get())
    }

    @Test
    fun `a disposed swatch no longer follows the color`(robot: FxRobot) {
        val followed = Swatch.of(state.second, state)
        val disposed = Swatch.of(state.second, state)
        robot.interact { disposed.dispose() }

        robot.interact { state.second.set(CssColor.fromRgb8(0, 255, 0)) }

        assertEquals("#00ff00", followed.tooltipText)
        assertEquals("#ffffff", disposed.tooltipText)
    }

    private fun click(
        swatch: Swatch,
        button: MouseButton,
    ) = Event.fireEvent(
        swatch,
        MouseEvent(
            MouseEvent.MOUSE_CLICKED,
            1.0,
            1.0,
            1.0,
            1.0,
            button,
            1,
            false,
            false,
            false,
            false,
            button == MouseButton.PRIMARY,
            false,
            button == MouseButton.SECONDARY,
            true,
            false,
            false,
            null,
        ),
    )

    private fun unusedStore() =
        ProjectDataStore(
            Path
                .of("unused-projects"),
            Path
                .of("unused-shared.json"),
            emptyList(),
        )
}
