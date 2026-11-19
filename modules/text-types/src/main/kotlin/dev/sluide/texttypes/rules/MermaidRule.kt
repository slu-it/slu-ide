package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/**
 * Mermaid by the diagram keyword on the first line that is neither blank nor a `%%` comment, as the whole line (with
 * the keyword's own options: a direction, `pie title …`), followed by at least one more line.
 */
internal object MermaidRule : TextTypeSource {
    const val SCORE = 90

    private val keywordLine =
        Regex(
            """(?:graph|flowchart)(?:[ \t]++(?:TB|TD|BT|RL|LR))?;?""" +
                """|sequenceDiagram|classDiagram(?:-v2)?|stateDiagram(?:-v2)?|erDiagram""" +
                """|gantt|journey|mindmap|timeline""" +
                """|gitGraph(?:[ \t]++(?:TB|BT|LR))?:?|pie(?:[ \t]++showData)?(?:[ \t]++title[ \t].*)?""",
        )

    override fun scores(text: String): List<TypeScore> {
        val lines =
            text
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("%%") }
                .take(2)
                .toList()
        if (lines.size < 2 || !keywordLine.matches(lines.first())) return emptyList()
        return listOf(TypeScore(TextTypes.MERMAID, SCORE))
    }
}
