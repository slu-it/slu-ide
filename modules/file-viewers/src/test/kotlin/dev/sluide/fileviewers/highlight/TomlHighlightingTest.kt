package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** TOML 1.0, mostly with the examples of the specification (toml.io/en/v1.0.0). */
class TomlHighlightingTest {
    private fun toml(text: String) = Highlighted(text, TomlHighlighter)

    private fun Highlighted.assertUnstyled(
        snippet: String,
        occurrence: Int = 0,
    ) = assertEquals(emptySet(), stylesOf(snippet, occurrence), "$snippet (#$occurrence)")

    private val document =
        toml(
            """
            |# This is a TOML document
            |
            |title = "TOML Example"
            |
            |[owner]
            |name = "Tom Preston-Werner"
            |dob = 1979-05-27T07:32:00-08:00
            |
            |[database]
            |enabled = true
            |ports = [ 8000, 8001, 8002 ]
            |data = [ ["delta", "phi"], [3.14] ]
            |temp_targets = { cpu = 79.5, case = 72.0 }
            |
            |[servers]
            |
            |[servers.alpha]
            |ip = "10.0.0.1" # the first
            |role = "frontend"
            |
            |[[products]]
            |name = "Hammer"
            |sku = 738594937
            |
            |[ j . "ʞ" . 'l' ]  # same as [j."ʞ".'l']
            |
            """.trimMargin(),
        )

    @Test
    fun `comments, also after a value or a header`() {
        document.assertExactly(TokenStyle.COMMENT, "# This is a TOML document")
        document.assertExactly(TokenStyle.COMMENT, "# the first")
        document.assertExactly(TokenStyle.COMMENT, "# same as [j.\"ʞ\".'l']")
        val inString = toml("""a = "# no comment" # comment""")
        inString.assertExactly(TokenStyle.STRING, "\"# no comment\"")
        inString.assertExactly(TokenStyle.COMMENT, "# comment")
    }

    @Test
    fun `table headers are types, as a whole`() {
        document.assertExactly(TokenStyle.TYPE, "[owner]")
        document.assertExactly(TokenStyle.TYPE, "[servers.alpha]")
        document.assertExactly(TokenStyle.TYPE, "[[products]]")
        document.assertExactly(TokenStyle.TYPE, "[ j . \"ʞ\" . 'l' ]")
        val indented = toml("  [dog.\"tater.man\"]\n  type.name = \"pug\"\n[a.\"]\"]\n[[fruits.varieties]]\n")
        indented.assertExactly(TokenStyle.TYPE, "[dog.\"tater.man\"]")
        indented.assertExactly(TokenStyle.PROPERTY, "type.name")
        indented.assertExactly(TokenStyle.TYPE, "[a.\"]\"]")
        indented.assertExactly(TokenStyle.TYPE, "[[fruits.varieties]]")
    }

    @Test
    fun `an unterminated header runs to a comment or the end of its line`() {
        val text = toml("[a.b  # c\n[[d\nx = 1\n")
        text.assertExactly(TokenStyle.TYPE, "[a.b")
        text.assertExactly(TokenStyle.COMMENT, "# c")
        text.assertExactly(TokenStyle.TYPE, "[[d")
        text.assertExactly(TokenStyle.PROPERTY, "x")
    }

    @Test
    fun `keys are bare, quoted or dotted, before the equals sign`() {
        val keys =
            toml(
                """
                |key = "value"
                |bare_key = "value"
                |bare-key = "value"
                |1234 = "value"
                |"127.0.0.1" = "value"
                |"character encoding" = "value"
                |'key2' = "value"
                |'quoted "value"' = "value"
                |"" = "blank"
                |physical.color = "orange"
                |site."google.com" = true
                |fruit . flavor = "banana"
                |3.14159 = "pi"
                |
                """.trimMargin(),
            )
        for (key in listOf(
            "key",
            "bare_key",
            "bare-key",
            "1234",
            "\"127.0.0.1\"",
            "\"character encoding\"",
            "'key2'",
            "'quoted \"value\"'",
            "\"\"",
            "physical.color",
            "site.\"google.com\"",
            "fruit . flavor",
            "3.14159",
        )) {
            keys.assertExactly(TokenStyle.PROPERTY, key)
        }
        keys.assertUnstyled(" = ")
        keys.assertExactly(TokenStyle.STRING, "\"value\"")
        keys.assertExactly(TokenStyle.STRING, "\"blank\"")
        keys.assertExactly(TokenStyle.STRING, "\"pi\"")
    }

