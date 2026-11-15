package dev.sluide.findinfiles.search

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertEquals

internal class StatusTextTest {
    @ParameterizedTest(name = "{1}")
    @MethodSource("states")
    fun `the status line tells the state`(
        state: SearchState,
        expected: String,
    ) {
        assertEquals(expected, statusText(state))
    }

    companion object {
        private fun done(
            matches: Int,
            files: Int,
            skipped: Int = 0,
            totalCapped: Boolean = false,
            visitCapped: Boolean = false,
        ) = SearchState.Finished(SearchOutcome.Done(matches, files, skipped, totalCapped, visitCapped))

        @JvmStatic
        fun states(): Stream<Arguments> =
            Stream.of(
                Arguments.of(SearchState.Idle, ""),
                Arguments.of(SearchState.Failed, "Search failed"),
                Arguments.of(SearchState.Running(0, 0), "Searching…"),
                Arguments.of(SearchState.Running(1, 1), "1 match in 1 file…"),
                Arguments.of(SearchState.Running(12, 3), "12 matches in 3 files…"),
                Arguments.of(done(0, 0), "No matches"),
                Arguments.of(done(1, 1), "1 match in 1 file"),
                Arguments.of(done(2, 1), "2 matches in 1 file"),
                Arguments.of(done(12, 3), "12 matches in 3 files"),
                Arguments.of(
                    done(12, 3, skipped = 1),
                    "12 matches in 3 files · 1 file skipped (over 2 MiB or too slow)",
                ),
                Arguments.of(done(0, 0, skipped = 2), "No matches · 2 files skipped (over 2 MiB or too slow)"),
                Arguments.of(
                    done(0, 0, skipped = 12_345),
                    "No matches · 12 345 files skipped (over 2 MiB or too slow)",
                ),
                Arguments.of(done(1000, 7, totalCapped = true), "First 1000 matches in 7 files, type more to narrow"),
                Arguments.of(done(1000, 1, totalCapped = true), "First 1000 matches in 1 file, type more to narrow"),
                Arguments.of(
                    done(1000, 7, skipped = 3, totalCapped = true),
                    "First 1000 matches in 7 files, type more to narrow · 3 files skipped (over 2 MiB or too slow)",
                ),
                Arguments.of(
                    done(5, 2, visitCapped = true),
                    "5 matches in the first 200 000 files, type more to narrow",
                ),
                Arguments.of(
                    done(1, 1, visitCapped = true),
                    "1 match in the first 200 000 files, type more to narrow",
                ),
                Arguments.of(
                    done(0, 0, visitCapped = true),
                    "No matches in the first 200 000 files, type more to narrow",
                ),
                Arguments.of(
                    SearchState.Finished(
                        SearchOutcome.Done(0, 0, 0, totalCapped = false, visitCapped = true),
                        SearchLimits(maxVisited = 50),
                    ),
                    "No matches in the first 50 files, type more to narrow",
                ),
                Arguments.of(
                    SearchState.Finished(
                        SearchOutcome.Done(1, 1, 1, totalCapped = false, visitCapped = false),
                        SearchLimits(maxBytes = 100),
                    ),
                    "1 match in 1 file · 1 file skipped (over 100 bytes or too slow)",
                ),
                Arguments.of(SearchState.Finished(SearchOutcome.Invalid("Unclosed group")), "Unclosed group"),
            )
    }
}
