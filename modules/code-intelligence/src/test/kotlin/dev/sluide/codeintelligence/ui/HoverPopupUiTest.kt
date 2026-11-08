package dev.sluide.codeintelligence.ui

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.EditorUiFixture
import dev.sluide.codeintelligence.FeatureServer
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
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import javafx.stage.Stage
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
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
 * the delay, typing hides it, F1 shows it at the caret, the server's Markdown shows as text. When it asks and hides is
 * `HoverModelTest`'s, the blocks of the Markdown `MarkupDocumentTest`'s. Needs a display:
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
    fun `the server's hover renders its Markdown as a section`(robot: FxRobot) {
        val spec = PySpec()
        servers = FakeLanguageServers(listOf(spec), dir, dispatch = Platform::runLater) { FeatureServer() }
        val server = servers.awaitRunning(spec) as FeatureServer
        intelligence =
            fx {
                CodeIntelligence(servers.servers, FXCollections.observableSet()) {
                    listOf(ServerHoverFeature(), HoverFeature())
                }
            }
        val editor = open(robot, dir.resolve("a.py"), "import os\n", listOf(intelligence))
        robot.interact { editor.moveCaret(7) }
        press(robot, KeyCode.F1)
        awaitTrue("asked") { server.hovers.size == 1 }
        server.hovers[0].answer(Hover(MarkupContent(MarkupKind.MARKDOWN, "```python\nos\n```\nThe **os**&nbsp;module")))
        awaitTrue("shown") { popups("hover-popup").size == 1 }

        val content = popups("hover-popup").single().content.single()
        val code = fx { content.lookup(".markup-code-block") as TextFlow }
        assertEquals("os", fx { (code.children.single() as Text).text })
        assertEquals("os", fx { (content.lookup(".markup-strong") as Text).text })
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
