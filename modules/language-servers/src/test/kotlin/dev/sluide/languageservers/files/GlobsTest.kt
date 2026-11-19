package dev.sluide.languageservers.files

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The glob syntax of LSP 3.17 ("Glob Pattern"): `*`, `?`, `**`, `{a,b}`, `[a-z]`, `[!a-z]`; the characters the spec does
 * not give a meaning (a `,` outside braces, an unbalanced `}`) are literal.
 */
class GlobsTest {
    @ParameterizedTest(name = "{0} matches {1}: {2}")
    @CsvSource(
        // `**` only spans segments (LSP: "to match any number of path segments, including none") at a segment start.
        "a**/b, ab, false",
        "a**/b, axx/b, true",
        "'a,**/b', 'a,b', false",
        "'a,**/b', 'a,x/b', true",
        "'{x,**/b}', b, true",
        "'{x,**/b}', q/b, true",
        "'{a**/b,c}', ab, false",
        "**/b, b, true",
        "'x/**/b', 'x/b', true",
        "'x/**/b', 'x/p/q/b', true",
        // A trailing `/**` is the folder itself or anything below it, not a `/` with a `**` after it.
        "a/, a, false",
        "a/, a/, true",
        "'{a/,b}', a, false",
        "a/**b, ab, false",
        "a/**b, a/xb, true",
        "a/**b, a/b, true",
        "a/**, a, true",
        "a/**, a/b/c, true",
        "a/**, ab, false",
        "'{a/**,b}', a, true",
        "'{a/**,b}', a/x, true",
        "'{a/**,b}', b, true",
        "'{a/**}', a, true",
        "'{a/**}', a/x, true",
        "'a/**,b', a, false",
        "'a/**,b', 'a/x,b', true",
        "'a/**,b', 'a,b', false",
        "'{a/**x}', a, false",
        "'{a/**x}', a/yx, true",
        "'{a/**x}', ax, false",
        "a/***, a, true",
        "a/***, a/x, true",
        // Braces: `}` and `,` outside them are plain characters.
        "'a}', 'a}', true",
        "'{a},b', 'a,b', true",
        "'{a},b', 'b', false",
        "'a,b', 'a,b', true",
        "'a,b', 'a', false",
        "'{a,b}c', 'bc', true",
        "'{a,{b,c}}d', 'cd', true",
        "'{a,{b,c}}d', 'ad', true",
        "'{a,{b,c}}d', 'd', false",
        // Classes: `!` or `^` first negates, a class never matches `/`; characters regex gives a meaning are literal.
        "'[^a]', b, true",
        "'[^a]', a, false",
        "'[^a]', /, false",
        "'[!a]', /, false",
        "'[a!]', '!', true",
        "'[!a]', '!', true",
        "'[^a]', '^', true",
        "'[a^]', '^', true",
        "'[a^]', 'b', false",
        "'[a-c]', b, true",
        "'[a-c]', d, false",
        "'[a&b]', '&', true",
        "'[a&&b]', 'b', true",
        "'[a&&b]', 'c', false",
        "'a]b[c]', 'a]bc', true",
        "'[]a]', ']', true",
        "'[]a]', 'a', true",
        "'[]a]', 'b', false",
        "'[a[b]', '[', true",
        "'[a[b]', 'b', true",
        "'[^a[b]', '[', false",
        "'[^a[b]', 'c', true",
        "'[a]b]', 'ab]', true",
        // An unterminated class is a literal `[`.
        "'[abc', '[abc', true",
        "'[abc', 'a', false",
        "'[', '[', true",
        "'x[', 'x[', true",
        "'[a', '[a', true",
        "'[a', 'a', false",
        // `*` and `?` stay in one segment.
        "'*', 'a/b', false",
        "'a?b', 'a/b', false",
        "'a?b', 'axb', true",
        "'a*b', 'a/b', false",
    )
    fun `a glob matches a whole path by the LSP syntax`(
        glob: String,
        path: String,
        expected: Boolean,
    ) {
        assertEquals(expected, assertNotNull(Globs.regex(glob)).matches(path))
    }

    @ParameterizedTest(name = "{0} matches the backslash and not {1}")
    @CsvSource("'[\\d]', 5", "'[a\\]', b")
    fun `a backslash in a class is a literal character`(
        glob: String,
        other: String,
    ) {
        val regex = assertNotNull(Globs.regex(glob))

        assertTrue(regex.matches("\\"))
        assertFalse(regex.matches(other))
    }

    @ParameterizedTest(name = "{0} cannot be read")
    @CsvSource("'{a'", "'{a,b'", "'a{'", "'{{a}'")
    fun `an unbalanced brace is not a glob`(glob: String) {
        assertNull(Globs.regex(glob))
    }

    @Test
    fun `a very long pattern compiles and matches without trouble`() {
        val glob = "a/".repeat(20_000) + "**/*.kt"
        val path = "a/".repeat(20_000) + "x/y.kt"

        val regex = assertTimeoutPreemptively(Duration.ofSeconds(20)) { Globs.regex(glob) }

        assertTrue(assertNotNull(regex).matches(path))
        assertFalse(regex.matches("a/" + "x.kt"))
    }
}
