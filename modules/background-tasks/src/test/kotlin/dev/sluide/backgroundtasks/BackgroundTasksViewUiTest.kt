package dev.sluide.backgroundtasks

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** UI tests of the background tasks' inline list. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class BackgroundTasksViewUiTest {
    /** The timers waiting to show tasks; [elapse] runs them. FX thread. */
    private val timers = mutableListOf<Runnable>()

    private val tasks =
        BackgroundTasks(
            dispatch = Platform::runLater,
            // Fixed, so a task is shown only when the test runs the timers, however slow the FX thread is.
            clock = { 0L },
            later = { _, action ->
                timers += action
                Pending { timers -= action }
            },
            delay = Duration.ofMillis(500),
        )

    private lateinit var view: BackgroundTasksView

    @Start
    fun start(stage: Stage) {
        view = BackgroundTasksView(tasks)
        stage.scene = Scene(BorderPane().apply { bottom = view }, 300.0, 200.0)
        stage.show()
    }

    /** Lets the delay pass: the waiting tasks are shown. */
    private fun elapse(robot: FxRobot) {
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { timers.toList().forEach { it.run() } }
    }

    private fun rows(robot: FxRobot) =
        robot
            .from(view)
            .lookup(".background-task")
            .queryAllAs(HBox::class.java)
            .sortedBy { view.children.indexOf(it) }

    private fun HBox.name() = (children[0] as Label).text

    private fun HBox.cancelButton() = lookupAll(".background-task-cancel").filterIsInstance<Button>().singleOrNull()

    @Test
    fun `is hidden while no task is shown`(robot: FxRobot) {
        assertFalse(view.isVisible)
        assertFalse(view.isManaged)

        tasks.start("Quick").close()
        elapse(robot)
        assertFalse(view.isVisible)
    }

    @Test
    fun `a task gets a row once it ran past the delay`(robot: FxRobot) {
        tasks.start("Loading Git log")
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(view.isVisible, "not before the delay")

        elapse(robot)
        assertTrue(view.isVisible)
        assertTrue(view.isManaged)
        val row = rows(robot).single()
        assertEquals("Loading Git log", row.name())
        assertTrue((row.children[1] as ProgressBar).isIndeterminate)
        assertTrue(view.height > 0)
        assertEquals(0.0, view.minWidth(-1.0), "never widens what it sits in")
    }

    @Test
    fun `only cancellable tasks have a cancel button, which cancels, until the task withdraws it`(robot: FxRobot) {
        var cancelled = 0
        tasks.start("Fetching origin…") { cancelled++ }
        tasks.start("Refreshing Git status")
        val pull = tasks.start("Pulling…") { cancelled++ }
        elapse(robot)

        val (fetch, status, pulling) = rows(robot)
        assertEquals("Fetching origin…", fetch.name())
        assertEquals(null, status.cancelButton())
        val cancel = checkNotNull(fetch.cancelButton())
        assertEquals("Cancel", cancel.tooltip.text)
        assertFalse(cancel.isFocusTraversable)

        robot.interact { cancel.fire() }
        assertEquals(1, cancelled)

        val pullCancel = checkNotNull(pulling.cancelButton())
        assertTrue(pullCancel.isVisible)
        robot.interact { pull.withdrawCancel() }
        assertFalse(pullCancel.isVisible, "gone once withdrawn")
    }

    @Test
    fun `at most four rows and the number of the others`(robot: FxRobot) {
        val started = (1..6).map { tasks.start("Task $it") }
        elapse(robot)

        assertEquals(listOf("Task 1", "Task 2", "Task 3", "Task 4"), rows(robot).map { it.name() })
        val more = robot.from(view).lookup(".background-tasks-more").queryAs(Label::class.java)
        assertEquals("+2 more", more.text)
        assertEquals(more, view.children.last())

        started[0].close()
        started[1].close()
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("Task 3", "Task 4", "Task 5", "Task 6"), rows(robot).map { it.name() })
        assertTrue(
            robot
                .from(view)
                .lookup(".background-tasks-more")
                .tryQuery<Label>()
                .isEmpty,
        )
    }

    @Test
    fun `a row disappears when its task ends`(robot: FxRobot) {
        val first = tasks.start("First")
        tasks.start("Second")
        elapse(robot)
        assertEquals(2, rows(robot).size)
        val secondRow = rows(robot)[1]

        first.close()
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("Second"), rows(robot).map { it.name() })
        assertSame(secondRow, rows(robot).single(), "a surviving task keeps its row")

        tasks.close()
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(view.isVisible)
        assertFalse(view.isManaged)
    }

    @Test
    fun `a rename updates the row`(robot: FxRobot) {
        val task = tasks.start("Checking out main…")
        elapse(robot)

        task.rename("Updating files…")
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals("Updating files…", rows(robot).single().name())
    }
}
