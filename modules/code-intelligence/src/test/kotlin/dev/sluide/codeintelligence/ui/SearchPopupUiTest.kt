package dev.sluide.codeintelligence.ui

import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The search popup in a window: its field takes the focus, typed keys reach the field and tell the query, Up/Down,
 * PageUp/PageDown and Enter are taken by the field and go no further, Escape closes it, after which keys reach the
 * window again, a note hides on the next key. Needs a display: the focus in a popup and the routing of key presses from the owner window to it exist only in
 * a shown scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class SearchPopupUiTest {
    private val button = Button("elsewhere")
    private lateinit var stage: Stage
    private lateinit var popup: SearchPopup<String>
    private val chosen = mutableListOf<String>()
    private val queries = mutableListOf<String>()

    /** The keys that reached the window's focus owner past the popup. */
    private val buttonKeys = mutableListOf<KeyCode>()

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        button.addEventHandler(KeyEvent.KEY_PRESSED) { buttonKeys += it.code }
        stage.scene = Scene(StackPane(button), 600.0, 400.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        fx { popup.close() }
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get(5, TimeUnit.SECONDS)

    private fun setUp(robot: FxRobot) {
        popup =
            fx {
                SearchPopup<String>(
                    { SearchAnchor(stage, checkNotNull(button.localToScreen(button.boundsInLocal)), button) },
                    { PopupCell(it) },
                    visibleRows = 2,
                ).apply {
                    onChoose = { chosen += it }
                    onQuery = { queries += it }
                }
            }
        robot.interact {
            button.requestFocus()
            assertTrue(popup.open())
            popup.setItems((1..5).map { "item$it" })
        }
    }

    /** A key event fired at the window's focus owner, as the window dispatches it: the popup gets it first. */
    private fun key(
        robot: FxRobot,
        code: KeyCode,
        type: EventType<KeyEvent> = KeyEvent.KEY_PRESSED,
        character: String = "",
    ) {
        robot.interact { Event.fireEvent(button, KeyEvent(type, character, "", code, false, false, false, false)) }
    }

    @Test
    fun `the field takes the focus, typing tells the query, and the keys move and take the selection`(robot: FxRobot) {
        setUp(robot)
        assertTrue(fx { popup.queryField.scene.focusOwner === popup.queryField })
        assertTrue(fx { popup.isShowing })
        assertTrue(fx { popup.selected } == "item1")

        for (character in "it") key(robot, KeyCode.UNDEFINED, KeyEvent.KEY_TYPED, character.toString())
        assertEquals("it", fx { popup.query })
        assertEquals(listOf("i", "it"), queries)

        key(robot, KeyCode.DOWN)
        assertEquals("item2", fx { popup.selected })
        key(robot, KeyCode.UP)
        key(robot, KeyCode.UP)
        assertEquals("item5", fx { popup.selected }, "wrapping around")
        key(robot, KeyCode.PAGE_UP)
        assertEquals("item3", fx { popup.selected }, "a page of two rows")
        key(robot, KeyCode.PAGE_DOWN)
        assertEquals("item5", fx { popup.selected })
        assertEquals("it", fx { popup.query }, "the arrows did not move the field's caret into the text")

        key(robot, KeyCode.ENTER)
        assertEquals(listOf("item5"), chosen)
        assertFalse(fx { popup.isShowing })
        assertEquals(emptyList(), buttonKeys, "while it showed, the popup took every key")
        key(robot, KeyCode.F)
        assertEquals(listOf(KeyCode.F), buttonKeys, "the next key reaches the window again")
    }

    @Test
    fun `Escape closes the popup and returns the focus, without a choice`(robot: FxRobot) {
        setUp(robot)
        key(robot, KeyCode.ESCAPE)
        assertFalse(fx { popup.isShowing })
        assertEquals(emptyList(), chosen)
        key(robot, KeyCode.F)
        assertEquals(listOf(KeyCode.F), buttonKeys, "the next key reaches the window again, Escape did not")
    }

    @Test
    fun `a note shows without the field and hides on the next key`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.hide() }
        robot.interact { assertTrue(popup.note("Go to Symbol is not available (no language server running)")) }
        assertTrue(fx { popup.isShowing })
        assertFalse(fx { popup.queryField.isVisible })
        key(robot, KeyCode.A)
        assertFalse(fx { popup.isShowing })
    }
}
