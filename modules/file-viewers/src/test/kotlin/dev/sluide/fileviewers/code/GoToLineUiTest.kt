package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.press
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shortcut+G in a real [CodeView], with the dialog replaced. Needs a display (`uiTest`). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GoToLineUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 400.0, 200.0)
        stage.show()
    }

    private fun open(
        robot: FxRobot,
        lines: Int = 100,
    ): CodeView {
        val file = dir.resolve("lines.txt").apply { writeText((1..lines).joinToString("\n") { "line $it" }) }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(file, null) }.get()
        robot.interact { container.children.setAll(view.node) }
        return view
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get()

    private fun first(view: CodeView) = fx { view.area.firstVisibleParToAllParIndex() }

    @Test
    fun `shortcut+g moves the caret to the line and column entered and scrolls there`(robot: FxRobot) {
        val view = open(robot)
        robot.interact { view.area.moveTo(1, 2) }
        val asked = mutableListOf<String>()
        view.goToLine.ask = {
            asked += it
            "80:3"
        }

        robot.press(view.area, KeyCode.G, shortcut = true)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("2:3"), asked, "offers the caret's position")
        assertEquals(79, fx { view.area.currentParagraph })
        assertEquals(2, fx { view.area.caretColumn })
        assertTrue(fx { 79 in view.area.firstVisibleParToAllParIndex()..view.area.lastVisibleParToAllParIndex() })
    }

    /** Where the top of the first shown paragraph is, relative to the area's top: 0 if it is shown from its start. */
    private fun firstLineTop(view: CodeView): Double =
        WaitForAsyncUtils
            .asyncFx<Double> {
                val node =
                    view.area
                        .virtualFlow()!!
                        .getCellIfVisible(
                            view.area.firstVisibleParToAllParIndex(),
                        ).get()
                        .node
                node.layoutY + node.layoutBounds.minY
            }.get()

    @ParameterizedTest(name = "a jump to {0} line puts it first")
    @ValueSource(strings = ["a far", "a half shown bottom", "a top cut by 8px"])
    fun `a jump puts a line that is not fully shown at the top`(
        which: String,
        robot: FxRobot,
    ) {
        val view = open(robot, lines = 200)
        val target =
            when (which) {
                "a far" -> {
                    150
                }

                "a half shown bottom" -> {
                    fx { view.area.lastVisibleParToAllParIndex() }
                }

                else -> {
                    robot.interact { view.area.scrollYBy(500.0) }
                    robot.interact { view.area.scrollYBy(8.0) }
                    first(view)
                }
            }
        robot.interact { view.goToLine.goTo(target) }

        assertEquals(target, first(view))
        assertEquals(0.0, firstLineTop(view), 0.5, "shown from its start")
    }

    @Test
    fun `a jump to a line already fully shown does not scroll`(robot: FxRobot) {
        val view = open(robot, lines = 200)
        val before = first(view)
        robot.interact { view.goToLine.goTo(before + 1) }

        assertEquals(before, first(view))
        assertEquals(before + 1, fx { view.area.currentParagraph })
    }

    @ParameterizedTest(name = "showPosition({0}) before the view is laid out leaves line {1} first")
    @CsvSource("120,120", "3,0")
    fun `showPosition before the view is laid out scrolls once it is shown, unless the line is shown`(
        line: Int,
        expectedFirst: Int,
        robot: FxRobot,
    ) {
        val file = dir.resolve("lines.txt").apply { writeText((1..200).joinToString("\n") { "line $it" }) }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(file, null) }.get()
        robot.interact { view.showPosition(line, 2) }
        robot.interact { container.children.setAll(view.node) }
        // The scroll runs in pulses after the layout that gives the area its height; a poll, then the check.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline &&
            WaitForAsyncUtils.asyncFx<Int> { view.area.firstVisibleParToAllParIndex() }.get() != expectedFirst
        ) {
            WaitForAsyncUtils.waitForFxEvents()
        }
        assertEquals(expectedFirst, first(view))
        assertEquals(line, fx { view.area.currentParagraph })
        assertEquals(0.0, firstLineTop(view), 0.5, "shown from its start")
    }

    @Test
    fun `showPosition while a laid out view is out of its scene keeps its position when the line is shown there`(
        robot: FxRobot,
    ) {
        val view = open(robot, lines = 200)
        robot.interact { view.goToLine.goTo(40) }
        assertEquals(40, first(view))
        robot.interact { container.children.clear() }
        robot.interact { view.showPosition(45, 0) }
        robot.interact { container.children.setAll(view.node) }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline && fx { view.area.visibleParagraphs.isEmpty() }) {
            WaitForAsyncUtils.waitForFxEvents()
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(40, first(view))
        assertEquals(45, fx { view.area.currentParagraph })
    }

    @Test
    fun `positions beyond the text are clamped and other input is ignored`(robot: FxRobot) {
        val view = open(robot)

        view.goToLine.ask = { "500:99" }
        robot.press(view.area, KeyCode.G, shortcut = true)
        assertEquals(view.area.length, view.area.caretPosition)

        view.goToLine.ask = { "abc" }
        robot.press(view.area, KeyCode.G, shortcut = true)
        view.goToLine.ask = { null }
        robot.press(view.area, KeyCode.G, shortcut = true)
        assertEquals(view.area.length, view.area.caretPosition)
    }
}
