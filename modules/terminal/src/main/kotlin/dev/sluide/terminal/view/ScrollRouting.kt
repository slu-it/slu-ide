package dev.sluide.terminal.view

import com.jediterm.terminal.emulator.mouse.MouseMode
import kotlin.math.abs

/** What a terminal does with a scroll event, see [ScrollRouting.route]. */
internal sealed interface ScrollAction {
    /** The event is ignored and not consumed. */
    data object None : ScrollAction

    /** The program asked for mouse reports: send [notches] wheel reports, each meaning [SCROLL_LINES] lines. */
    data class Report(
        val notches: Int,
        val up: Boolean,
    ) : ScrollAction

    /** A full-screen program without mouse reports: send a wheel event worth [lines] arrow keys (maybe none). */
    data class ArrowKeys(
        val lines: Int,
        val up: Boolean,
    ) : ScrollAction

    /** Scroll the scrollback by [lines], negative towards the history (up); maybe none. */
    data class Scrollback(
        val lines: Int,
    ) : ScrollAction
}

/**
 * Decides what a scroll event of a terminal means: which [speeds] apply (trackpad or wheel) and whether it goes to
 * the program as mouse reports, as arrow keys, or scrolls the scrollback, keeping the fractions of steps between
 * events.
 */
internal class ScrollRouting(
    var speeds: ScrollSpeeds = ScrollSpeeds(),
) {
    /** Fractions of lines scrolled at speeds other than 100 %, for the scrollback and for arrow keys. */
    private val scrollLines = ScrollAccumulator()

    /** Fractions of wheel notches, for programs that get one mouse report per notch. */
    private val scrollNotches = ScrollAccumulator(perEvent = 1)

    /** Whether a scroll gesture (fingers on a trackpad) is going on. */
    var gesture = false

    fun route(
        deltaY: Double,
        inertia: Boolean,
        touchCount: Int,
        shift: Boolean,
        mouseMode: MouseMode,
        fullScreen: Boolean,
    ): ScrollAction {
        if (deltaY == 0.0) return ScrollAction.None
        val up = deltaY > 0
        val percent = if (isTrackpad(inertia, touchCount)) speeds.trackpad else speeds.wheel
        if (TerminalMouse.reportsWheel(mouseMode, shift)) {
            return ScrollAction.Report(abs(scrollNotches.add(up, percent)), up)
        }
        val lines = scrollLines.add(up, percent)
        return if (fullScreen) ScrollAction.ArrowKeys(abs(lines), up) else ScrollAction.Scrollback(lines)
    }

    /** Drops the fractions left over. */
    fun reset() {
        scrollLines.reset()
        scrollNotches.reset()
    }

    /**
     * Whether an event comes from a trackpad rather than a mouse wheel. Relies on what macOS sends: trackpad scroll
     * events between SCROLL_STARTED and SCROLL_FINISHED or with touch points, and after the fingers lift, inertia
     * events; a Magic Mouse sends them too, so it counts as a trackpad. On Linux and Windows touchpads send plain
     * wheel events and count as wheels.
     */
    private fun isTrackpad(
        inertia: Boolean,
        touchCount: Int,
    ) = gesture || inertia || touchCount > 0
}
