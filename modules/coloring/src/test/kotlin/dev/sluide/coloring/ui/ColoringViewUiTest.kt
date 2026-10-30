package dev.sluide.coloring.ui

import dev.sluide.coloring.SavedColors
import dev.sluide.coloring.color.ColorFormatter
import dev.sluide.coloring.color.CssColor
import dev.sluide.projectdata.ProjectDataStore
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.paint.Color
import javafx.scene.paint.Paint
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The Coloring view needs a scene for its columns, its tabs and the swatches' mouse handling. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ColoringViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val state = ColoringState()
    private lateinit var view: ColoringView

    @Start
    fun start(stage: Stage) {
        view =
            ColoringView(state, savedColors = SavedColors(store()), projectFolder = SimpleObjectProperty(null))
        stage.scene = Scene(view.node as Parent, 1200.0, 700.0)
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
    fun `focus gives the focus to the color input field`(robot: FxRobot) {
        robot.interact { view.focus() }

        val focused = view.node.scene.focusOwner
        assertTrue(focused is TextField && "coloring-input" in focused.styleClass)
    }

    @Test
    fun `the inspector, the tool tabs and the picker column are the three columns`() {
        val root = view.node as BorderPane
        assertEquals("coloring-left", root.left.id)
        assertNotNull(root.left.lookup("#coloring-use-for"))
        assertSame(view.toolTabs, root.center)
        assertEquals("coloring-right", root.right.id)
        assertNotNull(root.right.lookup(".coloring-input"))
        assertEquals(listOf(root.right, root.center, root.left), root.children, "Tab goes from the picker on")
        assertTrue(view.toolTabs.tabs.all { (it.content as? ScrollPane)?.isFitToWidth == true }, "tool tabs scroll")
    }

    @Test
    fun `switching the tool tab edits the slot that tab remembers`(robot: FxRobot) {
        val header = robot.lookup("#coloring-editing").queryAs(Label::class.java)
        assertEquals("Editing: Contrast › Text", header.text)

        robot.interact { selectTool(ColoringState.MIX) }
        assertEquals("Editing: Mix › Color 1", header.text)

        robot.interact {
            state.edit(state.mix2)
            selectTool(ColoringState.CONTRAST)
        }
        assertEquals("Editing: Contrast › Text", header.text)

        robot.interact { selectTool(ColoringState.MIX) }
        assertEquals("Editing: Mix › Color 2", header.text)
    }

    @Test
    fun `the inspector column shows a swatch clicked in a tool tab and offers it for that tab's slot`(robot: FxRobot) {
        robot.interact { selectTool(ColoringState.HARMONIES) }
        // Any shade or harmony but the base color itself.
        val shade =
            robot.lookup(".coloring-cell .swatch").queryAllAs(Swatch::class.java).first {
                it.tooltipText != ColorFormatter.hex(state.base.color.get())
            }
        val inspector = (view.node as BorderPane).left as Parent
        val useFor = inspector.lookup("#coloring-use-for") as Button

        robot.interact { click(shade) }

        assertEquals(shade.tooltipText, (inspector.lookup(".coloring-format-text") as TextField).text)
        assertEquals("Use for Shades & Harmonies › Base", useFor.text)
        assertFalse(useFor.isDisable)
    }

    @Test
    fun `a disposed swatch no longer follows the color`(robot: FxRobot) {
        val color = state.contrastBackground.color
        val followed = Swatch.of(color, state)
        val disposed = Swatch.of(color, state)
        robot.interact { disposed.dispose() }

        robot.interact { color.set(CssColor.fromRgb8(0, 255, 0)) }

        assertEquals("#00ff00", followed.tooltipText)
        assertEquals("#ffffff", disposed.tooltipText)
    }

    private fun selectTool(title: String) =
        view.toolTabs.selectionModel.select(
            view.toolTabs.tabs.single {
                it.text ==
                    title
            },
        )

    @Test
    fun `the copy icons take the text colour, also on a dark base`(robot: FxRobot) {
        // Dark like the dark theme, which re-tints Modena's base colour: an icon left black would vanish.
        robot.interact { view.node.scene.stylesheets += "data:text/css,.root { -fx-base: %232b2b2b; }" }

        robot.interact {
            val icons = robot.lookup(".coloring-copy .ikonli-font-icon").queryAll<FontIcon>()
            assertTrue(icons.isNotEmpty())
            icons.forEach { assertNotEquals<Paint>(Color.BLACK, it.iconColor) }
        }
    }

    private fun store() = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
}
