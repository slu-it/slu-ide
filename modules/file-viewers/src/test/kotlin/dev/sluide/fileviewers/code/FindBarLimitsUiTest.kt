package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.keyPress
import dev.sluide.fileviewers.press
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
import java.time.Duration
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
        followPause: Duration = FOLLOW_PAUSE,
    ): CodeView {
        val file = dir.resolve("text.txt").apply { writeText(text) }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(file, null, followPause = followPause) }.get()
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
    fun `a search that ran out of time is not repeated while typing`(robot: FxRobot) {
        val view = open("a".repeat(40) + "c\n", robot, followPause = Duration.ofMillis(1))
        robot.press(view.area, KeyCode.F, shortcut = true)
        val bar = view.findBar
        robot.interact {
            bar.regex.isSelected = true
            bar.query.text = "(a+)+\\1b"
        }
        assertEquals(TextSearch.TOO_LONG, bar.counter.text)

        // A text this regex finds nothing in at once, so a repeated search would say "No results".
        var pauseOver = false
        val over =
            view.area
                .multiPlainChanges()
                .successionEnds(Duration.ofMillis(50))
                .subscribe { pauseOver = true }
        robot.interact { view.area.replaceText("b\n") }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { pauseOver }
        WaitForAsyncUtils.waitForFxEvents()
        over.unsubscribe()

        assertEquals(TextSearch.TOO_LONG, bar.counter.text)
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
        val view = open("x\n".repeat(1 shl 19) + " needle\n", robot)
        robot.press(view.area, KeyCode.F, shortcut = true)
        val bar = view.findBar

        // In one go on the FX thread: on a slow machine, rendering the large text between two steps can outlast the pause.
        val (whileTyping, onEnter) =
            WaitForAsyncUtils
                .asyncFx<Pair<Int, Int>> {
                    bar.query.text = "needle"
                    val typing = bar.matches.size
                    Event.fireEvent(bar.query, keyPress(KeyCode.ENTER))
                    typing to bar.matches.size
                }.get()
        assertEquals(0, whileTyping, "not searched while typing")
        assertEquals(1, onEnter, "searched on enter")

        robot.interact { bar.query.text = "need" }
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) { bar.matches.singleOrNull()?.last == (1 shl 20) + 4 }
    }
}
