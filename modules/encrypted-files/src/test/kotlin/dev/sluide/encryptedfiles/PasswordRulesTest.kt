package dev.sluide.encryptedfiles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PasswordRulesTest {
    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(
        "abcdefABCD1, false",
        "abcdefABCD12, true",
        // 11 code points in 12 chars, then 12 in 13: an emoji counts once.
        "abcdefghij😀, false",
        "abcdefghijk😀, true",
    )
    fun `at least twelve code points are acceptable`(
        password: String,
        acceptable: Boolean,
    ) {
        assertEquals(acceptable, PasswordRules.acceptable(password.toCharArray()))
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(
        "abcdefABCD1, WEAK",
        "abcdefABCDEF, OK",
        "abcdefghijkl, WEAK",
        "abcdefABCD123, OK",
        "abcdefABCD1234, STRONG",
        "abcdefgABCDEFG, OK",
        "aaaaaaaaaaaaaaaaaaa, WEAK",
        "aaaaaaaaaaaaaaaaaaaa, STRONG",
        // 13 code points in 14 chars: lowercase, uppercase and the emoji as "other" are three classes, but too short.
        "abcdeABCDEfg😀, OK",
        "abcdeABCDEfgh😀, STRONG",
        "abcdefghijk😀, OK",
        "😀😀😀😀😀😀😀😀😀😀😀😀, WEAK",
    )
    fun `strength by length and character classes`(
        password: String,
        expected: Strength,
    ) {
        assertEquals(expected, PasswordRules.strength(password.toCharArray()))
    }
}
