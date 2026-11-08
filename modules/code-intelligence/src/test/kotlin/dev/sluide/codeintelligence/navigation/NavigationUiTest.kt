package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.EditorUiFixture
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.FakeLanguageServers
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Cursor
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Position
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
 * Navigation in a real Python code view: Shortcut+hover underlines the name with the hand cursor, Shortcut+click goes
 * to the definition without moving the caret to the click, Shortcut+B offers several declarations in a popup chosen
 * by keys, and the caret resting on a name highlights its occurrences. The decisions are `NavigationFlowTest`'s,
 * `LinkModelTest`'s and `OccurrenceModelTest`'s. Needs a display: the mouse hit on the text, the routing of presses
 * past RichTextFX, the caret, the overlays' styled text, the cursor and the popup exist only in a shown scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class NavigationUiTest : EditorUiFixture() {
    @TempDir
    lateinit var dir: Path

    private lateinit var servers: FakeLanguageServers
    private lateinit var server: NavigationServer
    private lateinit var intelligence: CodeIntelligence

    private val text = "def run():\n    pass\n\nrun()\nrun()\n"
    private val file get() = dir.resolve("a.py")

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
        supported: Set<String> = setOf(DEFINITION, REFERENCES, DOCUMENT_HIGHLIGHT),
        text: String = this.text,
    ): ExtensibleCodeEditor {
        val spec = PySpec()
        servers = FakeLanguageServers(listOf(spec), dir, dispatch = Platform::runLater) { NavigationServer(supported) }
        server = servers.awaitRunning(spec) as NavigationServer
        intelligence =
            fx {
                CodeIntelligence(servers.servers, FXCollections.observableSet()) {
                    listOf(NavigationFeature(navigation), OccurrencesFeature(delay = 10.milliseconds))
                }
            }
        val editor = open(robot, file, text, listOf(intelligence))
        // Jumps within the file move the caret of the focused editor only.
        robot.interact { area().requestFocus() }
        return editor
    }

    /** Fires a mouse event of [type] at the middle of [start, end), with Shortcut held if [shortcut]. */
    private fun mouse(
        robot: FxRobot,
        editor: ExtensibleCodeEditor,
        type: EventType<MouseEvent>,
        start: Int,
        shortcut: Boolean,
    ) {
        val area = area()
        val character = bounds(editor, start, start + 1)
        val mac = "mac" in System.getProperty("os.name").lowercase()
        val button = if (type == MouseEvent.MOUSE_MOVED) MouseButton.NONE else MouseButton.PRIMARY
        robot.interact {
            val local = area.screenToLocal(character.centerX, character.centerY)
            Event.fireEvent(
                area,
                MouseEvent(
                    type,
                    local.x,
                    local.y,
                    character.centerX,
                    character.centerY,
                    button,
                    1,
                    false,
                    shortcut && !mac,
                    false,
                    shortcut && mac,
                    button == MouseButton.PRIMARY,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )
        }
    }

    private fun linked(): Int = fx { area().lookupAll(".ci-link").size }

    @Test
    fun `Shortcut over a name underlines it with the hand cursor, and releasing Shortcut clears it`(robot: FxRobot) {
        val editor = setUp(robot)
        mouse(robot, editor, MouseEvent.MOUSE_MOVED, 23, shortcut = false)
        assertEquals(0, linked())
        mouse(robot, editor, MouseEvent.MOUSE_MOVED, 23, shortcut = true)
        assertTrue(linked() > 0, "the name is styled as a link")
        assertEquals(Cursor.HAND, fx { area().cursor })

        val mac = "mac" in System.getProperty("os.name").lowercase()
        val key = if (mac) KeyCode.META else KeyCode.CONTROL
        robot.interact {
            Event.fireEvent(
                area(),
                KeyEvent(KeyEvent.KEY_RELEASED, "", "", key, false, false, false, false),
            )
        }
        assertEquals(0, linked())
        assertEquals(Cursor.TEXT, fx { area().cursor })
    }

    @Test
    fun `Shortcut+click on a name jumps to its definition in the file instead of moving the caret there`(
        robot: FxRobot,
    ) {
        val editor = setUp(robot)
        server.definitionAnswer = { listOf(location(file, 0, 4, 3)) }
        // On `(`, no name: left to the editor, no request (the one request below is the name's).
        mouse(robot, editor, MouseEvent.MOUSE_PRESSED, 24, shortcut = true)
        mouse(robot, editor, MouseEvent.MOUSE_RELEASED, 24, shortcut = true)
        robot.interact { editor.moveCaret(0) }
        mouse(robot, editor, MouseEvent.MOUSE_PRESSED, 23, shortcut = true)
        mouse(robot, editor, MouseEvent.MOUSE_RELEASED, 23, shortcut = true)
        awaitTrue("jumped") { fx { editor.caretOffset } == 4 }
        assertEquals(Position(3, 2), server.definitionRequests.single().position, "asked for the name only")
        // The press on the name did not move the caret: the way back is where it was before that click.
        assertEquals(listOf(SourcePosition(file, 0, 0)), fx { intelligence.history.places.first })
    }

    @Test
    fun `a jump to a definition below the view shows its line at the top`(robot: FxRobot) {
        val editor = setUp(robot, text = text + "\n".repeat(80) + "def other():\n    pass\n" + "\n".repeat(40))
        server.definitionAnswer = { listOf(location(file, 85, 4, 5)) }
        assertEquals(0, fx { editor.shownLines.first })
        robot.interact { editor.moveCaret(23) }
        press(robot, KeyCode.B, shortcut = true)
        awaitTrue("the definition's line is the first shown one") { fx { editor.shownLines.first } == 85 }
    }

    @Test
    fun `Shortcut+B offers several declarations, Down and Enter jump to the second`(robot: FxRobot) {
        val editor = setUp(robot)
        server.definitionAnswer = { listOf(location(file, 0, 4, 3), location(file, 4, 0, 3)) }
        robot.interact { editor.moveCaret(23) }
        press(robot, KeyCode.B, shortcut = true)
        awaitTrue("chooser") { popups("caret-popup").isNotEmpty() }
        val labels =
            fx {
                popups(
                    "caret-popup",
                ).single().content.single().lookupAll(".caret-popup-label").map { (it as Label).text }.sorted()
            }
        assertEquals(listOf("a.py:1", "a.py:5"), labels.take(2))

        press(robot, KeyCode.DOWN)
        press(robot, KeyCode.ENTER)
        assertEquals(27, fx { editor.caretOffset })
        assertEquals(emptyList(), popups("caret-popup"))
        assertEquals(text, fx { editor.text }, "Enter went to the popup, not into the text")
    }

    @Test
    fun `the caret resting on a name highlights its occurrences by kind, and leaving the name clears them`(
        robot: FxRobot,
    ) {
        val editor = setUp(robot)
        server.highlightAnswer = {
            listOf(
                DocumentHighlight(location(file, 0, 4, 3).range, DocumentHighlightKind.Write),
                DocumentHighlight(location(file, 3, 0, 3).range, DocumentHighlightKind.Read),
            )
        }
        robot.interact { editor.moveCaret(22) }
        awaitTrue("highlighted") { fx { area().lookupAll(".ci-occurrence-read").isNotEmpty() } }
        assertTrue(fx { area().lookupAll(".ci-occurrence-write").isNotEmpty() })
        assertEquals(Position(3, 1), server.highlightRequests.last().position)

        robot.interact { editor.moveCaret(13) }
        assertEquals(0, fx { area().lookupAll(".ci-occurrence-read").size })
    }

    @Test
    fun `a note Shortcut+B shows at once stays until the next key`(robot: FxRobot) {
        val editor = setUp(robot, supported = emptySet())
        robot.interact { editor.moveCaret(23) }
        press(robot, KeyCode.B, shortcut = true)
        val note =
            popups("caret-popup").singleOrNull()?.let { popup ->
                fx { (popup.content.single().lookup(".caret-popup-placeholder") as Label).text }
            }
        assertEquals("Go to Definition is not available (not supported)", note)

        press(robot, KeyCode.LEFT)
        assertEquals(emptyList(), popups("caret-popup"))
        assertEquals(22, fx { editor.caretOffset }, "the key still moved the caret")
    }
}
