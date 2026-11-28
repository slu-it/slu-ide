package dev.sluide.httpclient.complete

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VariableCompletionTest {
    @TempDir
    lateinit var folder: Path

    private val envJson = """{"dev": {"host": "SECRET-HOST-VALUE", "port": 1}, "prod": {"host": "other"}}"""

    private fun envs(json: String = envJson) = environments(folder, public = json)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET {{|",
            "GET /a\n  {{|",
            "GET /a\nX-Y: {{|",
            "GET /a\nX-Y: a\n  {{|",
            "POST /a\n\n{\"a\": \"{{|",
            "POST /a\n\n< ./{{|",
            "@b = {{|",
            "GET /a\n###\nGET /b?q={{|",
            "GET {{ho|",
            "GET /a\r\nX-Y: {{|\r\n",
        ],
    )
    fun `offers variables after an open brace pair`(marked: String) {
        val answer = answer(marked, envs())

        assertTrue("host" in answer.labels, answer.labels.toString())
        assertEquals("", answer.replaced.removePrefix("ho"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "# {{|",
            "// {{|",
            "# @name {{|",
            "### {{|",
            "GET /a\n# {{|",
            "GET /a\n\n{\"a\": 1}\n> {% {{|",
            "< {%\n {{|",
            "GET /a\n\n{\"a\": 1}\n> {%\n {{|\n%}",
            "GET {{host}}|",
            "GET {|",
            "GET /a\n{{|",
            "GET /a\n\n{\"a\": 1}\n<> {{|",
        ],
    )
    fun `offers no variables where none belong`(marked: String) {
        val answer = answer(marked, envs())

        assertEquals(emptyList(), answer.labels)
        assertEquals(answer.caret, answer.anchor)
    }

    @Test
    fun `anchors right after the braces and skips spaces`() {
        assertEquals("", answer("GET {{|", envs()).replaced)
        assertEquals("\$uu", answer("GET {{\$uu|", envs()).replaced)
        assertEquals("ho", answer("GET {{   ho|", envs()).replaced)
        assertEquals("ho", answer("GET {{a}} {{ho|", envs()).replaced)
    }

    @Test
    fun `finds the open braces at the line start, after closed ones and behind tabs`() {
        assertTrue("host" in answer("{{|", envs()).labels)
        assertTrue("host" in answer("GET {{a}}{{|", envs()).labels)
        assertEquals("ho", answer("GET {{\tho|", envs()).replaced)
        assertEquals("", answer("GET {{\t|", envs()).replaced)
    }

    @Test
    fun `offers neither the variable of the line being typed nor a dollar name of an environment`() {
        val labels = answer("@a = 1\n@b = {{|", envs("""{"dev": {"${'$'}dollar": 1, "ok": 1}}""")).labels

        assertTrue("a" in labels && "ok" in labels)
        assertFalse("b" in labels)
        assertFalse("\$dollar" in labels)
    }

    @Test
    fun `leaves out auth ids that cannot be written in a call`() {
        val ids = """"": {}, "a{{b": {}, "line\nbreak": {}, "tab\tid": {}, "u\u2028id": {}, "v\u2029id": {}, "ok": {}"""
        val json = """{"dev": {"Security": {"Auth": {$ids}}}}"""

        val labels = answer("GET {{\$auth.|", envs(json)).labels

        assertEquals(listOf("\$auth.idToken(\"ok\")", "\$auth.token(\"ok\")"), labels)
    }

    @ParameterizedTest
    @ValueSource(strings = ["a", "Ä", "5", "²", "Ⅷ", "́", "ः", "_", "𝒜"])
    fun `counts letters, numbers, marks and underscores as part of the word`(char: String) {
        assertTrue("\$random.email" in answer("GET {{\$random.$char|").labels)
    }

    @ParameterizedTest
    @ValueSource(strings = ["-", "/", " ", "$", ".", "{", "\"", "("])
    fun `ends the word at any other character`(char: String) {
        assertEquals(emptyList(), answer("GET {{\$random.$char|").labels)
    }

    @Test
    fun `reads the names of the operating system by default`() {
        val name = System.getenv().keys.first { it.matches(Regex("[A-Za-z_]+")) }
        val text = "GET {{\$env."

        val result = CompletionEngine().complete(text, text.length) { NO_ENVIRONMENTS }

        assertTrue("\$env.$name" in result.items.map { it.label })
    }

    @Test
    fun `inserts the closing braces unless they follow`() {
        assertEquals("host", answer("GET /a\nX: {{|}}", envs()).item("host").insertText)
        val open = answer("GET {{ho|", envs()).item("host")
        val closed = answer("GET {{ho|}}", envs()).item("host")
        val other = answer("GET {{ho|}", envs()).item("host")

        assertEquals("host}}", open.insertText)
        assertEquals("host", closed.insertText)
        assertEquals("host}}", other.insertText)
        assertEquals("\$uuid", answer("GET {{\$uu|}}").item("\$uuid").insertText)
    }

    @Test
    fun `orders file variables, environment names, dynamic and auth variables and drops duplicates`() {
        val auth = """{"dev": {"host": "a", "alpha": "b", "Security": {"Auth": {"gh": {"Type": "OAuth2"}}}}}"""
        val answer = answer("@zeta = 1\n@host = 2\n@alpha = 3\nGET {{|", envs(auth))

        val labels = answer.labels
        val plain = labels.filterNot { it.startsWith("\$") }
        assertEquals(listOf("alpha", "host", "zeta"), plain)
        assertEquals("dev, @ variable", answer.item("alpha").detail)
        assertEquals("dev, @ variable", answer.item("host").detail)
        assertEquals("@ variable", answer.item("zeta").detail)
        assertTrue(labels.indexOf("zeta") < labels.indexOf("\$random.alphabetic"))
        assertTrue(labels.indexOf("\$uuid") < labels.indexOf("\$auth.token(\"gh\")"))
        assertTrue(labels.indexOf("\$auth.idToken(\"gh\")") < labels.indexOf("\$auth.token(\"gh\")"))
        assertEquals(labels.size, labels.toSet().size)
        val dynamic = labels.filter { it.startsWith("\$") && !it.startsWith("\$auth") }
        assertEquals(dynamic.sorted(), dynamic.sortedWith(compareBy<String> { it }))
    }

    @Test
    fun `lists environment names alphabetically with the environments defining them`() {
        val answer = answer("GET {{|", envs("""{"dev": {"b": 1, "host": 1}, "prod": {"a": 1, "host": 1}}"""))

        assertEquals(listOf("a", "b", "host"), answer.labels.filterNot { it.startsWith("\$") })
        assertEquals("dev, prod", answer.item("host").detail)
        assertEquals("prod", answer.item("a").detail)
    }

    @Test
    fun `names environments in the order of the files`() {
        val envs =
            environments(
                folder,
                public = """{"prod": {"x": 1}, "dev": {"x": 1}}""",
                private = """{"local": {"x": 1}}""",
            )

        assertEquals("prod, dev, local", answer("GET {{|", envs).item("x").detail)
    }

    @Test
    fun `never shows a value of an environment`() {
        val envs =
            environments(
                folder,
                public = envJson,
                private =
                    """{"dev": {"token": "PRIVATE-VALUE-42", "Security": {"Auth": {"gh": {"Client Secret": "SECRET-S"}}}}}""",
            )
        val items = answer("GET {{|", envs).items
        val all = items.joinToString("\n") { "${it.label}|${it.insertText}|${it.detail}" }

        assertTrue("token" in all)
        val values = listOf("SECRET-HOST-VALUE", "PRIVATE-VALUE-42", "SECRET-S", "other")
        for (value in values) assertFalse(value in all, value)
    }

    @Test
    fun `pairs the braces from the left as the template does`() {
        assertEquals(emptyList(), answer("GET {{{|", envs()).labels)
        assertEquals(6, answer("GET {{{|", envs()).anchor)
        assertTrue("host" in answer("GET {{a}}{{{b}}{{|", envs()).labels)
        assertEquals(emptyList(), answer("GET {{{{}}|", envs()).labels)
        assertEquals(emptyList(), answer("GET {{{}}|", envs()).labels)
    }

    @Test
    fun `counts braces behind only spaces or tabs as closing`() {
        assertEquals("host", answer("GET {{ho|  }}", envs()).item("host").insertText)
        assertEquals("host", answer("GET {{ho| \t}}", envs()).item("host").insertText)
        assertEquals("host}}", answer("GET {{ho| x}}", envs()).item("host").insertText)
    }

    @Test
    fun `counts braces behind the rest of the name as closing`() {
        assertEquals("host", answer("GET {{ho|st}}", envs()).item("host").insertText)
        assertEquals("host", answer("GET {{ho|st \t}}", envs()).item("host").insertText)
        assertEquals("host}}", answer("GET {{ho|st x}}", envs()).item("host").insertText)
        assertEquals("host}}", answer("GET {{ho|st(}}", envs()).item("host").insertText)
    }

    @Test
    fun `inserts an opening parenthesis instead of the braces for names that need an argument`() {
        val answer = answer("GET {{\$random.|", envs())

        for (name in listOf("alphabetic", "alphanumeric", "hexadecimal")) {
            assertEquals("\$random.$name(", answer.item("\$random.$name").insertText)
        }
        assertEquals("\$random.integer}}", answer.item("\$random.integer").insertText)
        assertEquals("\$random.float}}", answer.item("\$random.float").insertText)
        assertEquals("\$random.email}}", answer.item("\$random.email").insertText)
        assertEquals("\$random.alphabetic(", answer("GET {{\$random.al|}}").item("\$random.alphabetic").insertText)
    }

    @Test
    fun `offers the dollar names with their hints`() {
        val answer = answer("GET {{\$|", NO_ENVIRONMENTS)

        assertTrue(answer.labels.all { it.startsWith("\$") })
        assertEquals("(from, to)", answer.item("\$random.integer").detail)
        assertEquals("dynamic", answer.item("\$uuid").detail)
        assertEquals("\$env.", answer.item("\$env.").insertText)
        assertTrue("\$random.email" in answer.labels)
    }

    @Test
    fun `holds back what does not start with the fixed part`() {
        // The editor filters by the word (`uu`); the fixed part is what lies between the anchor and the word.
        val dollar = answer("GET {{\$uu|").labels
        assertTrue("\$uuid" in dollar && "\$isoTimestamp" in dollar && dollar.all { it.startsWith("\$") })
        val random = answer("GET {{\$random.alpha|").labels
        assertTrue("\$random.alphabetic" in random && "\$random.email" in random)
        assertTrue(random.all { it.startsWith("\$random.") }, random.toString())
        assertEquals(emptyList(), answer("GET {{host.|", envs()).labels)
    }

    @Test
    fun `offers the names of the operating system environment after env dot`() {
        val os = setOf("HOME", "HOSTNAME", "PATH", "BAD NAME", "A(B)")

        val answer = answer("GET {{\$env.HO|", NO_ENVIRONMENTS, os)

        assertEquals(listOf("\$env.HOME", "\$env.HOSTNAME", "\$env.PATH"), answer.labels)
        assertEquals("\$env.HOME}}", answer.item("\$env.HOME").insertText)
        assertEquals("\$env.HO", answer.replaced)
        assertEquals(
            listOf("\$env.HOME", "\$env.HOSTNAME", "\$env.PATH"),
            answer("GET {{\$env.|", NO_ENVIRONMENTS, os).labels,
        )
        assertEquals(
            emptyList(),
            answer("GET {{\$random.|", NO_ENVIRONMENTS, os).labels.filter { it.startsWith("\$env") },
        )
    }

    @Test
    fun `offers auth calls for the ids of all environments`() {
        val json =
            """{"dev": {"Security": {"Auth": {"github": {"Type": "OAuth2"}, "a\"b": {}, "x}}y": {}}}},
               "prod": {"Security": {"Auth": {"github": {"Type": "OAuth2"}, "gitlab": {"Type": "OAuth2"}}}}}"""
        val answer = answer("GET {{\$auth.|", envs(json))

        assertEquals(
            listOf(
                "\$auth.idToken(\"github\")",
                "\$auth.idToken(\"gitlab\")",
                "\$auth.token(\"github\")",
                "\$auth.token(\"gitlab\")",
            ).sorted(),
            answer.labels.sorted(),
        )
        assertEquals("dev, prod", answer.item("\$auth.token(\"github\")").detail)
        assertEquals("prod", answer.item("\$auth.idToken(\"gitlab\")").detail)
        assertEquals("\$auth.token(\"github\")}}", answer.item("\$auth.token(\"github\")").insertText)
    }

    @Test
    fun `offers only names the resolver can resolve and survives hostile names`() {
        val json =
            """{"dev": {"ok": 1, "with space": 1, "a(b)": 1, "br{ace": 1, "${'$'}dollar": 1, "q\"uote": 1, "<b>x</b>": 1,
               "e}}nd": 1, "": 1, "line\nbreak": 1, "dot.ted": 1}}"""
        val labels = answer("GET {{|", envs(json)).labels

        assertEquals(listOf("<b>x</b>", "dot.ted", "ok", "q\"uote"), labels.filterNot { it.startsWith("\$") })
    }

    @Test
    fun `answers a huge line`() {
        val line = "x".repeat(300_000)

        val answer = answer("GET /$line?a={{ho|", envs())

        assertTrue("host" in answer.labels)
    }
}
