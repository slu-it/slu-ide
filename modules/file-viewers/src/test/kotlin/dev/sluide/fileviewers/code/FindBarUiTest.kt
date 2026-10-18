package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.languages.PythonFileViewer
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
import kotlin.io.path.writeBytes
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

    @Test
    fun `replace replaces the selected match and goes to the next`(robot: FxRobot) {
        val view = open("a1 a2 a3\n", robot)
        robot.press(view.area, KeyCode.R, shortcut = true)
        val bar = view.findBar
        assertTrue(bar.isReplacing)
        robot.interact {
            bar.regex.isSelected = true
            bar.query.text = "a(\\d)"
            bar.replacement.text = "b$1"
        }
        assertEquals(0 to 2, view.selected())

        robot.interact { bar.replace() }
        assertEquals("b1 a2 a3\n", view.area.text)
        assertEquals(3 to 5, view.selected())
        assertEquals("1 of 2", bar.counter.text)

        robot.press(bar.replacement, KeyCode.ENTER)
        assertEquals("b1 b2 a3\n", view.area.text)

        robot.interact { view.area.undo() }
        assertEquals("b1 a2 a3\n", view.area.text, "each replacement is an undo step")
    }

    @Test
    fun `replace all replaces each match on its own, as one undo step`(robot: FxRobot) {
        val view = open("x = x + x\n", robot)
        robot.press(view.area, KeyCode.R, shortcut = true)
        val bar = view.findBar
        robot.interact {
            bar.query.text = "x"
            bar.replacement.text = "value"
        }
        val changed = mutableListOf<Pair<Int, String>>()
        val changes = view.area.plainTextChanges().subscribe { changed += it.position to it.inserted }

        robot.interact { bar.replaceAll() }
        changes.unsubscribe()
        assertEquals("value = value + value\n", view.area.text)
        assertEquals("3 replaced", bar.counter.text)
        assertTrue(bar.counter.pseudoClassStates.none { it.pseudoClassName == "error" }, "not shown as an error")
        assertEquals(listOf(0, 4, 8), changed.map { it.first }.sorted(), "the text between the matches stays")
        assertEquals(21, view.area.caretPosition, "after the last replacement")

        robot.interact { view.area.undo() }
        assertEquals("x = x + x\n", view.area.text)
        assertFalse(view.dirty.get())
    }

    @Test
    fun `an invalid replacement is shown and changes nothing`(robot: FxRobot) {
        val view = open("a1\n", robot)
        robot.press(view.area, KeyCode.R, shortcut = true)
        val bar = view.findBar
        robot.interact {
            bar.regex.isSelected = true
            bar.query.text = "a(\\d)"
            bar.replacement.text = "$2"
        }

        robot.interact { bar.replaceAll() }
        assertEquals("a1\n", view.area.text)
        assertTrue("2" in bar.counter.text, bar.counter.text)

        robot.interact { bar.replace() }
        assertEquals("a1\n", view.area.text)
    }

    @Test
    fun `shortcut+f and shortcut+r in the bar switch the replace row and keep the query`(robot: FxRobot) {
        val view = open("a b\n", robot)
        robot.press(view.area, KeyCode.R, shortcut = true)
        val bar = view.findBar
        assertTrue(bar.isReplacing)
        robot.interact {
            bar.regex.isSelected = true
            bar.query.text = "a|b"
        }
        assertEquals("a", view.area.selectedText)

        robot.press(bar.query, KeyCode.F, shortcut = true)
        assertTrue(bar.isOpen)
        assertFalse(bar.isReplacing)
        robot.press(bar.query, KeyCode.R, shortcut = true)
        assertTrue(bar.isReplacing)
        assertEquals("a|b", bar.query.text)
    }

    @Test
    fun `replacing is disabled while the view is read-only`(robot: FxRobot) {
        // Valid UTF-8 in the sample that decides whether a file is text, invalid after it: shown read-only.
        val file = dir.resolve("latin1.txt").apply { writeBytes("a".repeat(9000).toByteArray() + 0xE9.toByte()) }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(file, null) }.get()
        robot.interact { container.children.setAll(view.node) }
        robot.press(view.area, KeyCode.R, shortcut = true)
        val bar = view.findBar
        robot.interact {
            bar.query.text = "a"
            bar.replacement.text = "b"
        }

        assertTrue(bar.replacement.isDisabled)
        robot.interact { bar.replaceAll() }
        assertTrue(view.area.text.startsWith("aaa"))

        file.writeText("a\n")
        robot.interact { view.reload() }
        assertFalse(bar.replacement.isDisabled, "editable again")
    }
}