    @Test
    fun `keys inside inline tables, also nested and in arrays`() {
        document.assertExactly(TokenStyle.PROPERTY, "cpu")
        document.assertExactly(TokenStyle.PROPERTY, "case")
        document.assertExactly(TokenStyle.NUMBER, "79.5")
        val inline =
            toml(
                """
                |name = { first = "Tom", last = "Preston-Werner" }
                |point = {x=1,y=2}
                |animal = { type.name = "pug", "the key" = { inner = 'x' } }
                |points = [ { x = 1, y = 2, z = 3 },
                |           { x = 7, y = 8, z = 9 } ]
                |
                """.trimMargin(),
            )
        for (key in listOf("name", "first", "last", "point", "animal", "type.name", "\"the key\"", "inner", "points")) {
            inline.assertExactly(TokenStyle.PROPERTY, key)
        }
        inline.assertExactly(TokenStyle.PROPERTY, "x")
        inline.assertExactly(TokenStyle.PROPERTY, "y")
        inline.assertExactly(TokenStyle.NUMBER, "1")
        inline.assertExactly(TokenStyle.PROPERTY, "z")
        inline.assertExactly(TokenStyle.PROPERTY, "x", 2)
        inline.assertExactly(TokenStyle.NUMBER, "9")
        inline.assertExactly(TokenStyle.STRING, "\"Preston-Werner\"")
    }

    @Test
    fun `words in arrays and after values are no keys`() {
        document.assertExactly(TokenStyle.STRING, "\"delta\"")
        document.assertExactly(TokenStyle.NUMBER, "3.14")
        document.assertExactly(TokenStyle.NUMBER, "8001")
        val text = toml("a = [\n  b,\n  { c = d },\n]\ne = f g\n")
        text.assertUnstyled("b")
        text.assertExactly(TokenStyle.PROPERTY, "c")
        text.assertUnstyled("d")
        text.assertExactly(TokenStyle.PROPERTY, "e")
        text.assertUnstyled("f g")
    }

    @Test
    fun `basic and literal strings`() {
        val strings =
            toml(
                """
                |str = "I'm a string. \"You can quote me\". Name\tJos\u00E9\nLocation\tSF."
                |winpath  = 'C:\Users\nodejs\templates'
                |quoted   = 'Tom "Dubs" Preston-Werner'
                |regex    = '<\i\c*\s*>'
                |open = "runs to the end
                |next = 'too
                |back = "slash\
                |
                """.trimMargin(),
            )
        strings.assertExactly(
            TokenStyle.STRING,
            "\"I'm a string. \\\"You can quote me\\\". Name\\tJos\\u00E9\\nLocation\\tSF.\"",
        )
        strings.assertExactly(TokenStyle.STRING, "'C:\\Users\\nodejs\\templates'")
        strings.assertExactly(TokenStyle.STRING, "'Tom \"Dubs\" Preston-Werner'")
        strings.assertExactly(TokenStyle.STRING, "'<\\i\\c*\\s*>'")
        strings.assertExactly(TokenStyle.STRING, "\"runs to the end")
        strings.assertExactly(TokenStyle.PROPERTY, "next")
        strings.assertExactly(TokenStyle.STRING, "'too")
        strings.assertExactly(TokenStyle.STRING, "\"slash\\")
    }

