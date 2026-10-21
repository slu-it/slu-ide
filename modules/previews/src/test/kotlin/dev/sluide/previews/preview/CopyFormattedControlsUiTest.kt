package dev.sluide.previews.preview

import javafx.animation.Animation
import javafx.css.PseudoClass
import javafx.event.ActionEvent
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Copy Formatted button and its message, on a stub. Needs a display, see `uiTest`. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CopyFormattedControlsUiTest {
    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 400.0, 100.0)
        stage.show()
    }

    private var result: Formatted = Formatted.Text("formatted")
    private val copied = ArrayList<String>()

    private val error = PseudoClass.getPseudoClass("error")

    private fun show(robot: FxRobot): CopyFormattedControls {
        val controls =
            WaitForAsyncUtils
                .asyncFx<CopyFormattedControls> {
                    CopyFormattedControls(
                        { result },
                        copied::add,
                    )
                }.get()
        robot.interact { container.children.setAll(controls.node) }
        return controls
    }

    private fun CopyFormattedControls.click(robot: FxRobot) = robot.interact { button.fire() }

    private fun <T> fx(read: () -> T): T = WaitForAsyncUtils.asyncFx<T> { read() }.get()

    private fun CopyFormattedControls.icon() = fx { (button.graphic as FontIcon).iconCode }

    private fun CopyFormattedControls.text() = fx { message.text }

    private fun CopyFormattedControls.failed() = fx { error in message.pseudoClassStates }

    @Test
    fun `starts with the copy icon and no message`(robot: FxRobot) {
        val controls = show(robot)

        assertEquals("", controls.text())
        assertFalse(fx { controls.message.isManaged })
        assertEquals(MaterialDesignC.CONTENT_COPY, controls.icon())
        assertEquals("Copy Formatted", fx { controls.button.tooltip.text })
        assertFalse(fx { controls.button.isFocusTraversable })
    }

    @Test
    fun `a click copies the text, shows Copied and swaps the icon`(robot: FxRobot) {
        val controls = show(robot)

        controls.click(robot)

        assertEquals(listOf("formatted"), copied)
        assertEquals("Copied", controls.text())
        assertTrue(fx { controls.message.isVisible })
        assertEquals(MaterialDesignC.CHECK, controls.icon())
        assertFalse(controls.failed())
        assertEquals(Animation.Status.RUNNING, fx { controls.pause.status })
    }

    @Test
    fun `a failure copies nothing and says why`(robot: FxRobot) {
        result = Formatted.Failed("Line 3, column 4: bad")
        val controls = show(robot)

        controls.click(robot)

        assertTrue(copied.isEmpty())
        assertEquals("Not copied: Line 3, column 4: bad", controls.text())
        assertTrue(controls.failed())
        assertEquals(MaterialDesignC.CONTENT_COPY, controls.icon())
        assertNotNull(fx { controls.message.tooltip })
    }

    @Test
    fun `the message goes away when the pause ends`(robot: FxRobot) {
        val controls = show(robot)
        controls.click(robot)

        robot.interact {
            // Ends the pause at once, as its end would.
            controls.pause.stop()
            controls.pause.onFinished.handle(ActionEvent())
        }

        assertEquals("", controls.text())
        assertEquals(MaterialDesignC.CONTENT_COPY, controls.icon())
        assertNull(fx { controls.message.tooltip })
    }

    @Test
    fun `the next click replaces the message and restarts the pause`(robot: FxRobot) {
        val controls = show(robot)
        controls.click(robot)
        robot.interact { controls.pause.jumpTo(Duration.seconds(2.0)) }
        result = Formatted.Failed("oops")

        controls.click(robot)

        assertEquals("Not copied: oops", controls.text())
        assertTrue(controls.failed())
        val at = fx { controls.pause.currentTime.toMillis() }
        assertTrue(at < 500, "the pause went on at $at ms")
        assertEquals(1, copied.size)
    }

    @Test
    fun `a success after a failure drops the failure look`(robot: FxRobot) {
        result = Formatted.Failed("oops")
        val controls = show(robot)
        controls.click(robot)
        result = Formatted.Text("fine")

        controls.click(robot)

        assertEquals("Copied", controls.text())
        assertFalse(controls.failed())
    }

    @Test
    fun `clear takes the message away`(robot: FxRobot) {
        val controls = show(robot)
        controls.click(robot)

        robot.interact { controls.clear() }

        assertEquals("", controls.text())
        assertEquals(Animation.Status.STOPPED, fx { controls.pause.status })
    }
}
