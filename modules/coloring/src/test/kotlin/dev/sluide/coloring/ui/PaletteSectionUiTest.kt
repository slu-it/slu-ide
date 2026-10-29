package dev.sluide.coloring.ui

import dev.sluide.coloring.SavedColors
import dev.sluide.coloring.color.CssColor
import dev.sluide.projectdata.ProjectDataStore
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.input.ContextMenuEvent
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

/** The wiring of the saved-colors section to the store and the state; the logic is in SavedColorsTest. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PaletteSectionUiTest {
    @TempDir
    lateinit var dir: Path

    private val state = ColoringState()
    private val red = CssColor.fromRgb8(255, 0, 0)
    private val folderProperty = SimpleObjectProperty<Path?>()
    private lateinit var store: ProjectDataStore
    private lateinit var folder: Path
    private lateinit var savedColors: SavedColors
    private lateinit var section: PaletteSection

    @Start
    fun start(stage: Stage) {
        folder = Files.createDirectories(dir.resolve("proj"))
        folderProperty.set(folder)
        store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        savedColors = SavedColors(store)
        section = PaletteSection(state, savedColors, folderProperty)
        stage.scene = Scene(section.node as Parent, 320.0, 200.0)
        stage.show()
    }

    private fun swatches(robot: FxRobot) = robot.lookup(".swatch").queryAll<Swatch>().toList()

    @Test
    fun `Save adds a swatch of the current color and disables itself`(robot: FxRobot) {
        val save = robot.lookup("#coloring-save-color").queryAs(Button::class.java)

        robot.interact { save.fire() }

        assertEquals(listOf(state.current.get()), savedColors.saved(folder))
        assertEquals(1, swatches(robot).size)
        assertTrue(save.isDisable)

        robot.interact { state.current.set(red) }
        assertFalse(save.isDisable)
    }

    @Test
    fun `clicking a saved swatch makes it the current color`(robot: FxRobot) {
        robot.interact { savedColors.save(folder, red) }
        val swatch = swatches(robot).single()

        robot.interact { click(swatch) }

        assertEquals(red, state.current.get())
    }

    @Test
    fun `the Remove menu item removes the color`(robot: FxRobot) {
        robot.interact { savedColors.save(folder, red) }
        val swatch = swatches(robot).single()

        robot.interact {
            swatch.fireEvent(
                ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0.0, 0.0, 0.0, 0.0, false, null),
            )
        }
        val menu = ContextMenu.getWindows().filterIsInstance<ContextMenu>().single { it.isShowing }
        robot.interact { menu.items.single { it.text == "Remove" }.fire() }

        assertEquals(emptyList(), savedColors.saved(folder))
        assertEquals(0, swatches(robot).size)
    }

    @Test
    fun `a save in another window of the project shows up`(robot: FxRobot) {
        val other = PaletteSection(ColoringState(), savedColors, SimpleObjectProperty(folder))

        robot.interact { savedColors.save(folder, red) }

        assertEquals(1, (other.node as Parent).lookupAll(".swatch").size)
        assertEquals(1, swatches(robot).size)
        robot.interact { other.dispose() }
    }

    @Test
    fun `the saved colors follow a change of the project folder`(robot: FxRobot) {
        robot.interact { savedColors.save(folder, red) }
        val other = Files.createDirectories(dir.resolve("other"))

        robot.interact { folderProperty.set(other) }

        assertEquals(0, swatches(robot).size)
    }

    @Test
    fun `a refused save is shown`(robot: FxRobot) {
        store.update(folder) { it.put("colors", "oops") }
        val message =
            robot
                .lookup(
                    ".coloring-muted",
                ).queryAllAs(Label::class.java)
                .single { it.text.startsWith("The saved") }
        assertFalse(message.isVisible)

        robot.interact { robot.lookup("#coloring-save-color").queryAs(Button::class.java).fire() }

        assertTrue(message.isVisible)
    }

    @Test
    fun `a disposed section no longer reacts`(robot: FxRobot) {
        val save = robot.lookup("#coloring-save-color").queryAs(Button::class.java)
        robot.interact { save.fire() }
        assertTrue(save.isDisable)
        robot.interact { section.dispose() }

        robot.interact {
            savedColors.save(folder, red)
            state.current.set(red)
        }

        assertEquals(1, swatches(robot).size)
        assertTrue(save.isDisable)
    }
}
