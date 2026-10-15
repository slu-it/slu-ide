package dev.sluide.fileviewers

/**
 * Highlights Mermaid with [lines] (line rules, see `RSyntaxHighlighter.mermaid`) and adds the diagram type (and a
 * flowchart's direction) as keywords: only on the first line that is no comment, directive or front matter (`---`
 * block), since names like `block` or `pie` may also be nodes further down.
 */
internal class MermaidHighlighter(
    private val lines: SyntaxHighlighter,
) : SyntaxHighlighter {
    override fun highlight(text: String): List<HighlightSpan> {
        val spans = lines.highlight(text)
        val type = diagramType(text)
        if (type.isEmpty()) return spans
        return (spans.filter { span -> type.none { it.start < span.end && span.start < it.end } } + type)
            .sortedBy { it.start }
    }

    private fun diagramType(text: String): List<HighlightSpan> {
        var lineStart = 0
        var frontMatter = false
        var first = true
        for (line in text.split('\n')) {
            val content = line.removeSuffix("\r").trim()
            when {
                content == "---" && (first || frontMatter) -> {
                    frontMatter = !frontMatter
                }

                frontMatter || content.isEmpty() || content.startsWith("%%") -> {}

                else -> {
                    val match = TYPE.find(line) ?: return emptyList()
                    return listOfNotNull(match.groups[1], match.groups[2]).map {
                        HighlightSpan(lineStart + it.range.first, lineStart + it.range.last + 1, TokenStyle.KEYWORD)
                    }
                }
            }
            if (content.isNotEmpty()) first = false
            lineStart += line.length + 1
        }
        return emptyList()
    }

    private companion object {
        /** Mermaid's diagram types; longer names before their prefixes. */
        const val DIAGRAMS =
            "flowchart-elk|flowchart|graph|sequenceDiagram|classDiagram-v2|classDiagram|stateDiagram-v2|" +
                "stateDiagram|erDiagram|gantt|pie|journey|gitGraph|mindmap|timeline|quadrantChart|" +
                "requirementDiagram|C4Context|C4Container|C4Component|C4Dynamic|C4Deployment|sankey-beta|sankey|" +
                "xychart-beta|xychart|block-beta|block|packet-beta|packet|architecture-beta|kanban|radar-beta|" +
                "treemap-beta|zenuml"

        val TYPE = Regex("""^\s*($DIAGRAMS)(?![\w-])(?:\s+(TB|TD|BT|RL|LR)\b)?""")
    }
}
