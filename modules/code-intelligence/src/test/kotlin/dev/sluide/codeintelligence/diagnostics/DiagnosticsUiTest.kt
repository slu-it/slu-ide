package dev.sluide.codeintelligence.diagnostics

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.EditorUiFixture
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.ui.HoverFeature
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.FakeLanguageServers
import dev.sluide.languageservers.LspUris
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.event.Event
import javafx.scene.Parent
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Stage
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * The diagnostics in a real code view: a pushed diagnostic underlines its text and marks its line in the gutter,
 * Alt+Enter lists the quick fixes at the caret and Enter applies one, the hover shows the diagnostic first. What is
 * shown and asked is `DocumentDiagnosticsTest`'s and `PlacedDiagnosticsTest`'s. Needs a display: the overlay classes on
 * the text, the gutter rows, the key routing and the popups exist only in a shown code view.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class DiagnosticsUiTest : EditorUiFixture() {
    @TempDir
    lateinit var dir: Path

    private lateinit var servers: FakeLanguageServers
    private lateinit var intelligence: CodeIntelligence
    private lateinit var server: LspTestServer
    private val file get() = dir.resolve("a.py")

    @Start
    fun start(stage: Stage) = startStage(stage)

    @AfterEach
    fun close() {
        closeView()
        fx { intelligence.close() }
        servers.close()
    }

    /** A Python editor with the diagnostics, a hover section "doc" after theirs, and `Path` undefined on line 0. */
    private fun setUp(robot: FxRobot): ExtensibleCodeEditor {
        val spec = PySpec()
        servers =
            FakeLanguageServers(listOf(spec), dir, dispatch = Platform::runLater) {
                LspTestServer { setCodeActionProvider(true) }
            }
        val doc =
            BindingFeature { binding ->
                binding.hovers.add { _, done ->
                    done(listOf(Label("doc")))
                    AutoCloseable {}
                }
            }
        intelligence =
            fx {
                CodeIntelligence(servers.servers, FXCollections.observableSet()) {
                    listOf(DiagnosticsFeature({ false }), doc, HoverFeature(restDelay = 50.milliseconds))
                }
            }
        val editor = open(robot, file, "p = Path()\n", listOf(intelligence))
        server = servers.awaitRunning(spec) as LspTestServer
        awaitTrue("didOpen") { server.documentNotifications.any { it is DidOpenTextDocumentParams } }
        val undefined =
            Diagnostic(Range(Position(0, 4), Position(0, 8)), "Name `Path` used when not defined").apply {
                severity = DiagnosticSeverity.Error
            }
        server.client.publishDiagnostics(PublishDiagnosticsParams(LspUris.of(file), listOf(undefined)))
        awaitTrue("underlined") { styled(".diagnostic-error").isNotEmpty() }
        return editor
    }

    private fun styled(selector: String) =
        fx {
            view
                ?.node
                ?.lookupAll(selector)
                .orEmpty()
                .toList()
        }

    @Test
    fun `a pushed diagnostic underlines its text and marks its line in the gutter`(robot: FxRobot) {
        setUp(robot)
        awaitTrue("gutter icon") { styled(".diagnostic-error-icon").isNotEmpty() }
        assertEquals(1, styled(".diagnostic-error-icon").size)
    }

    @Test
    fun `Alt+Enter lists the quick fixes at the caret and Enter applies the one taken`(robot: FxRobot) {
        val editor = setUp(robot)
        server.codeActions =
            listOf(
                Either.forRight(
                    CodeAction("import pathlib.Path").apply {
                        edit =
                            WorkspaceEdit(
                                mapOf(
                                    LspUris.of(file) to
                                        listOf(
                                            TextEdit(
                                                Range(Position(0, 0), Position(0, 0)),
                                                "from pathlib import Path\n",
                                            ),
                                        ),
                                ),
                            )
                    },
                ),
            )
        robot.interact { editor.moveCaret(6) }
        val area = area()
        robot.interact {
            Event.fireEvent(area, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, true, false))
        }
        awaitTrue("the fix listed") { popups("caret-popup").isNotEmpty() && fixTitles().isNotEmpty() }
        assertEquals(listOf("import pathlib.Path"), fixTitles())

        press(robot, KeyCode.ENTER)
        assertEquals("from pathlib import Path\np = Path()\n", fx { editor.text })
    }

    private fun fixTitles(): List<String> =
        fx {
            popups("caret-popup").flatMap { popup ->
                popup.content.flatMap { (it as Parent).lookupAll(".caret-popup-label") }.map { (it as Label).text }
            }
        }

    @Test
    fun `the hover shows the diagnostic before the other sections`(robot: FxRobot) {
        val editor = setUp(robot)
        robot.interact { editor.moveCaret(5) }
        press(robot, KeyCode.F1)
        val sections =
            fx {
                val popup = popups("hover-popup").single()
                (
                    popup.content.single().lookup(
                        ".hover-sections",
                    ) as Parent
                ).childrenUnmodifiable.filter { it !is Separator }
            }
        assertEquals(2, sections.size)
        assertEquals(true, "diagnostic-hover" in sections[0].styleClass)
        val message = fx { sections[0].lookup(".diagnostic-hover-message") as Label }
        assertEquals("Name `Path` used when not defined", message.text)
        assertEquals("doc", (sections[1] as Label).text)
    }
}