    @Test
    fun `multi-line strings span lines, with up to two quotes before their end`() {
        val strings =
            toml(
                "str1 = \"\"\"\nRoses are red\nViolets are blue\"\"\"\n" +
                    "str2 = \"\"\"\nThe quick brown \\\n\n  fox jumps over \\\n    the lazy dog.\"\"\"\n" +
                    "str4 = \"\"\"Here are two quotation marks: \"\". Simple enough.\"\"\"\n" +
                    "str5 = \"\"\"Here are three quotation marks: \"\"\\\".\"\"\"\n" +
                    "str7 = \"\"\"\"This,\" she said, \"is just a pointless statement.\"\"\"\"\n" +
                    "lines  = '''\nThe first newline is\ntrimmed in raw strings.\n   All other whitespace\n" +
                    "   is preserved.\n'''\n" +
                    "quot15 = '''Here are fifteen quotation marks: \"\"\"\"\"\"\"\"\"\"\"\"\"\"\"'''\n" +
                    "apos15 = \"Here are fifteen apostrophes: '''''''''''''''\"\n" +
                    "str = ''''That,' she said, 'is still pointless.''''\n" +
                    "after = 42\n",
            )
        strings.assertExactly(TokenStyle.STRING, "\"\"\"\nRoses are red\nViolets are blue\"\"\"")
        strings.assertExactly(
            TokenStyle.STRING,
            "\"\"\"\nThe quick brown \\\n\n  fox jumps over \\\n    the lazy dog.\"\"\"",
        )
        strings.assertExactly(TokenStyle.STRING, "\"\"\"Here are two quotation marks: \"\". Simple enough.\"\"\"")
        strings.assertExactly(TokenStyle.STRING, "\"\"\"Here are three quotation marks: \"\"\\\".\"\"\"")
        strings.assertExactly(
            TokenStyle.STRING,
            "\"\"\"\"This,\" she said, \"is just a pointless statement.\"\"\"\"",
        )
        strings.assertExactly(
            TokenStyle.STRING,
            "'''\nThe first newline is\ntrimmed in raw strings.\n   All other whitespace\n   is preserved.\n'''",
        )
        strings.assertExactly(
            TokenStyle.STRING,
            "'''Here are fifteen quotation marks: \"\"\"\"\"\"\"\"\"\"\"\"\"\"\"'''",
        )
        strings.assertExactly(TokenStyle.STRING, "\"Here are fifteen apostrophes: '''''''''''''''\"")
        strings.assertExactly(TokenStyle.STRING, "''''That,' she said, 'is still pointless.''''")
        for (key in listOf("str1", "str2", "str4", "str5", "str7", "lines", "quot15", "apos15", "after")) {
            strings.assertExactly(TokenStyle.PROPERTY, key)
        }
        strings.assertExactly(TokenStyle.NUMBER, "42")
    }

    @Test
    fun `an unterminated multi-line string runs to the end of the text`() {
        val text = toml("a = \"\"\"\n[table]\nb = 1\n")
        text.assertExactly(TokenStyle.STRING, "\"\"\"\n[table]\nb = 1\n")
        toml("a = '''x\n# y\n").assertExactly(TokenStyle.STRING, "'''x\n# y\n")
        toml("a = \"\"\"x\\").assertExactly(TokenStyle.STRING, "\"\"\"x\\")
    }

    @Test
    fun `integers, floats, infinity and nan are numbers`() {
        val numbers =
            toml(
                """
                |int1 = +99
                |int2 = 42
                |int3 = 0
                |int4 = -17
                |int5 = 1_000
                |int6 = 5_349_221
                |hex1 = 0xDEADBEEF
                |hex3 = 0xdead_beef
                |oct1 = 0o01234567
                |bin1 = 0b11010110
                |flt1 = +1.0
                |flt3 = -0.01
                |flt4 = 5e+22
                |flt5 = 1e06
                |flt6 = -2E-2
                |flt7 = 6.626e-34
                |flt8 = 224_617.445_991_228
                |sf1 = inf
                |sf2 = +inf
                |sf3 = -inf
                |sf4 = nan
                |sf5 = +nan
                |sf6 = -nan
                |word = info
                |
                """.trimMargin(),
            )
        for (number in listOf(
            "+99",
            "42",
            "-17",
            "1_000",
            "5_349_221",
            "0xDEADBEEF",
            "0xdead_beef",
            "0o01234567",
            "0b11010110",
            "+1.0",
            "-0.01",
            "5e+22",
            "1e06",
            "-2E-2",
            "6.626e-34",
            "224_617.445_991_228",
            "+inf",
            "-inf",
            "+nan",
            "-nan",
        )) {
            numbers.assertExactly(TokenStyle.NUMBER, number)
        }
        numbers.assertExactly(TokenStyle.NUMBER, "0")
        numbers.assertExactly(TokenStyle.NUMBER, "inf", 0)
        numbers.assertExactly(TokenStyle.NUMBER, "nan", 0)
        numbers.assertUnstyled("info")
        numbers.assertExactly(TokenStyle.PROPERTY, "int1")
        numbers.assertExactly(TokenStyle.PROPERTY, "sf6")
    }

