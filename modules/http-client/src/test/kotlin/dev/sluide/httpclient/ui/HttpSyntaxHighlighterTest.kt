package dev.sluide.httpclient.ui

import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TokenStyle.ANNOTATION
import dev.sluide.fileviewers.TokenStyle.COMMENT
import dev.sluide.fileviewers.TokenStyle.KEYWORD
import dev.sluide.fileviewers.TokenStyle.NUMBER
import dev.sluide.fileviewers.TokenStyle.OPERATOR
import dev.sluide.fileviewers.TokenStyle.PROPERTY
import dev.sluide.fileviewers.TokenStyle.STRING
import dev.sluide.fileviewers.TokenStyle.TAG
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpSyntaxHighlighterTest {
    private val highlighter = HttpSyntaxHighlighter()

    private val sample =
        """
        |@host = https://example.com
        |@token = {{${'$'}random.uuid}}
        |
        |### Get user
        |# @name getUser
        |# @timeout 5 s
        |// just a comment
        |GET {{host}}/users/1?x=1 HTTP/1.1
        |Accept: application/json
        |Authorization: Bearer {{token}}
        |
        |###
        |POST {{host}}/users
        |    ?verbose=true
        |Content-Type: application/json
        |
        |{
        |  "name": "{{name}}",
        |  "age": 42
        |}
        |
        |> {%
        |  client.test("ok", function() {});
        |%}
        |
        |### Upload
        |POST {{host}}/upload
        |Content-Type: application/xml
        |
        |<a>1</a>
        |< ./data/{{file}}.xml
        |
        |### Plain
        |PUT {{host}}/text
        |Content-Type: text/plain
        |
        |# not a comment in a body
        |
        """.trimMargin()

    private val http = Highlighted(sample, highlighter)

    @Test
    fun `variable definitions and their uses are annotations`() {
        assertEquals(setOf(ANNOTATION), http.stylesOf("@host"))
        assertEquals(emptySet(), http.stylesOf("https://example.com"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{\$random.uuid}}"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{token}}"))
    }

    @Test
    fun `separators and comments are comments, request tags annotations`() {
        assertEquals(setOf(COMMENT), http.stylesOf("### Get user"))
        assertEquals(setOf(COMMENT), http.stylesOf("// just a comment"))
        assertEquals(setOf(COMMENT, ANNOTATION), http.stylesOf("# @name"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("@name"))
        assertEquals(emptySet(), http.stylesOf("getUser"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("@timeout"))
    }

    @Test
    fun `request lines have a method, a URL and a version`() {
        assertEquals(setOf(KEYWORD), http.stylesOf("GET"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{host}}"))
        assertEquals(setOf(STRING), http.stylesOf("/users/1?x=1"))
        assertEquals(setOf(KEYWORD), http.stylesOf("HTTP/1.1"))
        assertEquals(setOf(KEYWORD), http.stylesOf("POST"))
        assertEquals(setOf(STRING), http.stylesOf("?verbose=true"))
    }

    @Test
    fun `header names are properties, their values plain`() {
        assertEquals(setOf(PROPERTY), http.stylesOf("Accept"))
        assertEquals(emptySet(), http.stylesOf("application/json"))
        assertEquals(emptySet(), http.stylesOf("Bearer"))
        assertEquals(setOf(PROPERTY), http.stylesOf("Content-Type"))
    }

    @Test
    fun `bodies are highlighted by their content type, with variables on top`() {
        assertEquals(setOf(PROPERTY), http.stylesOf("\"name\""))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{name}}"))
        assertEquals(setOf(STRING, ANNOTATION), http.stylesOf("\"{{name}}\""))
        assertEquals(setOf(NUMBER), http.stylesOf("42"))
        assertTrue(TAG in http.stylesOf("<a>"))
        assertEquals(emptySet(), http.stylesOf("# not a comment in a body"))
    }

    @Test
    fun `file bodies have a marker and a path`() {
        assertEquals(setOf(OPERATOR), http.stylesOf("< "))
        assertEquals(setOf(STRING), http.stylesOf("./data/"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{file}}"))
        assertEquals(setOf(STRING), http.stylesOf(".xml"))
    }

    @Test
    fun `scripts are JavaScript between operators`() {
        assertEquals(setOf(OPERATOR), http.stylesOf("> {%"))
        assertEquals(setOf(KEYWORD), http.stylesOf("function"))
        assertEquals(setOf(STRING), http.stylesOf("\"ok\""))
        assertEquals(setOf(OPERATOR), http.stylesOf("%}"))
    }

    @Test
    fun `script files are strings`() {
        val http = Highlighted("< pre.js\nGET http://x\n\n> after.js\n", highlighter)

        assertEquals(setOf(OPERATOR), http.stylesOf("<"))
        assertEquals(setOf(STRING), http.stylesOf("pre.js"))
        assertEquals(setOf(STRING), http.stylesOf("after.js"))
    }

    @Test
    fun `a separator inside a script is script code`() {
        val http = Highlighted("GET http://x\n\n> {%\n  /* ### no separator */\n%}\nGET\n", highlighter)

        assertEquals(setOf(COMMENT), http.stylesOf("/* ### no separator */"))
        assertEquals(setOf(OPERATOR), http.stylesOf("%}"))
    }

    @Test
    fun `unknown tags are comments`() {
        val http = Highlighted("# @no-log\nGET http://x\n", highlighter)

        assertEquals(setOf(COMMENT), http.stylesOf("# @no-log"))
    }

    @Test
    fun `a URL without a method is a string`() {
        val http = Highlighted("https://example.com/{{path}}\n", highlighter)

        assertEquals(setOf(STRING), http.stylesOf("https://example.com/"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{path}}"))
    }

    @Test
    fun `bodies of other content types stay plain`() {
        val http = Highlighted("POST http://x\nContent-Type: text/plain\n\n{\"a\": 1}\n", highlighter)

        assertEquals(emptySet(), http.stylesOf("{\"a\": 1}"))
    }

    @Test
    fun `CRLF line breaks keep the offsets`() {
        val http = Highlighted("GET http://x\r\nAccept: */*\r\n\r\n### next\r\n", highlighter)

        assertEquals(setOf(PROPERTY), http.stylesOf("Accept"))
        assertEquals(setOf(COMMENT), http.stylesOf("### next"))
        http.assertWellFormed()
    }

    @Test
    fun `scripts in a row end at their own close`() {
        val http = Highlighted("GET http://x\n\n> {% one %}\n> {%\n  two\n%}\n> {% three\n", highlighter)

        assertEquals(setOf(OPERATOR), http.stylesOf("%}", 0))
        assertEquals(setOf(OPERATOR), http.stylesOf("%}", 1))
        assertEquals(setOf(OPERATOR), http.stylesOf("> {%", 2))
        assertEquals(emptySet(), http.stylesOf("two"))
        http.assertWellFormed()
    }

    @Test
    fun `many scripts in a row take linear time`() {
        // Each script used to scan all script lines after it: about 3 s for 100 000 scripts, growing with the square;
        // now under a second for these.
        val scripts = 300_000
        val text = "GET http://x\n\n" + "> {%\n  client.log(1)\n%}\n".repeat(scripts)

        val started = System.nanoTime()
        val spans = HttpSyntaxHighlighter().highlight(text)
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertEquals(scripts, spans.count { it.style == OPERATOR && text.startsWith("%}", it.start) })
        assertTrue(elapsed < Duration.ofSeconds(10), "took $elapsed")
    }

    @Test
    fun `spans are well-formed`() = http.assertWellFormed()

    @Test
    fun `spans are well-formed while typing`() {
        for (length in 0..sample.length) Highlighted(sample.substring(0, length), highlighter).assertWellFormed()
    }

    /** The styled snippets of [text] in order, to compare where each span starts and ends. */
    private fun snippets(
        text: String,
        with: HttpSyntaxHighlighter = highlighter,
    ) = with.highlight(text).map { text.substring(it.start, it.end) to it.style }

    /** A highlighter for every language that styles the whole text it is given as a keyword and records the text. */
    private class Recording : (String) -> SyntaxHighlighter? {
        val calls = mutableListOf<Pair<String, String>>()

        override fun invoke(language: String) =
            SyntaxHighlighter { text ->
                calls += language to text
                listOf(HighlightSpan(0, text.length, KEYWORD))
            }
    }

    @Test
    fun `variables in comments and separators stay comments, trailing blanks are not styled`() {
        assertEquals(
            listOf("# {{x}} note" to COMMENT, "### {{y}}" to COMMENT, "GET" to KEYWORD, "http://x" to STRING),
            snippets("# {{x}} note   \n### {{y}}  \nGET http://x\n"),
        )
    }

    @Test
    fun `tags split into the comment marker and the annotation, the value stays plain`() {
        assertEquals(
            listOf("# " to COMMENT, "@name" to ANNOTATION, "// " to COMMENT, "@timeout" to ANNOTATION),
            snippets("# @name  getUser\n// @timeout=5\n"),
        )
    }

    @Test
    fun `variable definitions style the name, the value only for its variables`() {
        assertEquals(
            listOf("@host" to ANNOTATION, "{{a}}" to ANNOTATION, "@b" to ANNOTATION, "@c.d-e" to ANNOTATION),
            snippets("@host = https://x/{{a}}\n@b=2  \n@c.d-e = 1\n"),
        )
    }

    @Test
    fun `request lines style the method, the URL and the version separately`() {
        assertEquals(
            listOf("GET" to KEYWORD, "http://x/a" to STRING, "HTTP/1.1" to KEYWORD),
            snippets("GET http://x/a  HTTP/1.1  \n"),
        )
        assertEquals(listOf("GET" to KEYWORD, "http://x" to STRING), snippets("GET\thttp://x\n"))
        assertEquals(listOf("GET" to KEYWORD), snippets("GET\n"))
        assertEquals(listOf("WEBSOCKET" to KEYWORD, "ws://x" to STRING), snippets("WEBSOCKET ws://x\n"))
        assertEquals(
            listOf("GET" to KEYWORD, "http://x" to STRING, "HTTP/1.1 (Prior Knowledge)" to KEYWORD),
            snippets("GET http://x HTTP/1.1 (Prior Knowledge)\n"),
        )
        assertEquals(listOf("GET" to KEYWORD, "{{u}}" to ANNOTATION), snippets("GET {{u}}\n"))
    }

    @Test
    fun `URL continuation lines are strings without their indentation`() {
        assertEquals(
            listOf("POST" to KEYWORD, "http://x" to STRING, "?a=1" to STRING, "&b=2" to STRING),
            snippets("POST http://x\n    ?a=1\n\t&b=2  \n"),
        )
    }

    @Test
    fun `header names end before the colon, continuation lines only have variables`() {
        assertEquals(
            listOf(
                "GET" to KEYWORD,
                "http://x" to STRING,
                "X-A" to PROPERTY,
                "Name" to PROPERTY,
                "{{v}}" to ANNOTATION,
            ),
            snippets("GET http://x\nX-A : v:w\nName:value\n  {{v}}\n"),
        )
    }

    @Test
    fun `file references style the marker with its encoding and the path`() {
        assertEquals(
            listOf(
                "POST" to KEYWORD,
                "http://x" to STRING,
                "Content-Type" to PROPERTY,
                "<" to OPERATOR,
                "./a.txt" to STRING,
                "<@utf-8" to OPERATOR,
                "./b.txt" to STRING,
            ),
            snippets("POST http://x\nContent-Type: text/plain\n\n< ./a.txt\n<@utf-8   ./b.txt  \n"),
        )
    }

    @Test
    fun `response references style the two character marker and the path`() {
        assertEquals(
            listOf("GET" to KEYWORD, "http://x" to STRING, ">>" to OPERATOR, "out.json" to STRING),
            snippets("GET http://x\n\n>>   out.json  \n"),
        )
        assertEquals(
            listOf("GET" to KEYWORD, "http://x" to STRING, "<>" to OPERATOR, "prev.json" to STRING),
            snippets("GET http://x\n\n<> prev.json\n"),
        )
    }

    @Test
    fun `each run of body lines is highlighted on its own, by the last Content-Type`() {
        val languages = Recording()
        val text =
            "POST http://x\ncontent-type: text/plain\nContent-Type: application/json\n\n" +
                "{\n  \"a\": 1\n}\n< ./f.json\n[2]\n\n###\n" +
                "POST http://y\nContent-Type: text/plain\n\n{\"no\": 1}\n\n###\n" +
                "POST http://z\nContent-Type: application/xml\n\n<a/>"

        snippets(text, HttpSyntaxHighlighter(languages))

        assertEquals(
            listOf("json" to "{\n  \"a\": 1\n}", "json" to "[2]", "xml" to "<a/>"),
            languages.calls,
        )
    }

    @Test
    fun `script code is handed to the JavaScript highlighter exactly between the delimiters`() {
        val languages = Recording()

        assertEquals(
            listOf(
                "GET" to KEYWORD,
                "http://x" to STRING,
                ">" to OPERATOR,
                "{%" to OPERATOR,
                " code " to KEYWORD,
                "%}" to OPERATOR,
            ),
            snippets("GET http://x\n\n> {% code %} \n", HttpSyntaxHighlighter(languages)),
        )
        assertEquals(listOf("javascript" to " code "), languages.calls)
    }

    @Test
    fun `multi-line and unclosed scripts`() {
        val languages = Recording()
        val highlighter = HttpSyntaxHighlighter(languages)

        snippets("GET http://x\n\n>   {%\n a\n b\n%}\n", highlighter)
        val unclosed = snippets("GET http://x\n\n> {% x\n y", highlighter)

        assertEquals(listOf("javascript" to "\n a\n b\n", "javascript" to " x\n y"), languages.calls)
        assertEquals(
            listOf(">" to OPERATOR, "{%" to OPERATOR, " x\n y" to KEYWORD),
            unclosed.drop(2),
        )
    }

    @Test
    fun `an empty script has no code span and a script without highlighter only its delimiters`() {
        assertEquals(
            listOf(">" to OPERATOR, "{%" to OPERATOR, "%}" to OPERATOR),
            snippets("GET http://x\n\n> {%%}\n", HttpSyntaxHighlighter(Recording())).drop(2),
        )
        assertEquals(
            listOf(">" to OPERATOR, "{%" to OPERATOR, "%}" to OPERATOR),
            snippets("GET http://x\n\n> {% a %}\n", HttpSyntaxHighlighter { null }).drop(2),
        )
    }

    private val words =
        SyntaxHighlighter { text ->
            Regex("""\S+""").findAll(text).map { HighlightSpan(it.range.first, it.range.last + 1, KEYWORD) }.toList()
        }

    @Test
    fun `variables cut the spans of the languages they lie in`() {
        val text = "POST http://x\nContent-Type: application/json\n\naa{{x}} bb{{y}}cc{{z}} dd {{w}}\n"

        assertEquals(
            listOf(
                "aa" to KEYWORD,
                "{{x}}" to ANNOTATION,
                "bb" to KEYWORD,
                "{{y}}" to ANNOTATION,
                "cc" to KEYWORD,
                "{{z}}" to ANNOTATION,
                "dd" to KEYWORD,
                "{{w}}" to ANNOTATION,
            ),
            snippets(text, HttpSyntaxHighlighter { words }).drop(3),
        )
    }

    @Test
    fun `a span that ends or starts inside a variable keeps only its part outside`() {
        val ends = SyntaxHighlighter { listOf(HighlightSpan(0, 4, KEYWORD), HighlightSpan(4, 9, KEYWORD)) }
        val text = "POST http://x\nContent-Type: application/json\n\naa{{x}}bb\n"

        assertEquals(
            listOf("aa" to KEYWORD, "{{x}}" to ANNOTATION, "bb" to KEYWORD),
            snippets(text, HttpSyntaxHighlighter { ends }).drop(3),
        )
    }

    @Test
    fun `a body without a highlighter for its language stays plain`() {
        assertEquals(
            listOf("POST" to KEYWORD, "http://x" to STRING, "Content-Type" to PROPERTY),
            snippets("POST http://x\nContent-Type: application/json\n\n{\"a\": 1}\n", HttpSyntaxHighlighter { null }),
        )
    }

    @Test
    fun `an indented request line is styled from its first character`() {
        assertEquals(
            listOf("GET" to KEYWORD, "http://x" to STRING),
            snippets("  GET   http://x\n"),
        )
    }

    @Test
    fun `a response reference without a path has only its marker`() {
        assertEquals(
            listOf("GET" to KEYWORD, "http://x" to STRING, ">>" to OPERATOR),
            snippets("GET http://x\n\n>>\n"),
        )
    }

    @Test
    fun `spans after the last variable are kept`() {
        assertEquals(
            listOf("GET" to KEYWORD, "{{h}}" to ANNOTATION, "/x" to STRING, "Accept" to PROPERTY),
            snippets("GET {{h}}/x\nAccept: a\n"),
        )
    }

    @Test
    fun `a script starts at its own opening delimiter and ends at the first closing one after it`() {
        val recording = Recording()
        val highlighter = HttpSyntaxHighlighter(recording)

        // `{%}` is no closed script: its `%}` overlaps the opening delimiter, so the code runs to the last line.
        assertEquals(
            listOf(">" to OPERATOR, "{%" to OPERATOR, "}\n" to KEYWORD),
            snippets("GET http://x\n\n> {%}\n", highlighter).drop(2),
        )
        // The last line of the text, without a line break after it.
        assertEquals(
            listOf(">" to OPERATOR, "{%" to OPERATOR, " x" to KEYWORD),
            snippets("GET http://x\n\n> {% x", highlighter).drop(2),
        )
        // A closing delimiter at the start of a line ends the script, the next script is its own.
        assertEquals(
            listOf(
                ">" to OPERATOR,
                "{%" to OPERATOR,
                "\na\n" to KEYWORD,
                "%}" to OPERATOR,
                ">" to OPERATOR,
                "{%" to OPERATOR,
            ),
            snippets("GET http://x\n\n> {%\na\n%}\n> {%\n", highlighter).drop(2).take(6),
        )
    }

    @Test
    fun `old Mac line breaks and unclosed or nested braces are handled`() {
        assertEquals(
            listOf("GET" to KEYWORD, "http://x/{{a" to STRING, "Accept" to PROPERTY),
            snippets("GET http://x/{{a\rAccept: b\r"),
        )
        assertEquals(
            listOf("GET" to KEYWORD, "http://x/{{{{a}}}}" to STRING),
            snippets("GET http://x/{{{{a}}}}\n"),
        )
    }
}
