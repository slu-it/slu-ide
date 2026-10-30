package dev.sluide.httpclient.curl

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurlTokenizerTest {
    private fun words(
        text: String,
        shell: Shell = CurlTokenizer.shellOf(text),
    ) = CurlTokenizer.tokenize(text, shell).map { command -> command.words.map { it.text } }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("commands")
    fun `splits a command into words as the shell does`(
        shell: String,
        text: String,
        expected: List<String>,
    ) {
        assertEquals(Shell.ofLabel(shell), CurlTokenizer.shellOf(text))
        assertEquals(listOf(expected), words(text, Shell.ofLabel(shell)))
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unclosed")
    fun `an unclosed quote fails at the quote`(
        shell: String,
        text: String,
        offset: Int,
    ) {
        val error = assertFailsWith<CurlSyntaxException> { CurlTokenizer.tokenize(text, Shell.ofLabel(shell)) }
        assertEquals(offset, error.offset)
        assertTrue(error.message.orEmpty().startsWith("Unclosed"), error.message)
    }

    @Test
    fun `commands end at line ends, also when separated by blank lines`() {
        val text = "curl a\n\ncurl b \\\n  -v\ncurl c"

        assertEquals(listOf(listOf("curl", "a"), listOf("curl", "b", "-v"), listOf("curl", "c")), words(text))
        assertEquals(listOf(0, 8, 22), CurlTokenizer.tokenize(text).map { it.offset })
    }

    @Test
    fun `words know where they start`() {
        assertEquals(
            listOf(0, 5, 8),
            CurlTokenizer
                .tokenize("curl -H 'a: b'")
                .single()
                .words
                .map { it.offset },
        )
    }

    @Test
    fun `comment lines name the next command, a prompt is skipped`() {
        val commands = CurlTokenizer.tokenize("# Get users\n$ curl https://x # trailing")

        assertEquals("Get users", commands.first().comment)
        assertEquals(listOf("curl", "https://x"), commands.first().words.map { it.text })
        assertEquals(
            "Users",
            CurlTokenizer.tokenize("REM Users\ncurl ^\n  x", Shell.CMD).single().comment,
        )
    }

    @Test
    fun `what follows a shell operator is not part of the command`() {
        val command = CurlTokenizer.tokenize("curl https://x \\\n  -s | jq '.a'").single()

        assertEquals(listOf("curl", "https://x", "-s"), command.words.map { it.text })
        assertEquals("| jq '.a'", command.ignored)
        assertEquals("& ok", CurlTokenizer.tokenize("curl \"a&b\" & ok", Shell.CMD).single().ignored)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\\U00110000", "\\UFFFFFFFF"])
    fun `an escape beyond Unicode fails at its backslash`(escape: String) {
        val error = assertFailsWith<CurlSyntaxException> { CurlTokenizer.tokenize("curl $'$escape'", Shell.POSIX) }

        assertEquals(7, error.offset)
        assertEquals("$escape is no Unicode character", error.message)
    }

    @Test
    fun `tells curl commands from other text`() {
        listOf("curl x", "  \n$ curl x", "curl.exe x", "# name\ncurl x", "CURL x").forEach {
            assertTrue(CurlTokenizer.looksLikeCurl(it), it)
        }
        listOf(
            "curly x",
            "GET https://x",
            "",
            "echo curl x",
        ).forEach { assertFalse(CurlTokenizer.looksLikeCurl(it), it) }
    }

    companion object {
        @JvmStatic
        fun commands() =
            listOf(
                Arguments.of(Shell.POSIX.label, "curl 'a b' \"c d\" e\\ f", listOf("curl", "a b", "c d", "e f")),
                Arguments.of(
                    Shell.POSIX.label,
                    "curl 'it'\\''s' \"say \\\"hi\\\" \\x\"",
                    listOf("curl", "it's", "say \"hi\" \\x"),
                ),
                Arguments.of(Shell.POSIX.label, "curl $'a\\nb\\t\\x41\\u00e4\\''", listOf("curl", "a\nb\tAä'")),
                Arguments.of(
                    Shell.POSIX.label,
                    "curl x \\\n  -H 'A: b' \\\n  -d ''",
                    listOf("curl", "x", "-H", "A: b", "-d", ""),
                ),
                Arguments.of(
                    Shell.POSIX.label,
                    "curl \$TOKEN \"\$HOME\" a#b",
                    listOf("curl", "\$TOKEN", "\$HOME", "a#b"),
                ),
                Arguments.of(Shell.POSIX.label, "curl -d 'multi\nline'", listOf("curl", "-d", "multi\nline")),
                Arguments.of(Shell.CMD.label, "curl \"a b\" ^\n  -H \"x: %Y%\"", listOf("curl", "a b", "-H", "x: %Y%")),
                Arguments.of(
                    Shell.CMD.label,
                    "curl ^\"a ^& b^\" \"q\\\"x\" \"d\"\"e\"",
                    listOf("curl", "a & b", "q\"x", "d\"e"),
                ),
                Arguments.of(Shell.CMD.label, "curl ^\"\\^\"x\\^\"^\" ^\"a^\n\nb^\"", listOf("curl", "\"x\"", "a\nb")),
                Arguments.of(
                    Shell.CMD.label,
                    "curl ^\"C:\\dir\\\\^\" 'single'",
                    listOf("curl", "C:\\dir\\", "'single'"),
                ),
                Arguments.of(
                    Shell.CMD.label,
                    "curl ^\"https://x/?q=name='a'^\"",
                    listOf("curl", "https://x/?q=name='a'"),
                ),
                Arguments.of(Shell.CMD.label, "curl ^\r\n  -v x", listOf("curl", "-v", "x")),
                Arguments.of(Shell.CMD.label, "curl ^\"a^\r\n\r\nb^\"", listOf("curl", "a\nb")),
                // ^" inside single quotes is POSIX text.
                Arguments.of(
                    Shell.POSIX.label,
                    "curl -d '{\"p\":\"a^\"}' https://x",
                    listOf("curl", "-d", "{\"p\":\"a^\"}", "https://x"),
                ),
                Arguments.of(
                    Shell.POWERSHELL.label,
                    "curl.exe \u2018a b\u2019 \u201Cc d\u201D 'it\u2019\u2019s' x",
                    listOf("curl.exe", "a b", "c d", "it\u2019s", "x"),
                ),
                Arguments.of(
                    Shell.POWERSHELL.label,
                    "curl.exe 'it''s' \"a`tb\"\"c\" `\n  -v",
                    listOf("curl.exe", "it's", "a\tb\"c", "-v"),
                ),
                Arguments.of(
                    Shell.POWERSHELL.label,
                    "curl.exe \$x '\$y' a`'b",
                    listOf("curl.exe", "\$x", "\$y", "a'b"),
                ),
            )

        @JvmStatic
        fun unclosed() =
            listOf(
                Arguments.of(Shell.POSIX.label, "curl -d 'abc", 8),
                Arguments.of(Shell.POSIX.label, "curl -d \"abc", 8),
                Arguments.of(Shell.POSIX.label, "curl $'abc", 6),
                Arguments.of(Shell.CMD.label, "curl -d \"abc", 8),
                Arguments.of(Shell.POWERSHELL.label, "curl.exe -d 'abc", 12),
            )
    }
}
