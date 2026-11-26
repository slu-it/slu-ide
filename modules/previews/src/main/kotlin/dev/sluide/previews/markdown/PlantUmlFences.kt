package dev.sluide.previews.markdown

import dev.sluide.fileviewers.Highlighters
import dev.sluide.plantuml.Rendering
import dev.sluide.plantuml.plantUmlBlocks
import dev.sluide.previews.plantuml.PlantUmlHtml
import org.commonmark.node.FencedCodeBlock
import org.commonmark.renderer.html.HtmlWriter
import org.jsoup.nodes.Element
import java.util.UUID

/**
 * Where the PlantUML fences of a page get their drawings, for [MarkdownRenderer.body] (opt-in: without it, a PlantUML
 * fence is highlighted code). [get] is a page's `DiagramRenders.get`: the rendering of a source whose first line is
 * `firstLine` in the page's numbering (the Markdown file's lines, 1-based; a notebook cell's), or null while it is
 * drawn. An error's line follows [line] ("Line 12: …", in a notebook "Cell 3, line 2: …").
 */
internal class FenceDiagrams(
    val line: String = "Line",
    val get: (source: String, firstLine: Int) -> Rendering?,
)

/**
 * The PlantUML fences (` ```plantuml `, ` ```puml `) of one rendering of a Markdown text, drawn by [diagrams]. The
 * renderer [write]s an empty placeholder for each, marked by a class of this rendering that the text cannot know, and
 * [insert] replaces the placeholders in the sanitised body by the diagrams ([PlantUmlHtml.diagrams]) or by "Drawing the
 * diagram…", each carrying the fence's `data-line`. So the fence's text never becomes HTML: it reaches the page only as
 * the engine's SVG in a `data:` URI, which the sanitiser would drop. A fence without a `@start…` line is wrapped in
 * `@startuml`/`@enduml`. The source is drawn from line 1 on, so that a fence moved by an edit above it, or the same
 * fence twice, is drawn once; its error lines are shifted to the text's when the page is built: the source's first line
 * is the fence's first content line, or the line before it for a wrapped fence.
 */
internal class PlantUmlFences(
    private val diagrams: FenceDiagrams,
) {
    private class Fence(
        val source: String,
        val firstLine: Int,
        val line: Int,
    )

    private val marker = "plantuml-fence-" + UUID.randomUUID()

    private val fences = mutableListOf<Fence>()

    /**
     * Whether a fence of [language] ([Highlighters.fenceLanguage]) with [code] is drawn; an empty one stays an empty
     * code block.
     */
    fun draws(
        language: String,
        code: String,
    ): Boolean = (language == "plantuml" || language == "puml") && code.isNotBlank()

    /** Writes the placeholder of [block] to [html]. */
    fun write(
        block: FencedCodeBlock,
        html: HtmlWriter,
    ) {
        // The fence's opening line, 0-based; its first content line is two further on in 1-based numbering.
        val line = block.sourceSpans.first().lineIndex
        val code = block.literal
        fences +=
            if (plantUmlBlocks(code)) {
                Fence(code, line + 2, line)
            } else {
                Fence("@startuml\n" + code.removeSuffix("\n") + "\n@enduml\n", line + 1, line)
            }
        html.tag("div", mapOf("class" to "$marker-${fences.size - 1}"))
        html.tag("/div")
    }

    /** Replaces the placeholders in [body], the sanitised rendering, by the fences' diagrams. */
    fun insert(body: Element) {
        fences.forEachIndexed { index, fence ->
            // Gone if the file's HTML swallowed it (an unclosed `<textarea>` takes the rest as text).
            val placeholder = body.selectFirst("div.$marker-$index") ?: return@forEachIndexed
            val rendering = diagrams.get(fence.source, 1)
            val shown =
                if (rendering == null) {
                    PlantUmlHtml.drawing()
                } else {
                    PlantUmlHtml.diagrams(rendering, diagrams.line, fence.firstLine - 1)
                }
            placeholder.replaceWith(shown.attr("data-line", fence.line.toString()))
        }
    }
}
