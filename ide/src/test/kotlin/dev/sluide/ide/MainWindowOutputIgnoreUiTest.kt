package dev.sluide.ide

import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wiring of the window's git into the Java module's question about jdtls's output folders (ADR 0179); the
 * registration's own cases are in `OutputIgnoreRegistrationTest`. A window needs a stage, see the `uiTest` task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowOutputIgnoreUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private val answers = CopyOnWriteArrayList<List<Path>?>()

    /** Asks and returns how many answers came before the call returned. */
    private fun ask(robot: FxRobot): Int {
        var atOnce = -1
        robot.interact {
            outputIgnoreChecks.check(project, listOf(project.resolve("target"))) { answers += it }
            atOnce = answers.size
        }
        return atOnce
    }

    @Test
    fun `the window answers through its git status, which is asynchronous and knows no repository here`(
        robot: FxRobot,
    ) {
        robot.interact { projectService.open(project) }

        // An unregistered folder is answered at once; the window's git status answers after it ran on its thread.
        assertEquals(0, ask(robot), "answered at once")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (answers.isEmpty() && System.nanoTime() < deadline) robot.interact { }
        assertEquals(listOf<List<Path>?>(null), answers.toList(), "the project is no repository")
    }

    @Test
    fun `disposing the window ends its registration`(robot: FxRobot) {
        robot.interact { projectService.open(project) }

        robot.interact { mainWindow.dispose() }

        assertEquals(1, ask(robot), "answered at once, by nobody")
        assertEquals(listOf<List<Path>?>(null), answers.toList())
    }
}