    @Test
    fun `dates and times are numbers`() {
        for (date in listOf(
            "1979-05-27T07:32:00Z",
            "1979-05-27T00:32:00-07:00",
            "1979-05-27T00:32:00.999999-07:00",
            "1979-05-27 07:32:00Z",
            "1979-05-27T07:32:00",
            "1979-05-27T00:32:00.999999",
            "1979-05-27",
            "07:32:00",
            "00:32:00.999999",
        )) {
            toml("odt = $date # c\n").assertExactly(TokenStyle.NUMBER, date)
        }
        document.assertExactly(TokenStyle.NUMBER, "1979-05-27T07:32:00-08:00")
        val inArray = toml("dates = [1979-05-27 07:32:00, 1979-05-27]")
        inArray.assertExactly(TokenStyle.NUMBER, "1979-05-27 07:32:00")
        inArray.assertExactly(TokenStyle.NUMBER, "1979-05-27", 1)
        val notTime = toml("a = 1979-05-27 7\n")
        notTime.assertExactly(TokenStyle.NUMBER, "1979-05-27")
        notTime.assertExactly(TokenStyle.NUMBER, "7", 2)
    }

    @Test
    fun `booleans are keywords`() {
        document.assertExactly(TokenStyle.KEYWORD, "true")
        val text = toml("a = false\nb = [true, false]\ntrue = 1\nc = trueish\n")
        text.assertExactly(TokenStyle.KEYWORD, "false")
        text.assertExactly(TokenStyle.KEYWORD, "true")
        text.assertExactly(TokenStyle.KEYWORD, "false", 1)
        text.assertExactly(TokenStyle.PROPERTY, "true", 1)
        text.assertUnstyled("trueish")
    }

    @Test
    fun `brackets out of place change nothing before them`() {
        val text = toml("a = ]\nb = }\n[t]\nc = [1, }\nd = 2\n")
        text.assertExactly(TokenStyle.PROPERTY, "a")
        text.assertExactly(TokenStyle.PROPERTY, "b")
        text.assertExactly(TokenStyle.TYPE, "[t]")
        text.assertExactly(TokenStyle.NUMBER, "1")
        text.assertExactly(TokenStyle.PROPERTY, "d", 0)
        text.assertExactly(TokenStyle.NUMBER, "2")
    }

    @Test
    fun `an unclosed array ends at a key or a table header starting a line`() {
        val header = toml("a = [1, 2\n[t]\nk = 1\n")
        header.assertExactly(TokenStyle.TYPE, "[t]")
        header.assertExactly(TokenStyle.PROPERTY, "k")

        val key = toml("deps = [\n  \"httpx\",\nname = \"x\"\n[[tool.x]] # c\n")
        key.assertExactly(TokenStyle.STRING, "\"httpx\"")
        key.assertExactly(TokenStyle.PROPERTY, "name")
        key.assertExactly(TokenStyle.TYPE, "[[tool.x]]")
        key.assertExactly(TokenStyle.COMMENT, "# c")

        val quoted = toml("a = [\n  [\"d\".e]\nb = [\n  'f' . g = 1\n")
        quoted.assertExactly(TokenStyle.TYPE, "[\"d\".e]")
        quoted.assertExactly(TokenStyle.PROPERTY, "'f' . g")
    }

    @Test
    fun `a closing bracket closes what is open inside its opener`() {
        val array = toml("a = [ { b = 1 ]\nk = 1\n")
        array.assertExactly(TokenStyle.PROPERTY, "b")
        array.assertExactly(TokenStyle.PROPERTY, "k")
        val table = toml("a = {b = [1}\nk = 1\n")
        table.assertExactly(TokenStyle.PROPERTY, "b")
        table.assertExactly(TokenStyle.PROPERTY, "k")
    }

