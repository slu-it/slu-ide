package dev.sluide.githubactions.mermaid

/**
 * Writes a [Diagram] as a Mermaid `flowchart LR`. Subgraphs lay out their children top to bottom (the triggers left to
 * right, so unlinked events stack); Mermaid ignores a subgraph's `direction` once a node inside it links outside, so
 * links between jobs join the subgraphs, not steps.
 */
internal object MermaidWriter {
    fun write(diagram: Diagram): String =
        buildString {
            append("flowchart LR\n")
            for (element in diagram.elements) element(element, 1)
            for (link in diagram.links) link(link, 1)
            for (style in Style.entries) append("    classDef ${style.cssClass} ${style.definition}\n")
            val styled = LinkedHashMap<Style, MutableList<String>>()
            for (element in diagram.elements) collectStyles(element, styled)
            for (style in Style.entries) {
                val ids = styled[style] ?: continue
                append(ids.joinToString(",", prefix = "    class ", postfix = " ${style.cssClass}\n"))
            }
        }

    private fun StringBuilder.element(
        element: Element,
        depth: Int,
    ) {
        when (element) {
            is Node -> {
                node(element.id, element.label, element.shape, depth)
            }

            is ReferenceNode -> {
                val expansion = element.expansion
                if (expansion == null) {
                    node(element.id, element.label, Shape.BOX, depth)
                } else {
                    cluster(expansion.copy(id = element.id), depth)
                }
            }

            is Cluster -> {
                cluster(element, depth)
            }
        }
    }

    private fun StringBuilder.cluster(
        cluster: Cluster,
        depth: Int,
    ) {
        indent(depth)
            .append("subgraph ")
            .append(cluster.id)
            .append(" [\"")
            .append(cluster.title.ifBlank { EMPTY })
            .append("\"]\n")
        indent(depth + 1).append("direction ${cluster.direction}\n")
        for (child in cluster.children) element(child, depth + 1)
        for (link in cluster.links) link(link, depth + 1)
        indent(depth).append("end\n")
    }

    private fun StringBuilder.node(
        id: String,
        label: List<String>,
        shape: Shape,
        depth: Int,
    ) {
        val text = label.joinToString("<br/>").ifBlank { EMPTY }
        indent(depth).append(id)
        when (shape) {
            Shape.BOX -> append("[\"").append(text).append("\"]\n")
            Shape.STADIUM -> append("([\"").append(text).append("\"])\n")
        }
    }

    private fun StringBuilder.link(
        link: Link,
        depth: Int,
    ) {
        indent(depth).append(link.from).append(if (link.dotted) " -.->" else " -->")
        if (!link.label.isNullOrBlank()) append("|\"").append(link.label).append("\"|")
        append(' ').append(link.to).append('\n')
    }

    private fun collectStyles(
        element: Element,
        styled: MutableMap<Style, MutableList<String>>,
    ) {
        when (element) {
            is Node -> {
                element.style?.let { styled.getOrPut(it) { mutableListOf() } += element.id }
            }

            is ReferenceNode -> {
                val expansion = element.expansion
                if (expansion == null) {
                    styled.getOrPut(element.style) { mutableListOf() } += element.id
                } else {
                    for (child in expansion.children) collectStyles(child, styled)
                }
            }

            is Cluster -> {
                for (child in element.children) collectStyles(child, styled)
            }
        }
    }

    private fun StringBuilder.indent(depth: Int) = append("    ".repeat(depth))

    /** Mermaid rejects an empty (or blank) quoted label, and with it the whole chart; this is a blank as entity code. */
    private const val EMPTY = "#32;"
}
