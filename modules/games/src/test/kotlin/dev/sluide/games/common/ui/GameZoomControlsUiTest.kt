package dev.sluide.games.common.ui

import dev.sluide.games.common.ZoomRange
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ScrollPane
import javafx.scene.input.InputEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Pane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// UI test: needs real controls (menu items in a toggle group, the menu button's skin) and event dispatch through a
// scene, where the filters on the root must consume before the scroll pane and the game see the events.
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GameZoomControlsUiTest {
    private lateinit var controls: GameZoomControls
    private lateinit var content: Pane
    private lateinit var scrollPane: ScrollPane
    private lateinit var status: Label
    private val zoomed = mutableListOf<Double>()

    /** The events that reached a handler on the root, i.e. that no filter consumed. */
    private val reached = mutableListOf<InputEvent>()

    @Start
    fun start(stage: Stage) {
        controls = GameZoomControls(ZoomRange.ABOVE_FIT, 0.5) { zoomed += it }
        content = Pane().apply { setPrefSize(2000.0, 2000.0) }
        scrollPane = ScrollPane(content)
        status = Label("status")
        val root = BorderPane(scrollPane, controls.node, null, status, null)
        root.addEventHandler(KeyEvent.KEY_PRESSED) { reached += it }
        root.addEventHandler(ScrollEvent.SCROLL) { reached += it }
        controls.install(root)
        stage.scene = Scene(root, 500.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get()

    private fun checked() = fx { (controls.levelMenu.items.firstOrNull { (it as RadioMenuItem).isSelected })?.text }

    @Test
    fun `the zoom buttons are disabled at the bounds and the level shows and checks the preset`(robot: FxRobot) {
        fun layOut() =
            controls.node.scene.root.run {
                applyCss()
                layout()
            }
        robot.interact { layOut() }
        val width = fx { controls.levelMenu.width }
        assertEquals("50%", fx { controls.levelMenu.text })
        assertEquals("50%", checked())

        robot.interact {
            controls.zoomTo(0.1)
            layOut()
        }
        assertEquals(0.25, fx { controls.zoom.get() })
        assertTrue(fx { controls.zoomOutButton.isDisable })
        assertFalse(fx { controls.zoomInButton.isDisable })
        assertEquals("25%", checked())

        robot.interact {
            controls.zoomTo(0.634)
            layOut()
        }
        assertEquals("63%", fx { controls.levelMenu.text })
        assertNull(checked())
        assertFalse(fx { controls.zoomOutButton.isDisable })

        robot.interact {
            controls.zoomTo(1.0)
            layOut()
        }
        assertEquals("Fit", fx { controls.levelMenu.text })
        assertEquals("Fit", checked())

        robot.interact {
            controls.zoomIn()
            controls.zoomIn()
            controls.zoomIn()
            layOut()
        }
        assertEquals(2.0, fx { controls.zoom.get() })
        assertEquals("200%", checked())
        assertTrue(fx { controls.zoomInButton.isDisable })
        assertFalse(fx { controls.zoomOutButton.isDisable })
        // The level keeps one width, so that the buttons beside it stay in place.
        assertEquals(width, fx { controls.levelMenu.width })
    }

    @Test
    fun `choosing a level, a button and Fit zoom, and every change is reported once`(robot: FxRobot) {
        assertEquals(emptyList(), zoomed)

        robot.interact {
            controls.levelMenu.items
                .first { it.text == "150%" }
                .fire()
        }
        robot.interact { controls.zoomOutButton.fire() }
        robot.interact { controls.zoomTo(1.25) }
        robot.interact { controls.fitButton.fire() }

        assertEquals(listOf(1.5, 1.25, 1.0), zoomed)
    }

    @Test
    fun `the zoom keys anywhere in the game zoom and are consumed, Shortcut+9 and plain keys pass`(robot: FxRobot) {
        fun press(event: KeyEvent) = robot.interact { Event.fireEvent(content, event) }

        press(keyPress(KeyCode.PLUS))
        assertEquals(0.75, fx { controls.zoom.get() })
        press(keyPress(KeyCode.MINUS))
        press(keyPress(KeyCode.SUBTRACT))
        assertEquals(0.25, fx { controls.zoom.get() })
        press(keyPress(KeyCode.DIGIT0))
        assertEquals(1.0, fx { controls.zoom.get() })
        assertEquals(emptyList(), reached)

        press(keyPress(KeyCode.DIGIT9))
        press(keyPress(KeyCode.PLUS, shortcut = false))

        assertEquals(1.0, fx { controls.zoom.get() })
        assertEquals(listOf(KeyCode.DIGIT9, KeyCode.PLUS), reached.map { (it as KeyEvent).code })
    }

    @Test
    fun `Shortcut+wheel and pinch zoom instead of scrolling, a plain wheel passes`(robot: FxRobot) {
        robot.interact { Event.fireEvent(content, scrollEvent(-40.0, shortcut = true)) }

        assertEquals(0.5 / 1.1, fx { controls.zoom.get() }, 1e-9)
        assertEquals(0.0, fx { scrollPane.vvalue })
        assertEquals(emptyList(), reached)

        robot.interact { Event.fireEvent(content, pinchEvent(100.0)) }
        assertEquals(2.0, fx { controls.zoom.get() })

        robot.interact { Event.fireEvent(status, scrollEvent(40.0, shortcut = false)) }
        assertEquals(2.0, fx { controls.zoom.get() })
        assertEquals(1, reached.size)
    }
}
