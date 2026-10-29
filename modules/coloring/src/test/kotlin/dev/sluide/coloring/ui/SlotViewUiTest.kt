package dev.sluide.coloring.ui

import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A slot view's mouse handling, focus and pseudo-class need a scene; the slot logic is in ColoringStateTest. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class SlotViewUiTest {
    private val state = ColoringState()
    private lateinit var text: SlotView
    private lateinit var background: SlotView

    @Start
    fun start(stage: Stage) {
        text = SlotView(state.contrastText, state)
        background = SlotView(state.contrastBackground, state, tab = ColoringState.VISION)
        stage.scene = Scene(VBox(text.node, background.node), 340.0, 120.0)
        stage.show()
    }

    private fun press() =
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

    private fun editing(view: SlotView) = view.node.pseudoClassStates.any { it.pseudoClassName == "editing" }

    @Test
    fun `a primary click on the swatch edits the slot for its tab and moves the focus to its field`(robot: FxRobot) {
        robot.interact { text.field.requestFocus() }
        robot.interact { click(background.swatch, MouseButton.SECONDARY) }
        assertSame(state.contrastText, state.editing.get())

        robot.interact { click(background.swatch) }

        assertSame(state.contrastBackground, state.editing.get())
        assertSame(state.contrastBackground, state.remembered[ColoringState.VISION])
        assertTrue(background.field.isFocused, "focus follows the slot being edited")
    }

    @Test
    fun `the slot keeps its own width in a wider row, so the ring of the slot being edited hugs it`() {
        val (width, pref) =
            WaitForAsyncUtils
                .asyncFx<Pair<Double, Double>> {
                    text.node.layoutBounds.width to
                        text.node.prefWidth(
                            -1.0,
                        )
                }.get()

        assertEquals(pref, width, 0.5)
        assertTrue(width < 340.0, "narrower than the row")
    }

    @Test
    fun `focus in the field edits the slot`(robot: FxRobot) {
        robot.interact { background.field.requestFocus() }

        assertSame(state.contrastBackground, state.editing.get())
    }

    @Test
    fun `a press in the field that already has the focus edits the slot`(robot: FxRobot) {
        robot.interact { text.field.requestFocus() }
        // Another slot is chosen without moving the focus, as a switch of the tool tab does.
        robot.interact { state.edit(state.contrastBackground) }
        assertTrue(text.field.isFocused)

        robot.interact { Event.fireEvent(text.field, press()) }

        assertSame(state.contrastText, state.editing.get())
    }

    @Test
    fun `the slot being edited is marked`(robot: FxRobot) {
        assertTrue(editing(text))
        assertFalse(editing(background))

        robot.interact { state.edit(state.contrastBackground) }

        assertFalse(editing(text))
        assertTrue(editing(background))
    }

    @Test
    fun `a disposed slot view no longer follows the editing slot`(robot: FxRobot) {
        robot.interact {
            text.dispose()
            state.edit(state.contrastBackground)
        }

        assertTrue(editing(text))
    }
}
