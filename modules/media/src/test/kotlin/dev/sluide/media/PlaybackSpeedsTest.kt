package dev.sluide.media

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class PlaybackSpeedsTest {
    @ParameterizedTest
    @CsvSource("0.5,0.5×", "1.0,1×", "0.75,0.75×", "1.25,1.25×", "2.0,2×")
    fun `speed label`(
        rate: Double,
        label: String,
    ) = assertEquals(label, PlaybackSpeeds.label(rate))
}
