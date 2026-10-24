package dev.sluide.terminal.view

import com.jediterm.terminal.emulator.mouse.MouseMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollRoutingTest {
    private fun ScrollRouting.scroll(
        deltaY: Double = 40.0,
        inertia: Boolean = false,
        touchCount: Int = 0,
        shift: Boolean = false,
        mouseMode: MouseMode = MouseMode.MOUSE_REPORTING_NONE,
        fullScreen: Boolean = false,
    ) = route(deltaY, inertia, touchCount, shift, mouseMode, fullScreen)

    @Test
    fun `an event without a vertical delta is ignored`() {
        assertEquals(ScrollAction.None, ScrollRouting().scroll(deltaY = 0.0))
    }

    @Test
    fun `the scrollback scrolls three lines per wheel step towards the history when going up`() {
        val routing = ScrollRouting()
        assertEquals(ScrollAction.Scrollback(-3), routing.scroll())
        assertEquals(ScrollAction.Scrollback(3), routing.scroll(deltaY = -40.0))
    }

    @Test
    fun `trackpad events scroll at the trackpad speed, wheel events at the wheel speed`() {
        val routing = ScrollRouting(ScrollSpeeds(trackpad = 200, wheel = 50))
        assertEquals(ScrollAction.Scrollback(-6), routing.scroll(deltaY = 2.0, touchCount = 2), "touch points")
        assertEquals(ScrollAction.Scrollback(-6), routing.scroll(deltaY = 2.0, inertia = true), "inertia")

        routing.gesture = true
        assertEquals(ScrollAction.Scrollback(-6), routing.scroll(deltaY = 2.0), "between start and end of a gesture")
        routing.gesture = false
        assertEquals(ScrollAction.Scrollback(-1), routing.scroll(deltaY = 2.0), "a wheel step again")
    }

    @Test
    fun `speeds can change later`() {
        val routing = ScrollRouting()
        routing.speeds = ScrollSpeeds(wheel = 200)
        assertEquals(ScrollAction.Scrollback(-6), routing.scroll())
    }

    @Test
    fun `fractions of lines carry over and reset drops them`() {
        val routing = ScrollRouting(ScrollSpeeds(wheel = 50))
        assertEquals(ScrollAction.Scrollback(-1), routing.scroll())
        assertEquals(ScrollAction.Scrollback(-2), routing.scroll())
        assertEquals(ScrollAction.Scrollback(-1), routing.scroll())
        routing.reset()
        assertEquals(ScrollAction.Scrollback(-1), routing.scroll())
    }

    @Test
    fun `programs that ask for mouse reports get one report per notch, at 50 percent every second step`() {
        val routing = ScrollRouting(ScrollSpeeds(wheel = 50))
        val mode = MouseMode.MOUSE_REPORTING_NORMAL
        assertEquals(ScrollAction.Report(0, up = true), routing.scroll(mouseMode = mode, fullScreen = true))
        assertEquals(ScrollAction.Report(1, up = true), routing.scroll(mouseMode = mode, fullScreen = true))
        assertEquals(
            ScrollAction.Report(0, up = false),
            routing.scroll(deltaY = -40.0, mouseMode = mode),
            "new direction",
        )
    }

    @Test
    fun `full-screen programs without mouse reports get an arrow key per line, at 50 percent 1, 2, 1, 2`() {
        val routing = ScrollRouting(ScrollSpeeds(wheel = 50))
        val keys = List(4) { routing.scroll(deltaY = -40.0, fullScreen = true) }
        assertEquals(List(4) { ScrollAction.ArrowKeys(listOf(1, 2, 1, 2)[it], up = false) }, keys)
        assertEquals(ScrollAction.ArrowKeys(3, up = true), ScrollRouting().scroll(fullScreen = true))
    }

    @Test
    fun `Shift keeps the wheel for the history even when the program asks for mouse reports`() {
        val routing = ScrollRouting()
        val mode = MouseMode.MOUSE_REPORTING_NORMAL
        assertEquals(ScrollAction.Report(1, up = true), routing.scroll(mouseMode = mode))
        assertEquals(ScrollAction.Scrollback(-3), routing.scroll(mouseMode = mode, shift = true))
    }
}
