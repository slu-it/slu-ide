package dev.sluide.python.settings

import dev.sluide.python.TyFakes
import dev.sluide.python.TyUpdates
import dev.sluide.servertools.HttpAnswer
import dev.sluide.servertools.ServerToolDialogs
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Python page is the shared server-tool page configured for ty: its place, its heading and the language in its
 * delete question. The page's behaviour is tested in server-tools (`ServerToolSettingsPageUiTest`,
 * `ServerToolPageModelTest`). Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PythonSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { TyFakes(dir) }
    private val questions = mutableListOf<String>()

    private val page by lazy {
        // Installed before the page is made: Delete needs a version to delete. The install itself, the update check and
        // the page's other controls are tested in server-tools (ServerToolSettingsPageUiTest) and TyInstallerTest.
        val installer = fakes.installer()
        installer.install(null)
        fakes.runQueued()
        val updates = TyUpdates(fakes.tool, { HttpAnswer(404, emptyMap(), "") }, Runnable::run, { it.run() })
        val dialogs =
            object : ServerToolDialogs {
                override fun confirmDelete(question: String): Boolean {
                    questions += question
                    return false
                }

                override fun chooseExecutable(current: String): Path? = null
            }
        PythonSettingsPage(fakes.settings, installer, updates, dialogs)
    }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(root, 600.0, 400.0)
        stage.show()
    }

    private val root = StackPane()

    private fun button(
        robot: FxRobot,
        text: String,
    ) = robot.lookup(".button").queryAllAs(Button::class.java).single { it.text == text }

    private fun labels(robot: FxRobot) = robot.lookup(".label").queryAllAs(Label::class.java).map { it.text }

    @Test
    fun `Languages, Python shows ty, and Delete asks about Python's code intelligence`(robot: FxRobot) {
        assertEquals(listOf("Languages", "Python"), page.path)
        robot.interact {
            root.children += page.node
            page.load()
        }
        assertTrue("ty, the language server for Python" in labels(robot), labels(robot).toString())

        robot.interact { button(robot, "Delete…").fire() }
        assertEquals(
            listOf(
                "Delete ty 0.0.99? The files in ${fakes.root} are removed permanently; Python code intelligence " +
                    "then needs ty to be installed again.",
            ),
            questions,
        )
    }
}
