package dev.sluide.translation.text

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

class MarkdownSegmentsTest {
    /** Markdown whose [translatable] sentences are the only text that reaches the model. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("blocks")
    fun `block rules keep what is not prose and translate the rest`(
        name: String,
        text: String,
        translatable: List<String>,
    ) {
        assertEquals(translatable, sentences(text, true), name)
        assertRoundTrip(text, true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inlines")
    fun `inline rules protect spans and keep emphasis in the sentence`(
        name: String,
        text: String,
        translatable: String,
        spans: List<String>,
    ) {
        val sentence = TranslationText.segments(text, true).filterIsInstance<Segment.Sentence>().single()

        assertEquals(translatable, sentence.text, name)
        assertEquals(spans, sentence.spans, name)
        assertRoundTrip(text, true)
    }

    @Test
    fun `line markers stay in place around the translated text`() {
        val text = "## Heading ##\n- [x] Done item\n> > Quoted text\n1) First\n"
        val segments = TranslationText.segments(text, true)

        val result = TranslationText.assemble(text, segments) { it.text.uppercase() }

        assertEquals("## HEADING ##\n- [x] DONE ITEM\n> > QUOTED TEXT\n1) FIRST\n", result)
    }

    @Test
    fun `table cells are translated one by one and the padding stays`() {
        val text = "| Name  | Value |\n| ----- | :---: |\n| One   | Two   |\n"
        val segments = TranslationText.segments(text, true)

        val result = TranslationText.assemble(text, segments) { it.text.uppercase() }

        assertEquals("| NAME  | VALUE |\n| ----- | :---: |\n| ONE   | TWO   |\n", result)
    }

    @Test
    fun `a link keeps its target and the translated text comes between the brackets`() {
        val text = "Read the [user guide](docs/guide.md \"The guide\") first."
        val segments = TranslationText.segments(text, true)

        val result = TranslationText.assemble(text, segments) { "Lies zuerst den {1}Benutzerleitfaden{2}." }

        assertEquals("Lies zuerst den [Benutzerleitfaden](docs/guide.md \"The guide\").", result)
    }

    @Test
    fun `a paragraph that wraps over lines is one sentence with a space for each break`() {
        val text = "It was a long\nsentence that wraps\n   over three lines. Next one."
        val segments = TranslationText.segments(text, true)

        assertEquals(
            listOf("It was a long sentence that wraps over three lines.", "Next one."),
            segments.filterIsInstance<Segment.Sentence>().map { it.text },
        )
        assertEquals(
            "It was a long sentence that wraps over three lines. NEXT ONE.",
            TranslationText.assemble(text, segments) { if (it.text == "Next one.") "NEXT ONE." else it.text },
        )
        assertRoundTrip(text, true)
    }

    @Test
    fun `list item and block quote paragraphs wrap too, the markers of later lines go with the break`() {
        val text = "- item that\n  wraps here\n- second\n\n> quote that\n> wraps here\n"

        assertEquals(
            listOf("item that wraps here", "second", "quote that wraps here"),
            sentences(text, true),
        )
        assertEquals(
            "- ITEM THAT WRAPS HERE\n- SECOND\n\n> QUOTE THAT WRAPS HERE\n",
            TranslationText.assemble(
                text,
                TranslationText.segments(text, true),
            ) {
                it.text.uppercase()
            },
        )
        assertRoundTrip(text, true)
    }

    @Test
    fun `a hard break ends the paragraph and stays verbatim`() {
        val text = "First line  \nSecond line\\\nThird line"
        val segments = TranslationText.segments(text, true)

        assertEquals(
            listOf(
                "First line",
                "Second line",
                "Third line",
            ),
            segments.filterIsInstance<Segment.Sentence>().map {
                it.text
            },
        )
        assertEquals(
            "FIRST LINE  \nSECOND LINE\\\nTHIRD LINE",
            TranslationText.assemble(text, segments) {
                it.text.uppercase()
            },
        )
    }

    @Test
    fun `another block ends a wrapped paragraph`() {
        val text = "Intro line\n# Heading\nMore text\n- item\nAfter\n```\ncode\n```\nEnd\n---\nLast"

        assertEquals(listOf("Intro line", "Heading", "More text", "item After", "End", "Last"), sentences(text, true))
    }

    @Test
    fun `lines of a paragraph before a table header stay a paragraph`() {
        val text = "Intro line\nA | B\n--|--\nc | d\n"

        assertEquals(listOf("Intro line", "A", "B", "c", "d"), sentences(text, true))
    }

    @Test
    fun `a sentence does not end inside inline code`() {
        assertEquals(listOf("Run {1} now.", "Then stop."), sentences("Run `a. b` now. Then stop.", true))
    }

    @Test
    fun `a realistic README round-trips and only prose goes to the model`() {
        val sentences = sentences(README, true)

        assertEquals(
            listOf(
                "My Project",
                "{1}{2}badge{3}{4}",
                "A small tool for {1}.",
                "It is fast.",
                "Install",
                "Run {1} and open {2}.",
                "Install it with the {1}Gradle plugin{2}.",
                "Usage",
                "Name",
                "Meaning",
                "The input file",
                "Quoted advice: stay calm.",
                "See the {1}docs{2} or write to {3}.",
                "A note.{1}",
                "License",
                "MIT, see {1}LICENSE{2}.",
            ),
            sentences.filter { it.isNotBlank() },
        )
        assertRoundTrip(README, true)
        // The code, HTML, comments and definitions are not among the sentences.
        assertEquals(false, sentences.any { "println" in it || "<" in it })
    }

    companion object {
        private val README =
            """
            ---
            title: My Project
            ---
            # My Project

            [![badge](https://img.shields.io/x.svg)](https://example.org/ci)

            <p align="center">
              <img src="logo.png">
            </p>

            <!-- a comment
               over lines -->

            A small tool for `scripts`. It is fast.

            ## Install

            Run `gradle build` and open <http://localhost:8080>.
            Install it with the [Gradle plugin](https://plugins.gradle.org).

            ```kotlin
            fun main() = println("not translated. really.")
            ```

            ## Usage

            | Name | Meaning |
            |------|---------|
            | `-v` | The input file |

            > Quoted advice: stay calm.

            See the [docs][d] or write to team@example.org.

            ---

            A note.[^1]

            [d]: https://example.org/docs "Docs"
            [^1]:

            ## License

            MIT, see [LICENSE](LICENSE).
            """.trimIndent()

        @JvmStatic
        fun blocks() =
            listOf(
                Arguments.of("YAML front matter", "---\ntitle: x\n---\nText here.", listOf("Text here.")),
                Arguments.of("TOML front matter", "+++\ntitle = \"x\"\n+++\nText here.", listOf("Text here.")),
                Arguments.of("a thematic break is no front matter", "---\n\nText here.\n---\n", listOf("Text here.")),
                Arguments.of(
                    "backtick fence",
                    "Before.\n```kotlin\nval a = 1. Two.\n```\nAfter.",
                    listOf("Before.", "After."),
                ),
                Arguments.of("tilde fence, longer closer", "~~~\ncode.\n~~~~\nAfter.", listOf("After.")),
                Arguments.of("unclosed fence runs to the end", "Before.\n```\ncode. more.", listOf("Before.")),
                Arguments.of(
                    "fence in a list item",
                    "- Item.\n\n  ```\n  code. x\n  ```\n\nAfter.",
                    listOf("Item.", "After."),
                ),
                Arguments.of(
                    "indented code after a blank line",
                    "Para.\n\n    code. here\n\nAfter.",
                    listOf("Para.", "After."),
                ),
                Arguments.of("tab-indented code", "Para.\n\n\tcode. here\n", listOf("Para.")),
                Arguments.of(
                    "an indented line continues a paragraph",
                    "Para\n    continues.",
                    listOf("Para continues."),
                ),
                Arguments.of(
                    "an indented line in a list is text",
                    "- Item.\n\n    More text.",
                    listOf("Item.", "More text."),
                ),
                Arguments.of("HTML comment on one line", "<!-- note. -->\nText.", listOf("Text.")),
                Arguments.of("HTML comment over lines", "<!--\nhidden. text\n-->\nText.", listOf("Text.")),
                Arguments.of(
                    "HTML block until a blank line",
                    "<div>\nInside text.\n</div>\n\nOutside.",
                    listOf("Outside."),
                ),
                Arguments.of("script block", "<script>\nvar a = 1;\n\nvar b;\n</script>\nText.", listOf("Text.")),
                Arguments.of("a lone tag line", "<img src=\"a.png\">\n\nText.", listOf("Text.")),
                Arguments.of(
                    "an autolink line is a paragraph",
                    "<https://example.com>\nis the home page.",
                    listOf("{1} is the home page."),
                ),
                Arguments.of(
                    "code inside a list item",
                    "- Run:\n\n      npm install\n      npm test\n\n- Next.",
                    listOf("Run:", "Next."),
                ),
                Arguments.of(
                    "code inside a list item in a quote",
                    "> - item\n>\n>       code here\n>\n> - Next.",
                    listOf("item", "Next."),
                ),
                Arguments.of("an inline tag line is a paragraph", "<b>Bold</b> text.", listOf("{1}Bold{2} text.")),
                Arguments.of(
                    "link reference definition",
                    "[id]: https://example.org \"Title\"\nText.",
                    listOf("Text."),
                ),
                Arguments.of("footnote definition text is translated", "[^1]: The note.", listOf("The note.")),
                Arguments.of("thematic breaks", "A.\n\n***\n\n- - -\n\n___\n\nB.", listOf("A.", "B.")),
                Arguments.of(
                    "ATX heading with closing hashes",
                    "### Title ###\n#hashtag text",
                    listOf("Title", "#hashtag text"),
                ),
                Arguments.of("a heading that ends in a hash", "# Learn C#", listOf("Learn C#")),
                Arguments.of("setext heading", "Title\n=====\n\nOther\n-----\n", listOf("Title", "Other")),
                Arguments.of(
                    "bullets and numbers",
                    "- One.\n* Two.\n+ Three.\n1. Four.\n2) Five.",
                    listOf("One.", "Two.", "Three.", "Four.", "Five."),
                ),
                Arguments.of("task boxes", "- [ ] Open\n- [X] Closed", listOf("Open", "Closed")),
                Arguments.of(
                    "nested block quote",
                    ">> Deep text.\n> Shallow text.",
                    listOf("Deep text.", "Shallow text."),
                ),
                Arguments.of("a code fence inside a quote", "> ```\n> code. x\n> ```\n\nAfter.", listOf("After.")),
                Arguments.of(
                    "table with delimiter row",
                    "| A | B |\n|---|---|\n| c | d |\n\nAfter.",
                    listOf("A", "B", "c", "d", "After."),
                ),
                Arguments.of("a pipe without a delimiter row is text", "a | b", listOf("a | b")),
                Arguments.of("emphasis is no list marker", "**Bold** text.", listOf("**Bold** text.")),
                Arguments.of(
                    "a year after a paragraph line is no list marker",
                    "Text\n2020. Then.",
                    listOf("Text 2020.", "Then."),
                ),
            )

        @JvmStatic
        fun inlines() =
            listOf(
                Arguments.of("inline code", "Use `foo()` here.", "Use {1} here.", listOf("`foo()`")),
                Arguments.of("double backtick code", "Use ``a ` b`` here.", "Use {1} here.", listOf("``a ` b``")),
                Arguments.of("unclosed backticks stay text", "Use `foo here.", "Use `foo here.", emptyList<String>()),
                Arguments.of(
                    "autolink",
                    "Go to <https://example.org/x> now.",
                    "Go to {1} now.",
                    listOf("<https://example.org/x>"),
                ),
                Arguments.of("e-mail autolink", "Write <a@b.org> now.", "Write {1} now.", listOf("<a@b.org>")),
                Arguments.of("inline HTML tags", "A <b>bold</b> word.", "A {1}bold{2} word.", listOf("<b>", "</b>")),
                Arguments.of("inline comment", "A <!-- x --> word.", "A {1} word.", listOf("<!-- x -->")),
                Arguments.of(
                    "link",
                    "See [the docs](a/b.md) now.",
                    "See {1}the docs{2} now.",
                    listOf("[", "](a/b.md)"),
                ),
                Arguments.of(
                    "link with a title",
                    "See [docs](a \"T (x)\") now.",
                    "See {1}docs{2} now.",
                    listOf("[", "](a \"T (x)\")"),
                ),
                Arguments.of(
                    "link with parentheses in the target",
                    "[w](https://x.org/a_(b))",
                    "{1}w{2}",
                    listOf("[", "](https://x.org/a_(b))"),
                ),
                Arguments.of(
                    "image",
                    "An ![alt text](img.png) here.",
                    "An {1}alt text{2} here.",
                    listOf("![", "](img.png)"),
                ),
                Arguments.of(
                    "reference link",
                    "See [the docs][d] now.",
                    "See {1}the docs{2} now.",
                    listOf("[", "][d]"),
                ),
                Arguments.of(
                    "image inside a link",
                    "[![alt](a.png)](b.html)",
                    "{1}{2}alt{3}{4}",
                    listOf("[", "![", "](a.png)", "](b.html)"),
                ),
                Arguments.of(
                    "a shortcut reference stays text",
                    "See [docs] now.",
                    "See [docs] now.",
                    emptyList<String>(),
                ),
                Arguments.of("footnote reference", "A claim[^note] here.", "A claim{1} here.", listOf("[^note]")),
                Arguments.of(
                    "emphasis stays in the sentence",
                    "Is **very** _good_ ~~bad~~.",
                    "Is **very** _good_ ~~bad~~.",
                    emptyList<String>(),
                ),
                Arguments.of(
                    "escapes stay",
                    "Not \\`code\\` or \\[a](b) here.",
                    "Not \\`code\\` or \\[a](b) here.",
                    emptyList<String>(),
                ),
                Arguments.of(
                    "a bare URL in a link text",
                    "[see https://a.org](https://a.org)",
                    "{1}see {2}{3}",
                    listOf("[", "https://a.org", "](https://a.org)"),
                ),
                Arguments.of(
                    "a link text wrapped from the line before",
                    "text](x.md) end.",
                    "text{1} end.",
                    listOf("](x.md)"),
                ),
            )
    }
}
