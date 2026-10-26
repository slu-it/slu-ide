package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.bicep.MAIN_BICEP
import dev.sluide.fileviewers.env.EnvValues
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
        assertFast("Ruby", RSyntaxHighlighter.ruby, lines("  x = { name: 1, k: %i[a b] }.map(&:to_s) # c"))
        assertFast("PHP", RSyntaxHighlighter.php, lines("#[Route('/a', name: 'b')]\n\$a = \$b->c; // d"))
        assertFast("Perl", RSyntaxHighlighter.perl, lines("my @w = qw(a b) . q{x}; \$h{a} = @_;"))
        assertFast("Lua", RSyntaxHighlighter.lua, lines("local x <const> = 1 ::a:: -- c"))
        assertFast("Clojure", RSyntaxHighlighter.clojure, lines("(defn f [x] {:a ::b :c/d (str x)}) ; c"))
        assertFast("Lisp", RSyntaxHighlighter.lisp, lines("(define (f x) (list :a #t #f x)) ; c"))
        assertFast("Tcl", RSyntaxHighlighter.tcl, lines("puts \"\$a(b) \${c} \$::d::e\" ;# c"))
        assertFast(
            "batch",
            RSyntaxHighlighter.batch,
            lines(":a\nif x goto :b else call :c %~dp0 !A! %%i \"%B%\" & title t"),
        )
        assertFast("SQL", RSyntaxHighlighter.sql, lines("INSERT INTO public.users (id, name) VALUES (1, 'a.b');"))
        assertFast("PowerShell", RSyntaxHighlighter.powerShell, lines("[int]\$a = \$b + \$c[0] # \$d"))
        assertFast("YAML", RSyntaxHighlighter.yaml, lines("  key: value # comment"))
        assertFast("INI", RSyntaxHighlighter.ini, lines("key = value"))
        assertFast("properties", RSyntaxHighlighter.properties, lines("a.b = \${c.d}/x \\"))
        assertFast("CODEOWNERS", RSyntaxHighlighter.codeowners, lines("/docs/**/*.md @org/team a@b.c # c"))
        assertFast("gitattributes", RSyntaxHighlighter.gitattributes, lines("*.p?g text eol=lf -diff !merge"))
        assertFast("mailmap", RSyntaxHighlighter.mailmap, lines("Jane Doe <jane@example.com> <old@example.org>"))
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
    fun `compiled languages with line rules, masks and raw strings`() {
        assertFast("C digit separators", RSyntaxHighlighter.c, lines("int n = 1'000'000 + 0xFF'FF; char c = 'a';"))
        assertFast(
            "C++ std types",
            RSyntaxHighlighter.cpp,
            lines("std::vector<std::string> v = std::move(w); // 1'000"),
        )
        assertFast(
            "C# attributes",
            RSyntaxHighlighter.csharp,
            lines("    [Obsolete(\"x\")] file class C { } \$\"a{b}\""),
        )
        assertFast("C# raw strings", RSyntaxHighlighter.csharp, lines("var s = \"\"\"a\"\"\"; var t = \"\";"))
        assertFast("Go raw strings", RSyntaxHighlighter.go, lines("s := `a` + `b` + \"c\""))
        assertFast("go.mod", RSyntaxHighlighter.goMod, lines("\tgolang.org/x/text v0.14.0 // indirect"))
        assertFast(
            "Rust",
            RSyntaxHighlighter.rust,
            lines("#[derive(Debug)] fn f<'a>(x: &'a str) { println!(\"{}\", 'x'); }"),
        )
        assertFast("Scala raw strings", RSyntaxHighlighter.scala, lines("val s = s\"\"\"a \$b\"\"\" + raw\"x\""))
        assertFast("Visual Basic", RSyntaxHighlighter.visualBasic, lines("    REM comment : x = 1 : rem again"))
        assertFast("Pascal", RSyntaxHighlighter.pascal, lines("  x := \$1F + 42; // comment"))
        assertFast("Fortran", RSyntaxHighlighter.fortran, lines("  x = 1.5d0 + 2.0_dp .and. .not. b ! comment"))
        assertFast("assembler", RSyntaxHighlighter.assembler, lines("loop: mov eax, 0x1F ; add r12d, 0FFh"))
    }

    @Test
    fun `lexer masks cost little next to the lexer`() {
        // The digit separator mask once ran its lookbehind at every position and made C 35 times slower than the bare
        // lexer. The runs alternate and the best of each are compared, so that a busy machine slows both alike.
        val text = lines("    int n = 1'000 + (a[i] * 0xFF) + sizeof(struct s); // comment 'x' \"s\"", 20_000)

        val bareLexer = RSyntaxHighlighter("text/c")
        val maskedLexer = RSyntaxHighlighter.c
        bareLexer.highlight("warm up\n")
        maskedLexer.highlight("warm up\n")
        val bare = mutableListOf<Duration>()
        val masked = mutableListOf<Duration>()
        repeat(3) {
            bare += measureTime { bareLexer.highlight(text) }
            masked += measureTime { maskedLexer.highlight(text) }
        }
        assertTrue(masked.min() < bare.min() * 8 + 100.milliseconds, "masked C took $masked, the bare lexer $bare")
    }

    @Test
    fun `compiled languages with long lines`() {
        val blanks = " ".repeat(80_000)
        assertFast(
            "C digit separators",
            RSyntaxHighlighter.cpp,
            "1" + "'1".repeat(40_000) + "\n" + "'".repeat(80_000) + "\n",
        )
        assertFast("C# attribute blanks", RSyntaxHighlighter.csharp, "[a$blanks\nfile$blanks\n")
        assertFast("C# attribute brackets", RSyntaxHighlighter.csharp, "[a" + "(b)".repeat(30_000) + "x\n")
        assertFast(
            "C# interpolation",
            RSyntaxHighlighter.csharp,
            "\$".repeat(80_000) + "\n" + "@\$".repeat(40_000) + "\n",
        )
        assertFast(
            "go.mod versions",
            RSyntaxHighlighter.goMod,
            "go" + " v1.2.3".repeat(20_000) + "\n" + "1." + "1.".repeat(40_000) + "\n",
        )
        assertFast("go.mod comments", RSyntaxHighlighter.goMod, "// ".repeat(30_000) + "\n" + "/".repeat(80_000) + "\n")
        val rust = RSyntaxHighlighter.rust
        assertFast("Rust attributes", rust, "#[".repeat(30_000) + "\n" + "#![".repeat(20_000) + "]\n")
        assertFast("Rust lifetimes", rust, "'a ".repeat(30_000) + "\n" + "'" + "a".repeat(80_000) + "\n")
        assertFast("Rust macros", rust, "a!".repeat(30_000) + "\n" + "a" + "!".repeat(80_000) + "(\n")
        assertFast("Rust raw string quotes", rust, "r#\"".repeat(20_000) + "\n")
        assertFast("Go backticks", RSyntaxHighlighter.go, "`".repeat(80_000) + "\n" + "`a".repeat(30_000) + "\n")
        assertFast(
            "Scala quotes",
            RSyntaxHighlighter.scala,
            "\"\"\"a".repeat(20_000) + "\n" + "s".repeat(80_000) + "\"\n",
        )
        assertFast(
            "Visual Basic rem",
            RSyntaxHighlighter.visualBasic,
            ":$blanks".repeat(2) + "rem\n" + ": ".repeat(40_000) + "\n",
        )
        assertFast(
            "Fortran numbers",
            RSyntaxHighlighter.fortran,
            "1." + "1.".repeat(40_000) + "\n" + "1d" + "1".repeat(80_000) + "\n",
        )
        assertFast(
            "assembler labels",
            RSyntaxHighlighter.assembler,
            "a" + ".a".repeat(40_000) + "\n" + blanks + "a" + blanks + "\n",
        )
        assertFast(
            "assembler numbers",
            RSyntaxHighlighter.assembler,
            "1".repeat(80_000) + "\n" + "0x" + "f".repeat(80_000) + "\n",
        )
        assertFast(
            "assembler registers",
            RSyntaxHighlighter.assembler,
            "r1".repeat(40_000) + "\n" + " eax".repeat(20_000) + "\n",
        )
        assertFast(
            "Pascal hex",
            RSyntaxHighlighter.pascal,
            "\$f".repeat(40_000) + "\n" + "\$" + "f".repeat(80_000) + "\n",
        )
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
        assertFast("Ruby keys and literals", RSyntaxHighlighter.ruby, "a${blanks}b: " + "%i[".repeat(40_000) + "\n")
        assertFast("Ruby unclosed %i", RSyntaxHighlighter.ruby, "%i[a ".repeat(16_000) + "\n")
        assertFast("Ruby names", RSyntaxHighlighter.ruby, "a".repeat(80_000) + ":" + "a:".repeat(40_000) + "\n")
        assertFast(
            "PHP attributes",
            RSyntaxHighlighter.php,
            "#[" + "a[b]".repeat(30_000) + "\n" + "#[".repeat(40_000) + "\n",
        )
        assertFast("PHP brackets", RSyntaxHighlighter.php, "#[[".repeat(30_000) + "\n#[" + "[".repeat(80_000) + "\n")
        assertFast("Perl quotes", RSyntaxHighlighter.perl, "q(".repeat(40_000) + "\n" + "q ".repeat(40_000) + "\n")
        assertFast("Lua attributes", RSyntaxHighlighter.lua, "a<".repeat(40_000) + "\n" + "::a".repeat(30_000) + "\n")
        assertFast(
            "Clojure keywords",
            RSyntaxHighlighter.clojure,
            "::".repeat(40_000) + "\n" + "::a ".repeat(20_000) + "\n",
        )
        assertFast("Lisp keywords", RSyntaxHighlighter.lisp, ":a".repeat(40_000) + "\n#" + "#t".repeat(30_000) + "\n")
        assertFast("Tcl variables", RSyntaxHighlighter.tcl, "\$(".repeat(40_000) + "\n" + "\${".repeat(40_000) + "\n")
        assertFast(
            "Tcl scopes",
            RSyntaxHighlighter.tcl,
            "\$a" + "::a".repeat(30_000) + "\n\$a(" + "b".repeat(80_000) + "\n",
        )
        val batch = RSyntaxHighlighter.batch
        assertFast(
            "batch variables",
            batch,
            "%a".repeat(40_000) + "\n" + "!a".repeat(40_000) + "\n%" + "a".repeat(80_000) + "\n",
        )
        assertFast("batch blanks", batch, "goto$blanks:x\n($blanks" + "title$blanks\n:" + blanks + "x\n")
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
        assertFast("properties blanks", RSyntaxHighlighter.properties, "a${blanks}b\nc$blanks=d$blanks\n")
        assertFast("properties braces", RSyntaxHighlighter.properties, "a=" + "\${".repeat(40_000) + "\n")
        assertFast("CODEOWNERS wildcards", RSyntaxHighlighter.codeowners, "*".repeat(80_000) + " @a\n")
        assertFast("CODEOWNERS blanks", RSyntaxHighlighter.codeowners, "a${blanks}b$blanks@x$blanks#c\n")
        assertFast("CODEOWNERS at signs", RSyntaxHighlighter.codeowners, "a " + "@".repeat(80_000) + "\n")
        assertFast("CODEOWNERS slashes", RSyntaxHighlighter.codeowners, "a @" + "a/".repeat(40_000) + "\n")
        assertFast("gitattributes words", RSyntaxHighlighter.gitattributes, "a" + " -b".repeat(40_000) + "\n")
        assertFast("gitattributes blanks", RSyntaxHighlighter.gitattributes, "a${blanks}b${blanks}c=d$blanks\n")
        assertFast(
            "gitattributes wildcards",
            RSyntaxHighlighter.gitattributes,
            "?".repeat(80_000) + "\\\\\\".repeat(10_000) + " x\n",
        )
        assertFast("mailmap brackets", RSyntaxHighlighter.mailmap, "<".repeat(80_000) + "a$blanks>\n")
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
    fun `HCL tokenizer`() {
        val hcl = HclHighlighter
        val block =
            "resource \"aws_s3_bucket\" \"b\" {\n  bucket = \"\${var.prefix}-\${each.key}\" # c\n" +
                "  tags = { for k, v in local.t : k => upper(v) if v != null }\n  lifecycle { ignore_changes = [a, b] }\n}"
        assertFast("HCL lines", hcl, lines(block, count = 10_000))
        assertFast("HCL attributes", hcl, lines("  name = \"x\${a}\" // c", count = 50_000))
        assertFast("HCL interpolations", hcl, "a = \"" + "\${".repeat(80_000) + "\n" + "}\"".repeat(40_000))
        assertFast("HCL unterminated interpolation", hcl, "a = \"\${f(" + lines("  b = 1", count = 20_000))
        assertFast("HCL quotes", hcl, "a = " + "\"".repeat(80_000) + "\n")
        assertFast("HCL brackets", hcl, "a = " + "[".repeat(80_000) + "\n" + "]{".repeat(40_000) + "\n")
        assertFast("HCL header labels", hcl, "a" + " \"b\"".repeat(40_000) + " {\n")
        assertFast("HCL unclosed heredoc", hcl, "a = <<EOF\n" + lines("  b \${c} %{if d}e%{endif}", count = 20_000))
        assertFast("HCL heredoc lines", hcl, "a = <<EOF\n" + "x\n".repeat(80_000))
        assertFast("HCL escapes", hcl, "a = \"" + "\\\"".repeat(80_000) + "\nb = \"" + "\$\${".repeat(40_000) + "\n")
        assertFast("HCL block comments", hcl, "/**/".repeat(100_000) + "\n" + "/*".repeat(50_000))
        assertFast("HCL members", hcl, "x" + ".a.0".repeat(50_000) + "\n")
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
