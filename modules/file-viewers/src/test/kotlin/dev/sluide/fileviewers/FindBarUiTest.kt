package dev.sluide.fileviewers

import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The find bar of a real [CodeView]. Needs a display (`uiTest`). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FindBarUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 600.0, 300.0)
        stage.show()
    }

    private fun open(
        text: String,
        robot: FxRobot,
        dispatch: ((Runnable) -> Unit)? = null,
    ): CodeView {
        val file = dir.resolve("main.py").apply { writeText(text) }
        val highlighter = PythonFileViewer().highlighter
        val view =
            WaitForAsyncUtils
                .asyncFx<CodeView> {
                    dispatch?.let { CodeView(file, highlighter, dispatch = it) } ?: CodeView(file, highlighter)
                }.get()
        robot.interact {
            container.children.setAll(view.node)
            view.area.requestFocus()
        }
        return view
    }

    private fun CodeView.selected() = area.selection.start to area.selection.end

    private fun CodeView.stylesAt(index: Int) = area.getStyleOfChar(index)

    @Test
    fun `shortcut+f searches the selected text and marks all matches`(robot: FxRobot) {
        val view = open("foo = Foo\nfoo()\n", robot)
        robot.interact { view.area.selectRange(10, 13) }

        robot.press(view.area, KeyCode.F, shortcut = true)

        val bar = view.findBar
        assertTrue(bar.isOpen)
        assertEquals("foo", bar.query.text)
        assertSame(bar.query, bar.query.scene.focusOwner)
        assertEquals(listOf(0..2, 6..8, 10..12), bar.matches)
        assertEquals("3 of 3", bar.counter.text)
        assertEquals(10 to 13, view.selected())
        assertTrue("search-match-current" in view.stylesAt(10))
        assertTrue("search-match" in view.stylesAt(0) && "search-match-current" !in view.stylesAt(0))
        assertTrue("search-match" !in view.stylesAt(4))
    }

    @Test
    fun `enter, shift+enter, f3 and shift+f3 go to the next and previous match, wrapping around`(robot: FxRobot) {
        val view = open("a a a", robot)
        robot.press(view.area, KeyCode.F, shortcut = true)
        val bar = view.findBar
        robot.interact { bar.query.text = "a" }
        assertEquals(0 to 1, view.selected())

        robot.press(bar.query, KeyCode.ENTER)
        assertEquals(2 to 3, view.selected())
        assertEquals("2 of 3", bar.counter.text)
        robot.press(bar.query, KeyCode.F3)
        robot.press(bar.query, KeyCode.F3)
        assertEquals(0 to 1, view.selected(), "wrapped to the first")

        robot.press(bar.query, KeyCode.ENTER, shift = true)
        assertEquals(4 to 5, view.selected(), "wrapped to the last")
        robot.press(view.area, KeyCode.F3, shift = true)
        assertEquals(2 to 3, view.selected())
        assertTrue("search-match-current" in view.stylesAt(2))
        assertFalse("search-match-current" in view.stylesAt(4))
    }

    @Test
    fun `the options narrow the matches and the counter tells about none or an invalid regex`(robot: FxRobot) {
        val view = open("Foo foo food\n", robot)
        robot.press(view.area, KeyCode.F, shortcut = true)
        val bar = view.findBar
        robot.interact { bar.query.text = "foo" }
        assertEquals(3, bar.matches.size)

        robot.interact { bar.matchCase.isSelected = true }
        assertEquals(listOf(4..6, 8..10), bar.matches)
        robot.interact { bar.wholeWord.isSelected = true }
        assertEquals(listOf(4..6), bar.matches)

        robot.interact { bar.query.text = "bar" }
        assertEquals("No results", bar.counter.text)

        robot.interact {
            bar.regex.isSelected = true
            bar.query.text = "fo("
        }
        assertTrue(bar.matches.isEmpty())
        assertTrue(bar.counter.text.isNotEmpty() && bar.counter.text != "No results", bar.counter.text)
    }

    @Test
    fun `escape closes the bar, removes the marks and gives the focus back to the text`(robot: FxRobot) {
        val view = open("foo foo\n", robot)
        robot.press(view.area, KeyCode.F, shortcut = true)
        robot.interact { view.findBar.query.text = "foo" }
        assertTrue("search-match" in view.stylesAt(4))

        robot.press(view.findBar.query, KeyCode.ESCAPE)

        assertFalse(view.findBar.isOpen)
        assertFalse("search-match" in view.stylesAt(4))
        assertFalse("search-match-current" in view.stylesAt(0))
        assertSame(view.area, view.area.scene.focusOwner)
    }

    @Test
    fun `matches follow edits of the text`(robot: FxRobot) {
        val view = open("x = 1\n", robot)
        robot.press(view.area, KeyCode.F, shortcut = true)
        robot.interact { view.findBar.query.text = "x" }
        assertEquals("1 of 1", view.findBar.counter.text)

        robot.interact { view.area.insertText(view.area.length, "x = 2\n") }

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { view.findBar.matches.size == 2 }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue("search-match" in view.stylesAt(6))
    }

    @Test
    fun `marks stay when the text is highlighted again`(robot: FxRobot) {
        val results = ConcurrentLinkedQueue<Runnable>()
        val view = open("x = 1\n", robot) { results += it }
        robot.press(view.area, KeyCode.F, shortcut = true)
        robot.interact { view.findBar.query.text = "pass" }

        robot.interact { view.area.insertText(0, "def f(): pass\n") }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { results.isNotEmpty() }
        robot.interact { results.poll().run() }

        assertTrue("keyword" in view.stylesAt(1), "highlighted")
        assertTrue("keyword" in view.stylesAt(9) && "search-match" in view.stylesAt(9), view.stylesAt(9).toString())
        assertEquals(listOf(9..12), view.findBar.matches)
    }
}
