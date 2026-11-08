package dev.sluide.codeintelligence.completion

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.EditorUiFixture
import dev.sluide.codeintelligence.FeatureServer
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.FakeLanguageServers
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.input.KeyCode
import javafx.scene.layout.StackPane
import javafx.scene.text.Text
import javafx.stage.Stage
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * The completion popup in a real code view: the editor's typed keys, text changes and caret reach the model, the
 * popup opens on a typed `.` and on Ctrl+Space, filters while typing, Enter takes the item with its auto-import as one
 * undo step, Tab replaces the word, the detail pane shows the docs (also resolved ones), Escape closes it with the
 * focus left in the editor. What it asks and shows is `CompletionModelTest`'s.
 * Needs a display: key routing, focus and the popup window exist only in a shown scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class CompletionUiTest : EditorUiFixture() {
    @TempDir
    lateinit var dir: Path

    private lateinit var servers: FakeLanguageServers
    private lateinit var intelligence: CodeIntelligence
    private lateinit var server: FeatureServer

    @Start
    fun start(stage: Stage) = startStage(stage)

    @AfterEach
    fun close() {
        closeView()
        fx { intelligence.close() }
        servers.close()
    }

    private fun setUp(
        robot: FxRobot,
        text: String,
        caret: Int = text.length,
        resolves: Boolean = false,
    ): ExtensibleCodeEditor {
        val spec = PySpec()
        servers = FakeLanguageServers(listOf(spec), dir, dispatch = Platform::runLater) { FeatureServer(resolves) }
        server = servers.awaitRunning(spec) as FeatureServer
        intelligence =
            fx {
                CodeIntelligence(servers.servers, FXCollections.observableSet()) {
                    listOf(CompletionFeature(debounce = 1.milliseconds))
                }
            }
        val editor = open(robot, dir.resolve("a.py"), text, listOf(intelligence))
        val area = area()
        robot.interact {
            area.requestFocus()
            editor.moveCaret(caret)
        }
        assertTrue(fx { area.isFocused }, "the test needs the focus in the area")
        return editor
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(): ListView<CompletionItem>? =
        popups("caret-popup").singleOrNull()?.let { popup ->
            fx { popup.content.single().lookup(".caret-popup-list") as ListView<CompletionItem> }
        }

    private fun shownLabels(): List<String>? = list()?.let { list -> fx { list.items.map { it.label } } }

    @Test
    fun `a typed dot opens it, typing filters, and Enter takes the item with its auto-import as one undo step`(
        robot: FxRobot,
    ) {
        val editor = setUp(robot, "import os\nos")
        typeText(robot, ".")
        awaitTrue("asked") { server.completions.size == 1 }
        val sep =
            CompletionItem("sep").apply {
                kind = CompletionItemKind.Variable
                additionalTextEdits = listOf(TextEdit(Range(Position(1, 0), Position(1, 0)), "import sys\n"))
            }
        server.completions[0].answer(Either.forRight(CompletionList(true, listOf(CompletionItem("path"), sep))))
        awaitTrue("shown") { shownLabels() == listOf("path", "sep") }

        typeText(robot, "s")
        assertEquals(listOf("sep"), shownLabels(), "filtered as typed")

        press(robot, KeyCode.ENTER)
        assertEquals("import os\nimport sys\nos.sep", fx { editor.text })
        assertEquals(emptyList(), popups("caret-popup"))
        press(robot, KeyCode.Z, shortcut = true)
        assertEquals("import os\nos.s", fx { editor.text }, "one undo step")
    }

    @Test
    fun `Ctrl+Space opens it searching, and Escape closes it with the focus in the editor`(robot: FxRobot) {
        setUp(robot, "import os\nos.pa")
        pressControl(robot, KeyCode.SPACE)
        awaitTrue("asked") { server.completions.size == 1 }
        val placeholder = fx { list()?.placeholder as? Label }
        assertEquals("Searching…", fx { placeholder?.text })
        server.completions[0].answer(Either.forLeft(listOf(CompletionItem("path"))))
        awaitTrue("shown") { shownLabels() == listOf("path") }

        press(robot, KeyCode.ESCAPE)
        assertEquals(emptyList(), popups("caret-popup"))
        assertTrue(fx { area().isFocused })
    }

    @Test
    fun `Tab replaces the whole word with the item`(robot: FxRobot) {
        val editor = setUp(robot, "import os\nos.path", caret = 15)
        pressControl(robot, KeyCode.SPACE)
        awaitTrue("asked") { server.completions.size == 1 }
        server.completions[0].answer(Either.forLeft(listOf(CompletionItem("pathsep"))))
        awaitTrue("shown") { shownLabels() == listOf("pathsep") }

        press(robot, KeyCode.TAB)
        assertEquals("import os\nos.pathsep", fx { editor.text }, "Enter would leave the rest: os.pathsepth")
    }

    @Test
    fun `the selected item's docs show beside the list, also once they are resolved`(robot: FxRobot) {
        setUp(robot, "import os\nos", resolves = true)
        typeText(robot, ".")
        awaitTrue("asked") { server.completions.size == 1 }
        server.completions[0].answer(Either.forLeft(listOf(CompletionItem("path").apply { detail = "module" })))
        awaitTrue("resolve asked") { server.resolved.size == 1 }
        val detail =
            fx {
                popups("caret-popup")
                    .single()
                    .content
                    .single()
                    .lookup(".caret-popup-detail") as StackPane
            }
        assertEquals("module", fx { (detail.lookup(".markup-code-block .markup-text") as Text).text })

        server.resolved[0].answer(
            CompletionItem("path").apply {
                this.detail = "module"
                documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, "The **path** module"))
            },
        )
        awaitTrue("docs shown") { fx { (detail.lookup(".markup-strong") as? Text)?.text } == "path" }
    }
}
