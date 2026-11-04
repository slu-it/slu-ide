package dev.sluide.media

import javafx.util.Duration
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackMathTest {
    @ParameterizedTest
    @CsvSource(
        "0, 0:00",
        "999, 0:00",
        "42000, 0:42",
        "195000, 3:15",
        "3599999, 59:59",
        "3600000, 1:00:00",
        "3725000, 1:02:05",
        "36000000, 10:00:00",
        "-5000, 0:00",
    )
    fun `formats m_ss below an hour and h_mm_ss from one hour on`(
        millis: Double,
        expected: String,
    ) {
        assertEquals(expected, PlaybackMath.format(Duration.millis(millis)))
    }

    @ParameterizedTest
    @MethodSource("unknownTimes")
    fun `formats an unknown time as dashes`(time: Duration) {
        assertEquals("--:--", PlaybackMath.format(time))
    }

    @ParameterizedTest
    @CsvSource(
        // current, by, total, expected (seconds)
        "30, 10, 60, 40",
        "30, -10, 60, 20",
        "55, 10, 60, 60",
        "5, -10, 60, 0",
        "60, 10, 60, 60",
    )
    fun `skip stays within zero and the total`(
        current: Double,
        by: Double,
        total: Double,
        expected: Double,
    ) {
        val skipped = PlaybackMath.skip(Duration.seconds(current), Duration.seconds(by), Duration.seconds(total))

        assertEquals(expected, skipped.toSeconds(), 1e-9)
    }

    @ParameterizedTest
    @MethodSource("unknownTimes")
    fun `skip with an unknown total keeps only the lower bound`(total: Duration) {
        assertEquals(7200.0, PlaybackMath.skip(Duration.hours(2.0), Duration.ZERO, total).toSeconds(), 1e-9)
        assertEquals(7210.0, PlaybackMath.skip(Duration.hours(2.0), Duration.seconds(10.0), total).toSeconds(), 1e-9)
        assertEquals(0.0, PlaybackMath.skip(Duration.seconds(3.0), Duration.seconds(-10.0), total).toSeconds())
    }

    @Test
    fun `skip from an unknown time starts at zero`() {
        assertEquals(
            10.0,
            PlaybackMath.skip(Duration.UNKNOWN, Duration.seconds(10.0), Duration.seconds(60.0)).toSeconds(),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "0.5, true, 0.6",
        "0.5, false, 0.4",
        "0.95, true, 1.0",
        "1.0, true, 1.0",
        "0.05, false, 0.0",
        "0.0, false, 0.0",
        "0.35, true, 0.45",
    )
    fun `volume steps by a tenth within zero and one`(
        volume: Double,
        up: Boolean,
        expected: Double,
    ) {
        assertEquals(expected, PlaybackMath.volumeStep(volume, up))
    }

    @Test
    fun `volume steps do not drift`() {
        var volume = 0.0
        repeat(3) { volume = PlaybackMath.volumeStep(volume, up = true) }

        assertEquals(0.3, volume)
        repeat(13) { volume = PlaybackMath.volumeStep(volume, up = true) }
        assertEquals(1.0, volume)
        repeat(7) { volume = PlaybackMath.volumeStep(volume, up = false) }
        assertEquals(0.3, volume)
    }

    companion object {
        @JvmStatic
        fun unknownTimes() = listOf(Duration.UNKNOWN, Duration.INDEFINITE, Duration.millis(Double.NaN))
    }
}
