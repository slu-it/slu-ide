package dev.sluide.jshell.ui

import dev.sluide.jshell.EvalEvent
import javafx.application.Platform
import javafx.event.Event
import javafx.geometry.Point2D
import javafx.geometry.VerticalDirection
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.robot.Robot
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The transcript pane follows new text while the user leaves it at the end. A UI test: the decision reads Flowless's
 * layout, and the inputs are the scroll bar's, the flow's and the area's own events.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TranscriptPaneFollowUiTest : TranscriptPaneUiFixture() {
    @Start
    fun start(stage: Stage) = startPane(stage)

    @Test
    fun `the view follows new text at the bottom`() {
        onFx { pane.accept(EvalEvent.Output(lines(100), false)) }
        assertEquals(100, pane.area.paragraphs.size)
        assertTrue(atBottom(), "does not follow at the bottom")

        onFx { pane.accept(EvalEvent.Output(lines(20), false)) }
        assertTrue(atBottom(), "does not follow the second output")
    }

    @Test
    fun `output posted in bursts from another thread keeps the view at the bottom`() {
        val total = 1_200
        val posted = CountDownLatch(1)
        thread {
            for (i in 1..total) {
                Platform.runLater { pane.accept(EvalEvent.Output("line $i\n", false)) }
                // bursts of five events with a pause between them, like the session's reader thread delivers output
                if (i % 5 == 0) Thread.sleep(3)
            }
            Platform.runLater { posted.countDown() }
        }
        assertTrue(posted.await(60, TimeUnit.SECONDS))
        waitUntil { atBottom() }

        assertEquals(total, pane.area.paragraphs.size)
        assertTrue(atBottom(), "last visible ${lastVisible()} of $total")
    }

    @Test
    fun `the view stays when the user scrolled up and follows again after the user scrolled back down`() {
        onFx { pane.accept(EvalEvent.Output(lines(100), false)) }
        assertTrue(atBottom())

        wheel(30, VerticalDirection.UP)
        val lastVisible = lastVisible()
        assertTrue(lastVisible < 99, "the wheel did not scroll up")
        onFx { pane.accept(EvalEvent.Output(lines(20), false)) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(lastVisible, lastVisible(), "moved although the user scrolled up")

        wheel(500, VerticalDirection.DOWN)
        assertTrue(atBottom(), "the wheel did not scroll to the bottom")
        onFx { pane.accept(EvalEvent.Output(lines(20), false)) }
        waitUntil { atBottom() }
        assertTrue(atBottom(), "does not follow after the user scrolled back to the bottom")
    }

    @Test
    fun `one wheel notch up from the end stops following`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        assertTrue(atBottom())

        wheel(1, VerticalDirection.UP)
        val lastVisible = lastVisible()
        assertTrue(lastVisible < 199, "the wheel did not scroll up")
        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(lastVisible, lastVisible(), "moved although the user scrolled up")
    }

    @Test
    fun `scrolling down exactly onto the end follows again`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        wheel(1, VerticalDirection.UP)
        assertTrue(!atBottom(), "the wheel did not scroll up")
        wheel(1, VerticalDirection.DOWN)
        assertTrue(atBottom(), "the wheel did not scroll back to the end")

        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }
        waitUntil { atBottom() }

        assertTrue(atBottom(), "last visible ${lastVisible()} of 204")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a wheel notch up while new text waits for the scroll to the end scrolls up from the new end`(
        scrollToEndRan: Boolean,
    ) {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        assertTrue(atBottom())
        val flow = flow()

        // A fired event: only it can arrive at a set place among the pane's own runnables, here after the flush of the
        // new text and before or after its scroll to the end, both before the next layout.
        onFx {
            pane.accept(EvalEvent.Output(lines(50), false))
            Platform.runLater {
                if (scrollToEndRan) {
                    Platform.runLater { Event.fireEvent(flow, notch(40.0)) }
                } else {
                    Event.fireEvent(flow, notch(40.0))
                }
            }
        }
        val lastVisible = lastVisible()
        assertTrue(lastVisible in 240..248, "last visible $lastVisible of 249")
        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(lastVisible, lastVisible(), "moved although the user scrolled up")
    }

    @Test
    fun `steps of a few pixels up from the end while output streams stop following`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        assertTrue(atBottom())
        val flow = flow()

        // Fired events: only they can arrive at a set place among the pane's own runnables, here each step with output
        // queued behind it, as a slow trackpad gesture or a high-resolution wheel sends them while a snippet prints.
        repeat(5) {
            onFx {
                Event.fireEvent(flow, notch(3.0))
                pane.accept(EvalEvent.Output("more\n", false))
            }
        }

        assertEquals(199, lastVisible(), "pulled back to the end although the user scrolled up")
    }

    @Test
    fun `scrolling down onto the end of wrapped paragraphs of mixed heights follows again`() {
        // With paragraphs of mixed heights Flowless's estimates of offset and total height miss the end it scrolls to by
        // a fraction of a pixel: with this content by 0.15 px, which the slack of the end check absorbs.
        val random = java.util.Random(3)
        val text =
            (0 until 400).joinToString("") {
                "x".repeat(1 + random.nextInt(if (random.nextInt(10) == 0) 400 else 30)) + "$it\n"
            }
        onFx { pane.accept(EvalEvent.Output(text, false)) }
        val flow = flow()

        // Fired events: wheel steps of a set size, each decided before the next.
        repeat(30) { onFx { Event.fireEvent(flow, notch(40.0)) } }
        assertTrue(!atBottom(), "the wheel did not scroll up")
        repeat(60) { onFx { Event.fireEvent(flow, notch(-40.0)) } }
        assertTrue(atBottom(), "the wheel did not scroll back to the end")
        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }
        waitUntil { atBottom() }

        assertTrue(atBottom(), "last visible ${lastVisible()} of 404")
    }

    @Test
    fun `a wheel notch down at the end before new text is shown keeps following`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        assertTrue(atBottom())
        val flow = flow()

        // A fired event: only it can arrive at a set place among the pane's own runnables, here after new text was
        // queued and before it is shown.
        onFx {
            pane.accept(EvalEvent.Output(lines(50), false))
            Event.fireEvent(flow, notch(-40.0))
        }
        waitUntil { atBottom() }

        assertTrue(atBottom(), "last visible ${lastVisible()} of 249")
    }

    @Test
    fun `after a scroll up, Clear follows the new text, also when the scroll is not decided yet`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        val flow = flow()
        // Fired events: only they can arrive at a set place among the pane's own runnables, the second one together with
        // the Clear, before the scroll it causes is decided.
        onFx { Event.fireEvent(flow, notch(40.0)) }
        assertTrue(!atBottom(), "the wheel did not scroll up")

        onFx {
            Event.fireEvent(flow, notch(40.0))
            pane.clear()
        }
        onFx { pane.accept(EvalEvent.Output(lines(100), false)) }
        waitUntil { atBottom() }

        assertTrue(atBottom(), "last visible ${lastVisible()} of 99")
    }

    @Test
    fun `a taller view that shows the end follows again`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        wheel(1, VerticalDirection.UP)
        assertTrue(!atBottom(), "the wheel did not scroll up")

        onFx { stage.height += 100 }
        assertTrue(atBottom(), "the taller view does not show the end")
        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }
        waitUntil { atBottom() }

        assertTrue(atBottom(), "last visible ${lastVisible()} of 204")
    }

    @Test
    fun `a shorter view keeps following`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        assertTrue(atBottom())

        onFx { stage.height -= 100 }
        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }
        waitUntil { atBottom() }

        assertTrue(atBottom(), "last visible ${lastVisible()} of 204")
    }

    @Test
    fun `a scroll key moves the view and stops following`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        assertTrue(atBottom())

        // A fired key: the pane reacts to the key event, whichever control has the focus.
        onFx { Event.fireEvent(pane.area, key(KeyCode.PAGE_UP)) }
        val lastVisible = lastVisible()
        assertTrue(lastVisible < 199, "the key did not scroll up")
        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }

        assertEquals(lastVisible, lastVisible(), "moved although the user scrolled up")
    }

    private fun key(code: KeyCode) =
        KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, code == KeyCode.SHIFT, false, false, false)

    /** A following view whose end a shorter window hid: an input that is taken for a scroll stops its following. */
    private fun followingWithTheEndHidden() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        onFx { stage.height -= 100 }
        assertTrue(!atBottom(), "the shorter view still shows the end")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Shift", "a move over the scroll bar", "a press in the text", "a move over the text"])
    fun `an input that is no scroll leaves a following view where it is`(input: String) {
        followingWithTheEndHidden()
        val lastVisible = lastVisible()

        // Fired events: each reaches the pane's filters as real input would, without moving the view by itself.
        onFx {
            when (input) {
                "Shift" -> {
                    Event.fireEvent(pane.area, key(KeyCode.SHIFT))
                }

                "a move over the scroll bar" -> {
                    val bar = verticalBar()
                    val point = bar.localToScreen(bar.width / 2, bar.height / 2)
                    Event.fireEvent(bar, mouse(MouseEvent.MOUSE_MOVED, point))
                }

                "a press in the text" -> {
                    Event.fireEvent(pane.area, mouse(MouseEvent.MOUSE_PRESSED, centerOf(lastVisible - 2)))
                }

                else -> {
                    Event.fireEvent(pane.area, mouse(MouseEvent.MOUSE_MOVED, centerOf(lastVisible - 2)))
                }
            }
        }
        assertEquals(lastVisible, lastVisible(), "the input moved the view")
        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }
        waitUntil { atBottom() }

        assertTrue(atBottom(), "last visible ${lastVisible()} of 204")
    }

    @ParameterizedTest
    @ValueSource(strings = ["one step far up", "a step up and a smaller one down before the decision"])
    fun `a scroll that ends above the end stops following`(steps: String) {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        assertTrue(atBottom())
        val flow = flow()

        // Fired events: steps of a set size, together in one FX event where the case needs it.
        onFx {
            if (steps == "one step far up") {
                Event.fireEvent(flow, notch(400.0))
            } else {
                Event.fireEvent(flow, notch(6.0))
                Event.fireEvent(flow, notch(-3.0))
            }
        }
        val lastVisible = lastVisible()
        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }

        assertEquals(lastVisible, lastVisible(), "moved although the user scrolled up")
    }

    @Test
    fun `two wheel notches in one FX event both scroll`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        val flow = flow()
        onFx { Event.fireEvent(flow, notch(40.0)) }
        val afterOne = lastVisible()
        onFx { Event.fireEvent(flow, notch(-40.0)) }
        assertTrue(atBottom())

        // Fired events: only they arrive together in one FX event, before a layout.
        onFx {
            Event.fireEvent(flow, notch(40.0))
            Event.fireEvent(flow, notch(40.0))
        }

        assertTrue(lastVisible() < afterOne, "last visible ${lastVisible()}, after one notch $afterOne")
    }

    @Test
    fun `a scroll down that stops within the slack above the end follows again`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        val flow = flow()
        // Fired events: steps of a set size; the second one stops 8 pixels above the end, the slack of the end check.
        onFx { Event.fireEvent(flow, notch(48.0)) }
        onFx { Event.fireEvent(flow, notch(-40.0)) }
        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }
        waitUntil { atBottom() }

        assertTrue(atBottom(), "last visible ${lastVisible()} of 204")
    }

    @Test
    fun `a wheel notch while scrolled up and new text waits for its flush scrolls from where the view is`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        val flow = flow()
        onFx { Event.fireEvent(flow, notch(400.0)) }
        val before = lastVisible()

        // A fired event: only it arrives after the flush of new text and before the runnable that flush queued.
        onFx {
            pane.accept(EvalEvent.Output(lines(50), false))
            Platform.runLater { Event.fireEvent(flow, notch(40.0)) }
        }

        assertTrue(lastVisible() in before - 5 until before, "last visible ${lastVisible()}, before $before")
    }

    @Test
    fun `a wheel notch down in a following view whose end is hidden scrolls from where the view is`() {
        followingWithTheEndHidden()
        val before = lastVisible()

        // A fired event: a step of a set size, which stops short of the end.
        onFx { Event.fireEvent(flow(), notch(-40.0)) }

        assertTrue(lastVisible() in before + 1..before + 3, "last visible ${lastVisible()}, before $before")
    }

    @Test
    fun `dragging the scroll bar's thumb onto the end follows again`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        wheel(30, VerticalDirection.UP)
        assertTrue(lastVisible() < 199, "the wheel did not scroll up")
        val bar = verticalBar()
        val thumb = screenBounds(bar.lookup(".thumb"))
        val track = screenBounds(bar.lookup(".track"))

        val robot = FxRobot()
        // A drag of the thumb is real input: only it makes the scroll bar's own press, drag and release events.
        robot.drag(Point2D(thumb.centerX, thumb.centerY), MouseButton.PRIMARY)
        WaitForAsyncUtils.waitForFxEvents()
        // One jump of the mouse below the track, so that only the last drag event reaches the end; the robot's moves
        // come in steps of a pixel, of which the ones after the end would hide a decision made one event late.
        onFx { Robot().mouseMove(thumb.centerX, track.maxY + 40) }
        waitUntil { atBottom() }
        // A drag of the thumb is real input: only it makes the scroll bar's own press, drag and release events.
        robot.drop()
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(atBottom(), "the drag did not reach the end")
        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }
        waitUntil { atBottom() }

        assertTrue(atBottom(), "last visible ${lastVisible()} of 204")
    }

    @Test
    fun `holding the mouse on the scroll bar's track until the end follows again`() {
        onFx { pane.accept(EvalEvent.Output(lines(200), false)) }
        wheel(30, VerticalDirection.UP)
        assertTrue(lastVisible() < 199, "the wheel did not scroll up")
        val bar = verticalBar()
        val thumb = screenBounds(bar.lookup(".thumb"))
        val track = screenBounds(bar.lookup(".track"))

        val robot = FxRobot()
        // Holding the mouse on the track is real input: the scroll bar repeats its scrolling without further events.
        robot.moveTo(Point2D(thumb.centerX, track.maxY - 2))
        // Holding the mouse on the track is real input: the scroll bar repeats its scrolling without further events.
        robot.press(MouseButton.PRIMARY)
        waitUntil { atBottom() }
        // Holding the mouse on the track is real input: the scroll bar repeats its scrolling without further events.
        robot.release(MouseButton.PRIMARY)
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(atBottom(), "holding did not reach the end")
        onFx { pane.accept(EvalEvent.Output(lines(5), false)) }
        waitUntil { atBottom() }

        assertTrue(atBottom(), "last visible ${lastVisible()} of 204")
    }
}
