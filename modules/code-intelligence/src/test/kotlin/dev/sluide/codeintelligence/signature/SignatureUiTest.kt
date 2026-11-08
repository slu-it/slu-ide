package dev.sluide.codeintelligence.signature

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.EditorUiFixture
import dev.sluide.codeintelligence.FeatureServer
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.completion.CompletionFeature
import dev.sluide.languageservers.FakeLanguageServers
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.scene.input.KeyCode
import javafx.scene.text.Text
import javafx.stage.Stage
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either
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

/**
 * The signature popup in a real code view: a typed `(` reaches the model, the popup shows above the caret line with
 * the active parameter in bold, Escape closes it, before the completion's when both show. When it asks is `SignatureModelTest`'s. Needs a display: key
 * routing, focus and the popup window exist only in a shown scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class SignatureUiTest : EditorUiFixture() {
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

    @Test
    fun `a typed parenthesis shows the signature above the caret line, Escape closes it`(robot: FxRobot) {
        val spec = PySpec()
        servers = FakeLanguageServers(listOf(spec), dir, dispatch = Platform::runLater) { FeatureServer() }
        val server = servers.awaitRunning(spec) as FeatureServer
        intelligence =
            fx { CodeIntelligence(servers.servers, FXCollections.observableSet()) { listOf(SignatureFeature()) } }
        // Lines above, so that there is room above the caret's line.
        val text = "\n\n\n\n\nprint"
        val editor = open(robot, dir.resolve("a.py"), text, listOf(intelligence))
        val area = area()
        robot.interact {
            area.requestFocus()
            editor.moveCaret(text.length)
        }
        typeText(robot, "(")
        awaitTrue("asked") { server.signatures.size == 1 }
        val signature =
            SignatureInformation("(value: object, sep: str) -> None", "", listOf(ParameterInformation("value: object")))
        server.signatures[0].answer(SignatureHelp(listOf(signature), 0, 0))
        awaitTrue("shown") { popups("signature-popup").size == 1 }

        val popup = popups("signature-popup").single()
        val caret = bounds(editor, text.length + 1)
        assertTrue(fx { popup.y + popup.height } <= caret.minY + 1, "above the caret line")
        val bold = fx { popup.content.single().lookup(".markup-strong") as Text }
        assertEquals("value: object", fx { bold.text })

        press(robot, KeyCode.ESCAPE)
        assertEquals(emptyList(), popups("signature-popup"))
    }

    @Test
    fun `with completion and signature help open, the first Escape closes the signature, the next the completion`(
        robot: FxRobot,
    ) {
        val spec = PySpec()
        servers = FakeLanguageServers(listOf(spec), dir, dispatch = Platform::runLater) { FeatureServer() }
        val server = servers.awaitRunning(spec) as FeatureServer
        intelligence =
            fx {
                CodeIntelligence(servers.servers, FXCollections.observableSet()) {
                    listOf(CompletionFeature(), SignatureFeature())
                }
            }
        val text = "\n\n\n\n\nprint"
        val editor = open(robot, dir.resolve("a.py"), text, listOf(intelligence))
        val area = area()
        robot.interact {
            area.requestFocus()
            editor.moveCaret(text.length)
        }
        typeText(robot, "(")
        awaitTrue("signature asked") { server.signatures.size == 1 }
        server.signatures[0].answer(SignatureHelp(listOf(SignatureInformation("(value: object)")), 0, 0))
        awaitTrue("signature shown") { popups("signature-popup").size == 1 }
        pressControl(robot, KeyCode.SPACE)
        awaitTrue("completion asked") { server.completions.size == 1 }
        server.completions[0].answer(Either.forLeft(listOf(CompletionItem("value"))))
        awaitTrue("completion shown") { popups("caret-popup").size == 1 }

        press(robot, KeyCode.ESCAPE)
        assertEquals(emptyList(), popups("signature-popup"))
        assertEquals(1, popups("caret-popup").size, "the completion stays")
        press(robot, KeyCode.ESCAPE)
        assertEquals(emptyList(), popups("caret-popup"))
    }
}
