package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.press
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.fxmisc.wellbehaved.event.EventPattern.keyPressed
import org.fxmisc.wellbehaved.event.InputMap
import org.fxmisc.wellbehaved.event.Nodes
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The macOS keys on a real [CodeArea]. UI test because [macTextKeys] go through RichTextFX's navigation, wrapping and
 * undo, which need a scene (`uiTest`); the offsets they compute are unit-tested in [MacTextKeysTest].
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MacTextKeysUiTest {
    private lateinit var area: CodeArea

    /** What the keys behind [macTextKeys] got, for those it must not consume. */
    private val passedOn = mutableListOf<String>()

    @Start
    fun start(stage: Stage) {
        area = CodeArea()
        Nodes.addInputMap(
            area,
            InputMap.sequence(
                InputMap.consume(keyPressed(KeyCode.UP, KeyCombination.ALT_DOWN, KeyCombination.SHIFT_DOWN)) {
                    passedOn += "alt-shift-up"
                },
                InputMap.consume(keyPressed(KeyCode.LEFT, KeyCombination.ALT_DOWN, KeyCombination.META_DOWN)) {
                    passedOn += "cmd-alt-left"
                },
                InputMap.consume(keyPressed(KeyCode.RIGHT, KeyCombination.ALT_DOWN, KeyCombination.META_DOWN)) {
                    passedOn += "cmd-alt-right"
                },
            ),
        )
        stage.scene = Scene(StackPane(area), 400.0, 200.0)
        stage.show()
    }

    private fun open(
        robot: FxRobot,
        text: String = TEXT,
        caret: Int = 0,
        anchor: Int = caret,
        mac: Boolean = true,
        editable: Boolean = true,
    ) {
        robot.interact {
            installMacTextKeys(area, mac)
            area.replaceText(text)
            area.undoManager.forgetHistory()
            area.isEditable = editable
            area.selectRange(anchor, caret)
            area.requestFocus()
        }
    }

    @ParameterizedTest(name = "{0} {1} from {2} with shift {3}: caret {4}")
    @CsvSource(
        "META,LEFT,8,false,0",
        "META,KP_LEFT,8,false,0",
        "META,RIGHT,8,false,15",
        "META,KP_RIGHT,8,true,15",
        "META,LEFT,8,true,0",
        "META,UP,20,false,0",
        "META,DOWN,20,false,33",
        "META,UP,20,true,0",
        "ALT,LEFT,8,false,6",
        "ALT,LEFT,6,false,0",
        "ALT,RIGHT,6,false,9",
        "ALT,RIGHT,5,false,9",
        "ALT,RIGHT,8,true,9",
        "ALT,LEFT,8,true,6",
        "ALT,UP,20,false,16",
        "ALT,UP,16,false,0",
        "ALT,DOWN,20,false,27",
        "ALT,DOWN,27,false,28",
    )
    fun `navigation keys move the caret or extend the selection with shift`(
        modifier: String,
        key: String,
        caret: Int,
        shift: Boolean,
        expectedCaret: Int,
        robot: FxRobot,
    ) {
        open(robot, caret = caret)
        robot.press(area, KeyCode.valueOf(key), shift = shift, alt = modifier == "ALT", meta = modifier == "META")

        assertEquals(expectedCaret, area.caretPosition)
        assertEquals(if (shift) caret else expectedCaret, area.anchor)
    }

    @Test
    fun `option shift up and cmd option left are left to other keys`(robot: FxRobot) {
        open(robot, caret = 8)
        robot.press(area, KeyCode.UP, shift = true, alt = true)
        robot.press(area, KeyCode.LEFT, alt = true, meta = true)
        robot.press(area, KeyCode.RIGHT, alt = true, meta = true)

        assertEquals(listOf("alt-shift-up", "cmd-alt-left", "cmd-alt-right"), passedOn)
    }

    // The deleted range [start, end) of TEXT.
    @ParameterizedTest(name = "{0} {1} from {2}-{3} deletes {4}-{5}")
    @CsvSource(
        "ALT,BACK_SPACE,8,8,6,8",
        "ALT,DELETE,8,8,8,9",
        "META,BACK_SPACE,8,8,0,8",
        "META,DELETE,8,8,8,15",
        "META,BACK_SPACE,20,20,16,20",
        "META,BACK_SPACE,16,16,15,16",
        "META,DELETE,15,15,15,16",
        "ALT,BACK_SPACE,2,9,2,9",
        "META,DELETE,9,2,2,9",
        "META,BACK_SPACE,0,0,0,0",
        "ALT,BACK_SPACE,0,0,0,0",
        "META,DELETE,33,33,33,33",
        "ALT,DELETE,33,33,33,33",
    )
    fun `delete keys delete a word, to the line boundary or the selection, as one undo step`(
        modifier: String,
        key: String,
        caret: Int,
        anchor: Int,
        start: Int,
        end: Int,
        robot: FxRobot,
    ) {
        open(robot, caret = caret, anchor = anchor)
        robot.press(area, KeyCode.valueOf(key), alt = modifier == "ALT", meta = modifier == "META")

        assertEquals(TEXT.removeRange(start, end), area.text)
        assertEquals(start.takeIf { end > start } ?: caret, area.caretPosition)
        robot.interact { area.undoManager.undo() }
        assertEquals(TEXT, area.text)
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource("ALT,BACK_SPACE", "ALT,DELETE", "META,BACK_SPACE", "META,DELETE")
    fun `delete keys do nothing in a read-only area`(
        modifier: String,
        key: String,
        robot: FxRobot,
    ) {
        open(robot, caret = 8, anchor = 2, editable = false)
        robot.press(area, KeyCode.valueOf(key), alt = modifier == "ALT", meta = modifier == "META")

        assertEquals(TEXT, area.text)
    }

    @Test
    fun `navigation works in a read-only area`(robot: FxRobot) {
        open(robot, caret = 8, editable = false)
        robot.press(area, KeyCode.LEFT, meta = true)

        assertEquals(0, area.caretPosition)
    }

    @Test
    fun `without the mac keys cmd left is not the line start`(robot: FxRobot) {
        open(robot, caret = 8, mac = false)
        robot.press(area, KeyCode.LEFT, meta = true)

        // RichTextFX does not bind Cmd+Left to the line start: on Linux Meta is no Shortcut and nothing moves the
        // caret, on macOS it skips a word. Either way it is not 0, which only the mac keys give.
        assertTrue(area.caretPosition != 0, "caret at ${area.caretPosition}")
    }

    @Test
    fun `on a wrapped line cmd left and right go where home and end go`(robot: FxRobot) {
        open(robot, "hello big world and more words to wrap around the narrow area", caret = 0)
        robot.interact {
            area.isWrapText = true
            area.prefWidth = 100.0
            area.maxWidth = 100.0
            area.parent.layout()
        }
        robot.interact { area.moveTo(0, 40) }
        val inSecondLine = area.caretPosition
        assertTrue(area.getParagraphLinesCount(0) > 1, "text should wrap")

        fun caretAfter(
            code: KeyCode,
            meta: Boolean,
        ): Int {
            robot.interact { area.moveTo(inSecondLine) }
            robot.press(area, code, meta = meta)
            return area.caretPosition
        }
        val lineStart = caretAfter(KeyCode.HOME, meta = false)
        val lineEnd = caretAfter(KeyCode.END, meta = false)

        assertTrue(lineStart > 0, "the caret is not on the first visual line")
        assertEquals(lineStart, caretAfter(KeyCode.LEFT, meta = true))
        assertEquals(lineEnd, caretAfter(KeyCode.RIGHT, meta = true))
    }

    private companion object {
        // "hello big world" 0-15, "\n", "second line" 16-27, "\n", "\n", "last" 29-33.
        const val TEXT = "hello big world\nsecond line\n\nlast"
    }
}
