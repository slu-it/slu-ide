package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.BicepHighlighter
import dev.sluide.fileviewers.EnvValues
import dev.sluide.fileviewers.MAIN_BICEP
import dev.sluide.fileviewers.SyntaxHighlighter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Large files and long lines highlight in time linear in their size: no comparison of every span with every other
 * one, no regex that backtracks over a long line or recurses per character. The bounds are generous, so that a slow
 * machine does not fail them; the quadratic versions took from 5 to over 30 seconds. A busy CI runner still took
 * 3.1 s once for a linear highlighter that takes 0.6 s alone: the fastest of up to three runs counts. Lines with
 * many spans (Mermaid links) come in fewer copies, since a GitHub ubuntu runner took over 3 s in all three runs for
 * 450,000 spans that take 0.5 s locally; nearly 100,000 spans still make a quadratic version fail.
 */
class HighlightingPerformanceTest {
    private fun assertFast(
        name: String,
        highlighter: SyntaxHighlighter,
        text: String,
        limit: Duration = 3.seconds,
    ) {
        highlighter.highlight("warm up\n")
        val runs = mutableListOf<Duration>()
        while (runs.size < 3 && runs.none { it < limit }) runs += measureTime { highlighter.highlight(text) }
        assertTrue(runs.any { it < limit }, "$name took $runs")
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
        assertFast(
            "Mermaid",
            RSyntaxHighlighter.mermaid,
            "flowchart LR\n" + lines("    A[\"Start\"] -->|go| B{x} -.-> C -. y .-> D ==> E <|-- F ||--o{ G", 10_000),
        )
        assertFast("Mermaid sequence", RSyntaxHighlighter.mermaid, lines("    Alice-xBob: hi ->> there -) x", 25_000))
        assertFast("Mermaid statements", RSyntaxHighlighter.mermaid, lines("    subgraph one %% x"))
        assertFast(
            "cookies",
            RSyntaxHighlighter.cookies,
            "# domain\tpath\tname\tvalue\tdate\n" + lines("example.com\t/\tid\t\"abc\"\t-1"),
        )
        assertFast("env", RSyntaxHighlighter.env, lines("export URL=https://x.y/a#b # c\nKEY=\"a # b\" # c"))
        assertFast("PEM", RSyntaxHighlighter.pem, lines("-----BEGIN CERTIFICATE-----\nProc-Type: 4\nMIIBVQIBADAN"))
    }

    @Test
    fun `KQL`() {
        val kql = KqlHighlighter
        val query =
            "| where TimeGenerated > ago(1d) and Name !has 'x' // c\n" +
                "| summarize n = count() by bin(TimeGenerated, 5m), k = datetime(2024-01-01)\n" +
                "| project-away a, b\n| extend p = @\"C:\\x\", q = h'y', r = a-b, t = 1.5e3"
        assertFast("KQL", kql, "let x = 1;\nT\n" + lines(query, 15_000) + ".show tables\n")
        assertFast("KQL multi-line strings", kql, lines("print ```a\nb``` // ```", 25_000))
        val blanks = " ".repeat(80_000)
        assertFast("KQL blanks", kql, "T$blanks|$blanks where$blanks x\n$blanks.show\n")
        assertFast(
            "KQL query starts",
            kql,
            "print$blanks\n" + "(range ".repeat(20_000) + "\n" + "(".repeat(50_000) + "\n",
        )
        assertFast("KQL pipes", kql, "|".repeat(100_000) + "\n" + "<|".repeat(50_000) + "\n")
        assertFast("KQL literals", kql, "print " + "datetime(".repeat(20_000) + "\n" + "guid(x".repeat(20_000) + "\n")
        assertFast("KQL hyphens", kql, "project-".repeat(20_000) + "\n" + "graph-to-".repeat(20_000) + "x\n")
        assertFast("KQL negations", kql, "!".repeat(50_000) + "\n" + "!in~".repeat(20_000) + "\n")
        assertFast("KQL escapes", kql, "print '" + "\\x".repeat(50_000) + "\n" + "@'".repeat(50_000) + "\n")
        assertFast("KQL unclosed", kql, "print ```" + "x\n".repeat(50_000))
        assertFast("KQL numbers", kql, "print " + "1.".repeat(40_000) + "\n" + "0x".repeat(40_000) + "\n")
    }

    @Test
    fun `masking env values is linear too`() {
        val text = lines("export URL=https://x.y/a#b # c\nKEY=\"a # b\" # c\nMULTI=\"x\ny\" # z\n# OLD=1")
        val blanks = " ".repeat(80_000)
        val long = "a${blanks}b\n$blanks=x${blanks}y$blanks#z\n#${blanks}KEY$blanks=\"" + "\\\"".repeat(40_000) + "\n"
        for ((name, env) in listOf("env lines" to text, "env long lines" to long)) {
            val took = measureTime { EnvValues.ranges(env) }
            assertTrue(took < 3.seconds, "$name took $took")
        }
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
        val mermaid = RSyntaxHighlighter.mermaid
        assertFast("Mermaid statement blanks", mermaid, "graph TD\nend${blanks}x\nstate$blanks-\n")
        assertFast("Mermaid dashes", mermaid, "a " + "-".repeat(80_000) + "\nb ." + "-".repeat(80_000) + "x\n")
        assertFast("Mermaid crosses", mermaid, ("a-x" + "b".repeat(100) + " ").repeat(1_000) + "\n")
        assertFast("Mermaid dots", mermaid, "-." + ".".repeat(80_000) + "\n" + "-.-".repeat(30_000) + "\n")
        assertFast("Mermaid quotes and pipes", mermaid, "\"|".repeat(100_000) + "\n" + "|\"".repeat(100_000) + "x\n")
        assertFast("Mermaid first line", mermaid, "%% c\n".repeat(50_000) + "flowchart$blanks LR\n")
        val env = RSyntaxHighlighter.env
        assertFast("env blanks", env, "a${blanks}b\n$blanks=x${blanks}y$blanks#z\n#$blanks=\n")
        assertFast("env hashes", env, "A=" + "x#".repeat(40_000) + "\nB=" + " #".repeat(40_000) + "\n")
        assertFast("env quotes", env, "A=" + "\"'".repeat(40_000) + "\n")
        val pem = RSyntaxHighlighter.pem
        assertFast("PEM dashes", pem, "-----BEGIN " + "A".repeat(80_000) + "\n" + "-".repeat(80_000) + "\n")
        assertFast("PEM header", pem, "A" + "-".repeat(80_000) + "\n")
    }

