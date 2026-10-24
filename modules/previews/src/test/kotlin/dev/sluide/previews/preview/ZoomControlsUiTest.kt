package dev.sluide.previews.preview

import dev.sluide.previews.keyPress
import dev.sluide.previews.pinchEvent
import dev.sluide.previews.scrollEvent
import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.web.WebView
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The input handling of [ZoomControls] over a [DocumentZoom], without a page or a preview view: keys, buttons, wheel,
 * pinch, clamps. `DocumentZoomUiTest` checks that the previews wire it up. Needs a display, see the `uiTest` task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ZoomControlsUiTest {
    private val container = StackPane()
    private lateinit var webView: WebView
    private lateinit var zoom: ZoomControls

    /** Stands for the source beside the preview: the keys are only for the web view and the controls. */
    private lateinit var source: TextField
    private var changes = 0

    @Start
    fun start(stage: Stage) {
        webView = WebView()
        source = TextField()
        zoom = ZoomControls(DocumentZoom(webView)) { changes++ }.also { it.install(webView) }
        container.children.setAll(VBox(zoom.node, webView, source))
        stage.scene = Scene(container, 600.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    /** Fires [event] at [target] and returns whether a filter consumed it (it did not reach the container). */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Event> fire(
        target: Node,
        event: T,
    ): Boolean {
        var reached = false
        val handler = EventHandler<T> { reached = true }
        val type = event.eventType as EventType<T>
        fx {
            container.addEventHandler(type, handler)
            try {
                Event.fireEvent(target, event)
            } finally {
                container.removeEventHandler(type, handler)
            }
        }
        return !reached
    }

    private fun key(
        code: KeyCode,
        shortcut: Boolean = true,
        target: Node = webView,
    ) = fire(target, keyPress(code, shortcut = shortcut))

    private fun press(
        robot: FxRobot,
        button: Button,
    ) = robot.interact { button.fire() }

    /** Asserts that scale, label and `WebView.zoom` all say [scale]. */
    private fun assertZoom(
        scale: Double,
        label: String,
        message: String = "",
    ) {
        assertEquals(label, fx { zoom.level.text }, message)
        assertEquals(scale, fx { zoom.scale }, 1e-9, message)
        assertEquals(scale, fx { webView.zoom }, 1e-9, message)
    }

    private val zoomKeys =
        listOf(
            KeyCode.EQUALS,
            KeyCode.PLUS,
            KeyCode.ADD,
            KeyCode.MINUS,
            KeyCode.SUBTRACT,
            KeyCode.DIGIT0,
            KeyCode.NUMPAD0,
            KeyCode.DIGIT9,
            KeyCode.NUMPAD9,
        )

    @Test
    fun `Shortcut with equals zooms in, minus twice zooms out, zero shows 100 percent`() {
        assertTrue(key(KeyCode.EQUALS), "consumed")
        assertZoom(1.25, "125%")
        assertTrue(key(KeyCode.MINUS))
        assertZoom(1.0, "100%")
        assertTrue(key(KeyCode.MINUS))
        assertZoom(0.75, "75%")
        assertTrue(key(KeyCode.DIGIT0))
        assertZoom(1.0, "100%")
    }

    @Test
    fun `Shortcut with plus, numpad plus, numpad minus and numpad zero zoom too`() {
        key(KeyCode.PLUS)
        assertZoom(1.25, "125%")
        key(KeyCode.ADD)
        assertZoom(1.5, "150%")
        key(KeyCode.SUBTRACT)
        assertZoom(1.25, "125%")
        key(KeyCode.NUMPAD0)
        assertZoom(1.0, "100%")
    }

    @Test
    fun `the keys work on the controls as well`() {
        assertTrue(key(KeyCode.EQUALS, target = zoom.node))
        assertZoom(1.25, "125%")
    }

    @Test
    fun `a key without Shortcut does not zoom`() {
        for (code in zoomKeys) {
            assertFalse(key(code, shortcut = false), "$code not consumed")
            assertZoom(1.0, "100%", "$code")
        }
    }

    @Test
    fun `Shortcut with 9 changes nothing and is not consumed`() {
        key(KeyCode.EQUALS)
        assertFalse(key(KeyCode.DIGIT9), "not consumed")
        assertFalse(key(KeyCode.NUMPAD9), "not consumed")
        assertZoom(1.25, "125%")
        assertFalse(fx { zoom.fit })
    }

    @Test
    fun `the keys in the source beside the preview do not zoom`() {
        for (code in zoomKeys) {
            assertFalse(key(code, target = source), "$code not consumed")
            assertZoom(1.0, "100%", "$code")
        }

        key(KeyCode.EQUALS)
        for (code in zoomKeys) {
            key(code, target = source)
            assertZoom(1.25, "125%", "$code")
        }
    }

    @Test
    fun `the zoom buttons do what the keys do`(robot: FxRobot) {
        press(robot, zoom.zoomInButton)
        assertZoom(1.25, "125%")
        press(robot, zoom.zoomInButton)
        assertZoom(1.5, "150%")
        press(robot, zoom.actualSizeButton)
        assertZoom(1.0, "100%")
        press(robot, zoom.zoomOutButton)
        press(robot, zoom.zoomOutButton)
        assertZoom(0.67, "67%")
    }

    @Test
    fun `documents have no Fit button and open at 100 percent`() {
        assertNull(zoom.fitButton)
        assertFalse(fx { zoom.fit })
        assertEquals(3, fx { zoom.node.lookupAll(".zoom-button").size }, "zoom out, zoom in and 100%")
        assertZoom(1.0, "100%")
    }

    @Test
    fun `Shortcut with the wheel at the web view zooms by a tenth per 40, a plain wheel does not`() {
        // The web view scrolls with a plain wheel itself, so whether it is consumed says nothing.
        fire(webView, scrollEvent(40.0, shortcut = false))
        assertZoom(1.0, "100%")

        assertTrue(fire(webView, scrollEvent(40.0, shortcut = true)), "consumed")
        assertEquals(1.1, fx { zoom.scale }, 1e-9)
        assertEquals(fx { zoom.scale }, fx { webView.zoom }, 1e-9)

        fire(webView, scrollEvent(-40.0, shortcut = true))
        assertZoom(1.0, "100%")
    }

    @Test
    fun `zooming out stops at 10 percent and in at 1600 percent`(robot: FxRobot) {
        repeat(10) { press(robot, zoom.zoomOutButton) }
        assertZoom(0.1, "10%")
        key(KeyCode.MINUS)
        fire(webView, scrollEvent(-4000.0, shortcut = true))
        assertZoom(0.1, "10%")

        key(KeyCode.DIGIT0)
        repeat(12) { press(robot, zoom.zoomInButton) }
        assertZoom(16.0, "1600%")
        key(KeyCode.EQUALS)
        fire(webView, scrollEvent(4000.0, shortcut = true))
        assertZoom(16.0, "1600%")
    }

    @Test
    fun `a pinch zooms and stays within 10 percent and 1600 percent`() {
        assertTrue(fire(webView, pinchEvent(1.5)), "consumed")
        assertZoom(1.5, "150%")
        fire(webView, pinchEvent(0.0001))
        assertZoom(0.1, "10%")
        fire(webView, pinchEvent(1_000_000.0))
        assertZoom(16.0, "1600%")
    }

    @Test
    fun `a document does not report its zoom, so what the page says is ignored`() {
        fx { zoom.zoomed(2.0, fitted = false) }

        assertZoom(1.0, "100%")
        assertEquals(0, changes, "nothing changed")
    }

    @Test
    fun `the change callback runs for a change of the zoom only`(robot: FxRobot) {
        robot.interact { zoom.actualSize() }
        assertEquals(0, changes, "100% at 100% is no change")

        robot.interact { zoom.zoomIn() }
        assertEquals(1, changes)
        robot.interact { zoom.actualSize() }
        assertEquals(2, changes)
    }
}
