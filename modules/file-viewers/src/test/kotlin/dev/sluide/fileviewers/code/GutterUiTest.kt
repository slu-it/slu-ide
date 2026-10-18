package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.PythonFileViewer
import dev.sluide.fileviewers.TextDiff
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Cursor
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.stage.Stage
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
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The gutter of a real [CodeView]: line numbers, change markers and further columns. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GutterUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    private fun open(
        text: String,
        robot: FxRobot,
    ): CodeView {
        val file = dir.resolve("main.py").apply { writeText(text) }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(file, PythonFileViewer().highlighter) }.get()
        robot.interact { container.children.setAll(view.node) }
        return view
    }

    /** The rows of the gutter shown, by their line number. */
    private fun rows(robot: FxRobot): Map<String, HBox> {
        WaitForAsyncUtils.waitForFxEvents()
        return robot
            .lookup(".${Gutter.GUTTER_STYLE}")
            .queryAllAs(HBox::class.java)
            .associateBy { (it.children.first() as Label).text.trim() }
    }

    /** The marker classes of the rows shown, by line number; rows without a marker are left out. */
    private fun markers(robot: FxRobot): Map<String, String> =
        rows(robot)
            .mapNotNull { (line, row) ->
                row.children[1]
                    .styleClass
                    .singleOrNull { it != Gutter.MARKER_STYLE }
                    ?.let { line to it }
            }.toMap()

    @Test
    fun `every line has its number, padded like RichTextFX's`(robot: FxRobot) {
        val view = open((1..12).joinToString("\n") { "x = $it" }, robot)
        val rows = rows(robot)
        val first = rows.getValue("1").children.first() as Label
        assertEquals(" 1", first.text)
        assertEquals(listOf("label", "lineno"), first.styleClass)
        assertEquals(listOf(Gutter.MARKER_STYLE), rows.getValue("1").children[1].styleClass)
        assertEquals(12, view.area.paragraphs.size)
    }

    @Test
    fun `changes are marked beside their lines until they are cleared`(robot: FxRobot) {
        // HEAD: a b c e; now: a B c x e (b modified, x added).
        val view = open("a\nB\nc\nx\ne\n", robot)
        val diff = TextDiff("a\nb\nc\ne\n", listOf(LineChange(1..1, 1..1), LineChange(3 until 3, 3..3)))
        robot.interact { view.showChanges(diff) }
        assertEquals(mapOf("2" to "diff-modified", "4" to "diff-added"), markers(robot))

        robot.interact {
            view.showChanges(TextDiff("a\nB\nc\nx\nd\ne\n", listOf(LineChange(4..4, 4 until 4))))
        }
        assertEquals(mapOf("5" to "diff-removed-above"), markers(robot))

        robot.interact { view.showChanges(null) }
        assertEquals(emptyMap(), markers(robot))
    }

    @Test
    fun `markers move along with unsaved edits and return when they are undone`(robot: FxRobot) {
        // HEAD: a b c; saved: a B c.
        val view = open("a\nB\nc\n", robot)
        robot.interact { view.showChanges(TextDiff("a\nb\nc\n", listOf(LineChange(1..1, 1..1)))) }

        robot.interact {
            view.area.insertText(0, "x")
            // Enter at the end of c, then a new line d.
            view.area.insertText(view.area.getAbsolutePosition(2, 1), "\nd")
            // Lines before line 1.
            view.area.insertText(0, "0\n1\n")
        }
        assertEquals(
            mapOf("1" to "diff-added", "2" to "diff-added", "3" to "diff-modified", "4" to "diff-modified") +
                mapOf("6" to "diff-added"),
            markers(robot),
        )

        robot.interact {
            view.area.deleteText(
                view.area.getAbsolutePosition(0, 0),
                view.area.getAbsolutePosition(2, 0),
            )
        }
        assertEquals(
            mapOf("1" to "diff-modified", "2" to "diff-modified", "4" to "diff-added"),
            markers(robot),
            "lines removed at the top of the file mark nothing, the first line is modified already",
        )

        robot.interact { while (view.dirty.get()) view.area.undo() }
        assertEquals(mapOf("2" to "diff-modified"), markers(robot))
    }

    @Test
    fun `a diff shown while the text is edited keeps the unsaved edits marked`(robot: FxRobot) {
        val view = open("a\nb\n", robot)
        robot.interact {
            view.area.insertText(view.area.getAbsolutePosition(1, 1), "\nc")
            view.showChanges(TextDiff("a\n", listOf(LineChange(1 until 1, 1..1))))
        }
        assertEquals(mapOf("2" to "diff-added", "3" to "diff-added"), markers(robot))
    }

    @Test
    fun `lines edited apart stay apart after saving`(robot: FxRobot) {
        val view = open((1..17).joinToString("\n", postfix = "\n") { "x$it = $it" }, robot)
        robot.interact {
            view.area.insertText(view.area.getAbsolutePosition(1, 0), "y")
            view.area.insertText(view.area.getAbsolutePosition(15, 0), "z")
        }
        val edited = mapOf("2" to "diff-modified", "16" to "diff-modified")
        assertEquals(edited, markers(robot))

        robot.interact { view.save() }

        assertEquals(edited, markers(robot))
    }

    @Test
    fun `a diff given while the file changed on disk under unsaved edits waits for the banner's answer`(
        robot: FxRobot,
    ) {
        val view = open("a\nb\n", robot)
        robot.interact { view.area.insertText(0, "x") }
        dir.resolve("main.py").writeText("a\nb\nc\n")
        robot.interact { view.reload() }
        assertTrue(view.conflictShown)

        // The diff of the file's new text against HEAD (a b): c added.
        robot.interact { view.showChanges(TextDiff("a\nb\n", listOf(LineChange(2 until 2, 2..2)))) }
        assertEquals(mapOf("1" to "diff-modified"), markers(robot), "still the markers of the unsaved edits")

        robot.interact {
            robot
                .lookup(".editor-banner .button")
                .queryAllAs(Button::class.java)
                .single { it.text == "Reload" }
                .fire()
        }
        assertEquals("a\nb\nc\n", view.area.text)
        assertEquals(mapOf("3" to "diff-added"), markers(robot))
    }

    @Test
    fun `several changes at once are each marked where they are`(robot: FxRobot) {
        val view = open("a\nb\nc\nd\ne\n", robot)
        robot.interact {
            view.area
                .createMultiChange()
                .replaceText(0, 1, "A")
                .insertText(view.area.getAbsolutePosition(3, 1), "\nnew")
                .commit()
        }
        assertEquals("A\nb\nc\nd\nnew\ne\n", view.area.text)
        assertEquals(mapOf("1" to "diff-modified", "5" to "diff-added"), markers(robot))
    }

    @Test
    fun `clicking a change marker asks the host for the changes at its line`(robot: FxRobot) {
        val view = open("a\nB\nc\n", robot)
        val asked = mutableListOf<Pair<Path, Int>>()
        view.host =
            object : FileViewHost {
                override fun openFile(file: Path) = false

                override fun openWebPage(uri: URI) {}

                override fun showChanges(
                    file: Path,
                    line: Int,
                ) {
                    asked += file to line
                }
            }
        robot.interact { view.showChanges(TextDiff("a\nb\nc\n", listOf(LineChange(1..1, 1..1)))) }
        val caret = view.area.caretPosition

        for (line in listOf("1", "2")) {
            val marker = rows(robot).getValue(line).children[1]
            robot.interact {
                for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                    Event.fireEvent(marker, mouseEvent(type))
                }
            }
        }

        assertEquals(listOf(dir.resolve("main.py") to 1), asked, "only the marked line")
        assertEquals(caret, view.area.caretPosition)
        assertEquals(Cursor.HAND, rows(robot).getValue("2").children[1].cursor)

        // Without a diff, markers of unsaved edits lead nowhere.
        robot.interact {
            view.showChanges(null)
            view.area.insertText(0, "x")
        }
        val unsaved = rows(robot).getValue("1").children[1]
        assertEquals(listOf(Gutter.MARKER_STYLE, "diff-modified"), unsaved.styleClass.toList())
        assertEquals(null, unsaved.cursor)
        robot.interact { Event.fireEvent(unsaved, mouseEvent(MouseEvent.MOUSE_CLICKED)) }
        assertEquals(1, asked.size)
    }

    private fun mouseEvent(type: EventType<MouseEvent>) =
        MouseEvent(
            type,
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
            true,
            false,
            false,
            null,
        )

    @Test
    fun `the rows are only created again when markers or digits change`(robot: FxRobot) {
        val view = open("a\nb\n", robot)
        val diff = TextDiff("a\n", listOf(LineChange(1 until 1, 1..1)))
        robot.interact { view.showChanges(diff) }
        val factory = view.area.paragraphGraphicFactory

        robot.interact { view.showChanges(diff.copy()) }
        assertSame(factory, view.area.paragraphGraphicFactory, "same markers")
        // Typing in the added line keeps the markers.
        robot.interact { view.area.insertText(view.area.getAbsolutePosition(1, 0), "x") }
        assertSame(factory, view.area.paragraphGraphicFactory, "same markers and digits")

        robot.interact { view.area.insertText(0, "\n".repeat(8)) }
        assertNotSame(factory, view.area.paragraphGraphicFactory, "10 lines")
        assertEquals(" 1", (rows(robot).getValue("1").children.first() as Label).text)
    }

    @Test
    fun `further columns show their node of each row, asked again on refresh`(robot: FxRobot) {
        val view = open("a\nb\nc", robot)
        var mark = "▶"
        robot.interact { view.addGutterColumn { paragraph -> if (paragraph == 1) Label(mark) else null } }

        fun shown(): Map<String, List<String>> =
            rows(robot).mapValues { (_, row) -> row.children.drop(2).map { (it as Label).text } }
        assertEquals(mapOf("1" to emptyList(), "2" to listOf("▶"), "3" to emptyList()), shown())

        mark = "■"
        robot.interact { view.refreshGutter() }
        assertEquals(listOf("■"), shown().getValue("2"))
    }
}
