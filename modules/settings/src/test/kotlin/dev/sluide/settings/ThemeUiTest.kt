package dev.sluide.settings

import javafx.application.ColorScheme
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.text.Text
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
    fun `the dark theme darkens open windows and windows opened later, with readable placeholder text`(robot: FxRobot) {
        install(robot)
        val root = stage.scene.root as Region
        robot.interact { settings.apply(Theme.LIGHT) }
        assertEquals(ColorScheme.LIGHT, stage.scene.preferences.colorScheme)
        assertTrue(!root.isDark(), "light when chosen")

        robot.interact { settings.apply(Theme.DARK) }
        assertEquals(ColorScheme.DARK, stage.scene.preferences.colorScheme)
        assertTrue(root.isDark(), "dark after applying the dark theme")

        val field = TextField().apply { promptText = "placeholder" }
        lateinit var prompt: Color
        robot.interact {
            (stage.scene.root as StackPane).children.add(field)
            field.applyCss()
            prompt =
                field
                    .lookupAll(".text")
                    .filterIsInstance<Text>()
                    .first { it.text == "placeholder" }
                    .fill as Color
        }
        assertTrue(
            prompt.brightness > Color.web("#2b2b2b").brightness + 0.3,
            "placeholder text is readable, was $prompt",
        )

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
    fun `dark text inputs are brighter than the panes and have a lighter edge, the editor preview is not`(
        robot: FxRobot,
    ) {
        install(robot)
        robot.interact { settings.apply(Theme.DARK) }
        val pane = Color.web("#2b2b2b")
        val field = TextField()
        val area = TextArea()
        val editorPreview = TextArea().apply { styleClass += "editor-preview" }
        lateinit var fieldFill: Color
        lateinit var fieldEdge: Color
        lateinit var areaFill: Color
        lateinit var previewFill: Color
        robot.interact {
            (stage.scene.root as StackPane).children.addAll(field, area, editorPreview)
            listOf(field, area, editorPreview).forEach { it.applyCss() }
            fieldFill = layer(field) { last() }
            fieldEdge = layer(field) { first() }
            areaFill = layer(area.lookup(".content") as Region) { last() }
            previewFill = layer(editorPreview.lookup(".content") as Region) { last() }
        }

        // A field in the pane colour, with Modena's border darker than it, is hard to see as one.
        assertTrue(fieldFill.brightness > pane.brightness + 0.03, "a text field stands out, was $fieldFill")
        assertTrue(areaFill.brightness > pane.brightness + 0.03, "a text area stands out, was $areaFill")
        assertTrue(fieldEdge.brightness > fieldFill.brightness, "the edge is lighter than the fill, was $fieldEdge")
        assertEquals(pane, previewFill, "the Fonts page's editor preview keeps the editor's colour")
    }

    @Test
    fun `a window that was open before the install is themed when it gets a new scene`(robot: FxRobot) {
        install(robot)
        robot.interact { settings.apply(Theme.DARK) }

        robot.interact { stage.scene = Scene(StackPane(Label("new scene")), 200.0, 100.0) }

        assertEquals(ColorScheme.DARK, stage.scene.preferences.colorScheme)
        assertTrue((stage.scene.root as Region).isDark())
    }

    @Test
    fun `the check mark of a menu opened from a hovered menu button is light in the dark theme`(robot: FxRobot) {
        install(robot)
        robot.interact { settings.apply(Theme.DARK) }
        val chosen = RadioMenuItem("chosen").apply { isSelected = true }
        // Modena's .menu-button:hover sets this; the pointer is still on the button while its menu is open.
        val button = MenuButton("menu", null, chosen).apply { style = "-fx-color: -fx-hover-base;" }
        lateinit var mark: Color
        robot.interact { (stage.scene.root as StackPane).children.add(button) }
        // A separate pass: the button opens its menu only once it has a skin.
        robot.interact { button.show() }
        robot.interact {
            val content = checkNotNull(chosen.parentPopup.skin?.node) { "the menu is not shown" }
            content.applyCss()
            mark = layer(content.lookup(".radio-menu-item .radio") as Region) { last() }
            button.hide()
        }

        assertTrue(mark.brightness > 0.7, "the mark stands out on the dark menu, was $mark")
    }

    @Test
    fun `the system theme follows the operating system`(robot: FxRobot) {
        install(robot)

        robot.interact { system.set(ColorScheme.DARK) }

        assertEquals(ColorScheme.DARK, stage.scene.preferences.colorScheme)
        assertTrue((stage.scene.root as Region).isDark())
    }

    /**
     * The colour of the background layer of [region] that [pick] chooses: a solid colour, or the last stop of a gradient
     * (Modena paints a text input's edge and fill as gradients).
     */
    private fun layer(
        region: Region,
        pick: List<BackgroundFill>.() -> BackgroundFill,
    ): Color =
        when (
            val paint =
                region.background.fills
                    .pick()
                    .fill
        ) {
            is LinearGradient -> paint.stops.last().color
            else -> paint as Color
        }
}
