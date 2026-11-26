package dev.sluide.previews.markdown

import dev.sluide.fileviewers.Highlighters
import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.Rendering
import dev.sluide.plantuml.plantUmlBlocks
import dev.sluide.previews.plantuml.PlantUmlHtml
import org.commonmark.node.FencedCodeBlock
import org.commonmark.renderer.html.HtmlWriter
import org.jsoup.nodes.Element
import java.util.UUID

/**
 * Where the PlantUML fences of a view's pages get their drawings (opt-in: without it, a PlantUML fence is highlighted
 * code). [get] is the view's `DiagramRenders.get`: the rendering of a source, or null while it is drawn. One per view
 * (or per export), since it keeps what the view's previous page showed ([PlantUmlFences.insert]).
 */
internal class FenceDiagrams(
    val get: (source: String, firstLine: Int) -> Rendering?,
) {
    /**
     * The fences of the previous page, in order: the last drawing shown in each place and the text it belongs to (a
     * kept drawing stays with its old text), or the fence's text and null if none was shown.
     */
    internal var shown: List<Pair<String, Rendering?>> = emptyList()
}

/**
 * The PlantUML fences (` ```plantuml `, ` ```puml `) of one page (a Markdown file, all Markdown cells of a notebook),
 * drawn by [diagrams]. The renderer [write]s an empty placeholder for each, marked by a class of this page that the
 * text cannot know, and [insert] replaces the placeholders in the sanitised page by the diagrams
 * ([PlantUmlHtml.diagrams]) or by "Drawing the diagram…", each carrying the fence's `data-line`. So the fence's text
 * never becomes HTML: it reaches the page only as the engine's SVG in a `data:` URI, which the sanitiser would drop. A
 * fence without a `@start…` line is wrapped in `@startuml`/`@enduml`. The source is drawn from line 1 on, so that a
 * fence moved by an edit above it, or the same fence twice, is drawn once; its error lines are shifted to the text's
 * when the page is built: the source's first line is the fence's first content line, or the line before it for a
 * wrapped fence.
 */
internal class PlantUmlFences(
    private val diagrams: FenceDiagrams,
) {
    private class Fence(
        val source: String,
        val firstLine: Int,
        val line: Int,
        val label: String,
    )

    private val marker = "plantuml-fence-" + UUID.randomUUID()

    private val fences = mutableListOf<Fence>()

    /** What an error's line is called in the fences [write]n next: "Line 12: …", in a notebook "Cell 3, line 2: …". */
    var line = "Line"

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
        val at = block.sourceSpans.first().lineIndex
        val code = block.literal
        fences +=
            if (plantUmlBlocks(code)) {
                Fence(code, at + 2, at, line)
            } else {
                Fence("@startuml\n" + code.removeSuffix("\n") + "\n@enduml\n", at + 1, at, line)
            }
        html.tag("div", mapOf("class" to "$marker-${fences.size - 1}"))
        html.tag("/div")
    }

    /**
     * Replaces the placeholders in [page], the sanitised page with all of them, by the fences' diagrams. A fence whose
     * drawing is not there yet shows the drawing the previous page showed in its place, if that fence was edited:
     * if the previous page had as many fences and the previous one's text is nowhere on this page (else it was moved).
     * Returns a line for the user about each failed diagram shown: "PlantUML diagram (line N) could not be drawn: …",
     * N being the error's line in the text, or the fence's opening line if PlantUML named none (the drawing took too
     * long, for example).
     */
    fun insert(page: Element): List<String> {
        val previous = diagrams.shown.takeIf { it.size == fences.size }.orEmpty()
        val sources = fences.map { it.source }.toSet()
        val shown = mutableListOf<Pair<String, Rendering?>>()
        val failed = mutableListOf<String>()
        fences.forEachIndexed { index, fence ->
            // Gone if the file's HTML swallowed it (an unclosed `<textarea>` takes the rest as text); it still counts.
            val placeholder = page.selectFirst("div.$marker-$index")
            if (placeholder == null) {
                shown += fence.source to null
                return@forEachIndexed
            }
            val drawing = diagrams.get(fence.source, 1)
            val kept = if (drawing == null) previous.getOrNull(index)?.takeIf { it.first !in sources } else null
            val rendering = drawing ?: kept?.second
            // A kept drawing stays with the text it belongs to, so that it is kept again until the new one is there.
            shown += kept ?: (fence.source to drawing)
            for (diagram in rendering?.diagrams.orEmpty().filterIsInstance<Diagram.Failed>()) {
                val line = diagram.line?.let { it + fence.firstLine - 1 } ?: (fence.line + 1)
                failed += "PlantUML diagram (line $line) could not be drawn: ${diagram.message}"
            }
            val element =
                if (rendering == null) {
                    PlantUmlHtml.drawing()
                } else {
                    PlantUmlHtml.diagrams(rendering, fence.label, fence.firstLine - 1)
                }
            placeholder.replaceWith(element.attr("data-line", fence.line.toString()))
        }
        diagrams.shown = shown
        return failed
    }
}
