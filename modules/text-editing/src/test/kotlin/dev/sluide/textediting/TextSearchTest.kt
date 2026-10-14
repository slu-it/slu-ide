package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class TextSearchTest {
    private val plain = SearchOptions()
    private val regex = SearchOptions(regex = true)

    private fun matched(
        text: String,
        query: String,
        options: SearchOptions = plain,
    ) = TextSearch.find(text, query, options).matches.map { text.substring(it) }

    @Test
    fun `finds literal matches ignoring case`() {
        assertEquals(listOf(0 until 3, 8 until 11), TextSearch.find("Foo bar foo", "foo", plain).matches)
        assertEquals(listOf("a.b"), matched("a.b axb", "a.b"))
    }

    @Test
    fun `matches case when asked`() {
        assertEquals(listOf("foo"), matched("Foo foo", "foo", SearchOptions(matchCase = true)))
        assertEquals(listOf("Ärger", "ärger"), matched("Ärger ärger", "ärger"))
    }

    @Test
    fun `finds whole words only when asked`() {
        val options = SearchOptions(wholeWord = true)
        assertEquals(listOf(5 until 8), TextSearch.find("cats cat cat_x écat", "cat", options).matches)
        assertEquals(listOf(0 until 2), TextSearch.find("a1 a1b", "a1", options).matches)
        assertEquals(listOf("x|y"), matched("x|y xx|y", "x|y", options))
        assertEquals(listOf(1 until 5, 6 until 10), TextSearch.find("x cat, cat", " cat", options).matches)
        assertEquals(listOf("-x"), matched("a-x -xy", "-x", options))
    }

    @Test
    fun `finds regular expressions`() {
        assertEquals(listOf("a1", "b22"), matched("a1 b22 c", "[a-z]\\d+", regex))
        assertEquals(listOf("b", "c"), matched("a\nb\nc", "^[bc]$", regex))
        assertEquals(listOf("ab", "abc"), matched("ab abc", "ab|abc", SearchOptions(wholeWord = true, regex = true)))
    }

    @Test
    fun `skips empty matches`() {
        assertEquals(listOf("aa"), matched("baab", "a*", regex))
        assertEquals(emptyList(), matched("abc", "^", regex))
    }

    @Test
    fun `finds nothing for an empty query`() {
        assertEquals(SearchResult(emptyList()), TextSearch.find("abc", "", regex))
    }

    @Test
    fun `reports an invalid regular expression`() {
        val result = TextSearch.find("abc", "(a", regex)
        assertEquals(emptyList(), result.matches)
        assertNotNull(result.error)
        assertNull(TextSearch.find("(a", "(a", plain).error)
    }

    @Test
    fun `steps to the next and previous match with wrapping`() {
        val matches = listOf(2..3, 6..7, 10..11)
        assertEquals(0, TextSearch.next(matches, 0))
        assertEquals(1, TextSearch.next(matches, 4))
        assertEquals(1, TextSearch.next(matches, 6))
        assertEquals(0, TextSearch.next(matches, 11))
        assertEquals(1, TextSearch.previous(matches, 10))
        assertEquals(2, TextSearch.previous(matches, 12))
        assertEquals(2, TextSearch.previous(matches, 2))
        assertNull(TextSearch.next(emptyList(), 0))
        assertNull(TextSearch.previous(emptyList(), 0))
    }

    @Test
    fun `replaces one match`() {
        val result = TextSearch.replace("a foo b", 2..4, "FOO", "bar", plain)
        assertEquals(ReplaceResult.Replaced(listOf(TextEdit(2, 5, "bar", Selection(5))), Selection(5)), result)
    }

    @Test
    fun `replaces literally outside regex mode`() {
        assertEquals("a \$1\\x\\n b", TextSearch.replace("a foo b", 2..4, "foo", "\$1\\x\\n", plain).applied("a foo b"))
    }

    @Test
    fun `replaces with groups in regex mode`() {
        val text = "let x = 1"
        assertEquals("let 1 = x", TextSearch.replace(text, 4..8, "(\\w) = (\\d)", "$2 = $1", regex).applied(text))
    }

    @Test
    fun `turns escapes in regex replacements into characters`() {
        val text = "a,b"
        assertEquals("a\n\tb", TextSearch.replace(text, 1..1, ",", "\\n\\t", regex).applied(text))
        assertEquals("a\\\$b", TextSearch.replace(text, 1..1, ",", "\\\\\\$", regex).applied(text))
        assertEquals("axb", TextSearch.replace(text, 1..1, ",", "\\x", regex).applied(text))
    }

    @Test
    fun `replaces a whole word match with context before it`() {
        val text = "xa a"
        assertEquals("xa b", TextSearch.replace(text, 3..3, "a", "b", SearchOptions(wholeWord = true)).applied(text))
        assertNull(TextSearch.replace(text, 1..1, "a", "b", SearchOptions(wholeWord = true)))
    }

    @Test
    fun `does not replace what is no longer a match`() {
        assertNull(TextSearch.replace("a bar b", 2..4, "foo", "x", plain))
        assertNull(TextSearch.replace("foo", 0..1, "foo", "x", plain))
    }

    @Test
    fun `reports an invalid query or replacement`() {
        assertIs<ReplaceResult.Invalid>(TextSearch.replace("foo", 0..2, "(f", "x", regex))
        assertIs<ReplaceResult.Invalid>(TextSearch.replace("foo", 0..2, "foo", "$1", regex))
        assertIs<ReplaceResult.Invalid>(TextSearch.replace("foo", 0..2, "foo", "$", regex))
        assertIs<ReplaceResult.Invalid>(TextSearch.replace("foo", 0..2, "foo", "x\\", regex))
        assertIs<ReplaceResult.Invalid>(TextSearch.replaceAll("abc", "(", "y", regex))
        assertIs<ReplaceResult.Invalid>(TextSearch.replaceAll("a(b)", "\\((\\w)\\)", "$3", regex))
    }

    @Test
    fun `replaces all matches with one edit each`() {
        val text = "a foo b Foo c"
        val result = TextSearch.replaceAll(text, "foo", "x", plain)
        val edits = listOf(TextEdit(2, 5, "x", Selection(3)), TextEdit(8, 11, "x", Selection(7)))
        assertEquals(ReplaceResult.Replaced(edits, Selection(7)), result)
        assertEquals("a x b x c", result.applied(text))
    }

    @Test
    fun `finds at most as many matches as asked for`() {
        assertEquals(SearchResult(listOf(0..0, 1..1), truncated = true), TextSearch.find("aaa", "a", plain, limit = 2))
        assertEquals(SearchResult(listOf(0..0, 1..1, 2..2)), TextSearch.find("aaa", "a", plain, limit = 3))
    }

    @Test
    fun `gives up on a catastrophic regex after the time budget`() {
        val text = "a".repeat(40) + "c"
        val started = System.nanoTime()

        assertEquals(
            SearchResult(emptyList(), TextSearch.TOO_LONG),
            TextSearch.find(text, "(a+)+\\1b", regex, budget = 50.milliseconds),
        )
        // Polynomial, but long enough on a long line.
        assertEquals(
            SearchResult(emptyList(), TextSearch.TOO_LONG),
            TextSearch.find("foo ".repeat(2000), ".*foo.*bar", regex, budget = 50.milliseconds),
        )
        assertEquals(
            ReplaceResult.Invalid(TextSearch.TOO_LONG),
            TextSearch.replaceAll(text, "(a+)+\\1b", "x", regex, budget = 50.milliseconds),
        )
        assertEquals(
            ReplaceResult.Invalid(TextSearch.TOO_LONG),
            TextSearch.replace(text, 0..40, "(a+)+\\1c?b|a+c", "x", regex, budget = 50.milliseconds),
        )

        assertTrue(System.nanoTime() - started < 5_000_000_000L, "gave up in time")
    }

    @Test
    fun `reading the text throws once the deadline passed`() {
        val text = "a".repeat(10_000)
        val unlimited = DeadlineText(text, 1.minutes)
        assertEquals(text.length, (0 until text.length).count { unlimited[it] == 'a' })

        val expired = DeadlineText(text, Duration.ZERO)
        assertFailsWith<SearchTimeoutException> { (0 until text.length).forEach { expired[it] } }
    }

    @Test
    fun `replaces all regex matches with groups`() {
        val text = "k1=v1\nk2=v2"
        val result = TextSearch.replaceAll(text, "^(\\w+)=(\\w*)", "$2=$1", regex)
        assertEquals("v1=k1\nv2=k2", result.applied(text))
        assertEquals(2, (result as ReplaceResult.Replaced).count)
    }

    @Test
    fun `replaces empty regex matches too`() {
        assertEquals("// a\n// b", TextSearch.replaceAll("a\nb", "^", "// ", regex).applied("a\nb"))
        assertEquals("a;\nb;", TextSearch.replaceAll("a\nb", "$", ";", regex).applied("a\nb"))
        // As String.replaceAll: an empty match also follows "aa".
        assertEquals("xbxxbx", TextSearch.replaceAll("baab", "a*", "x", regex).applied("baab"))
        assertEquals("# a\n", TextSearch.replaceAll("a\n", "^", "# ", regex).applied("a\n"))
    }

    @Test
    fun `replaces nothing without matches`() {
        assertNull(TextSearch.replaceAll("abc", "x", "y", plain))
        assertNull(TextSearch.replaceAll("abc", "", "y", plain))
    }

    private fun ReplaceResult?.applied(text: String) = (this as? ReplaceResult.Replaced)?.applyTo(text)
}
