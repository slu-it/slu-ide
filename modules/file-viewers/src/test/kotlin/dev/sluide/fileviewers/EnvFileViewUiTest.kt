package dev.sluide.fileviewers

import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ToggleButton
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A `.env` file in a real [EnvFileView], with its values masked. Needs a display (`uiTest`). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EnvFileViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = VBox()

    /** Something else in the window to give the focus to. */
    private lateinit var elsewhere: Button

    @Start
    fun start(stage: Stage) {
        // Controls need the toolkit, which is started for this method.
        elsewhere = Button("elsewhere")
        stage.scene = Scene(container, 600.0, 300.0)
        stage.show()
    }

    private val text = "FIRST=one\n# Database\nDB_PASSWORD=\"s3cret\" # rotated\nexport TOKEN=abc\n"

    private fun open(
        robot: FxRobot,
        name: String = ".env",
        dispatch: ((Runnable) -> Unit)? = null,
    ): EnvFileView {
        val file = dir.resolve(name).apply { writeText(text) }
        val viewer = EnvFileViewer()
        val view =
            WaitForAsyncUtils
                .asyncFx<EnvFileView> {
                    if (dispatch == null) {
                        viewer.open(file) as EnvFileView
                    } else {
                        EnvFileView(file, viewer.highlighter, viewer.comment, viewer.defaultIndent, dispatch)
                    }
                }.get()
        robot.interact {
            container.children.setAll(view.node, elsewhere)
            view.focusText()
        }
        return view
    }

    private fun EnvFileView.isMasked(snippet: String): Boolean {
        val start = source.area.text.indexOf(snippet)
        return (start until start + snippet.length).all { "masked" in source.area.getStyleOfChar(it) }
    }

    private fun EnvFileView.isShown(snippet: String): Boolean {
        val start = source.area.text.indexOf(snippet)
        return (start until start + snippet.length).none { "masked" in source.area.getStyleOfChar(it) }
    }

    @Test
    fun `values are masked when the file opens, also on the caret's first line`(robot: FxRobot) {
        val view = open(robot)

        assertTrue(view.isMasked("one"))
        assertTrue(view.isMasked("\"s3cret\""))
        assertTrue(view.isMasked("abc"))
        assertTrue(view.isShown("FIRST=") && view.isShown("# Database") && view.isShown(" # rotated"))
        assertTrue(view.isShown("export TOKEN="))
        val toggle = robot.lookup(".env-show-values").queryAs(ToggleButton::class.java)
        assertEquals("Show values", toggle.text)
        assertFalse(toggle.isSelected)
    }

    @Test
    fun `the line with the caret is revealed while the text has the focus`(robot: FxRobot) {
        val view = open(robot)

        robot.interact { view.source.area.moveTo(2, 3) }
        assertTrue(view.isShown("\"s3cret\""))
        assertTrue(view.isMasked("one") && view.isMasked("abc"))

        robot.interact { view.source.area.moveTo(0, 0) }
        assertTrue(view.isShown("one"))
        assertTrue(view.isMasked("\"s3cret\""))

        robot.interact { elsewhere.requestFocus() }
        assertTrue(view.isMasked("one"))

        robot.interact { view.focusText() }
        assertTrue(view.isShown("one"))
    }

    @Test
    fun `lines typed or pasted are masked once the caret leaves them`(robot: FxRobot) {
        val view = open(robot)
        val area = view.source.area

        robot.interact {
            area.moveTo(area.length)
            area.insertText(area.caretPosition, "NEW=pasted\nMORE=lines")
        }
        assertTrue(view.isShown("lines"))
        assertTrue(view.isMasked("pasted"), "masked right away, before highlighting")

        robot.interact { area.moveTo(0) }
        assertTrue(view.isMasked("lines"))
    }

    @Test
    fun `replacing all and undoing it masks the values right away`(robot: FxRobot) {
        // The highlighting pass never runs: only the synchronous masking can hide the values.
        val view = open(robot, dispatch = {})
        val bar = view.source.findBar

        robot.interact {
            bar.open(replacing = true)
            bar.regex.isSelected = true
            bar.query.text = "=(.*)"
            bar.replacement.text = "=$1"
            bar.replaceAll()
        }
        assertEquals(text, view.source.area.text)
        assertTrue(view.isMasked("one") && view.isMasked("\"s3cret\"") && view.isMasked("abc"))

        robot.interact {
            bar.regex.isSelected = false
            bar.query.text = "one"
            bar.replacement.text = "two"
            bar.replaceAll()
            view.source.area.undo()
        }
        assertEquals(text, view.source.area.text)
        assertTrue(view.isMasked("one"), "the undone value")
    }

    @Test
    fun `pasted lines are masked right away, though the caret stays on its line`(robot: FxRobot) {
        val view = open(robot, dispatch = {})
        val area = view.source.area

        robot.interact {
            // Two lines replace two selected lines: the caret stays on line 3, at the end of the paste.
            area.selectRange(area.text.indexOf("# Database"), area.text.indexOf("export"))
            area.replaceSelection("NEW=pasted\nMORE=lines\n")
        }
        assertEquals(3, area.currentParagraph)
        assertTrue(view.isMasked("pasted") && view.isMasked("lines"))
        assertTrue(view.isShown("abc"), "the caret's line")
    }

    @Test
    fun `the caret's line is revealed after deleting or moving lines`(robot: FxRobot) {
        val view = open(robot, dispatch = {})
        val area = view.source.area

        robot.interact { area.moveTo(0, 2) }
        assertTrue(view.isShown("one"))
        // Shortcut+Shift+K: the caret stays on line 0, which now holds the comment, then the password.
        robot.press(area, KeyCode.K, shortcut = true, shift = true)
        robot.press(area, KeyCode.K, shortcut = true, shift = true)
        assertEquals(0, area.currentParagraph)
        assertTrue(view.isShown("\"s3cret\""))
        assertTrue(view.isMasked("abc"))

        // Alt+Shift+Down: the password's line moves below the token's, with the caret.
        robot.press(area, KeyCode.DOWN, shift = true, alt = true)
        assertEquals(1, area.currentParagraph)
        assertTrue(view.isShown("\"s3cret\""))
        assertTrue(view.isMasked("abc"))
    }

    @Test
    fun `show values reveals all of them, until toggled off`(robot: FxRobot) {
        val view = open(robot)

        robot.interact { view.showValues.set(true) }
        assertTrue(view.isShown(text.trimEnd()))

        robot.interact { view.showValues.set(false) }
        assertTrue(view.isMasked("one") && view.isMasked("abc"))
    }

    @Test
    fun `copying, saving and the text keep the real values`(robot: FxRobot) {
        val view = open(robot)
        val file = dir.resolve(".env")

        robot.interact {
            view.source.area.selectAll()
            view.source.area.copy()
        }
        assertEquals(text, WaitForAsyncUtils.asyncFx<String> { Clipboard.getSystemClipboard().string }.get())

        robot.interact {
            view.source.area.insertText(0, "# top\n")
            view.save()
        }
        assertEquals("# top\n$text", file.readText())
        assertFalse(view.dirty.get())
    }

    @Test
    fun `a find match reveals its line, other matches stay masked`(robot: FxRobot) {
        val view = open(robot)
        val bar = view.source.findBar

        robot.interact {
            bar.open()
            bar.query.text = "c"
        }
        // The first match after the caret is in "s3cret", the focus is in the query.
        assertEquals(text.indexOf("cret"), view.source.area.selection.start)
        assertTrue(view.isShown("\"s3cret\""))
        assertTrue(view.isMasked("abc"))
        val other = text.indexOf("abc") + 2
        assertTrue("search-match" in view.source.area.getStyleOfChar(other), "marked, but masked over it")

        robot.interact { bar.next() }
        assertTrue(view.isShown("abc"))
        assertTrue(view.isMasked("\"s3cret\""))
    }

    @Test
    fun `placeholder files show their values and no toolbar`(robot: FxRobot) {
        val view = open(robot, ".env.example")

        assertTrue(view.isShown(text.trimEnd()))
        assertNull(robot.lookup(".env-show-values").tryQuery<ToggleButton>().orElse(null))
    }

    @Test
    fun `renamed to a placeholder file, the values show`(robot: FxRobot) {
        val view = open(robot)

        robot.interact { view.moved(dir.resolve(".env.sample")) }

        assertTrue(view.isShown(text.trimEnd()))
    }
}