    @Test
    fun `TOML large files and long lines`() {
        val toml = TomlHighlighter
        assertFast("TOML", toml, lines("a.\"b\" = { c = [1, 2.5e3, 'x'], d = 1979-05-27 07:32:00Z } # e\n[t.u]"))
        assertFast("TOML uv.lock", toml, uvLock(3_000))
        assertFast("TOML unterminated multi-line string", toml, "a = \"\"\"\n" + uvLock(3_000))
        assertFast("TOML unterminated array", toml, "a = [\n" + uvLock(3_000))
        assertFast("TOML recovered headers", toml, lines("b = [ {\n  [x.\"y\"] # c"))
        assertFast("TOML recovered keys", toml, lines("  b = { c = [1, { d = [2"))
        assertFast("TOML mismatched closers", toml, "a = " + "[{".repeat(40_000) + "]}".repeat(40_000) + "\n")
        assertFast("TOML long header in an array", toml, "a = [\n[" + "a.".repeat(40_000) + "a]\n")
        assertFast("TOML long key in an array", toml, "a = [\n" + "\"x\" . ".repeat(40_000) + "\n")
        val blanks = " ".repeat(80_000)
        assertFast("TOML blanks", toml, "a$blanks.${blanks}b$blanks=${blanks}1$blanks#\n[$blanks\n")
        assertFast("TOML brackets", toml, "a = " + "[".repeat(80_000) + "\n" + "{a=".repeat(40_000) + "\n")
        assertFast("TOML dots", toml, "a" + ".a".repeat(80_000) + " = 1\n[" + ".\"".repeat(40_000) + "]\n")
        assertFast("TOML escapes", toml, "a = \"" + "\\\"".repeat(80_000) + "\nb = \"\"\"" + "\\\"".repeat(80_000))
        assertFast("TOML quotes", toml, "a = " + "\"\"".repeat(80_000) + "\nb = " + "''''".repeat(40_000) + "\n")
    }

    /** A uv lock file of [count] packages, about 17 lines each. */
    private fun uvLock(count: Int) =
        "version = 1\nrequires-python = \">=3.12\"\n\n" +
            (1..count).joinToString("") { n ->
                """
                |[[package]]
                |name = "package-$n"
                |version = "1.$n.0"
                |source = { registry = "https://pypi.org/simple" }
                |dependencies = [
                |    { name = "idna" },
                |    { name = "sniffio", marker = "python_full_version < '3.13'" },
                |]
                |sdist = { url = "https://files.pythonhosted.org/packages/9f/09/package-$n.tar.gz", hash = "sha256:f9e57c", size = 173422, upload-time = 2024-10-14T14:31:44.000Z }
                |wheels = [
                |    { url = "https://files.pythonhosted.org/packages/e4/f5/package-$n-py3-none-any.whl", hash = "sha256:45ad0a", size = 89766 },
                |]
                |
                |[package.optional-dependencies]
                |trio = [
                |    { name = "trio" },
                |]
                |
                """.trimMargin()
            }

    @Test
    fun `Bicep tokenizer`() {
        val bicep = BicepHighlighter
        assertFast("Bicep lines", bicep, lines(MAIN_BICEP.lineSequence().joinToString("\n"), count = 500))
        assertFast("Bicep objects", bicep, lines("  name: 'kv-\${prefix}' // c", count = 50_000))
        assertFast("Bicep interpolations", bicep, "var a = '" + "\${'".repeat(50_000) + "\n" + "}'".repeat(50_000))
        assertFast("Bicep braces", bicep, "var a = " + "{".repeat(100_000) + "}".repeat(100_000) + "\n")
        assertFast("Bicep escapes", bicep, "var a = '" + "\\'".repeat(100_000) + "\n")
        assertFast("Bicep block comments", bicep, "/* */".repeat(50_000) + "\n" + "/*".repeat(50_000))
        assertFast("Bicep multi-line strings", bicep, "'''x'''".repeat(50_000) + "\n'''" + "'".repeat(100_000))
        assertFast("Bicep decorators", bicep, "@a.b".repeat(50_000) + "\n" + "@".repeat(100_000) + "\n")
        assertFast("Bicep keys", bicep, "{\n" + "a: b, ".repeat(50_000) + "\n" + "\n:".repeat(50_000))
        assertFast("Bicep members", bicep, "x" + ".?a::b".repeat(50_000) + "\n")
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
