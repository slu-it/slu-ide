package dev.sluide.fileviewers.diff

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.DiffFileView
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.fileviewers.languages.KotlinFileViewer
import dev.sluide.fileviewers.press
import dev.sluide.textediting.TextEncoding
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Renders the side-by-side diff in a stage. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DiffFileViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()
    private val viewers = FileViewers(listOf(KotlinFileViewer()), DefaultTextFileViewer())

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 300.0)
        stage.show()
    }

    // Original: one, two, three, four, five; file: one, TWO, three, five, six, seven.
    private val original = "val one = 1\nval two = 2\nval three = 3\nval four = 4\nval five = 5\n"
    private val diff =
        TextDiff(
            original,
            listOf(LineChange(1..1, 1..1), LineChange(3..3, 3 until 3), LineChange(5 until 5, 4..5)),
            "HEAD",
            "Working Tree",
        )

    private fun open(robot: FxRobot): Pair<CodeArea, CodeArea> =
        open(
            robot,
            dir.resolve("Main.kt").apply {
                writeText("val one = 1\nval TWO = 2\nval three = 3\nval five = 5\nsix()\nseven()\n")
            },
            diff,
        )

    /** Shows [file] against [diff], gives the left and the right code area. */
    private fun open(
        robot: FxRobot,
        file: Path,
        diff: TextDiff,
    ): Pair<CodeArea, CodeArea> {
        val view = openDiff(file, diff)
        robot.interact { container.children.setAll(view.node) }
        val areas = robot.lookup(".code-area").queryAllAs(CodeArea::class.java).sortedBy { it.localToScene(0.0, 0.0).x }
        return areas[0] to areas[1]
    }

    private fun CodeArea.paragraphClasses() = paragraphs.map { it.paragraphStyle.toList() }

    /** The toolbar button whose tooltip starts with [tooltip]. */
    private fun FxRobot.navigationButton(tooltip: String) =
        lookup(".diff-nav").queryAllAs(Button::class.java).single { it.tooltip.text.startsWith(tooltip) }

    private fun FxRobot.counter() = lookup(".diff-counter").queryAs(Label::class.java).text

    private fun FxRobot.press(
        button: String,
        left: CodeArea,
        right: CodeArea,
    ): Int {
        interact { navigationButton(button).fire() }
        assertEquals(left.currentParagraph, right.currentParagraph)
        return right.currentParagraph
    }

    /** Opens the diff view on the FX thread, as the editor area does. */
    private fun openDiff(
        file: Path,
        diff: TextDiff,
    ): DiffFileView = assertNotNull(WaitForAsyncUtils.asyncFx<DiffFileView?> { viewers.openDiff(file, diff) }.get())

    @Test
    fun `shows both versions aligned with the changes coloured and highlighted`(robot: FxRobot) {
        val (left, right) = open(robot)

        assertEquals("val one = 1\nval two = 2\nval three = 3\nval four = 4\nval five = 5\n\n", left.text)
        assertEquals("val one = 1\nval TWO = 2\nval three = 3\n\nval five = 5\nsix()\nseven()", right.text)
        val none = emptyList<String>()
        assertEquals(
            listOf(none, listOf("diff-modified"), none, listOf("diff-removed"), none, none, none),
            left.paragraphClasses(),
        )
        assertEquals(
            listOf(none, listOf("diff-modified"), none, none, none, listOf("diff-added"), listOf("diff-added")),
            right.paragraphClasses(),
        )
        assertFalse(left.isEditable || right.isEditable)
        assertTrue("keyword" in right.getStyleOfChar(right.text.indexOf("val") + 1))
        val titles =
            robot
                .lookup(".diff-title")
                .queryAllAs(Label::class.java)
                .map { it.text }
                .toSet()
        assertEquals(setOf("HEAD", "Working Tree"), titles)
    }

    @Test
    fun `the changed words of modified lines are marked on both sides, keeping their highlighting`(robot: FxRobot) {
        val file = dir.resolve("Main.kt").apply { writeText("var x = 2\n") }
        val (left, right) = open(robot, file, TextDiff("val x = 1\n", listOf(LineChange(0..0, 0..0))))

        for ((area, changed) in listOf(left to "1", right to "2")) {
            assertEquals(setOf("keyword", CHANGED_WORDS_STYLE), area.getStyleOfChar(1).toSet())
            assertFalse(CHANGED_WORDS_STYLE in area.getStyleOfChar(area.text.indexOf("x")))
            assertTrue(CHANGED_WORDS_STYLE in area.getStyleOfChar(area.text.indexOf(changed)))
        }
    }

    @Test
    fun `changed words are marked in plain text too`(robot: FxRobot) {
        val file = dir.resolve("notes.txt").apply { writeText("one new three\n") }
        val (left, right) = open(robot, file, TextDiff("one old three\n", listOf(LineChange(0..0, 0..0))))

        assertEquals(listOf(CHANGED_WORDS_STYLE), left.getStyleOfChar(left.text.indexOf("old") + 1).toList())
        assertEquals(listOf(CHANGED_WORDS_STYLE), right.getStyleOfChar(right.text.indexOf("new") + 1).toList())
        assertTrue(right.getStyleOfChar(right.text.indexOf("three") + 1).isEmpty())
    }

    @Test
    fun `the toolbar jumps between the changes on both sides`(robot: FxRobot) {
        val (left, right) = open(robot)

        assertEquals("3 changes", robot.counter())
        assertTrue(robot.navigationButton("Previous").isDisable)
        assertEquals(1, robot.press("Next", left, right))
        assertEquals("Change 1 of 3", robot.counter())
        assertEquals(3, robot.press("Next", left, right))
        assertEquals(1, robot.press("Previous", left, right))
        assertEquals(5, robot.press("Last", left, right))
        assertEquals("Change 3 of 3", robot.counter())
        assertTrue(robot.navigationButton("Next").isDisable)
        assertEquals(1, robot.press("First", left, right))
        assertTrue(robot.navigationButton("Previous").isDisable)
    }

    @Test
    fun `the Edit button and Escape go back while there is somewhere to go back to`(robot: FxRobot) {
        val file = dir.resolve("Main.kt").apply { writeText("val one = 1\n") }
        val view = openDiff(file, TextDiff("val one = 0\n", listOf(LineChange(0..0, 0..0))))
        robot.interact { container.children.setAll(view.node) }
        val right = robot.lookup(".code-area").queryAllAs(CodeArea::class.java).maxBy { it.localToScene(0.0, 0.0).x }

        fun escape() =
            robot.interact {
                Event.fireEvent(
                    right,
                    KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false),
                )
            }
        val back = robot.lookup(".diff-back").queryAs(Button::class.java)
        assertFalse(back.isVisible)
        escape()

        var backs = 0
        robot.interact { view.onBack = { backs++ } }
        assertTrue(back.isVisible)
        robot.interact { back.fire() }
        escape()
        assertEquals(2, backs)
    }

    @Test
    fun `jumps to the change at a line of the file`(robot: FxRobot) {
        val file =
            dir.resolve("Main.kt").apply {
                writeText("val one = 1\nval TWO = 2\nval three = 3\nval five = 5\nsix()\nseven()\n")
            }
        val view = openDiff(file, diff)
        robot.interact { container.children.setAll(view.node) }
        val (left, right) =
            robot.lookup(".code-area").queryAllAs(CodeArea::class.java).sortedBy { it.localToScene(0.0, 0.0).x }

        // Line 3 (five) is where four was removed.
        robot.interact { view.jumpToChangeAt(3) }
        assertEquals(listOf(3, 3), listOf(left.currentParagraph, right.currentParagraph))
        assertEquals("Change 2 of 3", robot.counter())

        robot.interact { view.jumpToChangeAt(4) }
        assertEquals(5, right.currentParagraph)
        assertEquals("Change 3 of 3", robot.counter())
    }

    @Test
    fun `F7 and Shift+F7 jump to the next and previous change, seen from the caret on either side`(robot: FxRobot) {
        val (left, right) = open(robot)

        fun key(shift: Boolean) {
            robot.interact {
                Event.fireEvent(right, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F7, shift, false, false, false))
            }
            WaitForAsyncUtils.waitForFxEvents()
        }
        robot.interact { left.moveTo(4, 0) }
        assertEquals("3 changes", robot.counter())
        key(shift = false)
        assertEquals(5, right.currentParagraph)
        key(shift = true)
        assertEquals(3, right.currentParagraph)
        assertEquals(3, left.currentParagraph)
        assertEquals("Change 2 of 3", robot.counter())
    }

    @Test
    fun `a change jumped to is scrolled into view`(robot: FxRobot) {
        val long = (1..200).joinToString("") { "line $it\n" }
        val file = dir.resolve("long.txt").apply { writeText(long.replace("line 150\n", "changed\n")) }
        val (left, right) = open(robot, file, TextDiff(long, listOf(LineChange(149..149, 149..149))))

        robot.interact { robot.navigationButton("Next").fire() }

        val visible =
            WaitForAsyncUtils.asyncFx<List<Int>> { listOf(left, right).map { it.firstVisibleParToAllParIndex() } }
        assertEquals(listOf(146, 146), visible.get())
    }

    @Test
    fun `without changes all buttons are disabled`(robot: FxRobot) {
        val file = dir.resolve("a.txt").apply { writeText("a\n") }
        open(robot, file, TextDiff("a\n", emptyList()))

        assertEquals("No changes", robot.counter())
        assertTrue(robot.lookup(".diff-nav").queryAllAs(Button::class.java).all { it.isDisable })
    }

    @Test
    fun `line numbers are the real ones, filler rows have none`(robot: FxRobot) {
        val (left, right) = open(robot)

        fun numbers(area: CodeArea) =
            (0 until area.paragraphs.size).map { (area.paragraphGraphicFactory.apply(it) as Label).text.trim() }
        assertEquals(listOf("1", "2", "3", "4", "5", "", ""), numbers(left))
        assertEquals(listOf("1", "2", "3", "", "4", "5", "6"), numbers(right))
        val added = right.paragraphGraphicFactory.apply(5) as Label
        assertTrue("diff-added" in added.styleClass)
    }

    @Test
    fun `scrolling one side scrolls the other`(robot: FxRobot) {
        val long = (1..200).joinToString("") { "line $it\n" }
        val file = dir.resolve("long.txt").apply { writeText(long) }
        val view =
            openDiff(file, TextDiff(long, emptyList()))
        robot.interact { container.children.setAll(view.node) }
        val areas = robot.lookup(".code-area").queryAllAs(CodeArea::class.java).toList()

        robot.interact { areas[0].scrollYToPixel(500.0) }

        val first = areas[0].firstVisibleParToAllParIndex()
        assertTrue(first > 10, "scrolled to row $first")
        assertEquals(first, areas[1].firstVisibleParToAllParIndex())
    }

    @Test
    fun `show reads the file again, Windows-1252 too, and uses the new diff`(robot: FxRobot) {
        val file = dir.resolve("a.txt").apply { writeText("a\nb\n") }
        val view =
            openDiff(file, TextDiff("a\n", listOf(LineChange(1 until 1, 1..1))))
        robot.interact { container.children.setAll(view.node) }

        file.writeBytes("ä\n".toByteArray(TextEncoding.WINDOWS_1252.charset))
        robot.interact { view.show(TextDiff("ä\n", emptyList())) }

        val right = robot.lookup(".code-area").queryAllAs(CodeArea::class.java).maxBy { it.localToScene(0.0, 0.0).x }
        assertEquals("ä", right.text)
        assertEquals(listOf(emptyList<String>()), right.paragraphClasses())
    }

    @Test
    fun `a file deleted meanwhile fails reload and show and leaves the shown diff as it is`(robot: FxRobot) {
        val file = dir.resolve("a.txt").apply { writeText("a\nb\n") }
        val diff = TextDiff("a\n", listOf(LineChange(1 until 1, 1..1)))
        val view = openDiff(file, diff)
        robot.interact { container.children.setAll(view.node) }
        val right = robot.lookup(".code-area").queryAllAs(CodeArea::class.java).maxBy { it.localToScene(0.0, 0.0).x }
        val before = right.paragraphClasses()

        file.deleteExisting()
        robot.interact {
            assertFailsWith<IOException> { view.reload() }
            assertFailsWith<IOException> { view.show(TextDiff("a\n", emptyList())) }
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("a\nb", right.text)
        assertEquals(before, right.paragraphClasses())
    }

    @Test
    fun `a diff of given texts shows both sides, highlighted by the name, without reading the file`(robot: FxRobot) {
        // No such file: the name only picks the highlighter.
        val name = dir.resolve("gone/Main.kt")
        val current = "val one = 1\nval TWO = 2\nval three = 3\nval five = 5\nsix()\nseven()\n"
        val view =
            assertNotNull(WaitForAsyncUtils.asyncFx<DiffFileView?> { viewers.openDiff(name, current, diff) }.get())
        robot.interact { container.children.setAll(view.node) }
        val (left, right) =
            robot.lookup(".code-area").queryAllAs(CodeArea::class.java).sortedBy { it.localToScene(0.0, 0.0).x }

        assertEquals("val one = 1\nval two = 2\nval three = 3\nval four = 4\nval five = 5\n\n", left.text)
        assertEquals("val one = 1\nval TWO = 2\nval three = 3\n\nval five = 5\nsix()\nseven()", right.text)
        assertEquals(listOf("diff-added"), right.paragraphClasses()[5])
        assertFalse(left.isEditable || right.isEditable)
        assertTrue("keyword" in right.getStyleOfChar(right.text.indexOf("val") + 1))

        // Shown again (e.g. reloaded), it keeps the given text.
        robot.interact {
            view.reload()
            view.show(diff.copy(changes = emptyList()))
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(current.removeSuffix("\n"), right.text.trimEnd('\n'))
        assertTrue(right.paragraphClasses().all { it.isEmpty() })
    }
}
