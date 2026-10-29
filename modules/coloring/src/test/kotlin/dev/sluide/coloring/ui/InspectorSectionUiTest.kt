package dev.sluide.coloring.ui

import dev.sluide.coloring.SavedColors
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.NamedColors
import dev.sluide.coloring.color.nearestNamed
import dev.sluide.coloring.color.toRgb8
import dev.sluide.projectdata.ProjectDataStore
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The wiring of the inspector to the state and the saved colors; the logic is in SavedColorsTest. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class InspectorSectionUiTest {
    @TempDir
    lateinit var dir: Path

    private val state = ColoringState()
    private val red = CssColor.fromRgb8(255, 0, 0)
    private lateinit var store: ProjectDataStore
    private lateinit var folder: Path
    private lateinit var savedColors: SavedColors
    private lateinit var inspector: InspectorSection

    @Start
    fun start(stage: Stage) {
        folder = Files.createDirectories(dir.resolve("proj"))
        store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        savedColors = SavedColors(store)
        inspector = InspectorSection(state, savedColors, SimpleObjectProperty(folder))
        stage.scene = Scene(inspector.node as Parent, 320.0, 900.0)
        stage.show()
    }

    private fun save(robot: FxRobot) = robot.lookup("#coloring-save-color").queryAs(Button::class.java)

    private fun useFor(robot: FxRobot) = robot.lookup("#coloring-use-for").queryAs(Button::class.java)

    @Test
    fun `Save saves the inspected color and disables itself until another color is inspected`(robot: FxRobot) {
        robot.interact { state.inspect(red) }

        robot.interact { save(robot).fire() }

        assertEquals(listOf(red), savedColors.saved(folder))
        assertTrue(save(robot).isDisable)

        robot.interact { state.inspect(CssColor.fromRgb8(0, 0, 255)) }
        assertFalse(save(robot).isDisable)
    }

    @Test
    fun `a refused save is shown`(robot: FxRobot) {
        store.update(folder) { it.put("colors", "oops") }
        val message =
            robot.lookup(".coloring-muted").queryAllAs(Label::class.java).single { it.text.startsWith("The saved") }
        assertFalse(message.isVisible)

        robot.interact { save(robot).fire() }

        assertTrue(message.isVisible)
    }

    @Test
    fun `Use for names the slot being edited and writes the inspected color into it`(robot: FxRobot) {
        assertEquals("Use for Contrast › Text", useFor(robot).text)
        assertTrue(useFor(robot).isDisable, "the inspector follows the slot")

        robot.interact {
            state.edit(state.mix1)
            state.inspect(red)
        }
        assertEquals("Use for Mix › Color 1", useFor(robot).text)

        robot.interact { useFor(robot).fire() }

        assertEquals(red, state.mix1.color.get())
        assertTrue(useFor(robot).isDisable)
    }

    @Test
    fun `the formats show the inspected color`(robot: FxRobot) {
        robot.interact { state.inspect(red) }

        assertEquals("#ff0000", robot.lookup(".coloring-format-text").queryAs(TextField::class.java).text)
    }

    @Test
    fun `a click on the nearest name inspects the named color with the alpha and leaves the slot`(robot: FxRobot) {
        val slot = state.contrastText.color.get()
        val translucent = CssColor.fromRgb8(59, 130, 246, 128)
        robot.interact { state.inspect(translucent) }
        val name = nearestNamed(translucent).name
        val label = robot.lookup(".coloring-name").queryAs(Label::class.java)
        assertEquals("Nearest name: $name", label.text)

        robot.interact { click(label) }

        val rgb = state.inspected.get().toRgb8()
        assertEquals(128, rgb.a)
        assertEquals(name, NamedColors.nameOf(rgb.copy(a = 255)))
        assertEquals(slot, state.contrastText.color.get())
    }

    @Test
    fun `a disposed inspector no longer reacts`(robot: FxRobot) {
        robot.interact { save(robot).fire() }
        assertTrue(save(robot).isDisable)
        robot.interact { inspector.dispose() }

        robot.interact {
            state.inspect(red)
            state.edit(state.base)
        }

        assertTrue(save(robot).isDisable)
        assertEquals("Use for Contrast › Text", useFor(robot).text)
    }
}
