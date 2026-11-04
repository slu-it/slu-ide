package dev.sluide.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StageContentTest {
    @Test
    fun `failed wins over video and audio`() {
        assertEquals(
            StageKind.FAILED,
            stageKind(PlaybackStatus.FAILED, video = true, hasVideoNode = true),
        )
        assertEquals(
            StageKind.FAILED,
            stageKind(PlaybackStatus.FAILED, video = false, hasVideoNode = false),
        )
    }

    @Test
    fun `video shows the video node`() {
        assertEquals(
            StageKind.VIDEO,
            stageKind(PlaybackStatus.PLAYING, video = true, hasVideoNode = true),
        )
    }

    @Test
    fun `video without a video node falls back to audio`() {
        assertEquals(
            StageKind.AUDIO,
            stageKind(PlaybackStatus.READY, video = true, hasVideoNode = false),
        )
        assertEquals(
            StageKind.AUDIO,
            stageKind(PlaybackStatus.READY, video = false, hasVideoNode = true),
        )
    }

    @Test
    fun `cover is capped and shrinks with the area`() {
        assertEquals(240.0 to 240.0, coverFit(800.0, 800.0))
        assertEquals(240.0 to 240.0, coverFit(272.0, 370.0))
        assertEquals(168.0 to 70.0, coverFit(200.0, 200.0))
    }

    @Test
    fun `cover fit is never 0`() {
        assertEquals(1.0 to 1.0, coverFit(0.0, 0.0))
        assertEquals(1.0 to 1.0, coverFit(20.0, 100.0))
    }

    @Test
    fun `title falls back to the file name`() {
        assertEquals("a.mp3", audioLines(emptyMap(), "a.mp3").title)
        assertEquals("a.mp3", audioLines(mapOf("title" to "  "), "a.mp3").title)
        assertEquals("Song", audioLines(mapOf("title" to "Song"), "a.mp3").title)
    }

    @Test
    fun `blank or missing artist and album are hidden`() {
        val lines = audioLines(mapOf("artist" to " ", "album" to 5), "a.mp3")
        assertNull(lines.artist)
        assertNull(lines.album)
        assertEquals("Me", audioLines(mapOf("artist" to "Me"), "a.mp3").artist)
    }

    @Test
    fun `failed notice names the file, size and reason`() {
        assertEquals(
            FailedNotice("a.mp3", "2.0 KB", "Cannot play a.mp3: unsupported media format"),
            failedNotice("a.mp3", 2048, "unsupported media format"),
        )
        assertEquals("Cannot play a.mp3: unknown error", failedNotice("a.mp3", 0, null).message)
    }
}
