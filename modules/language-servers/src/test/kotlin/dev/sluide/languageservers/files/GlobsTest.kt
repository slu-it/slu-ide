package dev.sluide.languageservers.files

import dev.sluide.languageservers.LogRecords
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger
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
        assertEquals(expected, assertNotNull(Globs.matcher(glob)).matches(path))
    }

    @ParameterizedTest(name = "{0} matches the backslash and not {1}")
    @CsvSource("'[\\d]', 5", "'[a\\]', b")
    fun `a backslash in a class is a literal character`(
        glob: String,
        other: String,
    ) {
        val matcher = assertNotNull(Globs.matcher(glob))

        assertTrue(matcher.matches("\\"))
        assertFalse(matcher.matches(other))
    }

    @ParameterizedTest(name = "{0} cannot be read")
    @CsvSource("'{a'", "'{a,b'", "'a{'", "'{{a}'")
    fun `an unbalanced brace is not a glob`(glob: String) {
        assertNull(Globs.matcher(glob))
    }

    @ParameterizedTest(name = "a glob of {0} characters is read: {1}")
    @CsvSource("1000, true", "1001, false")
    fun `a glob over the length cap is refused`(
        length: Int,
        read: Boolean,
    ) {
        assertEquals(read, Globs.matcher("a".repeat(length)) != null)
    }

    @Test
    fun `a refused glob is logged at INFO, a too long one with its length and not its text`() {
        LogRecords(Logger.getLogger(Globs::class.java.name)).use { logs ->
            val tooLong = "secret".repeat(200)
            assertNull(Globs.matcher(tooLong))
            assertNull(Globs.matcher("{a"))

            val messages = logs.messages(Level.INFO)
            assertEquals(2, messages.size)
            assertTrue("1200" in messages[0], messages[0])
            assertFalse("secret" in messages[0], messages[0])
            assertTrue("{a" in messages[1] && "Unclosed group" in messages[1], messages[1])
        }
    }

    @Test
    fun `a literal character outside the basic plane matches itself`() {
        // The regex translation this replaced quoted each half of the surrogate pair alone and never matched it.
        val matcher = assertNotNull(Globs.matcher("a\uD83D\uDE00"))

        assertTrue(matcher.matches("a\uD83D\uDE00"))
        assertFalse(matcher.matches("a\uD83D\uDE01"))
    }

    @Test
    fun `a very long pattern compiles and matches without trouble`() {
        val glob = "a/".repeat(400) + "**/*.kt"
        val path = "a/".repeat(400) + "x/y.kt"

        val matcher = Globs.matcher(glob)

        assertTrue(assertNotNull(matcher).matches(path))
        assertFalse(matcher.matches("a/" + "x.kt"))
    }

    // The deepest nesting the length cap allows. 256 KiB is a stack on which 1000 levels of recursion overflowed on
    // x64 runners (docs/testing.md, "Portability"), so compiling and matching must not recurse with the nesting.
    @Test
    fun `the deepest nesting of braces the cap allows is read and matched on a small stack`() {
        val glob = "{".repeat(500) + "}".repeat(500)
        var matched: Boolean? = null
        var failure: Throwable? = null
        val thread =
            Thread(null, {
                try {
                    matched = Globs.matcher(glob)?.matches("")
                } catch (e: Throwable) {
                    failure = e
                }
            }, "deep", 256 * 1024)

        thread.start()
        thread.join()

        assertNull(failure)
        assertEquals(true, matched)
    }

    // Repeated wildcards made the regex translation backtrack for seconds (the first, on this path) to minutes (the
    // second). A linear matcher needs milliseconds; the bound is far above that so that slow runners pass.
    @ParameterizedTest(name = "{0} is decided within a second")
    @ValueSource(strings = ["**?**?**?**?**?**x", "**{,}**{,}**{,}**{,}**{,}**x"])
    fun `repeated wildcards cannot make a match slow`(glob: String) {
        val path = "a".repeat(94)

        val result =
            assertTimeoutPreemptively(Duration.ofSeconds(1)) { Globs.matcher(glob)?.matches(path) }

        assertEquals(false, result)
    }
}
