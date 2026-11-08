package dev.sluide.languageservers

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/** What a language spec decides by default. */
class LanguageServerSpecTest {
    @ParameterizedTest
    @CsvSource(
        "start, true",
        "a-b, true",
        "名前, true",
        "'foo bar', false",
        "'', false",
        "'a\tb', false",
    )
    fun `by default a name is an identifier when it is not empty and has no whitespace or control characters`(
        name: String,
        expected: Boolean,
    ) {
        assertEquals(expected, TestSpec().isIdentifier(name))
    }
}
