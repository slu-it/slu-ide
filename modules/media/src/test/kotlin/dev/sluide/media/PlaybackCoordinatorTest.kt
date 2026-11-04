package dev.sluide.media

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackCoordinatorTest {
    private val coordinator = PlaybackCoordinator()

    @Test
    fun `starting another playback pauses the one playing`() {
        val first = FakePlayback()
        val second = FakePlayback()
        first.play()
        coordinator.started(first)

        second.play()
        coordinator.started(second)

        assertEquals(listOf("play", "pause"), first.calls)
        assertEquals(PlaybackStatus.PAUSED, first.status.get())
        assertEquals(listOf("play"), second.calls)
    }

    @Test
    fun `starting the same playback again does not pause it`() {
        val playback = FakePlayback()
        playback.play()
        coordinator.started(playback)

        coordinator.started(playback)

        assertEquals(listOf("play"), playback.calls)
    }

    @Test
    fun `a previous playback is paused whatever its status`() {
        // play() of a loading playback is queued until it is ready: it must not start after the next one.
        val first = FakePlayback(initialStatus = PlaybackStatus.LOADING)
        coordinator.started(first)

        coordinator.started(FakePlayback())

        assertEquals(listOf("pause"), first.calls)
    }

    @Test
    fun `a disposed playback is forgotten`() {
        val first = FakePlayback()
        first.play()
        coordinator.started(first)

        coordinator.disposed(first)
        coordinator.started(FakePlayback())

        assertEquals(listOf("play"), first.calls)
    }

    @Test
    fun `disposing another playback keeps the current one`() {
        val first = FakePlayback()
        first.play()
        coordinator.started(first)

        coordinator.disposed(FakePlayback())
        coordinator.started(FakePlayback())

        assertEquals(listOf("play", "pause"), first.calls)
    }
}
