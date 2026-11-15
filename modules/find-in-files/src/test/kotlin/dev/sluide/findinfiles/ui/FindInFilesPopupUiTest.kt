package dev.sluide.findinfiles.ui

import dev.sluide.findinfiles.search.FileHits
import dev.sluide.findinfiles.search.LineHit
import dev.sluide.textediting.SearchOptions
import javafx.application.ColorScheme
import javafx.beans.property.SimpleStringProperty
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Find in Files popup in a window. Needs a display: focus in a popup, key routing from the field, the preview
 * editor's styled text and the computed styles exist only in a shown scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class FindInFilesPopupUiTest {
    private lateinit var button: Button
    private lateinit var returnTo: Button
    private lateinit var stage: Stage
    private lateinit var popup: FindInFilesPopup

    private val font = SimpleStringProperty("-fx-font-size: 13px;")
    private val root = Path.of("/work/project")
    private val texts = mutableMapOf<Path, String>()

    /** Answers a load later when [hold] is set: the callbacks wait here. */
    private var hold = false
    private val pending = mutableListOf<Pair<Path, (String?) -> Unit>>()
    private val loads = mutableListOf<Path>()
    private val opened = mutableListOf<Pair<FileHits, LineHit>>()
    private var changes = 0
    private var widened = 0
    private var hiddenCount = 0

    private val a = root.resolve("a.kt")
    private val b = root.resolve("src/b.kt")

    private fun hit(line: Int) = LineHit(line, 0, "foo here", listOf(0 until 3))

    private val hitsA = FileHits(a, root, listOf(hit(1), hit(2)), 2, false)
    private val hitsB = FileHits(b, root, listOf(hit(0)), 1, false)

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        button = Button("elsewhere")
        returnTo = Button("return here")
        stage.scene = Scene(StackPane(button, returnTo), 900.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        fx { popup.hide() }
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get(5, TimeUnit.SECONDS)

    private fun setUp(robot: FxRobot) {
        texts[a] = "zero\nfoo one\nfoo two foo\n"
        texts[b] = "foo first\nsecond\n"
        popup =
            fx {
                FindInFilesPopup(
                    { _, _ -> null },
                    { null },
                    { file, done ->
                        loads.add(file)
                        if (hold) pending += file to done else done(texts[file])
                    },
                    font,
                ).apply {
                    onWiden = { widened++ }
                    onOpen = { hits, line -> opened += hits to line }
                    onChanged = { changes++ }
                    onHidden = { hiddenCount++ }
                }
            }
        robot.interact {
            button.requestFocus()
            popup.query = "foo"
            popup.open(anchor())
        }
    }

    private fun anchor() =
        FindAnchor(stage, checkNotNull(stage.scene.root.localToScreen(stage.scene.root.boundsInLocal)), returnTo)

    private fun key(
        robot: FxRobot,
        code: KeyCode,
        target: () -> Node = { popup.queryField },
        shift: Boolean = false,
    ) = robot.interact {
        val event = KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false)
        Event.fireEvent(target(), event)
    }

    private fun mouse(
        type: EventType<MouseEvent>,
        clicks: Int,
    ) = MouseEvent(
        type,
        1.0,
        1.0,
        1.0,
        1.0,
        MouseButton.PRIMARY,
        clicks,
        false,
        false,
        false,
        false,
        true,
        false,
        false,
        false,
        false,
        false,
        null,
    )

    /** The cell showing row [index] of the list. */
    private fun cell(index: Int): ListCell<*> =
        popup.list
            .lookupAll(".find-in-files-cell")
            .filterIsInstance<ListCell<*>>()
            .first { it.index == index }

    private fun overlayTexts(): List<Text> =
        popup.preview.node
            .lookupAll(".$HIT_CLASS")
            .filterIsInstance<Text>()

    private fun waitUntil(condition: () -> Boolean) = WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { fx(condition) }

    @Test
    fun `open focuses the query field with its text selected`(robot: FxRobot) {
        setUp(robot)
        robot.interact {
            assertTrue(popup.queryField.isFocused)
            assertEquals("foo", popup.queryField.selectedText)
        }
    }

    @Test
    fun `Down skips the header and the preview shows that file with the line marked`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.add(listOf(hitsA, hitsB)) }
        // The first line row is selected by add: a.kt.
        assertEquals(listOf(a), loads)
        key(robot, KeyCode.DOWN)
        key(robot, KeyCode.DOWN)
        waitUntil { popup.preview.shownFile == b }
        assertEquals(listOf(a, b), loads)
        waitUntil { overlayTexts().isNotEmpty() }
        assertEquals("foo", overlayTexts().joinToString("") { it.text })
        robot.interact { assertEquals(4, popup.list.selectionModel.selectedIndex) }
    }

    @Test
    fun `Enter opens the selected match`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.add(listOf(hitsA, hitsB)) }
        key(robot, KeyCode.DOWN)
        key(robot, KeyCode.ENTER)
        assertEquals(listOf(hitsA to hitsA.lines[1]), opened)
    }

    @Test
    fun `a click on a header selects its first line row`(robot: FxRobot) {
        setUp(robot)
        robot.interact {
            popup.add(listOf(hitsA, hitsB))
            popup.list.selectionModel.select(3)
            assertEquals(4, popup.list.selectionModel.selectedIndex)
        }
    }

    @Test
    fun `Escape hides and gives the focus back, once`(robot: FxRobot) {
        setUp(robot)
        key(robot, KeyCode.ESCAPE)
        robot.interact {
            assertFalse(popup.isShowing)
            assertTrue(returnTo.isFocused)
        }
        assertEquals(1, hiddenCount)
    }

    @Test
    fun `hideForOpen does not return the focus`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.hideForOpen() }
        robot.interact {
            assertFalse(popup.isShowing)
            assertFalse(returnTo.isFocused)
        }
        assertEquals(1, hiddenCount)
    }

    @Test
    fun `setting query, mask and options does not tell onChanged, typing and toggles do`(robot: FxRobot) {
        setUp(robot)
        robot.interact {
            popup.query = "bar"
            popup.mask = "*.kt"
            popup.options = SearchOptions(matchCase = true, regex = true)
        }
        assertEquals(0, changes)
        assertEquals(SearchOptions(matchCase = true, regex = true), fx { popup.options })
        robot.interact { popup.queryField.text = "typed" }
        assertEquals(1, changes)
        robot.interact { popup.maskField.text = "*.md" }
        assertEquals(2, changes)
        robot.interact { popup.wordToggle.fire() }
        assertEquals(3, changes)
        robot.interact { assertTrue(popup.options.wholeWord) }
    }

    @Test
    fun `toggles take no focus`(robot: FxRobot) {
        setUp(robot)
        robot.interact {
            assertFalse(popup.caseToggle.isFocusTraversable)
            assertFalse(popup.wordToggle.isFocusTraversable)
            assertFalse(popup.regexToggle.isFocusTraversable)
            assertFalse(popup.widenButton.isFocusTraversable)
            // A real press would focus a focus-traversable button.
            Event.fireEvent(popup.caseToggle, mouse(MouseEvent.MOUSE_PRESSED, 1))
            assertTrue(popup.queryField.isFocused)
        }
    }

    @Test
    fun `an answer for a file the selection left is dropped`(robot: FxRobot) {
        setUp(robot)
        hold = true
        robot.interact { popup.add(listOf(hitsA, hitsB)) }
        key(robot, KeyCode.DOWN)
        key(robot, KeyCode.DOWN)
        robot.interact {
            // The second line of a.kt does not load it again.
            assertEquals(listOf(a, b), pending.map { it.first })
            // b was selected last: its answer counts, the late one of a does not.
            pending.last().second(texts[b])
            pending.first().second(texts[a])
            assertEquals(b, popup.preview.shownFile)
            assertEquals(texts[b], popup.preview.text)
        }
    }

    @Test
    fun `hit text in a line row is painted with the stylesheet's fill, dark and light`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.add(listOf(hitsA)) }
        // Row 1 is selected, row 2 is a plain row.
        waitUntil { popup.list.lookupAll(".find-in-files-cell").any { (it as? ListCell<*>)?.index == 2 } }

        fun fill(scheme: ColorScheme): Any? {
            var color: Any? = null
            robot.interact {
                popup.list.scene.preferences.colorScheme = scheme
                popup.list.scene.root
                    .applyCss()
                color = (cell(2).lookup(".$HIT_TEXT_CLASS") as Text).fill
            }
            return color
        }
        assertEquals(Color.web("#ffd479"), fill(ColorScheme.DARK))
        // A plain Text has no .text class: it must not stay black on the dark fill.
        robot.interact {
            val plain = cell(2).lookup(".$SNIPPET_CLASS") as Text
            assertTrue(plain.fill != Color.BLACK, "plain snippet text is black in dark")
        }
        assertEquals(Color.web("#6b3f00"), fill(ColorScheme.LIGHT))
        robot.interact {
            val selected = cell(1).lookup(".$HIT_TEXT_CLASS") as Text
            assertTrue(selected.isUnderline)
            assertFalse((cell(2).lookup(".$HIT_TEXT_CLASS") as Text).isUnderline)
        }
    }

    @Test
    fun `the selection stays on its row when more rows are appended`(robot: FxRobot) {
        setUp(robot)
        val c = FileHits(root.resolve("c.kt"), root, listOf(hit(3)), 1, false)
        robot.interact { popup.add(listOf(hitsA, hitsB)) }
        key(robot, KeyCode.DOWN)
        key(robot, KeyCode.DOWN)
        waitUntil { popup.preview.shownFile == b }
        robot.interact { popup.add(listOf(c)) }
        robot.interact {
            assertEquals(4, popup.list.selectionModel.selectedIndex)
            assertEquals(b, popup.preview.shownFile)
        }
        assertEquals(listOf(a, b), loads)
    }

    @Test
    fun `Tab moves between the two fields and selects the text`(robot: FxRobot) {
        setUp(robot)
        // Makes the default traversal differ from the popup's own: from the mask it would go on to the list.
        robot.interact {
            popup.list.isFocusTraversable = true
            popup.mask = "*.kt"
        }
        key(robot, KeyCode.TAB)
        robot.interact {
            assertTrue(popup.maskField.isFocused)
            assertEquals("*.kt", popup.maskField.selectedText)
        }
        key(robot, KeyCode.TAB, { popup.maskField })
        robot.interact {
            assertTrue(popup.queryField.isFocused)
            assertEquals("foo", popup.queryField.selectedText)
        }
        key(robot, KeyCode.TAB, { popup.queryField }, shift = true)
        robot.interact { assertTrue(popup.maskField.isFocused) }
    }

    @Test
    fun `the scope chip shows the last folder with the path in its tooltip and widens`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.scopeLabel = "modules/file-explorer" }
        robot.interact {
            assertEquals("In: file-explorer", popup.scopeText.text)
            assertEquals("modules/file-explorer", popup.scopeText.tooltip.text)
        }
        robot.interact { popup.widenButton.fire() }
        assertEquals(1, widened)
    }

    @Test
    fun `a double click opens the line of its row, on a header it opens nothing`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.add(listOf(hitsA, hitsB)) }
        waitUntil { popup.list.lookupAll(".find-in-files-cell").any { (it as? ListCell<*>)?.index == 4 } }
        robot.interact { Event.fireEvent(cell(0), mouse(MouseEvent.MOUSE_CLICKED, 2)) }
        assertEquals(emptyList(), opened)
        robot.interact { Event.fireEvent(cell(4), mouse(MouseEvent.MOUSE_CLICKED, 2)) }
        assertEquals(listOf(hitsB to hitsB.lines[0]), opened)
        robot.interact { Event.fireEvent(cell(2), mouse(MouseEvent.MOUSE_CLICKED, 1)) }
        assertEquals(1, opened.size)
    }

    @Test
    fun `a press in the preview keeps the focus in the query field`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.add(listOf(hitsA)) }
        waitUntil { popup.preview.shownFile == a }
        robot.interact {
            val area = popup.preview.editorNode.lookup(".code-area")
            Event.fireEvent(area, mouse(MouseEvent.MOUSE_PRESSED, 1))
            assertTrue(popup.queryField.isFocused)
        }
    }

    @Test
    fun `moving the owner window hides the popup once and returns the focus`(robot: FxRobot) {
        setUp(robot)
        robot.interact { stage.x += 40.0 }
        robot.interact {
            assertFalse(popup.isShowing)
            assertTrue(returnTo.isFocused)
        }
        assertEquals(1, hiddenCount)
    }

    @Test
    fun `severalRoots prefixes the header's folder with the root's name`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.add(listOf(hitsA)) }
        waitUntil { popup.list.lookupAll(".find-in-files-cell").any { (it as? ListCell<*>)?.index == 0 } }

        fun headerLabels(): List<String> = cell(0).lookupAll(".label").filterIsInstance<Label>().map { it.text }
        robot.interact { assertFalse("project" in headerLabels()) }
        robot.interact { popup.severalRoots = true }
        waitUntil { "project" in headerLabels() }
    }

    @Test
    fun `the editor font reaches the snippets, the line numbers and the preview`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.add(listOf(hitsA)) }
        waitUntil {
            popup.preview.shownFile == a &&
                popup.list.lookupAll(".find-in-files-cell").any { (it as? ListCell<*>)?.index == 2 }
        }
        robot.interact { font.value = "-fx-font-size: 31px;" }
        robot.interact {
            popup.list.scene.root
                .applyCss()
            val snippet = cell(2).lookup(".$SNIPPET_CLASS") as Text
            val number = cell(2).lookupAll(".label").filterIsInstance<Label>().first { it.text == "3" }
            val previewText =
                popup.preview.editorNode
                    .lookupAll(".text")
                    .filterIsInstance<Text>()
                    .first()
            assertEquals(31.0, snippet.font.size)
            assertEquals(31.0, number.font.size)
            assertEquals(31.0, previewText.font.size)
        }
    }
}
