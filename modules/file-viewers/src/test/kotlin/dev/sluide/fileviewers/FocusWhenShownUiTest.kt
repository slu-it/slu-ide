package dev.sluide.fileviewers

import javafx.scene.Scene
import javafx.scene.control.TextField
import javafx.scene.layout.StackPane
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

/** [FocusWhenShown] with a node that is added to a scene later (ADR 0050). Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FocusWhenShownUiTest {
    private val container = StackPane()
    private val focusing = FocusWhenShown()
    private val calls = mutableListOf<String>()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 300.0, 200.0)
        stage.show()
    }

    @Test
    fun `a node in a scene is focused at once`(robot: FxRobot) {
        val field = TextField()
        robot.interact { container.children.setAll(field) }

        robot.interact { focusing.request(field) }

        assertSame(field, field.scene.focusOwner)
    }

    @Test
    fun `a node without a scene is focused after it was added`(robot: FxRobot) {
        val field = TextField()
        robot.interact { focusing.request(field) { calls += "field" } }
        assertEquals(emptyList(), calls, "not before it is shown")

        robot.interact { container.children.setAll(field) }

        assertEquals(listOf("field"), calls)
    }

    @Test
    fun `by default the node itself gets the focus`(robot: FxRobot) {
        val field = TextField()
        robot.interact { focusing.request(field) }

        robot.interact { container.children.setAll(field) }

        assertSame(field, field.scene.focusOwner)
    }

    @Test
    fun `a newer request drops the pending one`(robot: FxRobot) {
        val field = TextField()
        robot.interact {
            focusing.request(field) { calls += "first" }
            focusing.request(field) { calls += "second" }
        }

        robot.interact { container.children.setAll(field) }

        assertEquals(listOf("second"), calls)
    }

    @Test
    fun `cancel drops the pending request`(robot: FxRobot) {
        val field = TextField()
        robot.interact {
            focusing.request(field) { calls += "field" }
            focusing.cancel()
        }

        robot.interact { container.children.setAll(field) }

        assertEquals(emptyList(), calls)
    }

    @Test
    fun `a node removed again before the pulse is not focused`(robot: FxRobot) {
        val field = TextField()
        robot.interact { focusing.request(field) { calls += "field" } }

        robot.interact {
            container.children.setAll(field)
            container.children.clear()
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), calls)
        assertFalse(field.isFocused)
    }
}
