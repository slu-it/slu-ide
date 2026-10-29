package dev.sluide.coloring.editor

import dev.sluide.coloring.color.CssColor
import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.CodeEditorExtensions
import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ColorEditorExtension] in a real code editor: the swatches in the gutter rows, their refresh and click, and the
 * context menu item. Needs a display: gutter rows and the menu exist only in a laid-out scene; what is decided is
 * `ColorGutterTest`'s.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ColorGutterUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()
    private val shown = mutableListOf<CssColor>()
    private var editor: CodeEditor? = null

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    @AfterEach
    fun dispose() {
        editor?.let { WaitForAsyncUtils.asyncFx { it.dispose() }.get() }
    }

    private fun host() =
        object : FileViewHost {
            private val services =
                WindowServices().apply {
                    register(
                        CodeEditorExtensions::class.java,
                        CodeEditorExtensions().apply { add(ColorEditorExtension { shown += it }) },
                    )
                }

            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
        }

    private fun open(
        robot: FxRobot,
        name: String,
        text: String,
    ): CodeEditor {
        val file = dir.resolve(name).apply { writeText(text) }
        val opened = WaitForAsyncUtils.asyncFx<CodeEditor> { CodeEditors.open(file, null) }.get()
        editor = opened
        robot.interact {
            container.children.setAll(opened.node)
            opened.host = host()
        }
        return opened
    }

    private fun swatches(robot: FxRobot): List<Node> =
        robot.lookup(".color-gutter-swatch").queryAllAs(Node::class.java).toList()

    /** The line numbers of the rows with a swatch. */
    private fun rowsWithSwatch(robot: FxRobot): Set<String> =
        swatches(robot)
            .map { swatch ->
                (
                    swatch.parent.parent.childrenUnmodifiable
                        .first { it is Label } as Label
                ).text.trim()
            }.toSet()

    private fun fillOf(swatch: Node): Color = ((swatch as Pane).children[1] as Rectangle).fill as Color

    /** Repeats [assertion] until it passes or 10 s are over. */
    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            val failure = runCatching(assertion).exceptionOrNull() ?: return
            if (System.nanoTime() > deadline) throw failure
            Thread.sleep(50)
        }
    }

    private fun click(
        node: Node,
        robot: FxRobot,
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
                true,
                false,
                false,
                false,
                false,
                true,
                null,
            ),
        )
    }

    private fun areaOf(robot: FxRobot): CodeArea = robot.lookup(".code-area").queryAs(CodeArea::class.java)

    /** The Coloring items of the context menu with the caret at [column] of [line]. */
    private fun menuItems(
        robot: FxRobot,
        line: Int,
        column: Int,
    ): List<MenuItem> =
        WaitForAsyncUtils
            .asyncFx<List<MenuItem>> {
                val area = areaOf(robot)
                area.moveTo(line, column)
                val menu = checkNotNull(area.contextMenu)
                val at = area.localToScreen(5.0, 5.0)
                menu.show(area, at.x, at.y)
                val items = menu.items.filter { it.text?.contains("Coloring") == true }
                menu.hide()
                items
            }.get()

    @Test
    fun `a color gets a swatch in its row only, which opens it in the tool, and an edit updates it`(robot: FxRobot) {
        val view = open(robot, "main.css", "a {\n  color: #ff0000;\n}\n")

        eventually { assertEquals(setOf("2"), rowsWithSwatch(robot)) }
        val swatch = swatches(robot).single()
        assertEquals(Color.RED, fillOf(swatch))
        click(swatch, robot)
        assertEquals(listOf(CssColor.fromRgb8(255, 0, 0)), shown)

        // Only the color changes: its row is rebuilt in place.
        robot.interact { areaOf(robot).replaceText(1, 9, 1, 16, "#00ff00") }
        eventually { assertEquals(Color.LIME, fillOf(swatches(robot).single())) }
    }

    @Test
    fun `a file that is no style file has no swatches until it is renamed to one`(robot: FxRobot) {
        val view = open(robot, "main.txt", "color: #ff0000;\n")
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(swatches(robot).isEmpty())

        robot.interact { view.moved(dir.resolve("main.css")) }
        eventually { assertEquals(setOf("1"), rowsWithSwatch(robot)) }
    }

    @Test
    fun `the context menu offers Open in Coloring for the color at the caret, disabled elsewhere`(robot: FxRobot) {
        open(robot, "main.css", "a { color: #3b82f6; }\nb { margin: 0; }\n")

        val onColor = menuItems(robot, 0, 12).single()
        assertEquals("Open \"#3b82f6\" in Coloring", onColor.text)
        assertFalse(onColor.isDisable)
        robot.interact { onColor.fire() }
        assertEquals(listOf(CssColor.fromRgb8(0x3b, 0x82, 0xf6)), shown)

        val elsewhere = menuItems(robot, 1, 3).single()
        assertEquals("Open in Coloring", elsewhere.text)
        assertTrue(elsewhere.isDisable)
    }

    @Test
    fun `a plain text file has no Open in Coloring item`(robot: FxRobot) {
        open(robot, "notes.txt", "color: #3b82f6;\n")
        assertTrue(menuItems(robot, 0, 10).isEmpty())
    }

    @Test
    fun `a disposed editor no longer refreshes its swatches`(robot: FxRobot) {
        val view = open(robot, "main.css", "a { margin: 0; }\n")
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(swatches(robot).isEmpty())

        robot.interact { view.dispose() }
        robot.interact { view.replaceText("a { color: #ff0000; }\n") }
        // The edited row is rebuilt on the next pulse, which the wait covers.
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(swatches(robot).isEmpty())
    }

    @Test
    fun `a file renamed away from a style file loses its swatches`(robot: FxRobot) {
        val view = open(robot, "main.css", "a { color: #ff0000; }\n")
        eventually { assertEquals(setOf("1"), rowsWithSwatch(robot)) }

        robot.interact { view.moved(dir.resolve("main.txt")) }
        eventually { assertTrue(swatches(robot).isEmpty()) }
    }
}
