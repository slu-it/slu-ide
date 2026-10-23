package dev.sluide.githubactions.mermaid

/**
 * The boxes, links and characters a [WorkflowDrawing] may still use. Text is estimated as [MermaidWriter] writes it:
 * labels plus ids, an indent of 4 per nesting [depth][takeNode] and the brackets around them, so nested expansions
 * with their longer ids and deeper indents are counted fully. [attempt] draws something on trial and takes it back if
 * it did not fit.
 */
internal class Budget(
    private val maxNodes: Int,
    private val maxEdges: Int,
    private val maxText: Int,
) {
    var nodes = 0
        private set
    var edges = 0
        private set
    private var text = 0

    /** Whether a box was left out for [maxNodes] or the text. */
    var nodesCut = false
        private set

    /** Whether a link was left out for [maxEdges] or the text. */
    var edgesCut = false
        private set

    /** Whether one more box may still fit: worth drawing (or reading) something new for. */
    fun hasRoom(): Boolean = nodes < maxNodes && edges < maxEdges && text < maxText

    /** A box with escaped [labelLength] characters (with line breaks) at [depth], written as `id["…"]`. */
    fun takeNode(
        labelLength: Int,
        id: String,
        depth: Int,
    ): Boolean {
        val length = labelLength + NODE_OVERHEAD + INDENT * depth + 2 * id.length
        if (nodes >= maxNodes || text + length > maxText) {
            nodesCut = true
            return false
        }
        nodes++
        text += length
        return true
    }

    /** A link with an escaped [labelLength], written at [depth] (one deeper than the subgraph it is in). */
    fun takeEdge(
        labelLength: Int,
        from: String,
        to: String,
        depth: Int,
    ): Boolean {
        val length = labelLength + EDGE_OVERHEAD + INDENT * depth + from.length + to.length
        if (edges >= maxEdges || text + length > maxText) {
            edgesCut = true
            return false
        }
        edges++
        text += length
        return true
    }

    /**
     * A box with escaped [labelLength] characters and its link to [target], both or neither: one that does not fit is
     * left out without counting as cut, so the rest of the diagram is as it would be without it.
     */
    fun takeOptional(
        labelLength: Int,
        id: String,
        target: String,
        depth: Int,
    ): Boolean {
        val node = labelLength + NODE_OVERHEAD + INDENT * depth + 2 * id.length
        val edge = EDGE_OVERHEAD + INDENT * depth + id.length + target.length
        if (nodes >= maxNodes || edges >= maxEdges || text + node + edge > maxText) return false
        nodes++
        edges++
        text += node + edge
        return true
    }

    /** A subgraph's three lines (`subgraph id ["title"]`, `direction TB`, `end`) at [depth]; may go over the limit. */
    fun addCluster(
        titleLength: Int,
        id: String,
        depth: Int,
    ) {
        text += titleLength + CLUSTER_OVERHEAD + 3 * INDENT * depth + id.length
    }

    /** Text that may be needed later (a condition that may move into a title); may go over the limit. */
    fun reserve(length: Int) {
        text += length
    }

    /**
     * Runs [draw] on trial: if it left something out or went over the text limit, everything it took is given back and
     * the result is null. The cut flags stay as before either way.
     */
    fun <T : Any> attempt(draw: () -> T?): T? {
        val before = State(nodes, edges, text, nodesCut, edgesCut)
        nodesCut = false
        edgesCut = false
        val result = draw()
        val fits = result != null && !nodesCut && !edgesCut && text <= maxText
        if (!fits) {
            nodes = before.nodes
            edges = before.edges
            text = before.text
        }
        nodesCut = before.nodesCut
        edgesCut = before.edgesCut
        return if (fits) result else null
    }

    private data class State(
        val nodes: Int,
        val edges: Int,
        val text: Int,
        val nodesCut: Boolean,
        val edgesCut: Boolean,
    )

    private companion object {
        const val INDENT = 4

        /** `id["…"]`, line end, and the id's `,` in a `class` line (the ids themselves come on top, twice). */
        const val NODE_OVERHEAD = 8

        /** ` -.->|""| ` and line end (the ids come on top). */
        const val EDGE_OVERHEAD = 12

        /** `subgraph  [""]`, `direction TB`, `end`, `direction` indented once more, line ends (the id on top). */
        const val CLUSTER_OVERHEAD = 36
    }
}
