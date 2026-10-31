package dev.sluide.converting.ui

import dev.sluide.converting.data.DataFamily
import dev.sluide.converting.model.ConvertingModel
import dev.sluide.converting.model.ConvertingScheduler
import dev.sluide.converting.model.Pick
import dev.sluide.converting.model.Side
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ChoiceBox
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.RadioMenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import javafx.stage.Window
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Each connection of the Converting view to its model needs the controls in a scene (editors, menus, clicks), with the
 * real data family. Conversions run at once on the FX thread; their results arrive as in the application, through
 * `Platform.runLater`, which `interact` waits for. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ConvertingViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val immediate =
        object : ConvertingScheduler {
            override fun schedule(
                delay: Duration,
                task: () -> Unit,
            ): () -> Unit {
                task()
                return {}
            }
        }
    private val model = ConvertingModel(listOf(DataFamily()), Platform::runLater, Duration.ZERO, immediate)
    private var fileToOpen: Path? = null
    private var fileToSave: Path? = null
    private val suggestedNames = mutableListOf<String>()
    private val scratches = mutableListOf<Pair<String, String>>()
    private lateinit var view: ConvertingView

    @Start
    fun start(stage: Stage) {
        view = newView()
        stage.scene = Scene(view.node as Parent, 1100.0, 600.0)
        stage.show()
    }

    private fun newView() =
        ConvertingView(
            model,
            SimpleStringProperty("-fx-font-family: \"Monospaced\";"),
            { _: Window? -> fileToOpen },
            { _: Window?, name: String ->
                suggestedNames += name
                fileToSave
            },
            { extension, text -> scratches += extension to text },
        )

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun side(
        side: Side,
        of: ConvertingView = view,
    ): Node = of.node.lookup("#converting-${side.name.lowercase()}")

    private fun area(
        side: Side,
        of: ConvertingView = view,
    ): CodeArea = side(side, of).lookup(".code-area") as CodeArea

    private fun text(
        side: Side,
        of: ConvertingView = view,
    ): String = fx { area(side, of).text }

    private fun type(
        robot: FxRobot,
        side: Side,
        text: String,
    ) = robot.interact { area(side).replaceText(text) }

    private fun button(
        side: Side,
        text: String,
    ): Button =
        side(side)
            .lookupAll(".button")
            .filterIsInstance<Button>()
            .single { it.text == text }

    private fun fileItem(
        side: Side,
        text: String,
    ): MenuItem = (side(side).lookup(".converting-file") as MenuButton).items.single { it.text == text }

    private fun picker(side: Side): MenuButton = side(side).lookup(".converting-picker") as MenuButton

    private fun item(
        side: Side,
        text: String,
    ): MenuItem = picker(side).items.single { it.text == text }

    private fun status(side: Side): Label = side(side).lookup(".converting-status") as Label

    @Test
    fun `typing on the left converts into the right, which is no edit`(robot: FxRobot) {
        type(robot, Side.LEFT, """{"name": "app", "port": 8080}""")

        assertEquals("name: app\nport: 8080\n", text(Side.RIGHT))
        assertEquals("Detect: JSON", fx { picker(Side.LEFT).text })
        assertEquals("YAML", fx { picker(Side.RIGHT).text })
        assertEquals(Side.LEFT, model.source, "the right side's new text is not reported as an edit")
    }

    @Test
    fun `choosing a format in the picker pins it`(robot: FxRobot) {
        type(robot, Side.LEFT, """{"a": 1}""")

        robot.interact { item(Side.RIGHT, "JSON").fire() }

        assertEquals("JSON", (model.pick(Side.RIGHT) as Pick.Pinned).format.name)
        assertEquals("JSON", fx { picker(Side.RIGHT).text })
        assertEquals("{\n  \"a\": 1\n}", text(Side.RIGHT).trimEnd())
        val groups =
            fx {
                picker(Side.RIGHT)
                    .items
                    .filterIsInstance<RadioMenuItem>()
                    .map { it.toggleGroup }
                    .toSet()
            }
        assertEquals(1, groups.size, "one toggle group, so that choosing the current pick again keeps it checked")
        assertTrue(groups.single() != null)
    }

    @Test
    fun `opening the target's picker marks the formats that cannot hold the source`(robot: FxRobot) {
        robot.interact { item(Side.LEFT, "JSON").fire() }
        type(robot, Side.LEFT, "42")
        assertFalse(fx { item(Side.RIGHT, "CSV").isDisable }, "not checked before the picker opens")

        robot.interact { picker(Side.RIGHT).show() }

        val csv = fx { picker(Side.RIGHT).items.single { it.text.startsWith("CSV") } }
        assertTrue(fx { csv.isDisable }, csv.text)
        assertEquals("CSV — CSV needs a list of records", csv.text)
        robot.interact { picker(Side.RIGHT).hide() }
    }

    @Test
    fun `Swap exchanges the sides`(robot: FxRobot) {
        type(robot, Side.LEFT, """{"a": 1}""")

        robot.interact { (view.node.lookup("#converting-swap") as Button).fire() }

        assertEquals("a: 1\n", text(Side.LEFT))
        assertEquals("""{"a": 1}""", text(Side.RIGHT))
        assertEquals(Side.RIGHT, model.source)
    }

    @Test
    fun `an option's check box converts again`(robot: FxRobot) {
        type(robot, Side.LEFT, "b: 1\na: 2\n")
        assertEquals("{\n  \"b\": 1,\n  \"a\": 2\n}", text(Side.RIGHT).trimEnd())

        robot.interact { (view.node.lookup("#converting-option-sortKeys") as CheckBox).fire() }

        assertEquals("{\n  \"a\": 2,\n  \"b\": 1\n}", text(Side.RIGHT).trimEnd())

        @Suppress("UNCHECKED_CAST")
        robot.interact { (view.node.lookup("#converting-option-indent") as ChoiceBox<String>).value = "4" }

        assertEquals("{\n    \"a\": 2,\n    \"b\": 1\n}", text(Side.RIGHT).trimEnd())
    }

    @Test
    fun `a read error is marked and a click on it selects it, also in a file with CRLF line breaks`(robot: FxRobot) {
        fileToOpen = dir.resolve("bad.json").also { Files.writeString(it, "{\r\n\"a\": ]}") }
        robot.interact { fileItem(Side.LEFT, "Open File…").fire() }
        assertEquals("{\n\"a\": ]}", text(Side.LEFT))
        assertEquals("{\r\n\"a\": ]}", model.text(Side.LEFT), "the editor's text with LF is no edit")
        assertTrue(fx { status(Side.LEFT).text }.startsWith("Line 2, column 6"), fx { status(Side.LEFT).text })
        assertEquals(listOf(7), fx { errorChars(Side.LEFT) }, "the ] is marked")

        click(robot, status(Side.LEFT))

        assertEquals("]", fx { area(Side.LEFT).selectedText })
        assertTrue(fx { area(Side.LEFT).isFocused })

        // An error of the side itself is no read error: a click on it selects nothing.
        robot.interact {
            view.filesDropped(Side.LEFT, listOf(dir.resolve("missing.json").toFile()))
            area(Side.LEFT).moveTo(0)
        }
        click(robot, status(Side.LEFT))
        assertEquals("", fx { area(Side.LEFT).selectedText })

        type(robot, Side.LEFT, "{\"a\": 1}")
        assertEquals(emptyList(), fx { errorChars(Side.LEFT) }, "a text that reads has no mark")
    }

    @Test
    fun `Switch of the hint pins the format the text looks like`(robot: FxRobot) {
        robot.interact { item(Side.LEFT, "JSON").fire() }
        type(robot, Side.LEFT, "a: 1\nb: [x, y]\n")
        assertTrue(fx { side(Side.LEFT).lookup(".converting-hint").isVisible })

        robot.interact { button(Side.LEFT, "Switch").fire() }

        assertEquals("YAML", fx { picker(Side.LEFT).text })
        assertFalse(fx { side(Side.LEFT).lookup(".converting-hint").isVisible })
    }

    @Test
    fun `Copy copies the side's text and Clear empties both sides`(robot: FxRobot) {
        type(robot, Side.LEFT, """{"a": 1}""")

        robot.interact { button(Side.RIGHT, "Copy").fire() }
        assertEquals("a: 1\n", fx { Clipboard.getSystemClipboard().string })

        robot.interact { button(Side.LEFT, "Clear").fire() }
        assertEquals("", text(Side.LEFT))
        assertEquals("", text(Side.RIGHT))
    }

    @Test
    fun `Open File reads the chosen file, Save as writes the side's text`(robot: FxRobot) {
        fileToOpen = dir.resolve("application.yml").also { Files.writeString(it, "server:\n  port: 80\n") }
        robot.interact { fileItem(Side.LEFT, "Open File…").fire() }
        assertEquals("server:\n  port: 80\n", text(Side.LEFT))
        assertEquals("YAML", fx { picker(Side.LEFT).text }, "pinned to the extension's format")

        val saved = dir.resolve("out.json")
        fileToSave = saved
        robot.interact { fileItem(Side.RIGHT, "Save as…").fire() }

        assertEquals(listOf("application.json"), suggestedNames)
        assertEquals(text(Side.RIGHT), Files.readString(saved))
    }

    @Test
    fun `a dropped file opens on its side, an error shows in the status`(robot: FxRobot) {
        // Drag and drop itself needs real OS input; the drop handler only passes the files on to filesDropped.
        val file = dir.resolve("data.json").also { Files.writeString(it, """{"a": 1}""") }
        robot.interact { view.filesDropped(Side.RIGHT, listOf(file.toFile())) }
        assertEquals("""{"a": 1}""", text(Side.RIGHT))
        assertEquals("a: 1\n", text(Side.LEFT))

        robot.interact { view.filesDropped(Side.RIGHT, listOf(dir.resolve("missing.json").toFile())) }
        assertEquals("Cannot read missing.json: file not found", fx { status(Side.RIGHT).text })
    }

    @Test
    fun `Open as Scratch passes the side's extension and text`(robot: FxRobot) {
        type(robot, Side.LEFT, """{"a": 1}""")

        robot.interact { fileItem(Side.RIGHT, "Open as Scratch").fire() }

        assertEquals(listOf("yaml" to "a: 1\n"), scratches)
    }

    @Test
    fun `a disposed view does not react and a second view takes over the model`(robot: FxRobot) {
        robot.interact { view.dispose() }
        type(robot, Side.LEFT, """{"a": 1}""")
        assertEquals("", text(Side.RIGHT), "the disposed view no longer follows the model")

        lateinit var second: ConvertingView
        robot.interact {
            second = newView()
            view.node.scene.root = second.node as Parent
        }
        assertEquals("""{"a": 1}""", text(Side.LEFT, second), "a new view starts with the model's texts")
        robot.interact { area(Side.LEFT, second).undo() }
        assertEquals("""{"a": 1}""", text(Side.LEFT, second), "which are no undo step")

        robot.interact { model.edit(Side.LEFT, """{"b": 2}""") }
        assertEquals("b: 2\n", text(Side.RIGHT, second))
    }

    /** The characters styled as an error; RichTextFX gives a line break the style of the character before it. */
    private fun errorChars(side: Side): List<Int> =
        area(side).let { area ->
            (0 until area.length).filter { area.text[it] != '\n' && "text-error" in area.getStyleOfChar(it) }
        }

    private fun click(
        robot: FxRobot,
        node: Node,
    ) = robot.interact {
        Event.fireEvent(
            node,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1.0,
                1.0,
                1.0,
                1.0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                null,
            ),
        )
    }
}
