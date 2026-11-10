package dev.sluide.typescript.settings

import dev.sluide.servertools.HttpAnswer
import dev.sluide.servertools.ServerToolDialogs
import dev.sluide.typescript.TypeScriptFakes
import dev.sluide.typescript.TypeScriptUpdates
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
 * The TypeScript page is the shared server-tool page configured for the TypeScript server: its place, its heading and
 * the language in its delete question. The page's behaviour is tested in server-tools. Needs a display, see the
 * `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TypeScriptSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { TypeScriptFakes(dir) }
    private val questions = mutableListOf<String>()

    private val page by lazy {
        // Installed before the page is made: Delete needs a version to delete.
        val installer = fakes.installer()
        installer.install(null)
        fakes.runQueued()
        val notFound = { _: java.net.URI, _: Map<String, String> -> HttpAnswer(404, emptyMap(), "") }
        val updates = TypeScriptUpdates(fakes.tool, notFound, Runnable::run, { it.run() })
        val dialogs =
            object : ServerToolDialogs {
                override fun confirmDelete(question: String): Boolean {
                    questions += question
                    return false
                }

                override fun chooseExecutable(current: String): Path? = null
            }
        TypeScriptSettingsPage(fakes.settings, installer, updates, dialogs)
    }

    private val root = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(root, 600.0, 400.0)
        stage.show()
    }

    private fun button(
        robot: FxRobot,
        text: String,
    ) = robot.lookup(".button").queryAllAs(Button::class.java).single { it.text == text }

    private fun labels(robot: FxRobot) = robot.lookup(".label").queryAllAs(Label::class.java).map { it.text }

    @Test
    fun `Languages, TypeScript shows the server, and Delete asks about TypeScript's code intelligence`(robot: FxRobot) {
        assertEquals(listOf("Languages", "TypeScript"), page.path)
        robot.interact {
            root.children += page.node
            page.load()
        }
        val heading = "TypeScript 7, the language server for TypeScript and JavaScript"
        assertTrue(heading in labels(robot), labels(robot).toString())

        robot.interact { button(robot, "Delete…").fire() }
        assertEquals(1, questions.size)
        assertTrue(questions.single().startsWith("Delete TypeScript 7.0.2? "), questions.single())
        assertTrue("TypeScript code intelligence" in questions.single(), questions.single())
    }
}