    @Test
    fun `arrays of arrays and of inline tables over several lines stay values`() {
        val arrays = toml("a = [\n  [1, 2],\n  [3, 4],\n]\nk = 1\n")
        for (number in listOf("1", "2", "3", "4")) arrays.assertExactly(TokenStyle.NUMBER, number)
        arrays.assertUnstyled("[", 1)
        arrays.assertExactly(TokenStyle.PROPERTY, "k")

        // Alone on a line (the last element), nested arrays look like headers; their content says they are none.
        for (element in listOf("[5]", "[\"s\"]", "['s']", "[true]", "[-inf]", "[-1.5]", "[1979-05-27]", "[[1]]")) {
            val nested = toml("a = [\n  [0],\n  $element\n]\nk = 1\n")
            assertEquals(false, TokenStyle.TYPE in nested.stylesOf(element), element)
            nested.assertExactly(TokenStyle.PROPERTY, "k")
        }

        val tables =
            toml(
                "deps = [\n  { name = \"a\" },\n  { name = \"b\",\n    version = \"1\" },\n" +
                    "  {\n    name = \"c\"\n  },\n]\nk = 1\n",
            )
        tables.assertExactly(TokenStyle.PROPERTY, "name", 1)
        tables.assertExactly(TokenStyle.PROPERTY, "version")
        tables.assertExactly(TokenStyle.PROPERTY, "name", 2)
        tables.assertExactly(TokenStyle.STRING, "\"c\"")
        tables.assertExactly(TokenStyle.PROPERTY, "k")
    }

    @Test
    fun `inline tables over several lines, as TOML 1_1 allows`() {
        val text = toml("a = {\n  b = 1,\n  c = [\n    2,\n  ],\n}\nd = 3\n")
        text.assertExactly(TokenStyle.PROPERTY, "b")
        text.assertExactly(TokenStyle.PROPERTY, "c")
        text.assertExactly(TokenStyle.NUMBER, "2")
        text.assertExactly(TokenStyle.PROPERTY, "d")
    }

    @Test
    fun `a uv lock file`() {
        val lock =
            toml(
                """
                |version = 1
                |requires-python = ">=3.12"
                |
                |[[package]]
                |name = "anyio"
                |version = "4.6.2"
                |source = { registry = "https://pypi.org/simple" }
                |dependencies = [
                |    { name = "idna" },
                |    { name = "sniffio" },
                |]
                |sdist = { url = "https://files.pythonhosted.org/a.tar.gz", hash = "sha256:f9", size = 173422 }
                |
                |[package.optional-dependencies]
                |trio = [
                |    { name = "trio" },
                |]
                |
                """.trimMargin(),
            )
        lock.assertExactly(TokenStyle.PROPERTY, "requires-python")
        lock.assertExactly(TokenStyle.TYPE, "[[package]]")
        lock.assertExactly(TokenStyle.PROPERTY, "registry")
        lock.assertExactly(TokenStyle.PROPERTY, "name", 2)
        lock.assertExactly(TokenStyle.STRING, "\"sniffio\"")
        lock.assertExactly(TokenStyle.PROPERTY, "hash")
        lock.assertExactly(TokenStyle.NUMBER, "173422")
        lock.assertExactly(TokenStyle.TYPE, "[package.optional-dependencies]")
        lock.assertExactly(TokenStyle.PROPERTY, "trio")
        lock.assertExactly(TokenStyle.STRING, "\"trio\"")
        lock.assertWellFormed()
    }

    @Test
    fun `toml code fences use it`() {
        assertSame(TomlHighlighter, RSyntaxHighlighter.forLanguage("toml"))
        val markdown =
            Highlighted("# Config\n\n```toml\n[tool.ruff]\nline-length = 120\n```\n", RSyntaxHighlighter.markdown)
        markdown.assertExactly(TokenStyle.TYPE, "[tool.ruff]")
        markdown.assertExactly(TokenStyle.PROPERTY, "line-length")
        markdown.assertExactly(TokenStyle.NUMBER, "120")
    }

    @Test
    fun `CRLF line endings`() {
        val text = toml("[a]\r\nb = 1 # c\r\nd = \"e\r\n")
        text.assertExactly(TokenStyle.TYPE, "[a]")
        text.assertExactly(TokenStyle.PROPERTY, "b")
        text.assertExactly(TokenStyle.COMMENT, "# c")
        text.assertExactly(TokenStyle.PROPERTY, "d")
    }

    @Test
    fun `spans are well-formed`() = document.assertWellFormed()
}
