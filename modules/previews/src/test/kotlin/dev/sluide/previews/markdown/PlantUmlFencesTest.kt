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

    /**
     * As `DiagramRenders.get`: the renderings that are [drawn], others pending; a source with a line `bad` fails on
     * that line, numbered from the first line as the engine numbers it.
     */
    private val diagrams =
        FenceDiagrams { source, firstLine ->
            asked += source to firstLine
            val bad = source.lines().indexOf("bad")
            if (bad < 0) drawn[source] else Rendering(listOf(Diagram.Failed("Syntax?", firstLine + bad)), false)
        }

    /** The texts of the error boxes in [body]. */
    private fun errors(body: Element) = body.select("pre.plantuml-error").map { it.text() }

    private fun body(
        markdown: String,
        with: FenceDiagrams = diagrams,
    ): Element {
        val fences = PlantUmlFences(with)
        return MarkdownRenderer.body(markdown, dir, frontMatter = true, fences = fences).also(fences::insert)
    }

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
        assertEquals(listOf(wrapped), asked.map { it.first })
    }

    @Test
    fun `several fences each draw, in order, a block of their own is not wrapped`() {
        val own = "@startmindmap\n* root\n@endmindmap\n"
        drawn[wrapped] = rendering("one")
        drawn[own] = rendering("two")

        val body = body("```plantuml\nA -> B\n```\n\n```puml\n$own```\n\n```plantuml\nC -> D\nbad\n```\n")

        assertEquals(listOf(wrapped, own, "@startuml\nC -> D\nbad\n@enduml\n"), asked.map { it.first })
        assertEquals(listOf("0", "4", "10"), body.children().map { it.attr("data-line") })
        assertEquals(listOf("<svg>one</svg>", "<svg>two</svg>"), body.select("img.plantuml-light").map(::svg))
        assertEquals(listOf("Line 13: Syntax?"), errors(body))
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
            "- item\\n\\n  ```plantuml\\n  bad\\n  ```\\n|2",
            "> quote\\n>\\n> ```puml\\n> bad\\n> ```\\n|2",
            "---\\ntitle: x\\n---\\n\\n```plantuml\\nbad\\n```\\n|4",
        ],
    )
    fun `a fence in a list, a block quote or after front matter keeps the file's lines`(
        written: String,
        line: Int,
    ) {
        val diagram = body(written.replace("\\n", "\n")).select("div.plantuml").single()

        assertEquals(line.toString(), diagram.attr("data-line"))
        // The fence's only content line.
        assertEquals(listOf("Line ${line + 2}: Syntax?"), errors(diagram))
    }

    @Test
    fun `a fence with a start line but no end line says it has no block`() {
        // The real engine: PlantUML makes no block without an @end line, so the rendering has no diagram.
        val engine =
            FenceDiagrams { source, firstLine -> PlantUml.shared.render(source, firstLine).get(30, TimeUnit.SECONDS) }

        val body = body("# Notes\n\n```plantuml\n@startuml\nAlice -> Bob\n```\n", engine)

        val note = body.select("p.diagram-note").single()
        assertEquals(PlantUmlHtml.NO_BLOCK_IN_FENCE, note.text())
        assertEquals("2", note.attr("data-line"))
        assertTrue(body.select(".plantuml").isEmpty())
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

        assertEquals(listOf(source), asked.map { it.first })
        assertTrue(body.select("script, [onerror]").isEmpty())
        assertTrue("alert" !in body.outerHtml(), body.outerHtml())
        assertEquals(listOf("<svg>safe</svg>", "<svg>safe dark</svg>"), svgs(body))
    }

    @Test
    fun `a diagram has no text runs, the runs around it stay in order`() {
        val markdown = "Before\n\n```plantuml\nbad\n```\n\nAfter\n"

        val page = Jsoup.parse(MarkdownRenderer.render(markdown, dir, diagrams = diagrams))

        val diagram = assertNotNull(page.selectFirst("div.plantuml"))
        assertEquals(listOf("Line 4: Syntax?"), errors(diagram))
        assertTrue(diagram.select("[data-o]").isEmpty())
        assertEquals(
            listOf(0 to "Before", markdown.indexOf("After") to "After"),
            page.select("span[data-o]").map { it.attr("data-o").toInt() to it.text() },
        )
    }

    @Test
    fun `an edited fence shows its previous drawing while its new text is drawn`() {
        drawn[wrapped] = rendering("old")
        body("Intro\n\n```plantuml\nA -> B\n```\n\n```puml\nC -> D\n```\n")

        val body = body("Intro\n\n```plantuml\nA -> B : edited\n```\n\n```puml\nC -> D\n```\n")

        assertEquals(listOf("<svg>old</svg>"), body.select("img.plantuml-light").map(::svg))
        assertEquals(listOf("2", "6"), body.select("div.plantuml, p.diagram-note").map { it.attr("data-line") })
        assertTrue("@startuml\nA -> B : edited\n@enduml\n" in asked.map { it.first }, "its new text is drawn")
    }

    @Test
    fun `an edited fence keeps its previous drawing when its page is built again before the new one is there`() {
        drawn[wrapped] = rendering("old")
        body("```plantuml\nA -> B\n```\n")
        val edited = "```plantuml\nA -> B : edited\n```\n"
        body(edited)

        val again = body(edited)

        assertEquals(listOf("<svg>old</svg>"), again.select("img.plantuml-light").map(::svg))
    }

    @Test
    fun `an edited fence keeps the latest drawing shown in its place, not an older one`() {
        drawn[wrapped] = rendering("first")
        body("```plantuml\nA -> B\n```\n")
        drawn["@startuml\nA -> B : second\n@enduml\n"] = rendering("second")
        body("```plantuml\nA -> B : second\n```\n")

        val body = body("```plantuml\nA -> B : third\n```\n")

        assertEquals(listOf("<svg>second</svg>"), body.select("img.plantuml-light").map(::svg))
    }

    @Test
    fun `of two fences edited at once, the second keeps its previous drawing when the first one's new drawing comes`() {
        drawn[wrapped] = rendering("old one")
        drawn["@startuml\nC -> D\n@enduml\n"] = rendering("old two")
        body("```plantuml\nA -> B\n```\n\n```plantuml\nC -> D\n```\n")
        val edited = "```plantuml\nA -> B : edited\n```\n\n```plantuml\nC -> D : edited\n```\n"
        body(edited)
        drawn["@startuml\nA -> B : edited\n@enduml\n"] = rendering("new one")

        val body = body(edited)

        assertEquals(listOf("<svg>new one</svg>", "<svg>old two</svg>"), body.select("img.plantuml-light").map(::svg))
    }

    @Test
    fun `a fence the file's HTML swallowed still counts, so that an edited fence after it keeps its drawing`() {
        drawn["@startuml\nC -> D\n@enduml\n"] = rendering("old")

        // The first fence's placeholder is in the textarea's text, which ends before the second fence.
        fun page(second: String) =
            "Text <textarea>\n\n```plantuml\nA -> B\n```\n\nEnd </textarea>\n\n```plantuml\n$second\n```\n"
        body(page("C -> D"))

        val body = body(page("C -> D : edited"))

        assertEquals(listOf("<svg>old</svg>"), body.select("img.plantuml-light").map(::svg))
    }

    @Test
    fun `an edited fence says it is drawn when fences were added or removed`() {
        drawn[wrapped] = rendering("old")
        body("```plantuml\nA -> B\n```\n")

        val body = body("```plantuml\nA -> B : edited\n```\n\n```plantuml\nE -> F\n```\n")

        assertTrue(body.select("img").isEmpty())
        assertEquals(2, body.select("p.diagram-note").size)
    }

    @Test
    fun `a new fence where another was says it is drawn when the other's text is still on the page`() {
        drawn[wrapped] = rendering("old")
        body("```plantuml\nA -> B\n```\n\n```plantuml\nX -> Y\n```\n")

        // A new fence above: the old one moved to second place.
        val body = body("```plantuml\nN -> M\n```\n\n```plantuml\nA -> B\n```\n")

        assertEquals(PlantUmlHtml.DRAWING, body.child(0).text())
        assertEquals(listOf("<svg>old</svg>"), body.child(1).select("img.plantuml-light").map(::svg))
    }
}
