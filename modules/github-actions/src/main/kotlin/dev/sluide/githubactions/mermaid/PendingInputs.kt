package dev.sluide.githubactions.mermaid

/**
 * The inputs a [WorkflowDrawing] wants shown, asked for with [defer] while it draws and added by [draw] once everything
 * else is drawn: they come after every box and link that is needed, so they never cut anything, and inputs that do not
 * fit the [budget] are left out without a note. [size] is the budget size of a label.
 *
 * A `uses:` drawn as a box shows its inputs in a framed block below its label ([ReferenceNode.inputs]); an expanded
 * composite action or called workflow gets a framed box at its top, kept above its first steps or jobs by invisible
 * links.
 */
internal class PendingInputs(
    private val budget: Budget,
    private val size: (List<String>) -> Int,
) {
    /** The inputs (the `with:`) of the `uses:` drawn under the id [target]. */
    class Wanted(
        val target: String,
        val inputs: Map<String, String>,
    )

    /** [wanted] inputs of a `uses:` among the [children] of a subgraph, which are at [depth]. */
    private class Entry(
        val wanted: Wanted,
        val depth: Int,
        val children: MutableList<Element>,
    )

    private val entries = mutableListOf<Entry>()

    /** Asks for the inputs of each of [wanted] that has inputs, drawn among [children] at [depth]. */
    fun defer(
        wanted: List<Wanted>,
        depth: Int,
        children: MutableList<Element>,
    ) {
        for (one in wanted) if (one.inputs.isNotEmpty()) entries += Entry(one, depth, children)
    }

    /** A point to [trim] back to. */
    fun mark(): Int = entries.size

    /** Forgets what was asked for since [mark]: the expansion that asked did not fit and is taken back. */
    fun trim(mark: Int) {
        while (entries.size > mark) entries.removeLast()
    }

    /**
     * Adds the inputs asked for, as many as fit, in the order asked: the inputs within an expansion before those of
     * the `uses:` it is drawn for, which copies the expansion with what was added to it.
     */
    fun draw() {
        for (entry in entries) {
            val lines = StepInputs.lines(entry.wanted.inputs)
            val position = entry.children.indexOfFirst { it.id == entry.wanted.target }
            val target = entry.children.getOrNull(position) as? ReferenceNode
            if (lines.isEmpty() || target == null) continue
            val expansion = target.expansion
            val shown =
                if (expansion !=
                    null
                ) {
                    atTop(target, expansion, lines, entry.depth + 1)
                } else {
                    inLabel(target, lines)
                }
            if (shown != null) entry.children[position] = shown
        }
        entries.clear()
    }

    /** [target] with [lines] below its label, or null if their text does not fit. */
    private fun inLabel(
        target: ReferenceNode,
        lines: List<String>,
    ): ReferenceNode? = target.copy(inputs = lines).takeIf { budget.takeText(MermaidLabels.inputs(lines).length) }

    /**
     * [target] with a box of [lines] at the top of its [expansion], whose children are at [depth]: linked invisibly to
     * each child that no link leads to (the first step; the jobs that need none), so that it stands above them. Null if
     * the box and its links do not fit.
     */
    private fun atTop(
        target: ReferenceNode,
        expansion: Cluster,
        lines: List<String>,
        depth: Int,
    ): ReferenceNode? {
        val box = Node("${target.id}i", lines, Shape.ROUNDED, Style.INPUTS)
        val linked = expansion.links.map { it.to }.toSet()
        val links = expansion.children.filter { it.id !in linked }.map { Link(box.id, it.id, invisible = true) }
        val fits =
            budget.attempt {
                val drawn =
                    budget.takeNode(size(lines), box.id, depth) &&
                        links.all { budget.takeEdge(0, it.from, it.to, depth) }
                Unit.takeIf { drawn }
            }
        if (fits == null) return null
        val top = expansion.copy(children = listOf(box) + expansion.children, links = links + expansion.links)
        return target.copy(expansion = top)
    }
}
