package dev.sluide.codeintelligence.rename

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.EditorUiFixture
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.symbols.SymbolServer
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.FakeLanguageServers
import dev.sluide.languageservers.LspUris
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.event.Event
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Popup
import javafx.stage.Stage
import javafx.stage.Window
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rename field over a real Python code view: Shift+F6 opens it at the name with the name selected and the focus in
 * it, keys reach the field and not the editor, Enter renames in one undo step (a name the language does not allow is
 * refused below the field), Escape cancels, after which keys reach the editor again.
 * The decisions are `RenameFlowTest`'s and `RenameModelTest`'s. Needs a display: the focus moving into a popup, the
 * routing of key presses from the editor's window to the popup and the editor's undo exist only in a shown scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class RenameUiTest : EditorUiFixture() {
    @TempDir
    lateinit var dir: Path

    private lateinit var servers: FakeLanguageServers
    private lateinit var server: SymbolServer
    private lateinit var intelligence: CodeIntelligence

    private val text = "def run():\n    pass\n\nrun()\n"
    private val file get() = dir.resolve("a.py")

    @Start
    fun start(stage: Stage) = startStage(stage)

    @AfterEach
    fun close() {
        closeView()
        fx { intelligence.close() }
        servers.close()
    }

    private fun setUp(robot: FxRobot): ExtensibleCodeEditor {
        val spec = PySpec()
        servers =
            FakeLanguageServers(listOf(spec), dir, dispatch = Platform::runLater) { SymbolServer(prepares = false) }
        server = servers.awaitRunning(spec) as SymbolServer
        server.renameAnswer = { params ->
            val name = params.newName
            WorkspaceEdit(
                mapOf(
                    LspUris.of(file) to
                        listOf(
                            TextEdit(Range(Position(0, 4), Position(0, 7)), name),
                            TextEdit(Range(Position(3, 0), Position(3, 3)), name),
                        ),
                ),
            )
        }
        intelligence =
            fx { CodeIntelligence(servers.servers, FXCollections.observableSet()) { listOf(RenameFeature()) } }
        val editor = open(robot, file, text, listOf(intelligence))
        robot.interact {
            area().requestFocus()
            editor.moveCaret(22)
        }
        return editor
    }

    /** A key event of [type] fired at the text area, as the window dispatches it: popups get it first. */
    private fun key(
        robot: FxRobot,
        type: javafx.event.EventType<KeyEvent>,
        code: KeyCode,
        character: String = "",
        shift: Boolean = false,
        shortcut: Boolean = false,
    ) {
        val mac = "mac" in System.getProperty("os.name").lowercase()
        val area = area()
        robot.interact {
            Event.fireEvent(area, KeyEvent(type, character, "", code, shift, shortcut && !mac, false, shortcut && mac))
        }
    }

    private fun press(
        robot: FxRobot,
        code: KeyCode,
        shift: Boolean = false,
        shortcut: Boolean = false,
    ) = key(robot, KeyEvent.KEY_PRESSED, code, shift = shift, shortcut = shortcut)

    /** The rename field while it shows. */
    private fun field(): TextField? =
        fx {
            Window
                .getWindows()
                .filterIsInstance<Popup>()
                .filter { it.isShowing }
                .firstNotNullOfOrNull { it.scene.lookup(".rename-field") as? TextField }
        }

    @Test
    fun `Shift+F6 opens the field with the name selected and the focus, keys go to it, Enter renames in one step`(
        robot: FxRobot,
    ) {
        val editor = setUp(robot)
        press(robot, KeyCode.F6, shift = true)
        val field = checkNotNull(field()) { "the rename field shows" }
        fx {
            assertEquals("run", field.text)
            assertEquals("run", field.selectedText)
            assertTrue(field.scene.focusOwner === field, "the field has the popup's focus")
        }

        // The selection is replaced as typing does; the key goes to the field, not to the editor.
        robot.interact { field.replaceSelection("") }
        for (character in "start") key(robot, KeyEvent.KEY_TYPED, KeyCode.UNDEFINED, character.toString())
        assertEquals("start", fx { field.text })
        assertEquals(text, fx { editor.text }, "nothing typed into the editor")

        press(robot, KeyCode.ENTER)
        awaitTrue("renamed") { fx { editor.text } == "def start():\n    pass\n\nstart()\n" }
        assertTrue(field() == null, "the field closed")
        assertEquals(22 + 2, fx { editor.caretOffset }, "the caret at the new name")

        // The press reaches the editor again: the closed field takes no more keys.
        press(robot, KeyCode.Z, shortcut = true)
        assertEquals(text, fx { editor.text }, "one undo step takes back both places")
    }

    @Test
    fun `Escape closes the field without renaming, and the next key types into the editor again`(robot: FxRobot) {
        val editor = setUp(robot)
        press(robot, KeyCode.F6, shift = true)
        val field = checkNotNull(field())
        robot.interact { field.text = "start" }
        press(robot, KeyCode.ESCAPE)
        assertTrue(field() == null, "closed")
        assertEquals(text, fx { editor.text })
        assertEquals(emptyList(), server.renameRequests)

        typeText(robot, "x")
        assertEquals("def run():\n    pass\n\nrxun()\n", fx { editor.text })
    }

    @Test
    fun `a name the language does not allow is refused below the field, which stays open`(robot: FxRobot) {
        setUp(robot)
        press(robot, KeyCode.F6, shift = true)
        val field = checkNotNull(field())
        robot.interact { field.text = "foo bar" }
        press(robot, KeyCode.ENTER)
        assertTrue(field() === field, "still open")
        val problem = fx { field.scene.lookup(".rename-problem") as Label }
        assertEquals("Not a valid name: foo bar", fx { problem.text })
        assertTrue(fx { problem.isVisible })
        assertEquals(emptyList(), server.renameRequests)

        robot.interact { field.text = "foo_bar" }
        assertFalse(fx { problem.isVisible }, "typing clears it")
    }
}
