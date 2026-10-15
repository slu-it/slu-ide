package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Large files and long lines highlight in time linear in their size: no comparison of every span with every other
 * one, no regex that backtracks over a long line or recurses per character. The bounds are generous, so that a slow
 * machine does not fail them; the quadratic versions took from 5 to over 30 seconds.
 */
class HighlightingPerformanceTest {
    private fun assertFast(
        name: String,
        highlighter: SyntaxHighlighter,
        text: String,
        limit: Duration = 3.seconds,
    ) {
        highlighter.highlight("warm up\n")
        val took = measureTime { highlighter.highlight(text) }
        assertTrue(took < limit, "$name took $took")
    }

    private fun lines(
        line: String,
        count: Int = 50_000,
    ) = (line + "\n").repeat(count)

    @Test
    fun `large files with line rules`() {
        assertFast("SQL", RSyntaxHighlighter.sql, lines("INSERT INTO public.users (id, name) VALUES (1, 'a.b');"))
        assertFast("PowerShell", RSyntaxHighlighter.powerShell, lines("[int]\$a = \$b + \$c[0] # \$d"))
        assertFast("YAML", RSyntaxHighlighter.yaml, lines("  key: value # comment"))
        assertFast("INI", RSyntaxHighlighter.ini, lines("key = value"))
        assertFast("ignore file", RSyntaxHighlighter.ignoreFile, lines("!**/build/*.lo?"))
        assertFast("Caddyfile", RSyntaxHighlighter.caddyfile, lines("    header X \"a {path}\" {host} # c"))
    }

    @Test
    fun `long lines`() {
        val blanks = " ".repeat(80_000)
        assertFast("INI blanks", RSyntaxHighlighter.ini, "a${blanks}b\nc$blanks=d\n")
        assertFast("INI colon", RSyntaxHighlighter.ini, "a$blanks:b\n")
        assertFast("ssh blanks", RSyntaxHighlighter.sshConfig, "Host a${blanks}b$blanks\n")
        assertFast("Caddy blanks", RSyntaxHighlighter.caddyfile, "a${blanks}b$blanks{ x\n")
        assertFast("Dockerfile options", RSyntaxHighlighter.dockerfile, "FROM " + "--a ".repeat(20_000) + "\n")
        assertFast("Dockerfile braces", RSyntaxHighlighter.dockerfile, "RUN " + "\${".repeat(20_000) + "\n")
        assertFast("Makefile words", RSyntaxHighlighter.makefile, "a" + " b".repeat(40_000) + "\n")
    }

    @Test
    fun `long strings neither overflow the stack nor backtrack`() {
        val escapes = "\\x".repeat(100_000)
        assertFast("Caddy escapes", RSyntaxHighlighter.caddyfile, "  respond \"$escapes\" 200\n")
        assertFast("Caddy unclosed", RSyntaxHighlighter.caddyfile, "  respond \"$escapes\n")
        assertFast("Caddy plain", RSyntaxHighlighter.caddyfile, "  respond \"" + "x".repeat(100_000) + "\"\n")
        assertFast("Caddy quotes", RSyntaxHighlighter.caddyfile, "\"`".repeat(100_000) + "\n")
        assertFast("Caddy escaped quotes", RSyntaxHighlighter.caddyfile, "x \"" + "\\\"".repeat(100_000) + "\n")
    }
}
