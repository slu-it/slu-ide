package dev.sluide.media

import javafx.scene.media.MediaException
import javafx.scene.media.MediaPlayer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure parts of [JavaFxPlayback]; loading real media is in [JavaFxPlaybackUiTest]. */
class JavaFxPlaybackTest {
    @ParameterizedTest
    @CsvSource(
        "MEDIA_UNSUPPORTED, unsupported media format",
        "MEDIA_UNAVAILABLE, media unavailable",
        "MEDIA_INACCESSIBLE, media inaccessible",
        "MEDIA_CORRUPTED, media corrupted",
        "PLAYBACK_HALTED, playback halted",
    )
    fun `a known error type gets a readable reason`(
        type: MediaException.Type,
        expected: String,
    ) {
        assertEquals(expected, JavaFxPlayback.describe(type, "ERROR_MEDIA_AUDIO_FORMAT_UNSUPPORTED"))
    }

    @Test
    fun `an unspecified error shows its message without the wrapped exception's name`() {
        val message = "com.sun.media.jfxmedia.MediaException: Could not create player!"

        assertEquals("Could not create player!", JavaFxPlayback.describe(MediaException.Type.UNKNOWN, message))
        assertEquals("unknown error", JavaFxPlayback.describe(MediaException.Type.MEDIA_UNSPECIFIED, " "))
        assertEquals("unknown error", JavaFxPlayback.describe(null, null))
    }

    @ParameterizedTest
    @CsvSource(
        "UNKNOWN, LOADING",
        "READY, READY",
        "PLAYING, PLAYING",
        "STALLED, PLAYING",
        "PAUSED, PAUSED",
        "STOPPED, PAUSED",
        "HALTED, FAILED",
    )
    fun `player states map to playback states`(
        player: MediaPlayer.Status,
        expected: PlaybackStatus,
    ) {
        assertEquals(expected, JavaFxPlayback.mapStatus(player))
    }

    @Test
    fun `a disposed player keeps the last state`() {
        assertNull(JavaFxPlayback.mapStatus(MediaPlayer.Status.DISPOSED))
    }
}
