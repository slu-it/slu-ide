package dev.sluide.codeintelligence.ui

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.EditorUiFixture
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.FakeLanguageServers
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * The hover popup of a bound code view: the mouse resting on a character shows the providers' sections there after
 * the delay, typing hides it, F1 shows it at the caret. When it asks and hides is `HoverModelTest`'s. Needs a display:
 * the mouse hit on the text, the key routing and the popup window exist only in a shown scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class HoverPopupUiTest : EditorUiFixture() {
    @TempDir
    lateinit var dir: Path

    private lateinit var servers: FakeLanguageServers
    private lateinit var intelligence: CodeIntelligence

    @Start
    fun start(stage: Stage) = startStage(stage)

    @AfterEach
    fun close() {
        closeView()
        fx { intelligence.close() }
        servers.close()
    }

    /** A Python editor whose hover shows "doc <offset>" for every character. */
    private fun setUp(robot: FxRobot): ExtensibleCodeEditor {
        val spec = PySpec()
        servers = FakeLanguageServers(listOf(spec), dir, dispatch = Platform::runLater)
        val provider =
            BindingFeature { binding ->
                binding.hovers.add { offset, done ->
                    done(listOf(Label("doc $offset")))
                    AutoCloseable {}
                }
            }
        intelligence =
            fx {
                CodeIntelligence(servers.servers, FXCollections.observableSet()) {
                    listOf(HoverFeature(restDelay = 50.milliseconds), provider)
                }
            }
        return open(robot, dir.resolve("a.py"), "import os\n", listOf(intelligence))
    }

    private fun shownText(): String? =
        popups("hover-popup").singleOrNull()?.let { popup ->
            fx { (popup.content.single().lookup(".hover-sections .label") as Label).text }
        }

    @Test
    fun `the mouse resting on a character shows the hover after the delay, and typing hides it`(robot: FxRobot) {
        val editor = setUp(robot)
        val character = bounds(editor, 2, 3)
        mouseMove(robot, character.centerX, character.centerY)
        awaitTrue("hover shown") { shownText() != null }
        assertEquals("doc 2", shownText())
        val popup = popups("hover-popup").single()
        assertTrue(fx { popup.y } >= character.maxY - 1, "below the character")

        robot.interact { editor.applyEdits(listOf(OffsetEdit(0, 0, "x"))) }
        assertEquals(emptyList(), popups("hover-popup"))
    }

    @Test
    fun `F1 shows the hover at the caret at once`(robot: FxRobot) {
        val editor = setUp(robot)
        robot.interact { editor.moveCaret(7) }
        press(robot, KeyCode.F1)
        assertEquals("doc 7", shownText())

        press(robot, KeyCode.ESCAPE)
        assertEquals(emptyList(), popups("hover-popup"))
    }
}
