package dev.sluide.codeintelligence.workspace

import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.InstallPrompts
import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.ProblemAction
import dev.sluide.languageservers.ServerState
import dev.sluide.projectdata.ProjectDataStore
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.Pane
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
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The environment banner's view: its buttons and the in-place update of its message. Needs a display because it
 * builds controls; when the banner shows is `EnvironmentBannerModelTest`'s.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class EnvironmentBannerUiTest {
    @TempDir
    lateinit var data: Path

    private val window = Path.of("/work")
    private val folder = SimpleObjectProperty<Path?>(window)
    private val changes = SimpleIntegerProperty()
    private var problem: EnvironmentProblem? = null
    private val editor by lazy { FakeCodeEditor(window.resolve("A.java"), "") }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get(5, TimeUnit.SECONDS)

    private fun banner(): EnvironmentBanner =
        fx {
            val prompts =
                InstallPrompts(
                    ProjectDataStore(data.resolve("p"), data.resolve("shared.json"), emptyList()),
                    Runnable::run,
                )
            val model =
                EnvironmentBannerModel(
                    SimpleObjectProperty<ServerState>(ServerState.Running),
                    folder,
                    changes,
                    window,
                    "java.environment",
                    FXCollections.observableSet(),
                    prompts,
                    { problem },
                    openFile = {},
                    runInTerminal = { _, _, _ -> },
                )
            EnvironmentBanner(editor, model)
        }

    private fun change(robot: FxRobot) = robot.interact { changes.set(changes.get() + 1) }

    /** The banner node, read on the FX thread. */
    private fun node() = fx { editor.banners.single() }

    private fun buttons(): List<Button> = fx { (editor.banners.single() as Pane).children.filterIsInstance<Button>() }

    @Test
    fun `a problem that is not dismissible has neither Not Now nor Don't Ask Again`(robot: FxRobot) {
        problem = EnvironmentProblem(window, "Importing app (Gradle)… 0:01", emptyList(), dismissible = false)
        banner()

        assertEquals(emptyList(), buttons().map { it.text })
        assertEquals(
            "Importing app (Gradle)… 0:01",
            fx {
                (editor.banners.single() as Pane)
                    .children
                    .filterIsInstance<Label>()
                    .single()
                    .text
            },
        )
    }

    @Test
    fun `a change of the message alone updates the label and keeps the banner and its buttons`(robot: FxRobot) {
        val actions = listOf(ProblemAction.Callback("Retry Import") {})
        problem = EnvironmentProblem(window, "Import of app failed: 1", actions)
        banner()
        val node = node()
        val before = buttons()

        problem = EnvironmentProblem(window, "Import of app failed: 2", actions)
        change(robot)

        assertSame(node, node())
        assertEquals(before, buttons())
        assertTrue(before.zip(buttons()).all { (a, b) -> a === b })
        assertEquals(
            "Import of app failed: 2",
            fx {
                (node as Pane)
                    .children
                    .filterIsInstance<Label>()
                    .single()
                    .text
            },
        )
    }

    @Test
    fun `a change of the actions, the folder or the dismissibility builds the banner again`(robot: FxRobot) {
        val actions = listOf(ProblemAction.Callback("Retry Import") {})
        problem = EnvironmentProblem(window, "m", actions)
        banner()
        var node = node()

        problem = EnvironmentProblem(window, "m", listOf(ProblemAction.Callback("Retry Import") {}))
        change(robot)
        assertNotSame(node, node())
        node = node()

        problem = EnvironmentProblem(window, "m", problem?.actions.orEmpty(), dismissible = false)
        change(robot)
        assertNotSame(node, node())
        node = node()

        folder.set(window.resolve("other"))
        problem = EnvironmentProblem(window.resolve("other"), "m", problem?.actions.orEmpty(), dismissible = false)
        change(robot)
        assertNotSame(node, node())
    }
}
