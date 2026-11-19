package dev.sluide.ide.clipboard

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult
import dev.sluide.texttypes.TextType
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConvertingTextTypesTest {
    private class Format(
        override val id: String,
        override val extensions: List<String>,
    ) : TextFormat {
        override val name = id
        override val language: String? = null
    }

    /** A family that answers [detect] with [scores] (format, score); the rest is never asked. */
    private class FakeFamily(
        private val scores: List<Pair<Format, Int>>,
        private val failure: Throwable? = null,
    ) : FormatFamily<String> {
        override val name = "Fake"
        override val formats: List<TextFormat> = scores.map { it.first }

        override fun detect(text: String): List<FormatMatch> {
            failure?.let { throw it }
            return scores.map { FormatMatch(it.first, it.second) }
        }

        override fun readOptions(format: TextFormat) = emptyList<ConversionOption>()

        override fun writeOptions(format: TextFormat) = emptyList<ConversionOption>()

        override fun read(
            text: String,
            format: TextFormat,
            options: OptionValues,
        ): ReadResult<String> = error("not asked")

        override fun write(
            content: String,
            format: TextFormat,
            options: OptionValues,
        ): WriteResult = error("not asked")
    }

    private val json = Format("json", listOf("json", "jsonc"))
    private val yaml = Format("yaml", listOf("yaml", "yml"))
    private val toml = Format("toml", listOf("toml"))
    private val curl = Format("curl", listOf("sh"))
    private val http = Format("http", listOf("http"))
    private val csv = Format("csv", listOf("csv"))
    private val tsv = Format("tsv", listOf("tsv", "tab"))
    private val properties = Format("properties", listOf("properties"))

    private fun type(extension: String): TextType = checkNotNull(TextTypes.byExtension(extension))

    private fun scores(
        format: Format,
        score: Int,
        text: String,
    ): List<TypeScore> = ConvertingTextTypes(listOf(FakeFamily(listOf(format to score)))).scores(text)

    @Test
    fun `a match maps through its format's first extension to the score of that type, as given`() {
        assertEquals(listOf(TypeScore(type("json"), 93)), scores(json, 93, "{\"a\": 1}"))
        assertEquals(listOf(TypeScore(type("yaml"), 80)), scores(yaml, 80, "server:\n  port: 8080\n"))
        assertEquals(listOf(TypeScore(type("toml"), 70)), scores(toml, 70, "[a]\nb = 1\n"))
    }

    @Test
    fun `a cURL command is Shell Script and an HTTP request is HTTP Request`() {
        val curlText = "curl -X POST https://example.com \\\n  -d '{}'\n"
        assertEquals(listOf(TypeScore(type("sh"), 90)), scores(curl, 90, curlText))
        assertEquals(listOf(TypeScore(type("http"), 85)), scores(http, 85, "GET https://example.com\nAccept: */*\n"))
    }

    @Test
    fun `a format whose extension is no known type is dropped`() {
        val unknown = Format("xyz", listOf("xyz"))
        val family = FakeFamily(listOf(unknown to 90, json to 60))

        // Two lines: the unknown format's match must get past the two-line guard, to the type lookup.
        val text = "{\n  \"a\": 1\n}"
        assertEquals(listOf(TypeScore(type("json"), 60)), ConvertingTextTypes(listOf(family)).scores(text))
    }

    @Test
    fun `a format without extensions is dropped`() {
        val none = Format("none", emptyList())

        assertEquals(emptyList(), scores(none, 90, "a\nb"))
    }

    @Test
    fun `a family that throws counts as nothing and the others still score`() {
        val failing = FakeFamily(listOf(yaml to 80), RuntimeException("boom"))
        val working = FakeFamily(listOf(json to 90))

        assertEquals(listOf(TypeScore(type("json"), 90)), ConvertingTextTypes(listOf(failing, working)).scores("{}"))
    }

    @Test
    fun `a family that fails with an error such as a parser's stack overflow on deep nesting counts as nothing too`() {
        val failing = FakeFamily(listOf(toml to 80), StackOverflowError())
        val working = FakeFamily(listOf(json to 90))

        assertEquals(listOf(TypeScore(type("json"), 90)), ConvertingTextTypes(listOf(failing, working)).scores("{}"))
    }

    /** Whether [format] matching [text] with 70 survives the guards. */
    private fun counts(
        format: Format,
        text: String,
    ): Boolean = scores(format, 70, text).isNotEmpty()

    @Test
    fun `a JSON match counts on one line, every other match needs two non-blank lines`() {
        assertTrue(counts(json, "{\"a\": 1}"), "JSON on one line")
        assertTrue(counts(csv, "a,b,c\n1,2,3\n"), "two lines")
        assertFalse(counts(yaml, "Note: buy milk"), "YAML on one line")
        assertFalse(counts(csv, "Hello, world"), "CSV on one line")
        assertFalse(counts(toml, "a = 1"), "TOML on one line")
        assertFalse(counts(properties, "a=1"), "Properties on one line")
        assertFalse(counts(tsv, "a\tb\n\n  \n"), "blank lines do not count")
        // The format decides, not the extension: another format that maps to Shell Script needs two lines.
        assertFalse(counts(Format("shell", listOf("sh")), "curl -s https://example.com"), "another Shell Script format")
        assertFalse(counts(Format("web", listOf("http")), "GET https://example.com"), "another HTTP Request format")
    }

    @Test
    fun `a cURL match counts when the first word is curl and a later token is a URL or an option`() {
        assertTrue(counts(curl, "curl -X POST https://example.com -d 'a=1'"), "one line")
        assertTrue(counts(curl, "curl https://example.com"), "a URL")
        assertTrue(counts(curl, "curl -s"), "an option")
        assertTrue(counts(curl, "\n  curl https://example.com\n"), "blank lines around")
        assertTrue(counts(curl, "$ curl https://example.com"), "a shell prompt")
        assertTrue(counts(curl, "curl \\\n  -H 'A: b' \\\n  example"), "options on later lines")
        assertTrue(counts(curl, "# fetch the users\ncurl https://example.com/users"), "a comment line before")
        assertTrue(counts(curl, "  # one\n\n:: two\nREM three\nrem four\n$ curl -s"), "comment lines of every kind")
        assertTrue(counts(curl, "REM\ncurl -s"), "REM alone")
        assertTrue(counts(curl, "REM: why\ncurl -s"), "REM and a colon")
    }

    @Test
    fun `prose that Converting's requests family reads as cURL does not count`() {
        assertFalse(counts(curl, "Curl up with a good book tonight."), "capital C")
        assertFalse(counts(curl, "curl up with a good book"), "no URL and no option")
        assertFalse(counts(curl, "curl"), "no later token")
        assertFalse(counts(curl, "curly -x https://example.com"), "the first word is not exactly curl")
        assertFalse(counts(curl, "echo curl https://example.com"), "curl is not the first word")
        assertFalse(counts(curl, "$ $ curl https://example.com"), "two prompts")
        assertFalse(counts(curl, "curl - the tool everybody uses"), "a lone dash is no option")
        assertFalse(counts(curl, "# curl https://example.com"), "only a comment")
        assertFalse(counts(curl, "# fetch\nnot curl at all"), "prose after a comment")
        assertFalse(counts(curl, "REMEMBER curl\ncurl -s"), "REMEMBER is no comment")
        assertFalse(counts(curl, "REM_x\ncurl -s"), "REM and an underscore is no comment")
        assertFalse(counts(curl, "REM1\ncurl -s"), "REM and a digit is no comment")
        assertFalse(counts(curl, "abc def\ncurl -s"), "a text line is no comment")
        assertFalse(counts(curl, "ab\ncurl -s"), "a short text line is no comment")
    }

    @Test
    fun `an HTTP request match counts when its first line is a method and a URL or a variable`() {
        assertTrue(counts(http, "GET https://example.com"), "one line")
        assertTrue(counts(http, "POST {{host}}/api\nContent-Type: application/json"), "a variable")
        assertTrue(counts(http, "# a comment\n\n// another\nGET http://localhost:8080/x"), "after comments")
        assertTrue(counts(http, "DELETE   https://example.com/a"), "more whitespace")
        assertTrue(counts(http, "GET https://example.com HTTP/1.1"), "an HTTP version")
        assertTrue(counts(http, "GET /api/users HTTP/1.1\nHost: example.com"), "a raw request")
        assertTrue(counts(http, "GET /api/users HTTP/2"), "a path with a version")
        assertTrue(counts(http, "GET /api/users\nAccept: */*\nhost: example.com"), "a path with a Host header")
    }

    @Test
    fun `a bare URL or prose that starts like a request line does not count as an HTTP request`() {
        assertFalse(counts(http, "https://example.com/docs/getting-started"), "a URL")
        assertFalse(counts(http, "http://localhost:8080/actuator/health"), "a local URL")
        assertFalse(counts(http, "https://example.com/a\nhttps://example.com/b"), "two URLs")
        assertFalse(counts(http, "GET milk and eggs on the way home"), "GET prose")
        assertFalse(counts(http, "DELETE this line before you commit"), "DELETE prose")
        assertFalse(counts(http, "get https://example.com"), "a lower case method")
        assertFalse(counts(http, "GETS https://example.com"), "no such method")
        assertFalse(counts(http, "GET"), "a method alone")
        assertFalse(counts(http, "# GET https://example.com"), "only a comment")
        assertFalse(counts(http, "  \n"), "blank")
        assertFalse(counts(http, "GET /home"), "a path alone")
        assertFalse(counts(http, "GET / is the root path"), "a path with words")
        assertFalse(counts(http, "GET /api/users\nAccept: */*"), "a path with no version and no Host header")
        assertFalse(counts(http, "GET /api/users HTTP/x\nAccept: */*"), "a version that is none")
        assertFalse(counts(http, "GET https://example.com is what I tried, it failed"), "words after the URL")
        assertFalse(counts(http, "GET https://example.com HTTP/1.1 and more"), "words after the version")
        assertFalse(counts(http, "GET https://example.com HTTPS/1"), "no HTTP version")
        assertFalse(counts(http, "GET https://example.com tried it"), "four words")
        assertFalse(counts(http, "GET https://example.com HTTP/1.1 and"), "a version and a word")
        assertFalse(counts(http, "GET milk\nHost: example.com"), "a word with a Host header")
        assertFalse(counts(http, "GET milk HTTP/1.1"), "a word with a version")
        assertFalse(counts(http, "GET /a HTTP/"), "a version without a number")
        assertFalse(counts(http, "GET /a FOO/1.1"), "another protocol")
    }

    @Test
    fun `YAML counts with a structural sign`() {
        assertTrue(counts(yaml, "server:\n  port: 8080\n"), "a key entry under a key")
        assertTrue(counts(yaml, "# c\nserver:\n\n  port: 8080\n"), "a comment and a blank line between")
        assertTrue(counts(yaml, "items:\n  - a\n  - b\n"), "a list item under a key")
        assertTrue(counts(yaml, "- a: 1\n- b: 2\n"), "a list of mappings")
        assertTrue(counts(yaml, "---\na: 1\n"), "a document line")
        assertTrue(counts(yaml, "\n  {a: 1,\n b: 2}"), "a flow mapping starting the text")
        assertTrue(counts(yaml, "[1, 2,\n 3]"), "a flow sequence starting the text")
        assertTrue(counts(yaml, "server:\n  port: 1\n  my key: 2\n"), "a key with a space after the first entry")
        assertTrue(counts(yaml, "server:\n\tport: 1"), "a tab as the indent")
        assertTrue(counts(yaml, "- my key: 1\n- b: 2"), "a list item with a key with a space")
        assertTrue(counts(yaml, "{my key: 1,\n b: 2}"), "a flow mapping with a key with a space")
        assertTrue(counts(yaml, "# my note: here\nserver:\n  port: 1"), "a comment is no key")
        assertTrue(counts(yaml, "# c\n{a: 1,\n b: 2}"), "a flow mapping after a comment")
        assertTrue(counts(yaml, "server:\n  port: 1\nplain line"), "a top-level line without a key")
        assertTrue(counts(yaml, "items:\n  -\n  -"), "bare dashes under a key")
        assertTrue(counts(yaml, "  script: |\n    echo hi"), "an indented block scalar key")
        assertTrue(counts(yaml, "- see https://example.com: now\n- k: v"), "a URL in an item")
    }

    @Test
    fun `a block scalar counts as structure`() {
        assertTrue(counts(yaml, "script: |\n  echo hi\n  echo there"), "literal")
        assertTrue(counts(yaml, "description: >\n  A long text\n  over lines"), "folded")
        assertTrue(counts(yaml, "script: |-\n  echo hi\nname: x"), "with a chomping mark")
        assertTrue(counts(yaml, "script: >2\n  echo hi\nname: x"), "with an indent number")
        assertFalse(counts(yaml, "script: |\nname: x"), "no indented line after it")
        assertFalse(counts(yaml, "my script: |\n  echo hi"), "a key with whitespace")
        assertFalse(counts(yaml, "  my script: |\n    echo hi"), "an indented key with whitespace")
        assertFalse(counts(yaml, "script |\n  echo hi\nname: x"), "no colon")
        assertFalse(counts(yaml, ": |\n  echo hi\nname: x"), "an empty key")
        assertFalse(counts(yaml, "script: x\n  echo hi\nname: 1"), "a plain value")
        assertFalse(counts(yaml, "script: |x\n  echo hi\nname: y"), "not a block scalar mark")
    }

    @Test
    fun `flat YAML and Key colon prose do not count`() {
        assertFalse(counts(yaml, "Note: buy milk\nTodo: call mom"), "flat prose lines")
        assertFalse(counts(yaml, "name: x\nversion: 1"), "flat real YAML is a known limit")
        assertFalse(counts(yaml, "val name: String = \"x\"\nval n: Int = 1"), "Kotlin")
        assertFalse(counts(yaml, "a: 1\n  b: 2"), "an indent under a line that is no bare key")
        assertFalse(counts(yaml, "a:\nb: 1"), "a bare key with no indent below")
        assertFalse(counts(yaml, "server:\n  my key: 1\n  port: 2"), "the first entry has a key with whitespace")
        assertFalse(counts(yaml, "server:\n  : x\n  port: 2"), "the first entry has an empty key")
        assertFalse(counts(yaml, "    a:\n  b: 1"), "a line less indented than the bare key")
        assertFalse(counts(yaml, "# a\n# b"), "comments only")
    }

    @Test
    fun `prose with an indented continuation or a list of plain items is not YAML`() {
        assertFalse(counts(yaml, "Todo:\n  call mom\n  buy milk"), "an indent of plain text")
        assertFalse(counts(yaml, "Remember:\n  the meeting is moved"), "a sentence under a header")
        assertFalse(counts(yaml, "Ingredients:\n  2 eggs\n  1 cup flour\nSteps:\n  mix, bake"), "a recipe")
        assertFalse(counts(yaml, "- milk\n- eggs\n- bread"), "a list of plain items")
        assertFalse(counts(yaml, "Shopping list:\n- milk\n- eggs"), "a list under a header with whitespace")
        assertFalse(counts(yaml, "- [ ] buy milk\n- [x] call mom"), "a task list")
        assertFalse(counts(yaml, "Notes:\n- check logs\n- restart server"), "a list under a one-word header")
        // Known limits, pinned: the shape of these is the shape of a real mapping in a list.
        assertTrue(counts(yaml, "- Note: check the logs\n- Fix: restart the server"), "a list of Key: prose")
        assertTrue(counts(yaml, "Todo:\n  call: mom\n  buy: milk"), "an indent of Key: prose")
        assertTrue(counts(yaml, "- Setup:\n  - install java\n  - run gradle"), "a nested Markdown list")
    }

    @Test
    fun `YAML with a top-level key that has whitespace does not count`() {
        assertFalse(counts(yaml, "Things to bring:\n  laptops\n  chargers"), "an indent under it")
        assertFalse(counts(yaml, "my key: 1\n- k: v"), "a list item")
        assertFalse(counts(yaml, "server:\n  port: 1\nmy key: 2"), "the key after a valid part")
        assertFalse(counts(yaml, "a:b c: 1\n- k: v"), "a colon inside the key is skipped")
        assertFalse(counts(yaml, ":a my key: 1\n- k: v"), "a colon at the start is skipped")
        assertFalse(counts(yaml, "a b: 1\n- k: v"), "a space right after the first char")
    }

    @ParameterizedTest
    @ValueSource(strings = [".", "!", "?", ":"])
    fun `CSV with a line ending in sentence punctuation does not count`(end: String) {
        assertFalse(counts(csv, "a,b,c\n1,2,3$end"), "last line")
        assertFalse(counts(csv, "a,b,c$end\n1,2,3"), "first line")
        assertFalse(counts(csv, "a,b,c\n1,2,3$end  "), "trailing spaces")
        assertFalse(counts(csv, "a,b,c\n1,2,3$end\n\n"), "a trailing blank line")
    }

    @Test
    fun `CSV needs every line to have two delimiters outside quotes`() {
        assertTrue(counts(csv, "a.b,c!d,e\n1.5,2?3,4\n"), "punctuation inside the lines")
        assertTrue(counts(csv, "a;b;c\n1;2;3"), "semicolons")
        assertTrue(counts(csv, "id,note,x\n1,\"a, b\",2"), "a comma in quotes is no delimiter, the others count")
        assertFalse(counts(csv, "Hello, world\nHow are you, friend"), "one comma a line")
        assertFalse(counts(csv, "Hi Anna,\nthanks for the update, I will look at it tomorrow\nBest, Tom"), "a letter")
        assertFalse(counts(csv, "John Smith, 12 Main St\nSpringfield, IL 62701"), "an address")
        assertFalse(
            counts(csv, "2026-11-19 10:00:01 INFO Starting app, version 1.2\n2026-11-19 10:00:02 WARN Slow, 3s"),
            "a log",
        )
        assertFalse(counts(csv, "a,b,c\n1,2"), "one line short")
        assertFalse(counts(csv, "a,b,c\n1;2;3"), "two different delimiters")
        assertFalse(counts(csv, "id,note\n1,\"a, b, c\""), "commas in quotes do not count")
        assertFalse(counts(csv, "a,\"b, c, d\"\n1,\"2, 3, 4\""), "commas in quotes on every line")
        assertTrue(counts(csv, "\"x, y\",a,b\n\"p, q\",c,d"), "a quoted field first")
        assertFalse(counts(csv, "key,value\na,b"), "two columns are a known limit")
        // Known limit, pinned: lines with two or more commas each and no sentence end, a list of words or names.
        assertTrue(counts(csv, "Monday, Tuesday, Wednesday\nThursday, Friday, Saturday"), "a list of days")
        assertTrue(counts(csv, "Apples, pears, and plums are ripe now\nEggs, milk, and bread are on the list"), "prose")
        assertTrue(counts(csv, "Smith, John, Dr\nDoe, Jane, Mrs"), "names")
        assertTrue(counts(csv, "10:00 INFO a, b, c\n10:01 WARN d, e, f"), "a log with 3 fields")
    }

    @Test
    fun `TSV needs only the two lines, a spreadsheet range with sentences included`() {
        assertTrue(counts(tsv, "Item\tStatus\tComment\nA\tdone\tLooks good.\nB\topen\tNeeds work!"), "a sheet")
        assertTrue(counts(tsv, "a:b\tc\n1.5\t2.5"), "punctuation inside")
        assertFalse(counts(tsv, "only one line\twith a tab"), "one line")
    }

    @Test
    fun `Properties need a single token key on every line`() {
        assertTrue(counts(properties, "a.b=1\nc.d: 2\n"), "= and : separators")
        assertTrue(counts(properties, "# comment\n! other\na = 1\n\nb=2\n"), "comments, blank lines, spaces")
        assertTrue(counts(properties, "a=1\\\n  continued value\nb=2"), "a continuation line")
        assertFalse(counts(properties, "Hello world=1\na=2"), "a key with whitespace")
        assertFalse(counts(properties, "a=1\nsome text"), "a line without a separator")
        assertFalse(counts(properties, "a=1\nsometext"), "a single word without a separator")
        assertFalse(counts(properties, "a=1\n=2"), "an empty key")
    }

    @Test
    fun `TOML needs only the two lines`() {
        assertTrue(counts(toml, "a = 1\nb = 2."), "TOML")
    }

    @Test
    fun `the prose texts the detection measured count as no data`() {
        val prose =
            "Dear team, a quick update: the release moved to Thursday, because two reviews are still open.\n" +
                "Things to bring: laptops, chargers, and good ideas. Questions? Ask me: I'm around until five."
        assertFalse(counts(csv, prose))
        assertFalse(counts(yaml, "Das ist ein Satz: er hat\nzwei Zeilen, und noch mehr."))
    }
}
