package dev.sluide.fileviewers.code

import dev.sluide.textediting.TextSearch
import javafx.event.Event
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
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The find bar with slow regexes, many matches and large texts. Needs a display (`uiTest`). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FindBarLimitsUiTest {
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
    ): CodeView {
        val file = dir.resolve("text.txt").apply { writeText(text) }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(file, null) }.get()
        robot.interact {
            container.children.setAll(view.node)
            view.area.requestFocus()
        }
        return view
    }

    @Test
    fun `a regex that takes too long gives up and says so`(robot: FxRobot) {
        val view = open("a".repeat(40) + "c\n", robot)
        robot.press(view.area, KeyCode.F, shortcut = true)
        val bar = view.findBar
        robot.interact { bar.regex.isSelected = true }
        val started = System.nanoTime()

        robot.interact { bar.query.text = "(a+)+\\1b" }

        assertTrue(System.nanoTime() - started < 3_000_000_000L, "the UI is not blocked for long")
        assertEquals(TextSearch.TOO_LONG, bar.counter.text)
        assertTrue(bar.matches.isEmpty())
    }

    @Test
    fun `at most ten thousand matches are marked and counted`(robot: FxRobot) {
        val view = open("a\n".repeat(MAX_MARKED + 5), robot)
        robot.press(view.area, KeyCode.F, shortcut = true)
        val bar = view.findBar

        robot.interact { bar.query.text = "a" }

        assertEquals(MAX_MARKED, bar.matches.size)
        assertEquals("1 of 10000+", bar.counter.text)
        assertTrue("search-match" in view.area.getStyleOfChar(2 * (MAX_MARKED - 1)))
        assertFalse("search-match" in view.area.getStyleOfChar(2 * MAX_MARKED))
    }

    @Test
    fun `going to another match restyles only the old and the new current match`(robot: FxRobot) {
        val view = open("x a x a x a x\n", robot)
        robot.press(view.area, KeyCode.F, shortcut = true)
        val bar = view.findBar
        robot.interact { bar.query.text = "a" }
        assertEquals(2 to 3, view.area.selection.start to view.area.selection.end)
        val restyled = mutableListOf<Int>()
        val changes = view.area.richChanges().subscribe { restyled += it.position }

        robot.press(bar.query, KeyCode.ENTER)
        changes.unsubscribe()

        assertEquals(listOf(2, 6), restyled.sorted())
        assertTrue("search-match-current" in view.area.getStyleOfChar(6))
        assertFalse("search-match-current" in view.area.getStyleOfChar(2))
        assertTrue("search-match" in view.area.getStyleOfChar(2))
    }

    @Test
    fun `f3 with the bar closed opens it and keeps the focus in the text`(robot: FxRobot) {
        val view = open("foo bar foo\n", robot)
        robot.interact { view.area.selectRange(0, 3) }

        robot.press(view.area, KeyCode.F3)

        assertTrue(view.findBar.isOpen)
        assertEquals("foo", view.findBar.query.text)
        assertEquals(8 to 11, view.area.selection.start to view.area.selection.end)
        assertSame(view.area, view.area.scene.focusOwner)
    }

    @Test
    fun `in a large text the query is searched once typing it pauses, or on enter`(robot: FxRobot) {
        val view = open("x".repeat(1 shl 20) + " needle\n", robot)
        robot.press(view.area, KeyCode.F, shortcut = true)
        val bar = view.findBar

        // In one go on the FX thread: on a slow machine, rendering the long line between two steps can outlast the pause.
        val (whileTyping, onEnter) =
            WaitForAsyncUtils
                .asyncFx<Pair<Int, Int>> {
                    bar.query.text = "needle"
                    val typing = bar.matches.size
                    Event.fireEvent(bar.query, keyPress(KeyCode.ENTER, shortcut = false))
                    typing to bar.matches.size
                }.get()
        assertEquals(0, whileTyping, "not searched while typing")
        assertEquals(1, onEnter, "searched on enter")

        robot.interact { bar.query.text = "need" }
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) { bar.matches.singleOrNull()?.last == (1 shl 20) + 4 }
    }
}
