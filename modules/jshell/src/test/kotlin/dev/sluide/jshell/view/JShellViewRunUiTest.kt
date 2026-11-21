package dev.sluide.jshell.view

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.SplitMode
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.jshell.FakeAgent
import dev.sluide.jshell.JShellFileViewer
import javafx.event.Event
import javafx.geometry.Point2D
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The view's connections to its runs (ADR 0191): keys, buttons, the mode, the transcript's clicks and the tab's
 * warning, with fake agents. What the runs do with a session is JShellRunsTest's. Needs a display: the view is made of
 * controls.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class JShellViewRunUiTest : JShellViewUiFixture() {
    // the second request of compile-error.jsonl: the whole text, offsets 0..34
    private val whole = "String s = \"x\"\nint z = s.lenth();\n"
    private val wholeLines = listOf(1, 6, 7, 8, 9, 10, 11)

    @Start
    fun start(stage: Stage) = startWindow(stage)

    private fun ready(view: JShellView) = waitUntil("the JDK") { !view.runButton.isDisable }

    @Test
    fun `Shortcut+Enter in the source runs the snippet at the caret`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("value"))
        val view = open(robot, "int x = 5;")
        ready(view)

        fire(robot, codeArea(robot), key(KeyCode.ENTER))

        waitUntil("the value") { "x ==> 5" in view.transcript.area.text }
        assertTrue("JShell 25.0.5 (the project's JDK)" in fx { view.transcript.area.text })
    }

    @Test
    fun `Shortcut+Shift+Enter in the source runs the whole text`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("compile-error", only = wholeLines))
        val view = open(robot, whole)
        ready(view)

        fire(robot, codeArea(robot), key(KeyCode.ENTER, shift = true))

        waitUntil("the diagnostic") { "cannot find symbol" in view.transcript.area.text }
    }

    @Test
    fun `Shortcut+Enter in the transcript runs nothing`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("value"))
        val view = open(robot, "int x = 5;")
        ready(view)

        fire(robot, view.transcript.area, key(KeyCode.ENTER))
        fire(robot, view.transcript.area, key(KeyCode.ENTER, shift = true))

        // a run marks the view busy at once, before any worker is involved
        assertEquals(ViewActivity.NONE, fx { view.activity.value })
        assertFalse(fx { view.runButton.isDisable })
        assertEquals("", fx { view.transcript.area.text })
    }

    @Test
    fun `Shortcut+Enter in the find bar runs nothing`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("value"))
        val view = open(robot, "int x = 5;")
        ready(view)
        fire(robot, codeArea(robot), key(KeyCode.F))
        val field = robot.lookup(".find-bar .text-field").queryAs(TextField::class.java)

        // read in the same FX step: the fake's answer could end a run before a later step reads it
        var activity: ViewActivity? = null
        robot.interact {
            Event.fireEvent(field, key(KeyCode.ENTER))
            activity = view.activity.value
        }

        assertEquals(ViewActivity.NONE, activity)
        // no signal for "nothing ran": the fake answers a run within a few ms
        Thread.sleep(300)
        robot.interact { }
        assertEquals("", fx { view.transcript.area.text })
    }

    @Test
    fun `Run right after opening waits for the JDK and then runs`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("value"))
        val view = open(robot, "int x = 5;", withHost = false)

        // the host and the key come in one FX step: the JDK is not looked up yet
        robot.interact {
            view.host = host()
            Event.fireEvent(codeArea(robot), key(KeyCode.ENTER))
        }

        waitUntil("the value") { "x ==> 5" in view.transcript.area.text }
    }

    @Test
    fun `Run All right after opening waits for the JDK and then runs`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("compile-error", only = wholeLines))
        val all = open(robot, whole, withHost = false)

        // the host and the call come in one FX step: the JDK is not looked up yet
        robot.interact {
            all.host = host()
            all.runAll()
        }

        waitUntil("the diagnostic") { "cannot find symbol" in all.transcript.area.text }
    }

    @Test
    fun `Run with a selection runs the selected snippets`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("compile-error", only = wholeLines))
        val view = open(robot, whole)
        ready(view)
        robot.interact { codeArea(robot).selectRange(0, 34) }

        fire(robot, codeArea(robot), key(KeyCode.ENTER))

        waitUntil("the diagnostic") { "cannot find symbol" in view.transcript.area.text }
    }

    @Test
    fun `the Run All button runs the whole text`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("compile-error", only = wholeLines))
        val view = open(robot, whole)
        ready(view)

        robot.interact { view.runAllButton.fire() }

        waitUntil("the diagnostic") { "cannot find symbol" in view.transcript.area.text }
    }

    @Test
    fun `the Restart button ends the session and notes it`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        val agent = FakeAgent.of("value")
        agents.put(agent)
        val view = open(robot, "int x = 5;")
        ready(view)
        robot.interact { view.runButton.fire() }
        waitUntil("the value") { "x ==> 5" in view.transcript.area.text }

        robot.interact { view.restartButton.fire() }

        assertTrue("Session restarted" in fx { view.transcript.area.text })
        assertTrue(agent.waitFor(10_000), "the session ended")
    }

    @Test
    fun `the Clear button empties the transcript`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("value"))
        val view = open(robot, "int x = 5;")
        ready(view)
        robot.interact { view.runButton.fire() }
        waitUntil("the value") { "x ==> 5" in view.transcript.area.text }

        robot.interact { view.clearButton.fire() }

        assertEquals("", fx { view.transcript.area.text })
    }

    @Test
    fun `closing the view ends its session`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        val agent = FakeAgent.of("value")
        agents.put(agent)
        val view = open(robot, "int x = 5;")
        ready(view)
        robot.interact { view.runButton.fire() }
        waitUntil("the value") { "x ==> 5" in view.transcript.area.text }

        robot.interact { view.dispose() }

        assertTrue(agent.waitFor(10_000), "the session ended")
    }

    @Test
    fun `a view hands out completions of its text through the session, and none outside a window`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("completion"))
        val view = open(robot, "\"abc\".")
        ready(view)

        val answer = fx { view.complete(6) }

        waitUntil("the completions") { answer.isDone }
        assertEquals(6, answer.get().anchor)
        assertEquals(
            "charAt(",
            answer
                .get()
                .items
                .first()
                .text,
        )

        val alone = open(robot, "\"abc\".", withHost = false)
        assertTrue(fx { alone.complete(6) }.isCompletedExceptionally)
    }

    @Test
    fun `a view follows its file when it moves and passes its text through`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        val view = open(robot, "int x = 5;")
        val moved = dir.resolve("renamed.jsh")

        assertTrue(fx { view.canReplaceText })
        assertTrue(fx { view.replaceText("int y = 6;") })
        assertEquals("int y = 6;", fx { view.text })
        assertTrue(fx { view.moved(moved) })

        assertEquals(moved, fx { view.file })
    }

    @Test
    fun `focus goes to the source or to the transcript shown alone, and a line shows the source but keeps Source mode`(
        robot: FxRobot,
    ) {
        projectSetting = jdkHome(25, "25.0.5")
        val view = open(robot, "int x = 5;\nint y = 6;\n")
        val area = codeArea(robot)
        robot.interact { view.transcript.area.requestFocus() }
        robot.interact { view.focus() }
        waitUntil("the source focus") { area.isFocused }

        robot.interact { view.split.mode.set(SplitMode.OTHER) }
        robot.interact { view.jdkButton.requestFocus() }
        robot.interact { view.focus() }
        assertTrue(fx { view.transcript.area.isFocused })
        robot.interact { view.jdkButton.requestFocus() }
        robot.interact { view.focusText() }
        assertTrue(fx { view.transcript.area.isFocused })

        robot.interact { view.showLine(1) }

        assertEquals(SplitMode.SPLIT, fx { view.split.mode.get() })
        assertEquals(1, fx { editorOf.caretLine })
        robot.interact { view.transcript.area.requestFocus() }
        robot.interact { view.focusText() }
        waitUntil("the source focus again") { area.isFocused }

        robot.interact { view.split.mode.set(SplitMode.SOURCE) }
        robot.interact { view.showLine(0) }
        assertEquals(SplitMode.SOURCE, fx { view.split.mode.get() }, "the source shows already")
        assertEquals(0, fx { editorOf.caretLine })
    }

    @Test
    fun `a closed view's source takes no focus`(robot: FxRobot) {
        val view = open(robot, "int x = 5;", withHost = false)
        val area = codeArea(robot)
        robot.interact { view.transcript.area.requestFocus() }

        robot.interact {
            view.dispose()
            view.focus()
        }

        assertFalse(fx { area.isFocused })
        assertTrue(fx { view.transcript.area.isFocused })
    }

    @Test
    fun `the keys are consumed, and a click on a button leaves the focus in the source`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("value"))
        val view = open(robot, "int x = 5;")
        ready(view)
        // an event the view consumed in its filter never reaches the handlers of the text area
        var reached = 0
        robot.interact { codeArea(robot).addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED) { reached++ } }

        fire(robot, codeArea(robot), key(KeyCode.ENTER))
        waitUntil("the value") { "x ==> 5" in view.transcript.area.text }
        fire(robot, codeArea(robot), key(KeyCode.ENTER, shift = true))
        assertEquals(0, reached, "both keys were consumed")

        val area = codeArea(robot)
        robot.interact { view.transcript.area.requestFocus() }
        robot.interact { view.clearButton.fire() }
        waitUntil("the focus back in the source") { area.isFocused }
        robot.interact { view.split.mode.set(SplitMode.OTHER) }
        robot.interact { view.transcript.area.requestFocus() }
        robot.interact { view.clearButton.fire() }
        assertTrue(fx { view.transcript.area.isFocused }, "with the source hidden the focus stays")
        // a focus asked for the hidden source would be given to it once it is shown again
        robot.interact { view.split.mode.set(SplitMode.SPLIT) }
        waitUntil("the source shown") { area.scene != null }
        robot.interact { }
        assertTrue(fx { view.transcript.area.isFocused }, "and the source did not ask for it later")
    }

    @Test
    fun `a Run that cannot run leaves the mode alone, and a Run in Output mode leaves it too`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        // stop.jsonl: the lines of `while (true) {}` and of its stop
        agents.put(FakeAgent.of("stop", only = listOf(1, 6, 7, 12, 13, 14)))
        val view = open(robot, "while (true) {}")
        ready(view)
        robot.interact { view.runButton.fire() }
        waitUntil("the snippet to start") { !view.stopButton.isDisable }

        robot.interact { view.split.mode.set(SplitMode.SOURCE) }
        fire(robot, codeArea(robot), key(KeyCode.ENTER))
        assertEquals(SplitMode.SOURCE, fx { view.split.mode.get() }, "a busy view does not take the key")
        robot.interact { view.stopButton.fire() }
        waitUntil("the end of the run") { !view.runButton.isDisable }

        // the session outlives the Stop: this run goes to the same agent, which answers no more; the mode is set as
        // the run starts, so it is read in the same FX step
        robot.interact { view.split.mode.set(SplitMode.OTHER) }
        var activity: ViewActivity? = null
        robot.interact {
            view.runButton.fire()
            activity = view.activity.value
        }
        assertEquals(ViewActivity.RUNNING, activity, "the run started")
        assertEquals(SplitMode.OTHER, fx { view.split.mode.get() })
    }

    @Test
    fun `the viewer opens a view of the file with its text, highlighted as Java`(robot: FxRobot) {
        val file = dir.resolve("open.jsh").apply { writeText("final int z = 3;\n") }

        val view = WaitForAsyncUtils.asyncFx<FileView> { JShellFileViewer().open(file) }.get()

        assertIs<JShellView>(view)
        assertEquals("final int z = 3;\n", fx { view.text })
        // the code area is made with the source's skin: in a scene
        show(robot, view.node)
        val area = codeArea(robot)
        waitUntil("Java's highlighting of final") { "keyword" in area.getStyleOfChar(1) }
        robot.interact { view.dispose() }
    }

    @Test
    fun `Run from Source mode shows the transcript too`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("value"))
        val view = open(robot, "int x = 5;")
        ready(view)
        robot.interact { view.split.mode.set(SplitMode.SOURCE) }

        robot.interact { view.runButton.fire() }

        assertEquals(SplitMode.SPLIT, fx { view.split.mode.get() })
        waitUntil("the value") { "x ==> 5" in view.transcript.area.text }
    }

    @Test
    fun `a click on the header of a run goes to its line, a line past the end to the last`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        agents.put(FakeAgent.of("compile-error", only = listOf(1, 2, 3, 4, 5)))
        val view = open(robot, "// setup\nint y = \"s\";\n")
        ready(view)
        robot.interact { codeArea(robot).moveTo(9) }
        fire(robot, codeArea(robot), key(KeyCode.ENTER))
        waitUntil("the diagnostic") { "incompatible types" in view.transcript.area.text }

        robot.interact { editorOf.moveCaretTo(0) }
        click(robot, view, 1)
        waitUntil(
            "the caret at the line of the run: ${fx {
                view.transcript.area.paragraphs
                    .map { it.text }
            }}",
        ) { editorOf.caretLine == 1 }

        robot.interact { editorOf.replaceText("x") }
        robot.interact { editorOf.moveCaretTo(0) }
        click(robot, view, 1)
        assertEquals(0, fx { editorOf.caretLine }, "the line of the run is past the shortened text")
    }

    /** A click on [paragraph] of the transcript. */
    private fun click(
        robot: FxRobot,
        view: JShellView,
        paragraph: Int,
    ) {
        val bounds =
            fx {
                view.transcript.area
                    .getParagraphBoundsOnScreen(paragraph)
                    .get()
            }
        // a real click: the hit test of the area decides the paragraph (as in the pane's own test)
        robot.clickOn(Point2D(bounds.minX + 40, (bounds.minY + bounds.maxY) / 2))
    }

    @Test
    fun `a running snippet marks the tab and warns on close, an idle view does not`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        // stop.jsonl: the lines of `while (true) {}` and of its stop
        agents.put(FakeAgent.of("stop", only = listOf(1, 6, 7, 12, 13, 14)))
        val view = open(robot, "while (true) {}")
        ready(view)
        assertNull(fx { view.closeWarning })
        assertEquals(ViewActivity.NONE, fx { view.activity.value })

        robot.interact { view.runButton.fire() }
        waitUntil("the snippet to start") { !view.stopButton.isDisable }

        assertEquals("deploy.jsh is still running a JShell snippet.", fx { view.closeWarning })
        assertEquals(ViewActivity.RUNNING, fx { view.activity.value })
        assertTrue(fx { view.runButton.isDisable })
        robot.interact { view.stopButton.fire() }
        waitUntil("the end of the run") { !view.runButton.isDisable }
        assertNull(fx { view.closeWarning })
        assertEquals(ViewActivity.NONE, fx { view.activity.value })
        assertTrue(fx { view.stopButton.isDisable })
    }
}
