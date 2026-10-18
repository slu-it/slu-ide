package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class KqlHighlightingTest {
    private fun kql(text: String) = Highlighted(text, KqlHighlighter)

    private fun Char.isWordPart() = isLetterOrDigit() || this == '_'

    /** Where the [occurrence]th [snippet] starts that is no part of a longer name (`with` is not in `startswith`). */
    private fun Highlighted.find(
        snippet: String,
        occurrence: Int,
    ): Int {
        var start = -1
        var found = -1
        while (found < occurrence) {
            start = text.indexOf(snippet, start + 1)
            check(start >= 0) { "not in text: $snippet (#$occurrence)" }
            val end = start + snippet.length
            val inName =
                (snippet.first().isWordPart() && start > 0 && text[start - 1].isWordPart()) ||
                    (snippet.last().isWordPart() && end < text.length && text[end].isWordPart())
            if (!inName) found++
        }
        return start
    }

    /** The [occurrence]th [snippet] is exactly one span of [style], from its first to its last character. */
    private fun Highlighted.assertExactly(
        style: TokenStyle,
        snippet: String,
        occurrence: Int = 0,
    ) {
        val start = find(snippet, occurrence)
        val overlapping = spans.filter { it.start < start + snippet.length && it.end > start }
        assertEquals(
            listOf(HighlightSpan(start, start + snippet.length, style)),
            overlapping,
            "$snippet (#$occurrence)",
        )
    }

    private fun Highlighted.assertPlain(
        snippet: String,
        occurrence: Int = 0,
    ) {
        val start = find(snippet, occurrence)
        val overlapping = spans.filter { it.start < start + snippet.length && it.end > start }
        assertEquals(emptyList(), overlapping, "$snippet (#$occurrence)")
    }

    private val signIns =
        kql(
            """
            |// Failed sign-ins per hour, last day
            |let lookback = 1d;
            |let threshold = 5;
            |let failures = materialize(
            |    SigninLogs
            |    | where TimeGenerated > ago(lookback) and ResultType != "0"
            |    | where AppDisplayName !has "Teams" and UserPrincipalName !contains @"svc\"
            |    | project-away ConditionalAccessPolicies, DeviceDetail
            |    | extend Hour = bin(TimeGenerated, 1h), Span = TimeGenerated - datetime(2024-01-31 10:00)
            |);
            |failures
            || summarize Count = count(), Users = dcount(UserPrincipalName) by bin(TimeGenerated, 5m), IPAddress
            || where Count > threshold
            || join kind=inner (
            |    SecurityAlert // alerts of the same day
            |    | mv-expand Entities = todynamic(Entities)
            |    | project-rename AlertTime = TimeGenerated
            |    ) on ${'$'}left.IPAddress == ${'$'}right.Entities
            || order by Count desc nulls last
            || render timechart
            |
            """.trimMargin(),
        )

    private val table =
        kql(
            """
            |datatable(Name: string, Size: long, Started: datetime)
            |[
            |    "a", 1, datetime(2024-01-01),
            |    'b\'s', 2, datetime(null),
            |]
            || extend Age = now() - Started, Big = Size >= 0x1F, Id = guid(74be27de-1e4e-49d9-b579-fe0b331d3642)
            || where Name in~ ("A", "B") and Name !in ("c") and Name !in~ ("d")
            || where Name =~ "a" or Name !~ 'b' or Name has_any ("x") or Name matches regex "^[ab]${'$'}"
            || where Started between (datetime(2024-01-01) .. 1.5d) and Size <> 3 and Size <= 4
            || project-keep Name, Age
            || top 10 by Age asc
            |
            """.trimMargin(),
        )

    private val commands =
        kql(
            """
            |.show tables details
            || where TableName startswith "Sign"
            |
            |.create-or-alter function with (folder = "Monitoring", docstring = "Hosts") HostsWith(prefix: string) {
            |    Heartbeat
            |    | where Computer startswith prefix
            |    | distinct Computer
            |}
            |
            |.set-or-append Results <| print Count = 1, Secret = h'p@ss', Path = h@"C:\temp", Note = ```line one
            |line // two```, Next = 1
            |
            """.trimMargin(),
        )

    @Test
    fun `spans are sorted and disjoint`() {
        for (sample in listOf(signIns, table, commands)) sample.assertWellFormed()
    }

    @Test
    fun `comments run to the end of their line`() {
        signIns.assertExactly(TokenStyle.COMMENT, "// Failed sign-ins per hour, last day")
        signIns.assertExactly(TokenStyle.COMMENT, "// alerts of the same day")
        kql("T // | where x == 'a'\n| take 1").assertExactly(TokenStyle.COMMENT, "// | where x == 'a'")
    }

    @Test
    fun `strings with escapes, verbatim, obfuscated and multi-line`() {
        signIns.assertExactly(TokenStyle.STRING, "\"0\"")
        signIns.assertExactly(TokenStyle.STRING, "@\"svc\\\"", 0)
        table.assertExactly(TokenStyle.STRING, "'b\\'s'")
        table.assertExactly(TokenStyle.STRING, "\"^[ab]$\"")
        commands.assertExactly(TokenStyle.STRING, "h'p@ss'")
        commands.assertExactly(TokenStyle.STRING, "h@\"C:\\temp\"")
        commands.assertExactly(TokenStyle.STRING, "```line one\nline // two```")
        kql("print @'it''s'").assertExactly(TokenStyle.STRING, "@'it''s'")
        kql("print H\"secret\"").assertExactly(TokenStyle.STRING, "H\"secret\"")
        kql("print h```a\nb```").assertExactly(TokenStyle.STRING, "h```a\nb```")
    }

    @Test
    fun `unterminated strings end with their line, multi-line ones with the text`() {
        val text = kql("print 'open\n| take 1\nprint ```never\nclosed\n")
        text.assertExactly(TokenStyle.STRING, "'open")
        text.assertExactly(TokenStyle.KEYWORD, "take")
        text.assertExactly(TokenStyle.STRING, "```never\nclosed\n")
        kql("print \"a\\\n| take 1").assertExactly(TokenStyle.STRING, "\"a\\")
    }

    @Test
    fun `pipes and comparisons are operators`() {
        for (operator in listOf("|", "==", "!=", ">", ">=", "<>", "<=", "=~", "!~")) {
            val sample = if (operator in listOf("|", "==", "!=", ">")) signIns else table
            sample.assertExactly(TokenStyle.OPERATOR, operator)
        }
        commands.assertExactly(TokenStyle.OPERATOR, "<|")
        signIns.assertPlain("=")
        signIns.assertPlain(" - ")
    }

    @Test
    fun `tabular operators after a pipe are keywords`() {
        for (word in listOf("where", "extend", "summarize", "join", "order", "render")) {
            signIns.assertExactly(TokenStyle.KEYWORD, word)
        }
        table.assertExactly(TokenStyle.KEYWORD, "top")
        commands.assertExactly(TokenStyle.KEYWORD, "distinct")
    }

    @Test
    fun `hyphenated operators are one keyword`() {
        for (word in listOf(
            "project-away",
            "mv-expand",
            "project-rename",
        )) {
            signIns.assertExactly(TokenStyle.KEYWORD, word)
        }
        table.assertExactly(TokenStyle.KEYWORD, "project-keep")
        val text =
            kql("T\n| make-series n = count() on t step 1h\n| graph-to-table nodes\n| parse-where x with * \"a\" y")
        for (word in listOf(
            "make-series",
            "graph-to-table",
            "parse-where",
        )) {
            text.assertExactly(TokenStyle.KEYWORD, word)
        }
    }

    @Test
    fun `arithmetic with a minus stays plain`() {
        val text = kql("T\n| extend d = a-b, e = Count-1, f = project-x, g = top-nested")
        text.assertPlain("a-b")
        text.assertPlain("Count")
        text.assertPlain("project-x")
        text.assertExactly(TokenStyle.KEYWORD, "top-nested")
    }

    @Test
    fun `other keywords and string operators`() {
        for (word in listOf("let", "and", "by", "kind", "on", "desc", "nulls")) {
            signIns.assertExactly(TokenStyle.KEYWORD, word)
        }
        signIns.assertExactly(TokenStyle.KEYWORD, "last", 1)
        for (word in listOf("!has", "!contains")) signIns.assertExactly(TokenStyle.KEYWORD, word)
        for (word in listOf("in~", "!in", "!in~", "or", "has_any", "matches", "regex", "between", "asc")) {
            table.assertExactly(TokenStyle.KEYWORD, word)
        }
        commands.assertExactly(TokenStyle.KEYWORD, "startswith")
        commands.assertExactly(TokenStyle.KEYWORD, "with")
        val negated = kql("T\n| where a !startswith_cs 'x' and not(b) and c !between (1 .. 2) and d !hasprefix 'y'")
        for (word in listOf("!startswith_cs", "not", "!between", "!hasprefix")) {
            negated.assertExactly(TokenStyle.KEYWORD, word)
        }
    }

    @Test
    fun `the operators a query begins with are keywords where a statement begins`() {
        val text =
            kql(
                """
                |print x = 1;
                |range n from 1 to 3 step 1;
                |let T = range m from 1 to 2 step 1;
                |union (print a = 1), (datatable(v: int) [1])
                || take 1
                |
                |// next query
                |search "error"
                || count;
                |set notruncation;
                |declare query_parameters(start: datetime = datetime(2024-01-01));
                |
                """.trimMargin(),
            )
        text.assertExactly(TokenStyle.KEYWORD, "print")
        text.assertExactly(TokenStyle.KEYWORD, "range")
        text.assertExactly(TokenStyle.KEYWORD, "range", 1)
        text.assertExactly(TokenStyle.KEYWORD, "union")
        text.assertExactly(TokenStyle.KEYWORD, "print", 1)
        text.assertExactly(TokenStyle.KEYWORD, "datatable")
        text.assertExactly(TokenStyle.KEYWORD, "search")
        text.assertExactly(TokenStyle.KEYWORD, "count")
        text.assertExactly(TokenStyle.KEYWORD, "set")
        text.assertExactly(TokenStyle.KEYWORD, "declare")
        text.assertExactly(TokenStyle.BUILTIN, "query_parameters")
    }

    @Test
    fun `operator names elsewhere are columns or functions`() {
        val text =
            kql(
                """
                |T
                || project count, sample, range, where
                || summarize count() by print
                || extend r = range(1, 5, 1), s = sample
                || where x == 1 and (count > 2)
                |
                """.trimMargin(),
            )
        for (word in listOf("count", "sample", "range", "where")) text.assertPlain(word)
        text.assertExactly(TokenStyle.BUILTIN, "count", 1)
        text.assertPlain("count", 2)
        text.assertExactly(TokenStyle.KEYWORD, "where", 1)
        text.assertPlain("print")
        text.assertExactly(TokenStyle.BUILTIN, "range", 1)
        text.assertPlain("sample", 1)
        text.assertPlain("T")
    }

    @Test
    fun `literals and declared types are types`() {
        signIns.assertExactly(TokenStyle.TYPE, "datetime")
        for ((word, occurrence) in listOf("string" to 0, "long" to 0, "datetime" to 0, "datetime" to 1, "guid" to 0)) {
            table.assertExactly(TokenStyle.TYPE, word, occurrence)
        }
        commands.assertExactly(TokenStyle.TYPE, "string")
        val text = kql("print a = int(5), b = real(+inf), c = bool(true), d = dynamic({\"k\": [1]}), e = typeof(long)")
        for (word in listOf("int", "real", "bool", "dynamic", "long")) text.assertExactly(TokenStyle.TYPE, word)
        text.assertExactly(TokenStyle.KEYWORD, "true")
        text.assertExactly(TokenStyle.BUILTIN, "typeof")
        val columns = kql("T\n| project time, date, string\n| extend t = todatetime(x)")
        for (word in listOf("time", "date", "string")) columns.assertPlain(word)
        columns.assertExactly(TokenStyle.BUILTIN, "todatetime")
    }

    @Test
    fun `the values of date, time and guid literals are numbers`() {
        signIns.assertExactly(TokenStyle.NUMBER, "2024-01-31 10:00")
        table.assertExactly(TokenStyle.NUMBER, "2024-01-01")
        table.assertExactly(TokenStyle.NUMBER, "74be27de-1e4e-49d9-b579-fe0b331d3642")
        table.assertPlain("null")
        kql("print timespan(1.02:03:04)").assertExactly(TokenStyle.NUMBER, "1.02:03:04")
        val unclosed = kql("print datetime(2024-01-01\n| take 1")
        unclosed.assertExactly(TokenStyle.NUMBER, "2024")
        unclosed.assertExactly(TokenStyle.KEYWORD, "take")
    }

    @Test
    fun `numbers, hex and timespans in every unit`() {
        signIns.assertExactly(TokenStyle.NUMBER, "1d")
        signIns.assertExactly(TokenStyle.NUMBER, "5")
        signIns.assertExactly(TokenStyle.NUMBER, "1h")
        signIns.assertExactly(TokenStyle.NUMBER, "5m")
        table.assertExactly(TokenStyle.NUMBER, "0x1F")
        table.assertExactly(TokenStyle.NUMBER, "1.5d")
        val units =
            "1d 1day 1days 2h 2hr 2hrs 2hour 2hours 3m 3min 3minute 3minutes 4s 4sec 4second 4seconds 5ms 5milli " +
                "5millis 5millisec 5millisecond 5milliseconds 6micro 6microsecond 6microseconds 7tick 7ticks 1.5h " +
                "1e3 1.5e-3 2E+4 10"
        val text = kql("print x = dynamic([" + units.replace(" ", ", ") + "])")
        for (literal in units.split(" ")) text.assertExactly(TokenStyle.NUMBER, literal)
        val invalid = kql("print a = 1dayx, b = 2x, c = 1..10")
        invalid.assertPlain("1dayx")
        invalid.assertPlain("2x")
        invalid.assertExactly(TokenStyle.NUMBER, "1")
        invalid.assertExactly(TokenStyle.NUMBER, "10")
    }

    @Test
    fun `functions are builtins`() {
        for (word in listOf("materialize", "ago", "bin", "dcount", "todynamic")) {
            signIns.assertExactly(TokenStyle.BUILTIN, word)
        }
        signIns.assertExactly(TokenStyle.BUILTIN, "count")
        table.assertExactly(TokenStyle.BUILTIN, "now")
        commands.assertExactly(TokenStyle.BUILTIN, "HostsWith")
    }

    @Test
    fun `management commands where a statement begins or after a function body are annotations`() {
        commands.assertExactly(TokenStyle.ANNOTATION, ".show")
        commands.assertExactly(TokenStyle.ANNOTATION, ".create-or-alter")
        commands.assertExactly(TokenStyle.ANNOTATION, ".set-or-append")
        commands.assertPlain("tables")
        commands.assertPlain("function")
        commands.assertExactly(TokenStyle.KEYWORD, "print")
        val text = kql(".create function F() {\n    print 1\n}\n.drop table T\nT\n| extend y = x.show")
        text.assertExactly(TokenStyle.ANNOTATION, ".drop")
        text.assertPlain(".show")
    }

    @Test
    fun `a name first on a line after an expression continues it`() {
        val text = kql("T\n| extend p = Props\n    .name\n| where a == 1\n.other")
        text.assertPlain(".name")
        text.assertPlain(".other")
    }

    @Test
    fun `plain names and table names stay plain`() {
        for (word in listOf("SigninLogs", "failures", "TimeGenerated", "IPAddress", "timechart", "inner")) {
            signIns.assertPlain(word)
        }
        signIns.assertPlain("\$left")
        signIns.assertPlain("Hour")
    }

    @Test
    fun `fenced kql, kusto and csl are highlighted`() {
        assertSame(KqlHighlighter, RSyntaxHighlighter.forLanguage("kql"))
        assertSame(KqlHighlighter, RSyntaxHighlighter.forLanguage("kusto"))
        assertSame(KqlHighlighter, RSyntaxHighlighter.forLanguage("csl"))
    }

    @Test
    fun `query-start operators directly followed by a parenthesis are calls, except schemas`() {
        val text =
            kql(
                """
                |let r = range(1, 5, 1);
                |print(1);
                |externaldata(Line: string) [h@"https://x/a.txt"]
                || where (range > 3) or (range)
                |
                |range, print
                |
                """.trimMargin(),
            )
        text.assertExactly(TokenStyle.BUILTIN, "range")
        text.assertExactly(TokenStyle.BUILTIN, "print")
        text.assertExactly(TokenStyle.KEYWORD, "externaldata")
        text.assertExactly(TokenStyle.TYPE, "string")
        text.assertPlain("range", 1)
        text.assertPlain("range", 2)
        text.assertPlain("range", 3)
        text.assertPlain("print", 1)
    }

    @Test
    fun `deprecated operator names after a pipe`() {
        val text = kql("T\n| mvexpand x\n| makeseries n = count() on t from 0 to 9 step 1")
        text.assertExactly(TokenStyle.KEYWORD, "mvexpand")
        text.assertExactly(TokenStyle.KEYWORD, "makeseries")
        for (word in listOf("from", "to", "step")) text.assertExactly(TokenStyle.KEYWORD, word)
    }

    @Test
    fun `from, to, step and default are keywords within range and make-series`() {
        val text =
            kql(
                """
                |range n from 1 to 10 step 2
                || extend from = n, to = n, step = 1, default = 0
                || make-series c = count() default = 0 on t from ago(1d) to now() step 1h by k
                || make-series c = count() on t in range(from, to, step)
                |
                """.trimMargin(),
            )
        for (word in listOf("from", "to", "step")) {
            text.assertExactly(TokenStyle.KEYWORD, word)
            text.assertPlain(word, 1)
            text.assertExactly(TokenStyle.KEYWORD, word, 2)
            text.assertPlain(word, 3)
        }
        text.assertPlain("default")
        text.assertExactly(TokenStyle.KEYWORD, "default", 1)
        text.assertExactly(TokenStyle.BUILTIN, "range", 1)
    }

    @Test
    fun `a blank line with CRLF line endings begins a statement`() {
        val text = kql("T\r\n| take 1\r\n\r\nprint 1\r\n| take 1\r\nprint 2\r\n")
        text.assertExactly(TokenStyle.KEYWORD, "print")
        text.assertPlain("print", 1)
    }
}
