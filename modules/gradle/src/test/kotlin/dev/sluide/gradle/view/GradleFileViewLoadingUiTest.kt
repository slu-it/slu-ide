package dev.sluide.gradle.view

import dev.sluide.fileviewers.SplitMode
import dev.sluide.projecttrust.ProjectTrustState
import dev.sluide.projecttrust.TrustFinding
import javafx.scene.control.Button
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Gradle view finding Gradle, following the folder's trust and loading the tasks through the window's session
 * (ADR 0085). The tree's content and the texts are unit tests' (TaskTreeTest, RunTextsTest). Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GradleFileViewLoadingUiTest : GradleFileViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `the modes appear in Split once Gradle is found, the tasks load once per build, a save marks them stale`(
        robot: FxRobot,
    ) {
        build()
        val view = open(robot)
        awaitTasks(robot, view)
        robot.interact {
            assertTrue(view.toolbar.isVisible)
            assertEquals(SplitMode.SPLIT, view.split.mode.get())
            assertEquals(
                listOf("fx (:)", ":app", ":app:core", ":lib"),
                view.tasks.tree.root.children
                    .map { label(it.value) },
            )
            assertTrue(
                view.runPane.status.text
                    .startsWith("✔ Tasks loaded in "),
                view.runPane.status.text,
            )
        }
        assertTrue(taskLog.any { it.endsWith("started: Gradle: loading tasks") }, taskLog.toString())
        // Going to a line shows the source again.
        robot.interact {
            view.split.mode.set(SplitMode.OTHER)
            view.showLine(0)
            assertEquals(SplitMode.SPLIT, view.split.mode.get())
        }

        // Another view of the same build shares the loaded tasks.
        val app = open(robot, "app/build.gradle")
        awaitTasks(robot, app)
        robot.interact {
            assertEquals(
                listOf(":app", ":app:core"),
                app.tasks.tree.root.children
                    .map { label(it.value) },
            )
        }
        assertEquals(1, loads.readLines().size)

        session.fileSaved(project.resolve("app/build.gradle"))
        eventually(robot) {
            assertTrue(view.tasks.stale.isVisible)
            assertTrue(app.tasks.stale.isVisible)
        }

        robot.interact { view.tasks.refresh.fire() }
        eventually(robot) {
            assertEquals(2, loads.readLines().size)
            assertFalse(view.tasks.stale.isVisible)
            assertFalse(app.tasks.stale.isVisible)
        }
    }

    @Test
    fun `without a wrapper and without a global gradle the view stays plain source`(robot: FxRobot) {
        build(wrapper = false)
        val view = open(robot)
        sessionIdle()

        assertEquals(1, globalChecks.get())
        robot.interact {
            assertFalse(view.toolbar.isVisible)
            assertEquals(SplitMode.SOURCE, view.split.mode.get())
            assertNull(view.installation)
        }
    }

    @Test
    fun `while the folder is not trusted the tasks say so, Review asks, and trusting loads them`(robot: FxRobot) {
        build()
        findings = listOf(TrustFinding("Gradle wrapper in ."))
        val view = open(robot)
        val app = open(robot, "app/build.gradle")

        eventually(robot) {
            assertIs<ProjectTrustState.Ask>(trust.state.get())
            assertEquals(TaskNote.UNTRUSTED, view.tasks.shownNote)
            assertEquals(TaskNote.UNTRUSTED, app.tasks.shownNote)
        }
        robot.interact {
            view.tasks.node
                .lookupAll(".button")
                .filterIsInstance<Button>()
                .single { it.text == "Review…" }
                .fire()
        }
        assertEquals(1, reviews)
        assertFalse(loads.toFile().exists(), "nothing ran")

        robot.interact { trust.decide(assertIs<ProjectTrustState.Ask>(trust.state.get()), true, remember = false) }
        awaitTasks(robot, view)
        awaitTasks(robot, app)
        assertEquals(1, loads.readLines().size, "one view loads, the other waits for it")
    }

    @Test
    fun `the terminal of a load leaves the focus in the source, also when it is shown again`(robot: FxRobot) {
        build()
        val view = open(robot)
        awaitTasks(robot, view)
        robot.interact {
            view.split.mode.set(SplitMode.SOURCE)
            view.focus()
        }
        robot.interact { view.split.mode.set(SplitMode.SPLIT) }
        // A terminal that takes the focus when shown asks for it later on the FX thread.
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            val owner = view.node.scene.focusOwner
            assertTrue("code-area" in owner.styleClass, "focus in $owner")
        }
    }

    private fun label(value: Any?) = (value as TreeProject).label
}
