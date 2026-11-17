package dev.sluide.fileviewers

import javafx.beans.property.SimpleStringProperty
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.scene.text.Text
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [FilePreview] in a window, with texts the test answers by hand. Needs a display: the reveal needs a laid-out
 * viewport, the marks are checked as painted text nodes, and the press is checked by its routing to a parent.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FilePreviewUiTest {
    private val a = Path.of("a.kt")
    private val b = Path.of("b.kt")

    /** The loads asked for, answered by the test. */
    private val loads = mutableListOf<Pair<Path, (String?) -> Unit>>()
    private val highlighted = mutableListOf<Path>()
    private var presses = 0
    private var reachedParent = 0
    private lateinit var preview: FilePreview

    @Start
    fun start(stage: Stage) {
        val highlighter = { file: Path, _: String ->
            highlighted.add(file)
            null
        }
        preview =
            FilePreview(highlighter, { file, done -> loads += file to done }, SimpleStringProperty("")) { presses++ }
        val parent =
            StackPane(preview.node).apply {
                addEventHandler(MouseEvent.MOUSE_PRESSED) { reachedParent++ }
            }
        stage.scene = Scene(parent, 300.0, 200.0)
        stage.show()
    }

    /** Marks every `word` of the text with class [MARK], revealing [line] with its context. */
    private fun marking(
        word: String,
        line: Int = 0,
    ): (String) -> PreviewPlace? =
        { text ->
            val marks =
                Regex(Regex.escape(word)).findAll(text).map { StyledRange(it.range.first, it.range.last + 1, MARK) }
            val reveal = checkNotNull(revealAround(text, line))
            PreviewPlace(marks.toList(), reveal.first, reveal.last + 1)
        }

    private fun marked(): List<String> =
        preview.node
            .lookupAll(".$MARK")
            .filterIsInstance<Text>()
            .map { it.text }

    private fun shownTexts(): List<String> =
        preview.editorNode
            .lookupAll(".text")
            .filterIsInstance<Text>()
            .map { it.text }

    @Test
    fun `an answer for a file the selection left is dropped`(robot: FxRobot) {
        robot.interact {
            preview.show(a, marking("x"))
            preview.show(b, marking("x"))
        }
        assertEquals(listOf(a, b), loads.map { it.first })
        robot.interact { loads[0].second("text of a") }
        robot.interact {
            assertNull(preview.shownFile)
            assertEquals("", preview.text)
        }
        robot.interact { loads[1].second("text of b") }
        robot.interact {
            assertEquals(b, preview.shownFile)
            assertEquals("text of b", preview.text)
        }
    }

    @Test
    fun `another place in the shown file is marked without loading it again`(robot: FxRobot) {
        robot.interact { preview.show(a, marking("one")) }
        robot.interact { loads.single().second("one two") }
        var editor: Node? = null
        robot.interact {
            assertEquals(listOf("one"), marked())
            editor = preview.editorNode
        }

        robot.interact { preview.show(a, marking("two")) }

        assertEquals(1, loads.size)
        robot.interact {
            assertEquals(listOf("two"), marked())
            assertSame(editor, preview.editorNode)
        }
    }

    @Test
    fun `a place without a target in the shown file removes the marks`(robot: FxRobot) {
        robot.interact { preview.show(a, marking("one")) }
        robot.interact { loads.single().second("one two") }
        robot.interact { assertEquals(listOf("one"), marked()) }

        robot.interact { preview.show(a) { null } }

        robot.interact { assertEquals(emptyList(), marked()) }
    }

    @Test
    fun `back to the shown file drops the load of another one`(robot: FxRobot) {
        robot.interact { preview.show(a, marking("one")) }
        robot.interact { loads.single().second("one two") }
        robot.interact {
            preview.show(b, marking("x"))
            preview.show(a, marking("two"))
        }
        robot.interact { loads[1].second("text of b") }
        robot.interact {
            assertEquals(a, preview.shownFile)
            assertEquals(listOf("two"), marked())
        }
    }

    @Test
    fun `a file whose load was dropped loads again when asked again`(robot: FxRobot) {
        robot.interact { preview.show(a, marking("one")) }
        robot.interact { loads.single().second("one two") }
        robot.interact {
            preview.show(b, marking("x"))
            preview.show(a, marking("two"))
            preview.show(b, marking("x"))
        }
        assertEquals(listOf(a, b, b), loads.map { it.first })
        robot.interact { loads[2].second("text of b") }
        robot.interact { assertEquals(b, preview.shownFile) }
    }

    @Test
    fun `the place asked last while the file loads is the one marked`(robot: FxRobot) {
        robot.interact {
            preview.show(a, marking("one"))
            preview.show(a, marking("two"))
        }
        assertEquals(1, loads.size)
        robot.interact { loads.single().second("one two") }
        robot.interact { assertEquals(listOf("two"), marked()) }
    }

    @Test
    fun `a place far down a fresh file is scrolled into view with its context`(robot: FxRobot) {
        val text = (0..199).joinToString("\n") { "line$it" }
        robot.interact { preview.show(a, marking("line150", 150)) }
        robot.interact { loads.single().second(text) }
        robot.interact {
            // Lines 145 to 155 fit the 200 px viewport (13 lines).
            val missing = (145..155).map { "line$it" } - shownTexts().toSet()
            assertEquals(emptyList(), missing, "shown: ${shownTexts()}")
            assertEquals(listOf("line150"), marked())
        }
    }

    @Test
    fun `a place near the start of a fresh file is revealed without scrolling the start away`(robot: FxRobot) {
        val text = (0..29).joinToString("\n") { "line$it" }
        // Reveals lines 1 to 11, which fit the 200 px viewport (13 lines) below line 0.
        robot.interact { preview.show(a, marking("line6", 6)) }
        robot.interact { loads.single().second(text) }
        robot.interact { assertTrue("line0" in shownTexts(), "shown: ${shownTexts()}") }
    }

    @Test
    fun `no text empties the preview`(robot: FxRobot) {
        robot.interact { preview.show(a, marking("x")) }
        robot.interact { loads.single().second("x") }
        robot.interact { assertEquals(a, preview.shownFile) }

        robot.interact { preview.show(b, marking("x")) }
        robot.interact { loads[1].second(null) }

        robot.interact {
            assertNull(preview.shownFile)
            assertEquals("", preview.text)
        }
    }

    @Test
    fun `cancel keeps what shows, drops the load on its way and loads the file again when asked`(robot: FxRobot) {
        robot.interact { preview.show(a, marking("x")) }
        robot.interact { loads.single().second("text of a") }
        robot.interact {
            preview.show(b, marking("x"))
            preview.cancel()
        }
        robot.interact { loads[1].second("text of b") }
        robot.interact {
            assertEquals(a, preview.shownFile)
            assertEquals("text of a", preview.text)
        }

        robot.interact { preview.show(b, marking("x")) }

        assertEquals(listOf(a, b, b), loads.map { it.first })
    }

    @Test
    fun `a file without text is loaded again when asked again`(robot: FxRobot) {
        robot.interact { preview.show(a, marking("x")) }
        robot.interact { loads.single().second(null) }

        robot.interact { preview.show(a, marking("x")) }

        assertEquals(listOf(a, a), loads.map { it.first })
    }

    @Test
    fun `a fresh editor does not take the focus of a scene without a focus owner`(robot: FxRobot) {
        robot.interact { preview.show(a, marking("x")) }
        robot.interact { loads.single().second("x\ny\nz") }
        // The scene's focus cleanup runs on the next pulse, which interact waits for.
        robot.interact {
            assertNull(preview.node.scene.focusOwner)
            // Also what the editor's skin and cells created once shown and laid out.
            assertEquals(emptyList(), traversable(preview.node))
        }
    }

    private fun traversable(node: Node): List<Node> =
        listOfNotNull(node.takeIf { it.isFocusTraversable }) +
            ((node as? Parent)?.childrenUnmodifiable.orEmpty().flatMap(::traversable))

    @Test
    fun `the preview is read-only`(robot: FxRobot) {
        robot.interact { preview.show(a, marking("x")) }
        robot.interact { loads.single().second("x") }
        robot.interact { assertFalse((preview.editorNode.lookup(".code-area") as CodeArea).isEditable) }
    }

    @Test
    fun `texts up to the highlight limit are highlighted, longer ones are shown plain`(robot: FxRobot) {
        val atLimit = "x\n".repeat(CodeEditors.HIGHLIGHT_LIMIT / 2)
        robot.interact { preview.show(a, marking("y")) }
        robot.interact { loads[0].second(atLimit) }
        robot.interact { preview.show(b, marking("y")) }
        robot.interact { loads[1].second(atLimit + "x") }

        robot.interact { assertEquals(b, preview.shownFile) }
        assertEquals(listOf(a), highlighted)
    }

    @Test
    fun `clear drops a load on its way`(robot: FxRobot) {
        robot.interact {
            preview.show(a, marking("x"))
            preview.clear()
        }
        robot.interact { loads.single().second("x") }
        robot.interact {
            assertNull(preview.shownFile)
            assertEquals("", preview.text)
        }
    }

    @Test
    fun `a press in the preview is consumed and told`(robot: FxRobot) {
        robot.interact { preview.show(a, marking("x")) }
        robot.interact { loads.single().second("x\ny") }
        robot.interact {
            val area = preview.editorNode.lookup(".code-area")
            Event.fireEvent(area, press(area))
        }
        assertEquals(1, presses)
        assertEquals(0, reachedParent)
    }

    private fun press(target: Node) =
        MouseEvent(
            target,
            target,
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
            false,
            false,
            false,
            null,
        )

    private companion object {
        const val MARK = "preview-test-mark"
    }
}
