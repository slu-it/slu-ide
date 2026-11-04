package dev.sluide.speech.session

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartRuleTest {
    @ParameterizedTest(name = "buffered {0} s, {1} chars left, {2} done in {3} s for {4} s audio, done {5} -> {6}")
    @CsvSource(
        // Nothing synthesized yet: no rate to judge by.
        "0, 100, 0, 0, 0, false, false",
        "0, 0, 0, 0, 0, true, true",
        // Done: whatever is buffered reaches the end.
        "0, 0, 100, 50, 5, true, true",
        // As fast as playing (r = 1) or faster: any buffered audio is enough.
        "2, 1000, 100, 2, 2, false, true",
        "0.1, 1000, 100, 1, 4, false, true",
        // Buffered audio is needed even when fast.
        "0, 1000, 100, 1, 4, false, false",
        // r = 2, 10 s audio for 100 chars, 100 chars left (10 s): needs 1.2 × 1 × 10 = 12 s.
        "11.9, 100, 100, 20, 10, false, false",
        "12, 100, 100, 20, 10, false, true",
        // Nothing left to synthesize: buffered audio reaches the end.
        "1, 0, 100, 20, 10, false, true",
        // A huge rate needs the whole rest buffered many times over.
        "1000, 1000, 10, 10000, 1, false, false",
    )
    fun `starts once the buffer reaches the end without a gap`(
        buffered: Double,
        unsynthesizedChars: Int,
        synthesizedChars: Int,
        synthesisSeconds: Double,
        audioSeconds: Double,
        done: Boolean,
        expected: Boolean,
    ) {
        val actual =
            StartRule.canStart(
                buffered,
                unsynthesizedChars,
                synthesizedChars,
                synthesisSeconds,
                audioSeconds,
                done,
            )

        assertEquals(expected, actual)
    }

    @Test
    fun `chars without audio give no rate`() {
        // A chunk that synthesized to silence: chars but no audio seconds.
        assertFalse(StartRule.canStart(1.0, 100, 50, 1.0, 0.0, false))
        assertTrue(StartRule.canStart(1.0, 100, 50, 1.0, 0.0, true))
    }
}
