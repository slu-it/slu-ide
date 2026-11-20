package dev.sluide.jshell.ui

import javafx.application.Platform
import javafx.event.EventType
import javafx.geometry.Orientation
import javafx.geometry.Point2D
import javafx.geometry.VerticalDirection
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ScrollBar
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualFlow
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/**
 * A [TranscriptPane] alone in a 420×220 window, and the helpers that drive it. ApplicationExtension only finds `@Start`
 * on the test class itself: each topic class declares one that calls [startPane].
 */
abstract class TranscriptPaneUiFixture {
    internal lateinit var stage: Stage
    internal lateinit var pane: TranscriptPane
    internal val jumps = mutableListOf<Int>()
    private val wheelEvents = AtomicInteger()

    internal fun startPane(stage: Stage) {
        this.stage = stage
        pane = TranscriptPane { jumps += it }
        stage.scene = Scene(pane.node as Parent, 420.0, 220.0)
        stage.scene.addEventFilter(ScrollEvent.SCROLL) { wheelEvents.incrementAndGet() }
        // the tests share one stage, and some change its height
        stage.sizeToScene()
        stage.show()
    }

    internal fun onFx(block: () -> Unit) {
        val done = CountDownLatch(1)
        Platform.runLater {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        WaitForAsyncUtils.waitForFxEvents()
    }

    internal fun <T> onFxGet(block: () -> T): T {
        var result: Result<T>? = null
        onFx { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    internal fun lines(count: Int) = (1..count).joinToString("") { "line $it\n" }

    internal fun centerOf(paragraph: Int): Point2D {
        val bounds = pane.area.getParagraphBoundsOnScreen(paragraph).get()
        return Point2D(bounds.minX + 40, (bounds.minY + bounds.maxY) / 2)
    }

    /** A mouse event on the area at [screen]; a click is still since its press unless [stillSincePress] says not. */
    internal fun mouse(
        type: EventType<MouseEvent>,
        screen: Point2D,
        button: MouseButton = MouseButton.PRIMARY,
        stillSincePress: Boolean = true,
    ): MouseEvent {
        val local = pane.area.screenToLocal(screen)
        return MouseEvent(
            type,
            local.x,
            local.y,
            screen.x,
            screen.y,
            button,
            1,
            false,
            false,
            false,
            false,
            type == MouseEvent.MOUSE_CLICKED && button == MouseButton.PRIMARY,
            false,
            type == MouseEvent.MOUSE_CLICKED && button == MouseButton.SECONDARY,
            false,
            false,
            stillSincePress,
            null,
        )
    }

    internal fun lastVisible() = onFxGet { pane.area.lastVisibleParToAllParIndex() }

    internal fun atBottom() = onFxGet { pane.area.lastVisibleParToAllParIndex() == pane.area.paragraphs.size - 1 }

    internal fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition() && System.nanoTime() < deadline) WaitForAsyncUtils.waitForFxEvents()
    }

    internal fun wheel(
        amount: Int,
        direction: VerticalDirection,
    ) {
        val bounds = pane.area.localToScreen(pane.area.boundsInLocal)
        val robot = FxRobot()
        // The mouse wheel is real input: only it tells a scroll by the user from the pane's own scrolling.
        robot.moveTo(Point2D(bounds.centerX, bounds.centerY))
        val before = wheelEvents.get()
        // The mouse wheel is real input: only it tells a scroll by the user from the pane's own scrolling.
        robot.scroll(amount, direction)
        // the wheel events reach the FX thread after the robot returned
        waitUntil { wheelEvents.get() >= before + amount }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** The area's flow of paragraph cells, which handles the wheel. */
    internal fun flow(): Node = pane.area.childrenUnmodifiable.first { it is VirtualFlow<*, *> }

    /** A scroll of [deltaY] pixels, up for a positive one; 40 is one wheel notch. */
    internal fun notch(deltaY: Double): ScrollEvent =
        ScrollEvent(
            ScrollEvent.SCROLL,
            100.0,
            100.0,
            100.0,
            100.0,
            false,
            false,
            false,
            false,
            false,
            false,
            0.0,
            deltaY,
            0.0,
            deltaY,
            ScrollEvent.HorizontalTextScrollUnits.NONE,
            0.0,
            ScrollEvent.VerticalTextScrollUnits.LINES,
            deltaY / 40,
            0,
            null,
        )

    internal fun verticalBar() =
        (pane.node as Parent)
            .childrenUnmodifiable
            .filterIsInstance<ScrollBar>()
            .first { it.orientation == Orientation.VERTICAL }

    internal fun screenBounds(node: Node) = onFxGet { node.localToScreen(node.boundsInLocal) }
}
