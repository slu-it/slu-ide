package dev.sluide.games.common

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class RealtimeSessionTest {
    private var now = 1_000_000L
    private var changes = 0
    private var steps = 0

    private fun session(focused: Boolean = true): RealtimeSession =
        RealtimeSession({ if (it) "focused" else "unfocused" }).also {
            if (focused) it.focusChanged(true)
            it.onChange = { changes++ }
            it.onStep = { steps++ }
        }

    private fun RealtimeSession.advance(nanos: Long) {
        now += nanos
        tick(now)
    }

    /** READY to PLAYING; the tick that ends the countdown runs no step. */
    private fun RealtimeSession.toPlaying() {
        startOrResume()
        advance(0)
        advance(COUNTDOWN)
        assertEquals(SessionState.PLAYING, state)
        steps = 0
    }

    private fun RealtimeSession.toState(target: SessionState) {
        when (target) {
            SessionState.READY -> {
                Unit
            }

            SessionState.COUNTDOWN -> {
                startOrResume()
            }

            SessionState.PLAYING -> {
                toPlaying()
            }

            SessionState.PAUSED -> {
                toPlaying()
                togglePause()
            }

            SessionState.OVER -> {
                toPlaying()
                gameOver()
            }
        }
    }

    @Test
    fun `starts READY and unfocused`() {
        val session = session(focused = false)

        assertEquals(SessionState.READY, session.state)
        assertFalse(session.isFocused)
        assertEquals(0, session.countdown)
        assertFalse(session.needsAnimation)
        assertFalse(session.canPause)
    }

    @Test
    fun `startOrResume acts in READY and PAUSED only`() {
        val acting = listOf(SessionState.READY, SessionState.PAUSED)
        SessionState.entries.forEach { target ->
            val session = session()
            session.toState(target)
            changes = 0

            val acted = session.startOrResume()

            assertEquals(target in acting, acted, "$target")
            assertEquals(if (acted) 1 else 0, changes, "$target")
            assertEquals(if (acted) SessionState.COUNTDOWN else target, session.state, "$target")
        }
    }

    @Test
    fun `the countdown shows 3, 2, 1 for half a second each and then plays`() {
        val session = session()
        session.startOrResume()
        // The first tick only stores the base, however late it comes.
        session.advance(10_000_000_000L)
        assertEquals(3, session.countdown)

        session.advance(DIGIT - 1)
        assertEquals(3, session.countdown)
        session.advance(1)
        assertEquals(2, session.countdown)
        session.advance(DIGIT)
        assertEquals(1, session.countdown)
        session.advance(DIGIT - 1)
        assertEquals(SessionState.COUNTDOWN, session.state)
        session.advance(1)
        assertEquals(SessionState.PLAYING, session.state)
        assertEquals(0, session.countdown)
    }

    @Test
    fun `the end of the countdown leaves no time for steps`() {
        val session = session()
        session.startOrResume()
        session.advance(0)

        session.advance(COUNTDOWN + 4 * STEP)

        assertEquals(SessionState.PLAYING, session.state)
        assertEquals(0, steps)
        session.advance(STEP)
        assertEquals(1, steps)
    }

    @ParameterizedTest
    @EnumSource(value = SessionState::class, names = ["COUNTDOWN", "PLAYING"])
    fun `togglePause pauses while counting down or playing`(target: SessionState) {
        val session = session()
        session.toState(target)

        session.togglePause()

        assertEquals(SessionState.PAUSED, session.state)
        assertEquals(PauseReason.MANUAL, session.pauseReason)
        assertEquals("Resume", session.pauseButtonText)
        assertTrue(session.canPause)
    }

    @Test
    fun `togglePause resumes with the countdown at 3 and does nothing in READY and OVER`() {
        val session = session()
        session.toPlaying()
        session.togglePause()

        session.togglePause()

        assertEquals(SessionState.COUNTDOWN, session.state)
        assertNull(session.pauseReason)
        assertEquals(3, session.countdown)
        assertEquals("Pause", session.pauseButtonText)

        listOf(SessionState.READY, SessionState.OVER).forEach { target ->
            val other = session()
            other.toState(target)
            other.togglePause()
            assertEquals(target, other.state)
        }
    }

    @Test
    fun `a pause during the countdown restarts it at 3 on resume`() {
        val session = session()
        session.startOrResume()
        session.advance(0)
        session.advance(DIGIT + 1)
        assertEquals(2, session.countdown)
        session.togglePause()

        session.startOrResume()

        assertEquals(3, session.countdown)
    }

    @ParameterizedTest
    @EnumSource(value = SessionState::class, names = ["COUNTDOWN", "PLAYING"])
    fun `losing the focus pauses while counting down or playing`(target: SessionState) {
        val session = session()
        session.toState(target)
        changes = 0

        session.focusChanged(false)

        assertEquals(SessionState.PAUSED, session.state)
        assertEquals(PauseReason.FOCUS, session.pauseReason)
        assertEquals(1, changes)
    }

    @ParameterizedTest
    @EnumSource(value = SessionState::class, names = ["READY", "OVER"])
    fun `losing the focus does not pause READY and OVER`(target: SessionState) {
        val session = session()
        session.toState(target)

        session.focusChanged(false)

        assertEquals(target, session.state)
    }

    @Test
    fun `losing the focus is announced in every state, before the pause, and only on a change`() {
        SessionState.entries.forEach { target ->
            val session = session()
            session.toState(target)
            val seen = mutableListOf<SessionState>()
            session.onFocusLost = { seen += session.state }

            session.focusChanged(false)
            session.focusChanged(false)
            session.focusChanged(true)

            // Called once, while the state was still the one before the pause.
            assertEquals(listOf(target), seen, "$target")
        }
    }

    @Test
    fun `regaining the focus does not resume`() {
        val session = session()
        session.toPlaying()
        session.focusChanged(false)

        session.focusChanged(true)

        assertEquals(SessionState.PAUSED, session.state)
        assertEquals(PauseReason.FOCUS, session.pauseReason)
    }

    @Test
    fun `a focus change to the same value does nothing`() {
        val session = session()
        session.toState(SessionState.PAUSED)
        changes = 0

        session.focusChanged(true)

        assertEquals(0, changes)
    }

    @Test
    fun `the hint follows the focus and changing it notifies only while paused`() {
        val session = session(focused = false)
        assertEquals("unfocused", session.pauseHint)
        session.focusChanged(true)
        assertEquals("focused", session.pauseHint)
        assertEquals(0, changes)

        session.toPlaying()
        changes = 0
        session.togglePause()
        assertEquals(1, changes)
        session.focusChanged(false)
        assertEquals(2, changes)
        assertEquals(PauseReason.MANUAL, session.pauseReason)
        session.focusChanged(true)
        assertEquals(3, changes)
    }

    @Test
    fun `steps run once per sixtieth of a second and keep the remainder`() {
        val session = session()
        session.toPlaying()

        session.advance(STEP / 2)
        assertEquals(0, steps)
        session.advance(STEP / 2 + 1)
        assertEquals(1, steps)
        session.advance(2 * STEP)
        assertEquals(3, steps)
    }

    @Test
    fun `at most five steps run per tick and the leftover time is dropped`() {
        val session = session()
        session.toPlaying()

        session.advance(20 * STEP)
        assertEquals(5, steps)

        session.advance(STEP)
        assertEquals(6, steps)
    }

    @Test
    fun `the step size and the cap are parameters`() {
        val session = RealtimeSession({ "" }, stepNanos = 100, maxStepsPerTick = 2, countdownDigitNanos = 10)
        session.onStep = { steps++ }
        session.startOrResume()
        session.tick(0)
        session.tick(30)
        assertEquals(SessionState.PLAYING, session.state)

        session.tick(30 + 1_000)

        assertEquals(2, steps)
    }

    @Test
    fun `time spent paused never turns into steps`() {
        val session = session()
        session.toPlaying()
        session.togglePause()
        session.advance(10_000_000_000L)
        session.startOrResume()

        // The first tick of the new countdown only stores the base: 10 s of pause do not end it.
        session.advance(10_000_000_000L)
        assertEquals(SessionState.COUNTDOWN, session.state)
        session.advance(COUNTDOWN)
        assertEquals(SessionState.PLAYING, session.state)
        assertEquals(0, steps)
        session.advance(STEP)
        assertEquals(1, steps)
    }

    @Test
    fun `a partial step does not survive a pause`() {
        val session = session()
        session.toPlaying()
        session.advance(STEP - 1)
        session.togglePause()
        session.startOrResume()
        session.advance(0)
        session.advance(COUNTDOWN)

        session.advance(2)

        assertEquals(0, steps)
    }

    @Test
    fun `a clock that runs backwards adds no time`() {
        val session = session()
        session.toPlaying()

        session.advance(-5 * STEP)
        assertEquals(0, steps)
        session.advance(STEP)
        assertEquals(1, steps)
    }

    @Test
    fun `stepping stops as soon as a step ends the game`() {
        val session = session()
        session.toPlaying()
        session.onStep = {
            steps++
            session.gameOver()
        }

        session.advance(5 * STEP)

        assertEquals(1, steps)
        assertEquals(SessionState.OVER, session.state)
        assertFalse(session.needsAnimation)
    }

    @Test
    fun `reset and gameOver change the state and notify`() {
        val session = session()
        session.toState(SessionState.PAUSED)
        changes = 0

        session.reset()
        assertEquals(SessionState.READY, session.state)
        assertNull(session.pauseReason)
        assertEquals(1, changes)

        session.gameOver()
        assertEquals(SessionState.OVER, session.state)
        assertEquals(2, changes)
    }

    @Test
    fun `reset forgets the time base`() {
        val session = session()
        session.toPlaying()
        session.advance(STEP / 2)
        session.reset()
        session.startOrResume()

        session.advance(10_000_000_000L)

        assertEquals(SessionState.COUNTDOWN, session.state)
        assertEquals(3, session.countdown)
    }

    private companion object {
        const val STEP = 16_666_667L
        const val DIGIT = 500_000_000L
        const val COUNTDOWN = 3 * DIGIT
    }
}
