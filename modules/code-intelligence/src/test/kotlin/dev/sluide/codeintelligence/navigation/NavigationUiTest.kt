package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.EditorUiFixture
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.fileviewers.ConstantValue
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.PreviewTexts
import dev.sluide.languageservers.FakeLanguageServers
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Cursor
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Text
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
import kotlin.io.path.writeText
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
    private val targetPopups = mutableListOf<TargetPopup>()

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
        lines: LineReader = DiskLines(),
        previewTexts: PreviewTexts = PreviewTexts { _, done -> done(null) },
    ): ExtensibleCodeEditor {
        val spec = PySpec()
        servers = FakeLanguageServers(listOf(spec), dir, dispatch = Platform::runLater) { NavigationServer(supported) }
        server = servers.awaitRunning(spec) as NavigationServer
        intelligence =
            fx {
                CodeIntelligence(servers.servers, FXCollections.observableSet()) {
                    listOf(
                        NavigationFeature(navigation, lines, previewTexts = previewTexts) { editor ->
                            TargetPopup(editor, { _, _ -> null }, previewTexts, ConstantValue("")).also {
                                targetPopups += it
                            }
                        },
                        OccurrencesFeature(delay = 10.milliseconds),
                    )
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
        // The event is sent again until the binding runs with the definition capability and the layout has made the
        // link nodes: an event before that is ignored, and a single one raced the server's start.
        awaitTrue("the name is styled as a link, with the hand cursor") {
            mouse(robot, editor, MouseEvent.MOUSE_MOVED, 23, shortcut = true)
            linked() > 0 && fx { area().cursor } == Cursor.HAND
        }

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

    /** Alt+F7 on the text area, as the key reaches it. */
    private fun findUsages(robot: FxRobot) {
        robot.interact {
            Event.fireEvent(area(), KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F7, false, false, true, false))
        }
    }

    private fun selectedRow(): Int =
        fx {
            (
                popups("caret-popup")
                    .single()
                    .content
                    .single()
                    .lookup(".caret-popup-list") as ListView<*>
            ).selectionModel.selectedIndex
        }

    @Test
    fun `the selected usage stays selected when the line texts of other files arrive`(robot: FxRobot) {
        val held = mutableListOf<() -> Unit>()
        val lines =
            LineReader { positions, done ->
                val texts = positions.associateWith { "line ${it.line}" }
                held += { done(texts) }
                AutoCloseable {}
            }
        val editor = setUp(robot, lines = lines)
        val other = dir.resolve("b.py")
        other.writeText("run()\nrun()\n")
        server.referencesAnswer =
            { listOf(location(file, 3, 0, 3), location(other, 0, 0, 3), location(other, 1, 0, 3)) }
        robot.interact { editor.moveCaret(23) }
        findUsages(robot)
        awaitTrue("list") { popups("caret-popup").isNotEmpty() && held.isNotEmpty() }
        press(robot, KeyCode.DOWN)
        assertEquals(1, selectedRow())

        robot.interact { held.forEach { it() } }
        assertEquals(1, selectedRow(), "the line texts did not move the selection back to the first item")
        assertEquals(
            "b.py:1",
            fx {
                popups(
                    "caret-popup",
                ).single().content.single().lookupAll(".caret-popup-label").map { (it as Label).text }[1]
            },
        )
    }

    /** The text of the preview under the list, and the text of what is marked in it. */
    private fun previewShown(): Pair<String, String> =
        fx {
            val footer = (popups("caret-popup").single().content.single() as VBox).children.last()
            val all = footer.lookupAll(".text").filterIsInstance<Text>().joinToString("") { it.text }
            all to footer.lookupAll(".ci-preview-target").filterIsInstance<Text>().joinToString("") { it.text }
        }

    /** Find Usages over `a.py` (line 3) and two lines of `b.py`, the line texts of `b.py` still on their way. */
    private fun usagesInTwoFiles(
        robot: FxRobot,
        held: MutableList<() -> Unit> = mutableListOf(),
    ) {
        val lines =
            LineReader { positions, done ->
                val texts = positions.associateWith { "line ${it.line}" }
                held += { done(texts) }
                AutoCloseable {}
            }
        val previews = TargetPreviewTexts({ emptyList() }, { it.run() }, Platform::runLater)
        val editor = setUp(robot, lines = lines, previewTexts = previews)
        val other = dir.resolve("b.py")
        other.writeText("# second file\nrun()\nrun()\n")
        server.referencesAnswer =
            { listOf(location(file, 3, 0, 3), location(other, 1, 0, 3), location(other, 2, 0, 3)) }
        robot.interact { editor.moveCaret(23) }
        findUsages(robot)
        awaitTrue("list") { popups("caret-popup").isNotEmpty() && held.isNotEmpty() }
    }

    @Test
    fun `Down in the usages list shows the next place's file under it with the name marked`(robot: FxRobot) {
        usagesInTwoFiles(robot)
        awaitTrue("the first place's file shows") { previewShown().first.contains("def run") }
        assertEquals("run", previewShown().second, "the name at the place is marked")

        press(robot, KeyCode.DOWN)
        awaitTrue("the second file shows") { previewShown().first.contains("second file") }
        assertEquals("run", previewShown().second)
        assertEquals(false, previewShown().first.contains("def run"))
    }

    private fun previewText() = fx { targetPopups.single().previewText }

    @Test
    fun `the marked name in the preview is painted with the stylesheet's background, dark and light`(robot: FxRobot) {
        usagesInTwoFiles(robot)
        awaitTrue("the name is marked") { previewShown().second == "run" }

        fun background(scheme: ColorScheme): Any? {
            var color: Any? = null
            robot.interact {
                val window = popups("caret-popup").single()
                window.scene.preferences.colorScheme = scheme
                window.scene.root.applyCss()
                val footer = (window.content.single() as VBox).children.last()
                val marked = footer.lookup(".ci-preview-target")
                // RichTextFX's TextExt, which this module does not see at compile time, keeps the painted fill.
                color = marked.javaClass.getMethod("getBackgroundColor").invoke(marked)
            }
            return color
        }
        assertEquals(Color.web("#3f5273"), background(ColorScheme.DARK))
        assertEquals(Color.web("#cfe0fa"), background(ColorScheme.LIGHT))
    }

    @Test
    fun `hiding the list empties the preview`(robot: FxRobot) {
        usagesInTwoFiles(robot)
        awaitTrue("the first place's file shows") { previewText().contains("def run") }
        press(robot, KeyCode.ESCAPE)
        assertEquals(emptyList(), popups("caret-popup"))
        assertEquals("", previewText())
    }

    @Test
    fun `a new list shows the files as they are now, not as the list before showed them`(robot: FxRobot) {
        val held = mutableListOf<() -> Unit>()
        usagesInTwoFiles(robot, held)
        press(robot, KeyCode.DOWN)
        awaitTrue("the second file shows") { previewText().contains("second file") }

        // Only usages in b.py now: its first place is the file that showed before.
        val other = dir.resolve("b.py").also { it.writeText("# changed file\nrun()\nrun()\n") }
        server.referencesAnswer = { listOf(location(other, 1, 0, 3), location(other, 2, 0, 3)) }
        findUsages(robot)
        awaitTrue("the file as it is now") { previewText().contains("changed file") }
    }

    @Test
    fun `Go to Definition again with the same places shows the preview again`(robot: FxRobot) {
        // The line texts never arrive, so the second list equals the first one.
        val editor =
            setUp(
                robot,
                lines = { _, _ -> AutoCloseable {} },
                previewTexts = TargetPreviewTexts({ emptyList() }, { it.run() }, Platform::runLater),
            )
        val other = dir.resolve("b.py").also { it.writeText("# second file\nrun()\n") }
        server.definitionAnswer = { listOf(location(file, 3, 0, 3), location(other, 1, 0, 3)) }
        robot.interact { editor.moveCaret(23) }
        press(robot, KeyCode.B, shortcut = true)
        awaitTrue("the preview shows") { previewText().contains("def run") }

        press(robot, KeyCode.B, shortcut = true)
        awaitTrue("shown again for the new list") { previewText().contains("def run") }
    }

    @Test
    fun `a press in the preview leaves the popup open, the selection and the focus in the editor`(robot: FxRobot) {
        usagesInTwoFiles(robot)
        press(robot, KeyCode.DOWN)
        awaitTrue("the second file shows") { previewShown().first.contains("second file") }

        val target = fx { (popups("caret-popup").single().content.single() as VBox).children.last().lookup(".text") }
        val press =
            MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                5.0,
                5.0,
                5.0,
                5.0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null,
            )
        robot.interact { Event.fireEvent(target, press) }
        // The editor inside the preview would take the focus of the popup's scene on a press it was given.
        assertEquals(null, fx { popups("caret-popup").single().scene.focusOwner }, "the popup took no focus")
        assertEquals(1, popups("caret-popup").size, "the popup stays")
        assertEquals(1, selectedRow())
        assertTrue(fx { area().isFocused }, "the editor keeps the focus")
    }
}
