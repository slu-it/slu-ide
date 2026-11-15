package dev.sluide.findinfiles.ui

import dev.sluide.findinfiles.search.FileHits
import dev.sluide.findinfiles.search.LineHit
import dev.sluide.textediting.SearchOptions
import javafx.application.ColorScheme
import javafx.beans.property.SimpleStringProperty
import javafx.event.Event
import javafx.event.EventType
import javafx.geometry.BoundingBox
import javafx.geometry.Point2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.stage.Popup
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.Window
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
import kotlin.test.assertNotNull
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
        button: MouseButton = MouseButton.PRIMARY,
    ) = MouseEvent(
        type,
        1.0,
        1.0,
        1.0,
        1.0,
        button,
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

    /** The popup's window: the one showing popup of the owner stage. */
    private fun window(): Window = Window.getWindows().first { it is Popup && it.isShowing && it.ownerWindow == stage }

    /** Presses [code] in the query field until [done] holds; fails after 5 s. */
    private fun pressUntil(
        robot: FxRobot,
        code: KeyCode,
        done: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!fx(done)) {
            check(System.nanoTime() < deadline) { "$code did not get there" }
            key(robot, code)
        }
    }

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
    fun `open again while showing focuses the query field and selects its text`(robot: FxRobot) {
        setUp(robot)
        robot.interact {
            popup.queryField.deselect()
            assertTrue(popup.queryField.isFocused)
            assertEquals("", popup.queryField.selectedText)
            popup.open(anchor())
            assertEquals("foo", popup.queryField.selectedText)
            popup.maskField.requestFocus()
            popup.open(anchor())
            assertTrue(popup.queryField.isFocused)
        }
    }

    @Test
    fun `open puts the popup where findPopupPosition says and sizes it to the anchor`(robot: FxRobot) {
        setUp(robot)
        val area = fx { checkNotNull(stage.scene.root.localToScreen(stage.scene.root.boundsInLocal)) }

        fun expectedPosition(width: Double): Point2D {
            val screen =
                Screen.getScreensForRectangle(area.minX, area.minY, area.width, area.height).firstOrNull()
                    ?: Screen.getPrimary()
            return findPopupPosition(area, width, screen.visualBounds)
        }
        robot.interact {
            val position = expectedPosition(window().width)
            assertEquals(position.x, window().x)
            assertEquals(position.y, window().y)
        }
        // A wider anchor makes a wider popup, a narrower one narrows it again.
        robot.interact {
            popup.open(FindAnchor(stage, BoundingBox(area.minX, area.minY, 1200.0, area.height), returnTo))
            assertEquals(findPopupWidth(1200.0), window().width)
        }
        robot.interact {
            popup.open(anchor())
            assertEquals(findPopupWidth(area.width), window().width)
        }
    }

    @Test
    fun `open for an owner that is not showing does not watch it`(robot: FxRobot) {
        setUp(robot)
        val hidden = fx { Stage().apply { scene = Scene(StackPane(), 100.0, 100.0) } }
        robot.interact {
            popup.hide()
            popup.open(FindAnchor(hidden, BoundingBox(0.0, 0.0, 800.0, 600.0), null))
            popup.open(anchor())
        }
        // The first open must not have watched the hidden owner: its move would hide the popup.
        robot.interact { hidden.x = 10.0 }
        robot.interact { assertTrue(popup.isShowing) }
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
            popup.options = SearchOptions(matchCase = true, wholeWord = true, regex = true)
        }
        assertEquals(0, changes)
        assertEquals(SearchOptions(matchCase = true, wholeWord = true, regex = true), fx { popup.options })
        robot.interact { popup.queryField.text = "typed" }
        assertEquals(1, changes)
        robot.interact { popup.maskField.text = "*.md" }
        assertEquals(2, changes)
        robot.interact { popup.wordToggle.fire() }
        assertEquals(3, changes)
        robot.interact { assertFalse(popup.options.wholeWord) }
    }

    @Test
    fun `toggles and the list take no focus`(robot: FxRobot) {
        setUp(robot)
        robot.interact {
            assertFalse(popup.caseToggle.isFocusTraversable)
            assertFalse(popup.wordToggle.isFocusTraversable)
            assertFalse(popup.regexToggle.isFocusTraversable)
            assertFalse(popup.widenButton.isFocusTraversable)
            assertFalse(popup.list.isFocusTraversable)
            // A real press would focus a focus-traversable button.
            Event.fireEvent(popup.caseToggle, mouse(MouseEvent.MOUSE_PRESSED, 1))
            assertTrue(popup.queryField.isFocused)
            Event.fireEvent(popup.list, mouse(MouseEvent.MOUSE_PRESSED, 1))
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
            assertEquals(4, popup.maskField.caretPosition)
        }
        key(robot, KeyCode.TAB, { popup.maskField })
        robot.interact {
            assertTrue(popup.queryField.isFocused)
            assertEquals("foo", popup.queryField.selectedText)
            assertEquals(3, popup.queryField.caretPosition)
        }
        key(robot, KeyCode.TAB, { popup.queryField }, shift = true)
        robot.interact { assertTrue(popup.maskField.isFocused) }
    }

    @Test
    fun `the scope chip shows the last folder with the path in its tooltip and widens`(robot: FxRobot) {
        setUp(robot)
        val chip = checkNotNull(popup.scopeText.parent)
        robot.interact {
            assertFalse(chip.isVisible)
            assertFalse(chip.isManaged)
        }
        robot.interact { popup.scopeLabel = "modules/file-explorer" }
        robot.interact {
            assertTrue(chip.isVisible)
            assertTrue(chip.isManaged)
            assertEquals("modules/file-explorer", popup.scopeLabel)
            assertEquals("In: file-explorer", popup.scopeText.text)
            assertEquals("modules/file-explorer", popup.scopeText.tooltip.text)
        }
        robot.interact { popup.widenButton.fire() }
        assertEquals(1, widened)
        robot.interact { popup.scopeLabel = null }
        robot.interact {
            assertFalse(chip.isVisible)
            assertFalse(chip.isManaged)
        }
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
        robot.interact { Event.fireEvent(cell(2), mouse(MouseEvent.MOUSE_CLICKED, 2, MouseButton.SECONDARY)) }
        assertEquals(1, opened.size)
    }

    @Test
    fun `a press in the preview keeps the focus in the query field`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.add(listOf(hitsA)) }
        waitUntil { popup.preview.shownFile == a }
        robot.interact {
            // The press hands the focus to the query field, also from the mask field.
            popup.maskField.requestFocus()
            assertTrue(popup.maskField.isFocused)
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
    fun `a long folder gives way in the header, the name and the count keep their width`(robot: FxRobot) {
        setUp(robot)
        val deep = root.resolve("a".repeat(60) + "/" + "b".repeat(60) + "/" + "c".repeat(60) + "/Name.kt")
        robot.interact { popup.add(listOf(FileHits(deep, root, listOf(hit(0)), 120, true))) }
        waitUntil { popup.list.lookupAll(".find-in-files-cell").any { (it as? ListCell<*>)?.index == 0 } }

        robot.interact {
            val labels = cell(0).lookupAll(".label").filterIsInstance<Label>().associateBy { it.text }
            for (text in listOf("Name.kt", "(120+)")) {
                val label = assertNotNull(labels[text], "label $text")
                // A label narrower than its text shows it cut with an ellipsis.
                assertTrue(
                    label.width >= label.prefWidth(-1.0),
                    "$text is cut: ${label.width} < ${label.prefWidth(-1.0)}",
                )
            }
        }
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

    @Test
    fun `a key no field handles does not reach the owner window`(robot: FxRobot) {
        setUp(robot)
        var inPopup = 0
        var atStage = 0
        var atScene = 0
        robot.interact {
            popup.queryField.addEventFilter(KeyEvent.KEY_PRESSED) { inPopup++ }
            stage.addEventHandler(KeyEvent.KEY_PRESSED) { atStage++ }
            stage.scene.addEventHandler(KeyEvent.KEY_PRESSED) { atScene++ }
            // As a native key: from the owner's focus owner through the popup redirector.
            Event.fireEvent(button, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5, false, false, false, false))
        }
        assertEquals(listOf(1, 0, 0), listOf(inPopup, atStage, atScene))
    }

    @Test
    fun `a press outside hides the popup and still reaches the window beneath`(robot: FxRobot) {
        setUp(robot)
        var pressed = 0
        robot.interact {
            button.addEventHandler(MouseEvent.MOUSE_PRESSED) { pressed++ }
            Event.fireEvent(button, mouse(MouseEvent.MOUSE_PRESSED, 1))
        }
        robot.interact { assertFalse(popup.isShowing) }
        assertEquals(1, pressed)
    }

    @Test
    fun `status reaches the label`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.status("3 matches") }
        robot.interact { assertEquals("3 matches", popup.statusLabel.text) }
    }

    @Test
    fun `clear empties the list and the preview`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.add(listOf(hitsA)) }
        waitUntil { popup.preview.shownFile == a }
        robot.interact { popup.clear() }
        robot.interact {
            assertEquals(0, popup.list.items.size)
            assertEquals(null, popup.preview.shownFile)
            assertEquals("", popup.preview.text)
        }
    }

    @Test
    fun `clearing the selection empties the preview`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.add(listOf(hitsA)) }
        waitUntil { popup.preview.shownFile == a }
        robot.interact { popup.list.selectionModel.clearSelection() }
        robot.interact { assertEquals(null, popup.preview.shownFile) }
    }

    @Test
    fun `iconifying the owner hides the popup once`(robot: FxRobot) {
        setUp(robot)
        // Not TestFX's shared stage: iconifying it would hide the other tests' window.
        val owner = fx { Stage().apply { scene = Scene(StackPane(), 300.0, 200.0) } }
        try {
            robot.interact { owner.show() }
            // Unfocused when the popup opens, so that only the iconified watch can hide it, not the focus watch.
            robot.interact { stage.requestFocus() }
            waitUntil { !owner.isFocused }
            robot.interact {
                popup.hide()
                hiddenCount = 0
                popup.open(FindAnchor(owner, BoundingBox(owner.x, owner.y, 300.0, 200.0), null))
                assertTrue(popup.isShowing)
                owner.isIconified = true
                // The iconified watch hides the popup in the same step.
                assertFalse(popup.isShowing)
            }
            assertEquals(1, hiddenCount)
        } finally {
            robot.interact { owner.close() }
        }
    }

    @Test
    fun `an answer that arrives after the popup was hidden is not shown`(robot: FxRobot) {
        setUp(robot)
        hold = true
        robot.interact { popup.add(listOf(hitsA)) }
        robot.interact { popup.hide() }
        robot.interact {
            assertEquals(1, pending.size)
            pending.single().second(texts[a])
            assertEquals(null, popup.preview.shownFile)
        }
    }

    @Test
    fun `a header without a line row leaves no selection`(robot: FxRobot) {
        setUp(robot)
        val bare = FileHits(root.resolve("bare.kt"), root, emptyList(), 0, false)
        robot.interact { popup.add(listOf(bare)) }
        robot.interact {
            popup.list.selectionModel.select(0)
            assertEquals(-1, popup.list.selectionModel.selectedIndex)
            assertEquals(null, popup.preview.shownFile)
        }
    }

    @Test
    fun `adding no rows selects nothing`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.add(listOf(hitsA)) }
        waitUntil { popup.preview.shownFile == a }
        robot.interact {
            popup.list.selectionModel.clearSelection()
            popup.add(emptyList())
            assertEquals(-1, popup.list.selectionModel.selectedIndex)
        }
    }

    /** The index of the first row the list shows. */
    private fun firstVisibleRow(): Int = (popup.list.lookup(".virtual-flow") as VirtualFlow<*>).firstVisibleCell.index

    @Test
    fun `Page Down and Page Up keep the selected line in view`(robot: FxRobot) {
        setUp(robot)
        val many = FileHits(a, root, (0 until 60).map(::hit), 60, false)
        robot.interact { popup.add(listOf(many)) }
        // Page Down walks to the last line row, Page Up back to row 1 below the header.
        pressUntil(robot, KeyCode.PAGE_DOWN) { popup.list.selectionModel.selectedIndex >= 60 }
        waitUntil { firstVisibleRow() > 20 }
        pressUntil(robot, KeyCode.PAGE_UP) { popup.list.selectionModel.selectedIndex <= 1 }
        waitUntil { firstVisibleRow() == 0 }
    }

    @Test
    fun `a key that moves the selection on a list without line rows leaves the scroll alone`(robot: FxRobot) {
        setUp(robot)
        val bare = (0 until 60).map { FileHits(root.resolve("bare$it.kt"), root, emptyList(), 0, false) }
        robot.interact { popup.add(bare) }
        robot.interact { popup.list.scrollTo(40) }
        waitUntil { firstVisibleRow() > 20 }
        val before = fx { firstVisibleRow() }
        key(robot, KeyCode.DOWN)
        robot.interact { assertEquals(before, firstVisibleRow()) }
    }
}
