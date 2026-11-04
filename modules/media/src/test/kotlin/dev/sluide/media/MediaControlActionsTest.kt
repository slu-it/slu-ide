package dev.sluide.media

import javafx.scene.input.KeyCode
import javafx.util.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MediaControlActionsTest {
    private val playback = FakePlayback()
    private var plays = 0
    private val actions = MediaControlActions(playback) { plays++ }

    @Test
    fun `play calls onPlay before play, pause does not`() {
        val order = mutableListOf<String>()
        val a = MediaControlActions(playback) { order += "onPlay:" + playback.calls.size }
        a.togglePlay()
        a.togglePlay()
        assertEquals(listOf("play", "pause"), playback.calls)
        assertEquals(listOf("onPlay:0"), order)
    }

    @Test
    fun `play from ended is just play`() {
        playback.status.set(PlaybackStatus.ENDED)
        actions.togglePlay()
        assertEquals(listOf("play"), playback.calls)
        assertEquals(1, plays)
    }

    @Test
    fun `skip seeks within the length`() {
        playback.currentTime.set(Duration.seconds(5.0))
        actions.skip(-10.0)
        actions.skip(10.0)
        assertEquals(listOf("seek ${Duration.ZERO}", "seek ${Duration.seconds(10.0)}"), playback.calls)
    }

    @Test
    fun `volume steps and mute toggles, mute is left alone by volume`() {
        playback.volume.set(0.5)
        playback.muted.set(true)
        actions.volumeStep(true)
        assertEquals(0.6, playback.volume.get())
        assertTrue(playback.muted.get())
        actions.toggleMute()
        assertFalse(playback.muted.get())
    }

    @ParameterizedTest
    @CsvSource("LOADING", "FAILED")
    fun `nothing reacts while loading or failed`(status: PlaybackStatus) {
        playback.status.set(status)
        for (action in MediaKeyAction.entries) assertFalse(actions.perform(action))
        assertTrue(playback.calls.isEmpty())
        assertEquals(0, plays)
        assertEquals(1.0, playback.volume.get())
        assertFalse(playback.muted.get())
    }

    @ParameterizedTest
    @CsvSource("SPACE,TOGGLE_PLAY", "LEFT,BACK", "RIGHT,AHEAD", "UP,VOLUME_UP", "DOWN,VOLUME_DOWN", "M,TOGGLE_MUTE")
    fun `keys map to actions`(
        code: KeyCode,
        action: MediaKeyAction,
    ) {
        assertEquals(action, MediaKeys.actionFor(code, false))
        assertNull(MediaKeys.actionFor(code, true))
    }

    @Test
    fun `other keys map to nothing`() = assertNull(MediaKeys.actionFor(KeyCode.A, false))
}

internal class ProgressSyncTest {
    private val playback = FakePlayback()
    private var shown = -1.0
    private var max = 1.0

    // Like the real slider: every value change reaches valueChanged, and a smaller maximum clamps the value.
    private val sync: ProgressSync =
        ProgressSync(
            playback,
            {
                max = it
                if (shown > max) {
                    shown = max
                    sync.valueChanged(shown)
                }
            },
            {
                shown = it
                sync.valueChanged(it)
            },
        )

    @Test
    fun `follows the playback without seeking`() {
        playback.currentTime.set(Duration.seconds(42.0))
        sync.playbackMoved()
        assertEquals(42.0, shown)
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun `does not follow while dragging and seeks once on release`() {
        sync.draggingChanged(true, 0.0)
        playback.currentTime.set(Duration.seconds(42.0))
        sync.playbackMoved()
        sync.valueChanged(30.0)
        assertEquals(-1.0, shown)
        assertTrue(playback.calls.isEmpty())
        sync.draggingChanged(false, 30.0)
        assertEquals(listOf("seek ${Duration.seconds(30.0)}"), playback.calls)
    }

    @Test
    fun `a click on the track seeks`() {
        sync.valueChanged(12.0)
        assertEquals(listOf("seek ${Duration.seconds(12.0)}"), playback.calls)
    }

    @Test
    fun `a shrinking length that clamps the slider does not seek`() {
        playback.currentTime.set(Duration.seconds(100.0))
        playback.totalDuration.set(Duration.minutes(3.0))
        sync.playbackMoved()
        playback.totalDuration.set(Duration.UNKNOWN)
        playback.currentTime.set(Duration.UNKNOWN)
        sync.playbackMoved()
        assertEquals(1.0, max)
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun `an unknown time shows zero`() {
        playback.currentTime.set(Duration.UNKNOWN)
        sync.playbackMoved()
        assertEquals(0.0, shown)
    }
}
