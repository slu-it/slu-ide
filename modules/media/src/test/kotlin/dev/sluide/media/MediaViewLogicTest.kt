package dev.sluide.media

import dev.sluide.fileviewers.ViewActivity
import javafx.util.Duration
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaViewLogicTest {
    @ParameterizedTest
    @CsvSource(
        "LOADING,NONE",
        "READY,NONE",
        "PLAYING,RUNNING",
        "PAUSED,NONE",
        "ENDED,NONE",
        "FAILED,FAILED",
    )
    fun `activity follows the status`(
        status: PlaybackStatus,
        expected: ViewActivity,
    ) {
        assertEquals(expected, activityFor(status))
    }

    @Test
    fun `a reload plays on only what was playing`() {
        assertEquals(ResumePlan(Duration.seconds(5.0), true), resumePlan(PlaybackStatus.PLAYING, Duration.seconds(5.0)))
        assertFalse(resumePlan(PlaybackStatus.PAUSED, Duration.ZERO).play)
        assertFalse(resumePlan(PlaybackStatus.ENDED, Duration.seconds(9.0)).play)
    }

    @Test
    fun `the resume position is clamped into the new length`() {
        val total = Duration.seconds(10.0)
        assertEquals(Duration.seconds(4.0), clampPosition(Duration.seconds(4.0), total))
        assertEquals(total, clampPosition(Duration.seconds(40.0), total))
        assertEquals(Duration.ZERO, clampPosition(Duration.seconds(-1.0), total))
        assertEquals(Duration.seconds(40.0), clampPosition(Duration.seconds(40.0), Duration.UNKNOWN))
    }

    @Test
    fun `a reload is needed only when size or modification time changed`() {
        val stamp = FileStamp(100, FileTime.fromMillis(1000))
        assertFalse(needsReload(stamp, stamp.copy()))
        assertTrue(needsReload(stamp, stamp.copy(size = 101)))
        assertTrue(needsReload(stamp, stamp.copy(modified = FileTime.fromMillis(2000))))
        assertTrue(needsReload(null, stamp))
    }
}
