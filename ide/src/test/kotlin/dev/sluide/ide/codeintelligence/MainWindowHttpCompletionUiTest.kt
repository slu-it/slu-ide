package dev.sluide.ide.codeintelligence

import dev.sluide.ide.MainWindowUiFixture
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.Labeled
import javafx.scene.control.ListView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.text.Text
import javafx.stage.Popup
import javafx.stage.Stage
import javafx.stage.Window
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The completion of `.http` editors in the main window (ADR 0195): `CodeIntelligenceWiring` registers
 * `HttpCompletionSource`, which asks http-client's engine with the environment files of the window's project. What the
 * engine offers is http-client's, what the popup does is code-intelligence's. Needs a display: the typed key, focus
 * and the popup window exist only in a shown scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowHttpCompletionUiTest : MainWindowUiFixture() {
    private val secret = "https://example.invalid"

    @Start
    fun start(stage: Stage) {
        startView(stage)
    }

    private fun openHttp(
        robot: FxRobot,
        name: String = "api/a.http",
    ): CodeArea {
        project.resolve("http-client.env.json").writeText("""{"dev": {"host": "$secret"}}""")
        // below the project folder, where the environment file is: found only if the window's project folder is passed on
        val file = project.resolve(name).apply { parent.createDirectories() }.apply { writeText("") }
        robot.interact {
            projectService.open(project)
            editorArea.open(file)
        }
        eventually(robot) { assertEquals(1, robot.lookup(".code-area").queryAllAs(CodeArea::class.java).size) }
        val area = robot.lookup(".code-area").queryAs(CodeArea::class.java)
        robot.interact { area.requestFocus() }
        eventually(robot) { assertTrue(area.isFocused, "the test needs the focus in the source") }
        return area
    }

    private fun typeText(
        robot: FxRobot,
        area: CodeArea,
        text: String,
    ) {
        for (character in text) {
            robot.interact {
                Event.fireEvent(
                    area,
                    KeyEvent(
                        KeyEvent.KEY_TYPED,
                        character.toString(),
                        "",
                        KeyCode.UNDEFINED,
                        false,
                        false,
                        false,
                        false,
                    ),
                )
            }
        }
    }

    private fun completionPopups(): List<Popup> =
        WaitForAsyncUtils
            .asyncFx<List<Popup>> {
                Window.getWindows().filterIsInstance<Popup>().filter { popup ->
                    popup.isShowing && popup.content.any { "caret-popup" in it.styleClass }
                }
            }.get()

    /** Every text the single popup shows: the list's items and the texts of all its nodes. */
    @Suppress("UNCHECKED_CAST")
    private fun shownTexts(): List<String>? =
        completionPopups().singleOrNull()?.let { popup ->
            WaitForAsyncUtils
                .asyncFx<List<String>> {
                    val root = popup.content.single()
                    val list = root.lookup(".caret-popup-list") as ListView<Any>
                    val nodes: List<Node> = listOf(root) + root.lookupAll("*")
                    list.items.map { it.toString() } +
                        nodes.mapNotNull { (it as? Labeled)?.text ?: (it as? Text)?.text }
                }.get()
        }

    @Test
    fun `typing the braces in an http editor shows the environment's variable with its environment, and Enter takes it`(
        robot: FxRobot,
    ) {
        val area = openHttp(robot)

        typeText(robot, area, "GET {{")
        eventually(robot) { assertTrue(shownTexts().orEmpty().any { it.contains("host") }, "the popup shows host") }

        val texts = shownTexts().orEmpty()
        assertTrue(texts.any { it.contains("dev") }, "the detail names the environment: $texts")
        assertFalse(texts.any { it.contains(secret) }, "no value of the environment is shown: $texts")
        robot.interact {
            Event.fireEvent(area, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
        }

        assertEquals("GET {{host}}", WaitForAsyncUtils.asyncFx<String> { area.text }.get())
        assertEquals(emptyList(), completionPopups())
    }

    @Test
    fun `typing the braces in a txt editor of the same window shows no popup`(robot: FxRobot) {
        val area = openHttp(robot, "notes.txt")

        typeText(robot, area, "GET {{")

        // No signal says that nothing comes: a short wait, longer than a source takes to answer.
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        assertEquals(emptyList(), completionPopups())
    }
}
