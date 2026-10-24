package dev.sluide.gradle.view

import dev.sluide.fileviewers.ViewActivity
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs of the Gradle view: starting them from the tree, the status line, the tab marker, the stop question, Stop and
 * closing (ADR 0085, ADR 0086). The texts are RunTextsTest's. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GradleFileViewRunsUiTest : GradleFileViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private fun loaded(robot: FxRobot): GradleFileView {
        build()
        return open(robot).also { awaitTasks(robot, it) }
    }

    private fun status(view: GradleFileView) = view.runPane.status.text

    @Test
    fun `a double-click runs the task and the focus stays in the tree, Enter runs the selection in tree order`(
        robot: FxRobot,
    ) {
        val view = loaded(robot)
        robot.interact {
            view.tasks.tree.requestFocus()
            doubleClick(taskCell(robot, view, ":hello"))
        }
        eventually(robot) {
            assertTrue(status(view).startsWith("✔ :hello — finished in "), status(view))
            assertEquals(ViewActivity.NONE, view.activity.value)
        }
        robot.interact { assertSame(view.tasks.tree, view.node.scene.focusOwner) }

        robot.interact {
            val tree = view.tasks.tree
            tree.selectionModel.clearSelection()
            tree.selectionModel.select(taskCell(robot, view, ":help").treeItem)
            tree.selectionModel.select(taskCell(robot, view, ":hello").treeItem)
            Event.fireEvent(tree, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
        }
        eventually(robot) {
            assertTrue(status(view).startsWith("✔ :hello :help — finished in "), status(view))
        }
        assertTrue(taskLog.any { it.endsWith("started: Gradle: :hello :help") }, taskLog.toString())
    }

    @Test
    fun `a failing task marks the tab until the view is shown again, and Rerun runs it again`(robot: FxRobot) {
        val view = loaded(robot)
        robot.interact { doubleClick(taskCell(robot, view, ":fail")) }
        eventually(robot) {
            assertTrue(status(view).startsWith("✖ :fail — failed (exit 1) after "), status(view))
            assertEquals(ViewActivity.FAILED, view.activity.value)
        }
        assertTrue(
            taskLog.any { it.contains("failed after") && it.endsWith("Gradle: :fail: exit 1") },
            taskLog.toString(),
        )

        robot.interact {
            view.focus()
            assertEquals(ViewActivity.NONE, view.activity.value)
        }

        robot.interact { view.runPane.rerun.fire() }
        eventually(robot) { assertEquals(ViewActivity.FAILED, view.activity.value) }
        assertEquals(2, taskLog.count { it.endsWith("started: Gradle: :fail") })
    }

    @Test
    fun `a run while another runs asks first, Cancel keeps it, OK stops it for the new one`(robot: FxRobot) {
        val view = loaded(robot)
        val asked = mutableListOf<Pair<GradleJob, GradleJob>>()
        var answer = false
        view.confirmStop = { running, next ->
            asked += running to next
            answer
        }
        robot.interact { doubleClick(taskCell(robot, view, ":slow")) }
        val slow = slowProcess(robot)
        lateinit var slowOutput: Node
        robot.interact {
            slowOutput = view.runPane.shownOutput
            assertEquals(ViewActivity.RUNNING, view.activity.value)
        }

        robot.interact { doubleClick(taskCell(robot, view, ":hello")) }
        assertEquals(
            listOf<Pair<GradleJob, GradleJob>>(GradleJob.Tasks(listOf(":slow")) to GradleJob.Tasks(listOf(":hello"))),
            asked,
        )
        robot.interact {
            assertEquals(GradleJob.Tasks(listOf(":slow")), view.runner?.running)
            assertSame(slowOutput, view.runPane.shownOutput)
        }

        answer = true
        robot.interact { doubleClick(taskCell(robot, view, ":hello")) }
        eventually(robot) { assertTrue(status(view).startsWith("✔ :hello — finished in "), status(view)) }
        robot.interact { assertNotSame(slowOutput, view.runPane.shownOutput) }
        eventually(robot) { check(!slow.isAlive) { "the stopped run still runs" } }
    }

    @Test
    fun `cancelling the background task stops the run, and closing asks while one runs`(robot: FxRobot) {
        val view = loaded(robot)
        robot.interact { doubleClick(taskCell(robot, view, ":slow")) }
        val slow = slowProcess(robot)
        robot.interact { assertEquals("build.gradle is still running :slow.", view.closeWarning) }

        robot.interact { backgroundTasks.shown.single { it.name.value == "Gradle: :slow" }.cancel() }
        eventually(robot) {
            assertEquals("■ :slow — stopped", status(view))
            assertEquals(ViewActivity.NONE, view.activity.value)
            assertNull(view.closeWarning)
        }
        eventually(robot) { check(!slow.isAlive) { "the stopped run still runs" } }
    }

    @Test
    fun `Stop ends a run that ignores Ctrl+C once the grace is over`(robot: FxRobot) {
        build()
        val view = open(robot, stopGrace = 300.milliseconds).also { awaitTasks(robot, it) }
        robot.interact { doubleClick(taskCell(robot, view, ":stubborn")) }
        val stubborn = slowProcess(robot)

        robot.interact { view.runPane.stop.fire() }

        eventually(robot) {
            assertEquals("\u25a0 :stubborn \u2014 stopped", status(view))
            check(!stubborn.isAlive) { "the run still runs" }
        }
    }

    @Test
    fun `the arguments go to every run, Rerun takes the current ones, an unclosed quote runs nothing`(robot: FxRobot) {
        val view = loaded(robot)
        robot.interact {
            view.runPane.arguments.text = "--info -Dgreeting='a b'"
            doubleClick(taskCell(robot, view, ":hello"))
        }
        eventually(robot) { assertTrue(status(view).startsWith("\u2714 :hello"), status(view)) }
        assertEquals("--info|-Dgreeting=a b|:hello|", runs.readLines().last())

        robot.interact {
            view.runPane.arguments.text = "--quiet"
            view.runPane.rerun.fire()
        }
        eventually(robot) {
            assertEquals("--quiet|:hello|", runs.readLines().last())
            assertNull(view.runner?.running)
        }

        val before = runs.readLines().size
        robot.interact {
            view.runPane.arguments.text = "--info 'oops"
            doubleClick(taskCell(robot, view, ":help"))
            assertTrue(status(view).startsWith("\u2716 Unbalanced"), status(view))
            assertNull(view.runner?.running)
        }
        // Nothing was started: no task, so no process could still write.
        assertTrue(taskLog.none { it.endsWith("started: Gradle: :help") }, taskLog.toString())
        assertEquals(before, runs.readLines().size)
    }

    @Test
    fun `disposing the view ends its run`(robot: FxRobot) {
        val view = loaded(robot)
        robot.interact { doubleClick(taskCell(robot, view, ":slow")) }
        val slow = slowProcess(robot)

        robot.interact { view.dispose() }

        eventually(robot) { check(!slow.isAlive) { "the run of the closed view still runs" } }
        assertTrue(taskLog.any { it.endsWith("Gradle: :slow") && it.contains("finished in") }, taskLog.toString())
    }
}
