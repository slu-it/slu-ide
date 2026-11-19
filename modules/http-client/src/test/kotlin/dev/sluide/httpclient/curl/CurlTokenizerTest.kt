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

internal class CurlTokenizerTest {
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

    @ParameterizedTest(name = "{1}")
    @MethodSource("shells")
    fun `tells the shell by its line continuations, then by its quoting`(
        shell: Shell,
        text: String,
    ) {
        assertEquals(shell, CurlTokenizer.shellOf(text), text)
    }

    @Test
    fun `knows the shells by their labels, POSIX for any other`() {
        Shell.entries.forEach { assertEquals(it, Shell.ofLabel(it.label)) }
        assertEquals(Shell.POSIX, Shell.ofLabel("fish"))
        assertEquals(Shell.POSIX, Shell.ofLabel(""))
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("operators")
    fun `a shell operator ends the words and what follows is ignored`(
        shell: Shell,
        operator: Char,
        ignored: Boolean,
    ) {
        val program = if (shell == Shell.POWERSHELL) "curl.exe" else "curl"
        val command = CurlTokenizer.tokenize("$program x${operator}y z", shell).single()

        if (ignored) {
            assertEquals(listOf(program, "x"), command.words.map { it.text })
            assertEquals("${operator}y z", command.ignored)
        } else {
            // PowerShell's & is a call operator that a URL with & does not want to meet: kept as text on purpose.
            assertEquals(listOf(program, "x${operator}y", "z"), command.words.map { it.text })
            assertEquals(null, command.ignored)
        }
    }

    @Test
    fun `a command that starts with an operator has no words`() {
        assertEquals(emptyList(), CurlTokenizer.tokenize("| curl x", Shell.POSIX))
        assertEquals(emptyList(), CurlTokenizer.tokenize("|curl x", Shell.POSIX))
        assertEquals(emptyList(), CurlTokenizer.tokenize("| curl x", Shell.CMD))
        assertEquals(emptyList(), CurlTokenizer.tokenize("| curl.exe x", Shell.POWERSHELL))
        // No comment of an ignored line goes to the next command.
        assertEquals(null, CurlTokenizer.tokenize("|# c\ncurl x", Shell.POSIX).single().comment)
        assertEquals(null, CurlTokenizer.tokenize("|::c\ncurl x", Shell.CMD).single().comment)
        assertEquals(null, CurlTokenizer.tokenize("|REM c\ncurl x", Shell.CMD).single().comment)
    }

    @Test
    fun `reads comment lines and keeps none after an operator`() {
        assertEquals(null, CurlTokenizer.tokenize("curl x | REM y ^\n  z", Shell.CMD).single().comment)
        assertEquals("| REM y z", CurlTokenizer.tokenize("curl x | REM y ^\n  z", Shell.CMD).single().ignored)
        assertEquals("c", CurlTokenizer.tokenize("::c\ncurl x ^\n  y", Shell.CMD).single().comment)
        assertEquals("Users", CurlTokenizer.tokenize("rem Users\ncurl x", Shell.CMD).single().comment)
        assertEquals("", CurlTokenizer.tokenize("REM\ncurl x", Shell.CMD).single().comment)
        assertEquals(emptyList(), CurlTokenizer.tokenize("REM", Shell.CMD))
        assertEquals(emptyList(), CurlTokenizer.tokenize("::", Shell.CMD))
        assertEquals(emptyList(), CurlTokenizer.tokenize("# last line", Shell.POSIX))
    }

    @Test
    fun `a dollar at the end of the text is a word in every shell`() {
        // POSIX: a row of commands().
        listOf(Shell.CMD, Shell.POWERSHELL).forEach { assertEquals(listOf(listOf("$")), words("$", it), it.label) }
        assertEquals(listOf(listOf("curl.exe", "x$")), words("curl.exe x$", Shell.POWERSHELL))
    }

    @Test
    fun `an escape that is followed by a character and a line break is no continuation`() {
        // bash: a\x then a new line starts the next command.
        assertEquals(listOf(listOf("curl", "ax"), listOf("b")), words("curl a\\x\nb", Shell.POSIX))
        // The same in cmd: ^b is b and the line break after it ends the command.
        assertEquals(
            listOf(listOf("curl", "ab"), listOf("curl", "c", "d")),
            words("curl a^b\ncurl c ^\n  d", Shell.CMD),
        )
    }

    @Test
    fun `a command with an operator starts where its first word does`() {
        assertEquals(0, CurlTokenizer.tokenize("curl x | jq", Shell.POSIX).single().offset)
        assertEquals(0, CurlTokenizer.tokenize("curl x | jq", Shell.CMD).single().offset)
        assertEquals(2, CurlTokenizer.tokenize("  curl x|jq", Shell.POSIX).single().offset)
    }

    @Test
    fun `words start at their first character, also a quote or an escaped one`() {
        fun offsets(
            text: String,
            shell: Shell,
        ) = CurlTokenizer
            .tokenize(text, shell)
            .single()
            .words
            .map { it.offset }

        assertEquals(listOf(0, 5, 11, 13), offsets("curl 'a b' c \\x\\ y", Shell.POSIX))
        assertEquals(listOf(0, 5, 11, 16), offsets("curl \"a b\" \$'c' d", Shell.POSIX))
        assertEquals(listOf(0, 9, 13), offsets("curl.exe 'a' \u2018b\u2019", Shell.POWERSHELL))
        assertEquals(listOf(0, 9, 13), offsets("curl.exe \"a\" \u201Cb\u201D", Shell.POWERSHELL))
        // cmd: an escaped character starts a word at itself, a quote at the quote.
        assertEquals(listOf(0, 6, 9, 14), offsets("curl ^&a x ^\n y", Shell.CMD))
        assertEquals(listOf(0, 6, 14, 20), offsets("curl ^\"a^&b^\" x ^\n  y", Shell.CMD))
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
                // Each POSIX rule below was checked with bash -c "printf '<%s>' ..." (LC_ALL=C.UTF-8).
                Arguments.of(Shell.POSIX.label, "curl a\\", listOf("curl", "a\\")),
                // An escaped CR at the end of the text, a $ at the end of the text.
                Arguments.of(Shell.POSIX.label, "curl a\\\r", listOf("curl", "a\r")),
                Arguments.of(Shell.POSIX.label, "curl x\$", listOf("curl", "x$")),
                Arguments.of(Shell.POSIX.label, "\$", listOf("$")),
                Arguments.of(Shell.POSIX.label, "curl \$'\\0'", listOf("curl", "\u0000")),
                // A \ and CR LF continue a line, as pasted from Windows; bash would keep the CR in the word.
                Arguments.of(Shell.POSIX.label, "curl a \\\r\n  b", listOf("curl", "a", "b")),
                Arguments.of(Shell.POSIX.label, "curl a\\\rb \\", listOf("curl", "a\rb", "\\")),
                // bash keeps a bare CR and a tab inside the word of a CR; here a CR splits words like a blank.
                Arguments.of(Shell.POSIX.label, "curl a\tb\rc", listOf("curl", "a", "b", "c")),
                Arguments.of(Shell.POSIX.label, "a b", listOf("a", "b")),
                Arguments.of(Shell.POSIX.label, "\$x y", listOf("\$x", "y")),
                Arguments.of(Shell.POSIX.label, "a\$ b", listOf("a$", "b")),
                Arguments.of(Shell.POSIX.label, "curl \$ x", listOf("curl", "$", "x")),
                Arguments.of(Shell.POSIX.label, "\$\tcurl x", listOf("$", "curl", "x")),
                // bash would run a command named $; a leading "$ " is taken as a pasted prompt.
                Arguments.of(Shell.POSIX.label, "  \$ curl x", listOf("curl", "x")),
                Arguments.of(Shell.POSIX.label, "x#y c", listOf("x#y", "c")),
                Arguments.of(Shell.POSIX.label, "curl a# b", listOf("curl", "a#", "b")),
                Arguments.of(Shell.POSIX.label, "curl x #c", listOf("curl", "x")),
                Arguments.of(Shell.POSIX.label, "curl x'y z'w", listOf("curl", "xy zw")),
                Arguments.of(Shell.POSIX.label, "curl \$''", listOf("curl", "")),
                Arguments.of(Shell.POSIX.label, "curl \"\"", listOf("curl", "")),
                Arguments.of(Shell.POSIX.label, "curl \"a\"\"b\"", listOf("curl", "ab")),
                Arguments.of(Shell.POSIX.label, "curl \"a\\\nb\"", listOf("curl", "ab")),
                Arguments.of(
                    Shell.POSIX.label,
                    "curl \"\\q \\\\ \\\$ \\` \\\"\"",
                    listOf("curl", "\\q \\ \$ ` \""),
                ),
                // Typographic quotes are plain characters in POSIX shells.
                Arguments.of(
                    Shell.POSIX.label,
                    "curl \u2018a b\u2019 \u201Cc d\u201D",
                    listOf("curl", "\u2018a", "b\u2019", "\u201Cc", "d\u201D"),
                ),
                Arguments.of(Shell.POSIX.label, "curl \$'\\x4g'", listOf("curl", "\u0004g")),
                Arguments.of(Shell.POSIX.label, "curl \$'\\x414'", listOf("curl", "A4")),
                Arguments.of(Shell.POSIX.label, "curl \$'\\1234'", listOf("curl", "S4")),
                Arguments.of(Shell.POSIX.label, "curl \$'\\101\\7\\8'", listOf("curl", "A\u0007\\8")),
                Arguments.of(
                    Shell.POSIX.label,
                    "curl \$'\\u00e4a\\U0001F600a'",
                    listOf("curl", "\u00e4a\uD83D\uDE00a"),
                ),
                Arguments.of(Shell.POSIX.label, "curl \$'\\U0010FFFF'", listOf("curl", "\uDBFF\uDFFF")),
                Arguments.of(Shell.POSIX.label, "curl \$'\\q\\xg\\u'", listOf("curl", "\\q\\xg\\u")),
                // \cX is the control character X & 0x1F (? is DEL), \c\\ is Ctrl+\; a \c before the closing quote is text.
                Arguments.of(
                    Shell.POSIX.label,
                    "curl \$'\\cA\\ca\\c[\\c?'",
                    listOf("curl", "\u0001\u0001\u001b\u007f"),
                ),
                Arguments.of(Shell.POSIX.label, "curl \$'\\c\\\\x'", listOf("curl", "\u001cx")),
                Arguments.of(Shell.POSIX.label, "curl \$'\\c'", listOf("curl", "\\c")),
                // bash finds the closing quote first (a \' pair does not close), then \c takes one backslash of \c\'.
                Arguments.of(Shell.POSIX.label, "curl \$'\\c\\'x' y", listOf("curl", "\u001c'x", "y")),
                // For a character beyond ASCII (not checked against bash, whose result is bytes): code point & 0x1F.
                Arguments.of(Shell.POSIX.label, "curl \$'\\c\u00e9\\c\uD83D\uDE01'", listOf("curl", "\t\u0001")),
                // A single backslash after \c is Ctrl+\ on its own (bash: $'\c\x41' is 1C, x, 4, 1).
                Arguments.of(Shell.POSIX.label, "curl \$'\\c\\x41'", listOf("curl", "\u001cx41")),
                // bash gives the byte E9 for \xe9, which is no character; the tokenizer reads the code point U+00E9.
                Arguments.of(Shell.POSIX.label, "curl \$'\\xe9'", listOf("curl", "\u00e9")),
                Arguments.of(Shell.POSIX.label, "curl \$'a\\\\b'", listOf("curl", "a\\b")),
                // Not checked against a real PowerShell (none here): the tokenizer's own rules.
                Arguments.of(Shell.POWERSHELL.label, "curl.exe -d ''", listOf("curl.exe", "-d", "")),
                Arguments.of(Shell.POWERSHELL.label, "curl.exe \"\"", listOf("curl.exe", "")),
                Arguments.of(Shell.POWERSHELL.label, "curl.exe \$'a b'", listOf("curl.exe", "\$a b")),
                Arguments.of(Shell.POWERSHELL.label, "curl.exe \"a\\b\"", listOf("curl.exe", "a\\b")),
                Arguments.of(
                    Shell.POWERSHELL.label,
                    "curl.exe \"a`qb\" \"x`\$y`\"z\"",
                    listOf("curl.exe", "aqb", "x\$y\"z"),
                ),
                Arguments.of(
                    Shell.POWERSHELL.label,
                    "curl.exe \u201Ca\u201D\u201Db\u201D",
                    listOf("curl.exe", "a\u201Db"),
                ),
                Arguments.of(Shell.POWERSHELL.label, "curl.exe a`", listOf("curl.exe", "a`")),
                Arguments.of(Shell.CMD.label, "\$ curl ^\n  x", listOf("curl", "x")),
                Arguments.of(Shell.CMD.label, "\$x y ^\n  z", listOf("\$x", "y", "z")),
                Arguments.of(Shell.CMD.label, "curl x REM y ^\n  z", listOf("curl", "x", "REM", "y", "z")),
                Arguments.of(Shell.CMD.label, "curl x ::y ^\n  z", listOf("curl", "x", "::y", "z")),
                Arguments.of(Shell.CMD.label, "foo x ^\n  y", listOf("foo", "x", "y")),
                Arguments.of(Shell.CMD.label, "REMark x ^\n  y", listOf("REMark", "x", "y")),
                Arguments.of(Shell.CMD.label, "curl x ^", listOf("curl", "x")),
                Arguments.of(Shell.CMD.label, "curl x ^\n", listOf("curl", "x")),
                Arguments.of(Shell.CMD.label, "curl a^\nb", listOf("curl", "ab")),
                Arguments.of(Shell.CMD.label, "curl a^\r\nb", listOf("curl", "ab")),
                Arguments.of(Shell.CMD.label, "curl a^\r", listOf("curl", "a")),
                Arguments.of(Shell.CMD.label, "curl a^\rb ^\n  x", listOf("curl", "a", "b", "x")),
                Arguments.of(Shell.CMD.label, "curl a\rb ^\n  x", listOf("curl", "a", "b", "x")),
                // The first character of the next line is escaped by the continuation, here an operator.
                Arguments.of(Shell.CMD.label, "curl a^\n|b ^\n  x", listOf("curl", "a|b", "x")),
                Arguments.of(Shell.CMD.label, "curl \"a^b\" ^\n  x", listOf("curl", "a^b", "x")),
                Arguments.of(Shell.CMD.label, "curl a^^b ^\n  x", listOf("curl", "a^b", "x")),
                Arguments.of(Shell.CMD.label, "curl \"\" ^\n  x", listOf("curl", "", "x")),
                Arguments.of(Shell.CMD.label, "curl a\"\"b ^\n  x", listOf("curl", "ab", "x")),
                // cmd does not know \", so a ^ after one is literal: these rows put the quotes on the last line.
                // The C runtime: an odd run of backslashes before a quote leaves half of them and a literal quote.
                Arguments.of(Shell.CMD.label, "curl x ^\n  \"a\\\\\\\"b\"", listOf("curl", "x", "a\\\"b")),
                Arguments.of(Shell.CMD.label, "curl \"a\\\\\\\\\" ^\n  x", listOf("curl", "a\\\\", "x")),
                Arguments.of(Shell.CMD.label, "curl x ^\n  \"a\\\\\\\"\"", listOf("curl", "x", "a\\\"")),
                Arguments.of(
                    Shell.CMD.label,
                    "curl C:\\dir\\\\file b\\ ^\n  y",
                    listOf("curl", "C:\\dir\\\\file", "b\\", "y"),
                ),
                Arguments.of(Shell.CMD.label, "curl a\\\\^", listOf("curl", "a\\\\")),
                Arguments.of(Shell.CMD.label, "curl \"a\tb\"\tc ^\n  x", listOf("curl", "a\tb", "c", "x")),
                Arguments.of(Shell.CMD.label, "curl x>out ^\n  z", listOf("curl", "x")),
                Arguments.of(Shell.CMD.label, "curl a^>b ^\n  x", listOf("curl", "a>b", "x")),
                Arguments.of(Shell.CMD.label, "curl \"a|b\" ^\n  x", listOf("curl", "a|b", "x")),
            )

        @JvmStatic
        fun unclosed() =
            listOf(
                Arguments.of(Shell.POSIX.label, "curl -d 'abc", 8),
                Arguments.of(Shell.POSIX.label, "curl -d \"abc", 8),
                Arguments.of(Shell.POSIX.label, "curl $'abc", 6),
                Arguments.of(Shell.CMD.label, "curl -d \"abc", 8),
                Arguments.of(Shell.POWERSHELL.label, "curl.exe -d 'abc", 12),
                Arguments.of(Shell.POSIX.label, "curl \"abc\\", 5),
                Arguments.of(Shell.POSIX.label, "curl $'abc\\", 6),
                Arguments.of(Shell.POSIX.label, "curl $'\\x41", 6),
                Arguments.of(Shell.POSIX.label, "curl $'\\x4", 6),
                Arguments.of(Shell.POSIX.label, "curl $'\\c", 6),
                Arguments.of(Shell.POSIX.label, "curl $'\\c\\", 6),
                Arguments.of(Shell.POSIX.label, "curl $'\\1", 6),
                Arguments.of(Shell.POWERSHELL.label, "curl.exe \"abc", 9),
                Arguments.of(Shell.POWERSHELL.label, "curl.exe \"a`", 9),
            )

        @JvmStatic
        fun shells() =
            listOf(
                Arguments.of(Shell.POSIX, "curl x \\\n  -v"),
                Arguments.of(Shell.POSIX, "curl x \\  \n  -v"),
                // The backslash wins over curl.exe, ^" and the other continuations.
                Arguments.of(Shell.POSIX, "curl.exe x \\\n  -v"),
                Arguments.of(Shell.POSIX, "curl ^\"x^\" \\\n  -v"),
                Arguments.of(Shell.POSIX, "curl x ^\n  -v \\\n  -k"),
                Arguments.of(Shell.POSIX, "curl x `\n  -v \\\n  -k"),
                Arguments.of(Shell.CMD, "curl x ^\n  -v"),
                Arguments.of(Shell.CMD, "curl x ^  \n  -v"),
                Arguments.of(Shell.CMD, "curl.exe x ^\n  -v"),
                Arguments.of(Shell.CMD, "curl x `\n  -v ^\n  -k"),
                Arguments.of(Shell.POWERSHELL, "curl x `\n  -v"),
                Arguments.of(Shell.POWERSHELL, "curl x `  \n  -v"),
                Arguments.of(Shell.CMD, "curl ^\"x^\""),
                Arguments.of(Shell.POSIX, "curl 'x^\"'"),
                Arguments.of(Shell.CMD, "curl 'a' ^\"x^\""),
                Arguments.of(Shell.POWERSHELL, "curl.exe x"),
                Arguments.of(Shell.POWERSHELL, "CURL.EXE x"),
                Arguments.of(Shell.POSIX, "curl x"),
                Arguments.of(Shell.POSIX, "xcurl.exex"),
            )

        @JvmStatic
        fun operators() =
            Shell.entries.flatMap { shell ->
                "|;&<>".map {
                    val ends = !(shell == Shell.POWERSHELL && it == '&') && !(shell == Shell.CMD && it == ';')
                    Arguments.of(shell, it, ends)
                }
            }
    }
}
