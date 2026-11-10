package dev.sluide.typescript

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NpmRangesTest {
    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource(
        "'^5.4.5', TOO_OLD",
        "'~6.0.3', SUPPORTED",
        "'7.0.2', SUPPORTED",
        "'=7.0.0', SUPPORTED",
        "'v7.0.0', SUPPORTED",
        "'>=6', SUPPORTED",
        "'>6.0.0', SUPPORTED",
        "'>= 6.0.0', SUPPORTED",
        "'6.x', SUPPORTED",
        "'6.*', SUPPORTED",
        "'6', SUPPORTED",
        "'5.x', TOO_OLD",
        "'>=5', TOO_OLD",
        "'>5', SUPPORTED",
        "'>5.x', SUPPORTED",
        "'>5.*', SUPPORTED",
        "'>5.9', TOO_OLD",
        "'>5.9.2', TOO_OLD",
        "'..', UNDECIDABLE",
        "'6.0.0 - 7.1.0', SUPPORTED",
        "'4.9.0 - 7.1.0', TOO_OLD",
        "'>=6.0.0 <8', SUPPORTED",
        "'<8 >=6.0.0', SUPPORTED",
        "'7.1.0-beta', SUPPORTED",
        "'7.1.0-dev.20261110.1', SUPPORTED",
        "'^5 || ^7', UNDECIDABLE",
        "'^6 || ^7', SUPPORTED",
        "'^4 || ^5', TOO_OLD",
        "'<7', UNDECIDABLE",
        "'*', UNDECIDABLE",
        "'x', UNDECIDABLE",
        "'', UNDECIDABLE",
        "'latest', UNDECIDABLE",
        "'next', UNDECIDABLE",
        "'beta', UNDECIDABLE",
        "'workspace:*', UNDECIDABLE",
        "'workspace:^6.0.0', UNDECIDABLE",
        "'file:../ts', UNDECIDABLE",
        "'link:../ts', UNDECIDABLE",
        "'portal:../ts', UNDECIDABLE",
        "'github:microsoft/TypeScript', UNDECIDABLE",
        "'microsoft/TypeScript', UNDECIDABLE",
        "'git+https://github.com/microsoft/TypeScript.git#v6', UNDECIDABLE",
        "'https://example.com/typescript-6.0.0.tgz', UNDECIDABLE",
        "'./vendor/typescript-6.0.0.tgz', UNDECIDABLE",
        "'npm:typescript@^5.4', TOO_OLD",
        "'npm:typescript@~6.0.3', SUPPORTED",
        "'npm:@typescript/native-preview@7.0.0-dev.20260101.1', SUPPORTED",
        "'npm:@typescript/native-preview@latest', SUPPORTED",
        "'npm:@typescript/native-preview', SUPPORTED",
        "'npm:other@^6', UNDECIDABLE",
    )
    fun `a range gets its verdict`(
        range: String,
        expected: Verdict,
    ) {
        assertEquals(expected, rangeVerdict(range))
    }

    @ParameterizedTest
    @ValueSource(strings = ["6.0.3", "v6.0.3", "6", "6.x", "6.0.0-beta.1", "=6.0.0"])
    fun `the major is read from every form`(version: String) {
        assertEquals(6, majorOf(version))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "latest", "x", "*", "workspace:*", "file:x"])
    fun `a text without a major has none`(version: String) {
        assertNull(majorOf(version))
    }

    @Test
    fun `an installed version is decided by its major`() {
        assertEquals(Verdict.SUPPORTED, versionVerdict("7.0.2"))
        assertEquals(Verdict.TOO_OLD, versionVerdict("5.9.3"))
        assertEquals(Verdict.UNDECIDABLE, versionVerdict("nightly"))
    }
}
