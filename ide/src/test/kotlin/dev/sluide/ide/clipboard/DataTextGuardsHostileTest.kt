package dev.sluide.ide.clipboard

import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration

/** The guards scan lines and tokens; a 64 KiB prefix followed by one long run must not make them slow (ADR 0187). */
class DataTextGuardsHostileTest {
    private val prefixes =
        mapOf(
            "yaml" to "a:\n  b: 1\n".repeat(64 * 1024 / 10),
            "csv" to "a,b,c\n".repeat(64 * 1024 / 6),
            "tsv" to "a\tb\n".repeat(64 * 1024 / 4),
            "properties" to "a=1\n".repeat(64 * 1024 / 4),
            "curl" to "curl -x ".repeat(64 * 1024 / 8),
            "http" to "# c\n".repeat(64 * 1024 / 4),
        )

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "yaml, ' '",
        "yaml, ':'",
        "yaml, 'x'",
        "yaml, '\t'",
        "yaml, '-'",
        "yaml, '|'",
        "csv, ','",
        "csv, ';'",
        "csv, '\"'",
        "csv, '.'",
        "tsv, ' '",
        "properties, '='",
        "properties, ':'",
        "properties, ' '",
        "properties, '\\'",
        "curl, ' '",
        "curl, 'x'",
        "curl, '-'",
        "http, ' '",
        "http, 'x'",
        "http, '/'",
    )
    fun `a long run after a long prefix is answered quickly`(
        formatId: String,
        run: String,
    ) {
        val text = prefixes.getValue(formatId) + run.repeat(200_000)

        assertTimeoutPreemptively(Duration.ofSeconds(1)) { DataTextGuards.accepts(formatId, text) }
        assertTimeoutPreemptively(Duration.ofSeconds(1)) { DataTextGuards.accepts(formatId, "$run\n$text\n$run") }
        assertTimeoutPreemptively(
            Duration.ofSeconds(1),
        ) { DataTextGuards.accepts(formatId, run.repeat(200_000) + text) }
    }
}
