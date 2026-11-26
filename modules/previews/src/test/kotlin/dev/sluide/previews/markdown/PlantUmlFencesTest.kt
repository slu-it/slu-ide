package dev.sluide.previews.markdown

import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.PlantUml
import dev.sluide.plantuml.Rendering
import dev.sluide.previews.plantuml.PlantUmlHtml
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** PlantUML fences in Markdown ([PlantUmlFences]): where they are drawn, from which source, with which lines. */
class PlantUmlFencesTest {
    @TempDir
    lateinit var dir: Path

    /** What the page asked for: each source with its first line. */
    private val asked = mutableListOf<Pair<String, Int>>()

    /** The renderings that are there; any other is pending. */
    private val drawn = mutableMapOf<String, Rendering>()

    private val diagrams =
        FenceDiagrams { source, firstLine ->
            asked += source to firstLine
            drawn[source]
        }

    private fun body(
        markdown: String,
        with: FenceDiagrams = diagrams,
    ): Element = MarkdownRenderer.body(markdown, dir, frontMatter = true, diagrams = with)

    private fun rendering(name: String) =
        Rendering(listOf(Diagram.Drawn("<svg>$name</svg>", "<svg>$name dark</svg>")), false)

    /** The SVG of [image], decoded from its `data:` URI. */
    private fun svg(image: Element): String {
        val src = image.attr("src")
        val prefix = "data:image/svg+xml;base64,"
        assertTrue(src.startsWith(prefix), src)
        return String(Base64.getDecoder().decode(src.removePrefix(prefix)), Charsets.UTF_8)
    }

    /** The SVGs of the images in [element]. */
    private fun svgs(element: Element): List<String> = element.select("img").map(::svg)

    private val wrapped = "@startuml\nA -> B\n@enduml\n"

    @ParameterizedTest
    @ValueSource(strings = ["plantuml", "puml", "PlantUML", "puml title=\"x\""])
    fun `a fence with its rendering is the diagram in place, with the fence's line`(info: String) {
        drawn[wrapped] = rendering("one")

        val body = body("Intro\n\n```$info\nA -> B\n```\n\nAfter\n")

        val diagram = body.select("div.plantuml").single()
        assertEquals("2", diagram.attr("data-line"))
        assertEquals(listOf("<svg>one</svg>", "<svg>one dark</svg>"), svgs(diagram))
        assertEquals(listOf("p", "div", "p"), body.children().map { it.tagName() })
        assertTrue(body.select("pre, code").isEmpty(), "no code block")
    }

    @Test
    fun `a fence whose drawing is pending says so, with the fence's line`() {
        val body = body("# Title\n\n```plantuml\nA -> B\n```\n")

        val note = body.select("p.diagram-note").single()
        assertEquals(PlantUmlHtml.DRAWING, note.text())
        assertEquals("2", note.attr("data-line"))
        assertEquals(listOf(wrapped to 3), asked)
    }

    @Test
    fun `several fences each draw, in order, a block of their own is not wrapped`() {
        val own = "@startmindmap\n* root\n@endmindmap\n"
        drawn[wrapped] = rendering("one")
        drawn[own] = rendering("two")

        val body = body("```plantuml\nA -> B\n```\n\n```puml\n$own```\n\n```plantuml\nC -> D\n```\n")

        assertEquals(listOf(wrapped to 1, own to 6, "@startuml\nC -> D\n@enduml\n" to 11), asked)
        assertEquals(listOf("0", "4", "10"), body.children().map { it.attr("data-line") })
        assertEquals(listOf("<svg>one</svg>", "<svg>two</svg>"), body.select("img.plantuml-light").map(::svg))
        assertEquals(PlantUmlHtml.DRAWING, body.selectFirst("p.diagram-note")?.text())
    }

    @Test
    fun `the file's HTML cannot pose as a fence's placeholder`() {
        drawn[wrapped] = rendering("one")
        // What a placeholder's class would be if it had no random part.
        val body = body("<div class=\"plantuml-fence-null-0\">forged</div>\n\n```plantuml\nA -> B\n```\n")

        assertEquals(listOf("forged", ""), body.children().map { it.text() })
        assertEquals(1, body.child(1).select("img.plantuml-light").size)
    }

