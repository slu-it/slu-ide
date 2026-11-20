package dev.sluide.jshell.ui

import dev.sluide.jshell.EvalEvent
import javafx.application.ColorScheme
import javafx.event.Event
import javafx.geometry.Point2D
import javafx.scene.Cursor
import javafx.scene.control.MenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.stage.WindowEvent
import org.fxmisc.richtext.Caret
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The transcript pane's text, menu, clicks and colours. A UI test: hit testing, the cursor and the painted colours need
 * a scene; the paragraphs and jump targets are tested in [TranscriptModelTest]. Following new text:
 * [TranscriptPaneFollowUiTest].
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TranscriptPaneUiTest : TranscriptPaneUiFixture() {
    @Start
    fun start(stage: Stage) = startPane(stage)

    private fun text(content: String): Text =
        pane.area
            .lookupAll(".text")
            .filterIsInstance<Text>()
            .first { it.text == content }

    private fun fill(content: String): Color = text(content).fill as Color

    private fun luminance(color: Color): Double {
        fun channel(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val (hi, lo) = luminance(a).coerceAtLeast(luminance(b)) to luminance(a).coerceAtMost(luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `events, a continued print, late output and a note show as the model's paragraphs and styles`() {
        onFx {
            pane.startEvaluation("a\nb")
            pane.accept(EvalEvent.SnippetStarted(0, 1, 0))
            pane.accept(EvalEvent.Output("ab", false))
        }
        // the print's continuation comes after the area shows its start: it is appended to that paragraph
        onFx {
            pane.accept(EvalEvent.Output("cd\n", false))
            pane.accept(EvalEvent.Output("e", true))
            pane.accept(EvalEvent.Value("x ==> 5"))
            pane.accept(EvalEvent.Finished)
            pane.lateOutput("late", false)
            pane.note("Session ended (exit 3)")
        }

        assertEquals(
            listOf("jshell> a", "abcd", "e", "x ==> 5", "late", "Session ended (exit 3)"),
            pane.area.paragraphs.map { it.text },
        )
        assertEquals(listOf("transcript-output"), pane.area.getStyleOfChar(1, 3).toList(), "the continuation's style")
    }

    @Test
    fun `dropped paragraphs leave the area and clear empties it`() {
        onFx {
            pane.startEvaluation("a")
            repeat(60_000) { pane.accept(EvalEvent.Output("0123456789abcdef$it\n", false)) }
        }
        val paragraphs = pane.area.paragraphs
        assertEquals("0123456789abcdef59999", paragraphs.last().text)
        assertTrue(paragraphs.sumOf { it.text.length + 1 } <= 1_048_576 + 200, "area kept ${paragraphs.size}")
        assertTrue(paragraphs.size < 60_000)

        onFx { pane.clear() }
        assertEquals("", pane.area.text)

        onFx { pane.accept(EvalEvent.Output("again", false)) }
        assertEquals("again", pane.area.text)
    }

    @Test
    fun `the context menu has Copy, Select All and Clear, and Clear empties the transcript`() {
        onFx { pane.note("hello") }
        val items =
            pane.area.contextMenu.items
                .map { it.text }
        assertEquals(listOf("Copy", "Select All", "Clear"), items.filter { it.isNotEmpty() })

        onFx {
            pane.area.contextMenu.items
                .first { it is MenuItem && it.text == "Clear" }
                .fire()
        }

        assertEquals("", pane.area.text)
    }

    @Test
    fun `Copy puts the selection on the clipboard, Select All selects, and Copy is disabled without a selection`() {
        onFx { pane.note("hello") }
        val menu = pane.area.contextMenu
        val copy = menu.items.first { it.text == "Copy" }
        val selectAll = menu.items.first { it.text == "Select All" }

        onFx { Event.fireEvent(menu, WindowEvent(menu, WindowEvent.WINDOW_SHOWING)) }
        assertTrue(copy.isDisable, "Copy is enabled without a selection")

        onFx { selectAll.fire() }
        assertEquals("hello", pane.area.selectedText)
        onFx { Event.fireEvent(menu, WindowEvent(menu, WindowEvent.WINDOW_SHOWING)) }
        assertTrue(!copy.isDisable, "Copy is disabled with a selection")

        onFx { Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString("before") }) }
        onFx { copy.fire() }
        assertEquals("hello", onFxGet { Clipboard.getSystemClipboard().string })
    }

    @Test
    fun `the text is selectable but not editable, wraps, shows no caret and is monospace`() {
        assertTrue(!pane.area.isEditable)
        assertTrue(pane.area.isWrapText)
        assertEquals(Caret.CaretVisibility.OFF, pane.area.showCaret)
        onFx { pane.note("hello") }
        onFx { pane.area.selectAll() }
        assertEquals("hello", pane.area.selectedText)
        assertEquals("Monospaced", text("hello").font.family)
    }

    @Test
    fun `the transcript keeps no undo history of what it showed`() {
        onFx { pane.accept(EvalEvent.Output(lines(3), false)) }
        onFx { pane.clear() }

        assertTrue(!pane.area.isUndoAvailable, "the area keeps its changes for an undo")
    }

    @Test
    fun `a click on a header jumps to its line and a click after a drag selection does not`() {
        onFx {
            pane.startEvaluation("one\ntwo\nthree")
            pane.accept(EvalEvent.SnippetStarted(4, 7, 1))
            pane.accept(EvalEvent.Output("printed", false))
        }
        val header = centerOf(0)
        val output = centerOf(1)

        // Real mouse input: the jump depends on the press-release distance and the hit test of the area.
        val robot = FxRobot()
        // a drag is real input: only it makes the press-release distance
        robot.drag(Point2D(header.x - 20, header.y), MouseButton.PRIMARY).dropTo(Point2D(header.x + 40, header.y))
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(pane.area.selectedText.isNotEmpty(), "the drag selected nothing")
        assertEquals(emptyList(), jumps)

        // a click is real input: the hit test of the area decides the paragraph
        robot.clickOn(output)
        assertEquals(emptyList(), jumps)

        // a click is real input: the hit test of the area decides the paragraph
        robot.clickOn(header)
        assertEquals(listOf(1), jumps)
    }

    @Test
    fun `over a header the hand shows and a click jumps while output that drops the oldest paragraphs waits`() {
        val padding = (0 until 60_000).joinToString("") { "0123456789abcdef$it\n" }
        onFx {
            pane.startEvaluation("zero\none\ntwo\nthree")
            pane.accept(EvalEvent.Output(padding, false))
        }
        onFx {
            pane.accept(EvalEvent.SnippetStarted(9, 12, 2))
            pane.accept(EvalEvent.Output("x\ny\n", false))
        }

        // Fired events: only they can arrive in the same FX event as output that is not shown yet.
        fun whileOutputWaits(type: javafx.event.EventType<MouseEvent>) =
            onFx {
                val header = pane.area.paragraphs.indexOfLast { it.text == "jshell> two" }
                val event = mouse(type, centerOf(header))
                pane.accept(EvalEvent.Output("more\nmore\n", false))
                Event.fireEvent(pane.area, event)
            }
        whileOutputWaits(MouseEvent.MOUSE_MOVED)
        assertEquals(Cursor.HAND, pane.area.cursor)
        whileOutputWaits(MouseEvent.MOUSE_CLICKED)
        assertEquals(listOf(2), jumps)
    }

    @ParameterizedTest
    @ValueSource(strings = ["the secondary button", "a mouse moved since the press", "a selection"])
    fun `a click on a header does not jump with`(variant: String) {
        onFx {
            pane.startEvaluation("one")
            pane.accept(EvalEvent.SnippetStarted(0, 3, 0))
        }
        if (variant == "a selection") onFx { pane.area.selectRange(0, 3) }

        // A fired click: the variants are flags of the event that real input sets only together with other effects.
        onFx {
            val click =
                when (variant) {
                    "the secondary button" -> {
                        mouse(MouseEvent.MOUSE_CLICKED, centerOf(0), MouseButton.SECONDARY)
                    }

                    "a mouse moved since the press" -> {
                        mouse(MouseEvent.MOUSE_CLICKED, centerOf(0), stillSincePress = false)
                    }

                    else -> {
                        mouse(MouseEvent.MOUSE_CLICKED, centerOf(0))
                    }
                }
            Event.fireEvent(pane.area, click)
        }

        assertEquals(emptyList(), jumps)
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, 30.0])
    fun `below the last paragraph a header's hand and jump end, from the first pixel below it`(below: Double) {
        onFx {
            pane.startEvaluation("one")
            pane.accept(EvalEvent.SnippetStarted(0, 3, 0))
        }
        val bounds = onFxGet { pane.area.getParagraphBoundsOnScreen(0).get() }
        val point = Point2D(bounds.minX + 40, bounds.maxY + below)

        // Fired events at a set pixel: the area's hit test gives the last paragraph for every point below it.
        onFx { Event.fireEvent(pane.area, mouse(MouseEvent.MOUSE_MOVED, point)) }
        assertEquals(Cursor.TEXT, pane.area.cursor)
        onFx { Event.fireEvent(pane.area, mouse(MouseEvent.MOUSE_CLICKED, point)) }
        assertEquals(emptyList(), jumps)
    }

    @Test
    fun `the hand cursor shows over a paragraph with a jump target only`() {
        onFx {
            pane.startEvaluation("one")
            pane.accept(EvalEvent.SnippetStarted(0, 3, 0))
            pane.accept(EvalEvent.Output("printed", false))
        }

        // Real mouse movement: the cursor follows the hit test of mouse-moved events.
        val robot = FxRobot()
        // a move is real input: the cursor follows the hit test of the mouse position
        robot.moveTo(centerOf(0))
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(Cursor.HAND, pane.area.cursor)

        // a move is real input: the cursor follows the hit test of the mouse position
        robot.moveTo(centerOf(1))
        WaitForAsyncUtils.waitForFxEvents()
        assertNotEquals(Cursor.HAND, pane.area.cursor)
    }

    @Test
    fun `in the dark theme error output differs from output and both are readable, headers are not black`() {
        onFx {
            // The dark theme's base colours (settings' theme.css), which the test has no theme stylesheet for.
            stage.scene.stylesheets +=
                "data:text/css,.root { -fx-base: %233c3f41; -fx-control-inner-background: %232b2b2b; }"
            stage.scene.preferences.colorScheme = ColorScheme.DARK
            pane.startEvaluation("int x")
            pane.accept(EvalEvent.SnippetStarted(0, 1, 0))
            pane.accept(EvalEvent.Output("plain out", false))
            pane.accept(EvalEvent.Output("error out", true))
            pane.accept(EvalEvent.Diagnostic(0, 1, 0, "bad", true))
            pane.accept(EvalEvent.Thrown("thrown", null))
        }
        val background =
            pane.area.background.fills
                .first()
                .fill as Color

        val output = fill("plain out")
        val error = fill("error out")
        assertTrue(luminance(background) < 0.2, "the background is not dark: $background")
        assertNotEquals(output, error)
        assertTrue(contrast(output, background) >= 4.5, "output $output on $background")
        assertTrue(contrast(error, background) >= 4.5, "error $error on $background")
        assertTrue(contrast(fill("line 1: bad"), background) >= 4.5, "diagnostic")
        assertTrue(contrast(fill("thrown"), background) >= 4.5, "thrown")
        val header = fill("jshell> int x")
        assertNotEquals(Color.BLACK, header)
        assertTrue(contrast(header, background) >= 4.5, "header $header on $background")
    }

    @Test
    fun `a hundred thousand outputs are appended within 6 seconds`() {
        val started = System.nanoTime()
        onFx { repeat(100_000) { pane.accept(EvalEvent.Output("o$it\n", false)) } }
        val ms = (System.nanoTime() - started) / 1_000_000

        assertEquals(
            "o99999",
            pane.area.paragraphs
                .last()
                .text,
        )
        assertTrue(ms < BUDGET_MS, "took $ms ms")
    }

    private companion object {
        // measured: 0.5 s in the sandbox; the budget leaves room for slow CI runners but fails for per-event re-rendering (19 s)
        const val BUDGET_MS = 6_000
    }
}
