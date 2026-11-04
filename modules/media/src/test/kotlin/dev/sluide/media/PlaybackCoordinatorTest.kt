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

    @Test
    fun `any pausable takes part, not only media playbacks`() {
        // A Read Aloud reading is a Pausable, not a Playback: the media player and a reading pause each other.
        val reading = CountingPausable()
        coordinator.started(reading)
        val media = FakePlayback()
        media.play()

        coordinator.started(media)
        coordinator.started(CountingPausable())

        assertEquals(1, reading.pauses)
        assertEquals(listOf("play", "pause"), media.calls)
    }

    @Test
    fun `a disposed pausable is forgotten`() {
        val reading = CountingPausable()
        coordinator.started(reading)

        coordinator.disposed(reading)
        coordinator.started(FakePlayback())

        assertEquals(0, reading.pauses)
    }

    private class CountingPausable : Pausable {
        var pauses = 0

        override fun pause() {
            pauses++
        }
    }
}