    @Test
    fun `a fence the file's HTML swallows is not drawn`() {
        // An unclosed `<textarea>` in a paragraph takes the rest of the page as its text, also the fence's placeholder.
        val body = body("Text <textarea>\n\n```plantuml\nA -> B\n```\n")

        assertTrue(body.select(".plantuml, .diagram-note").isEmpty())
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `an empty fence stays an empty code block and is not drawn`() {
        val body = body("```plantuml\n```\n\n```puml\n  \n```\n")

        assertEquals(listOf("", "  \n"), body.select("pre > code").map { it.wholeText() })
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `without diagrams a PlantUML fence stays highlighted code, as on every other page`() {
        val page = Jsoup.parse(MarkdownRenderer.render("```plantuml\nA -> B\n```\n", dir))

        assertEquals("A -> B\n", page.selectFirst("pre > code.language-plantuml")?.wholeText())
        assertTrue(page.select(".plantuml, .diagram-note").isEmpty())
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            // The fence's opening line, 0-based, after a list item, in a block quote, after front matter.
            "- item\\n\\n  ```plantuml\\n  A -> B\\n  ```\\n|2",
            "> quote\\n>\\n> ```puml\\n> A -> B\\n> ```\\n|2",
            "---\\ntitle: x\\n---\\n\\n```plantuml\\nA -> B\\n```\\n|4",
        ],
    )
    fun `a fence in a list, a block quote or after front matter keeps the file's lines`(
        written: String,
        line: Int,
    ) {
        drawn[wrapped] = rendering("one")

        val diagram = body(written.replace("\\n", "\n")).select("div.plantuml").single()

        assertEquals(line.toString(), diagram.attr("data-line"))
        assertEquals(listOf(wrapped to line + 1), asked)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "A -> B\\nB -> A\\nthis is no diagram\\n",
            "@startuml\\nA -> B\\nthis is no diagram\\n@enduml\\n",
        ],
    )
    fun `an error names the line in the Markdown file, of a wrapped fence too`(content: String) {
        // The real engine, so that the line is PlantUML's own.
        val engine =
            FenceDiagrams { source, firstLine -> PlantUml.shared.render(source, firstLine).get(30, TimeUnit.SECONDS) }
        // Lines 1 to 9, then the fence on line 10: its third content line is line 13.
        val markdown =
            (1..9).joinToString("") { "Text $it\n" } + "```plantuml\n" + content.replace("\\n", "\n") + "```\n"
        assertEquals("```plantuml", markdown.lines()[9])

        val error = body(markdown, engine).select("pre.plantuml-error").single()

        assertTrue(error.text().startsWith("Line 13: "), error.text())
    }

    @Test
    fun `a fence's source only reaches the page as the engine's drawing`() {
        val hostile = "A -> B : </div></pre><script>alert(1)</script><img src=x onerror=alert(2)>\n"
        val source = "@startuml\n$hostile@enduml\n"
        drawn[source] = rendering("safe")

        val body = body("```plantuml\n$hostile```\n")

        assertEquals(listOf(source to 1), asked)
        assertTrue(body.select("script, [onerror]").isEmpty())
        assertTrue("alert" !in body.outerHtml(), body.outerHtml())
        assertEquals(listOf("<svg>safe</svg>", "<svg>safe dark</svg>"), svgs(body))
    }

    @Test
    fun `a diagram has no text runs, the runs around it stay in order`() {
        drawn[wrapped] = Rendering(listOf(Diagram.Failed("bad", 3)), true)
        val markdown = "Before\n\n```plantuml\nA -> B\n```\n\nAfter\n"

        val page = Jsoup.parse(MarkdownRenderer.render(markdown, dir, diagrams = diagrams))

        val diagram = assertNotNull(page.selectFirst("div.plantuml"))
        assertEquals("Line 3: bad", diagram.selectFirst("pre.plantuml-error")?.text())
        assertTrue(diagram.select("[data-o]").isEmpty())
        assertEquals(
            listOf(0 to "Before", markdown.indexOf("After") to "After"),
            page.select("span[data-o]").map { it.attr("data-o").toInt() to it.text() },
        )
    }
}
