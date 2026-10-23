package dev.sluide.githubactions.mermaid

import dev.sluide.githubactions.SourceLocation

/**
 * The inputs boxes a [WorkflowDrawing] wants, asked for per subgraph with [defer] while it draws and added by [draw]
 * once everything else is drawn: they come after every box and link that is needed, so they never cut anything. A
 * subgraph's boxes go in all together or not at all, and without a note when they do not fit the [budget]. [size] is
 * the budget size of a label.
 *
 * Each box stands left of its step, level with it: Mermaid (dagre) cannot link within a rank, and a link from a step
 * would pull the steps out of their column. So the boxes of a subgraph are chained to each other by
 * [invisible][LinkKind.INVISIBLE] links as long as the steps between them, from the first rank to the last, with a
 * [spacer][Style.SPACER] holding the first or last rank where no box does; the arrow into the step is a
 * [side link][LinkKind.SIDE], which the preview draws itself.
 */
internal class PendingInputs(
    private val budget: Budget,
    private val size: (List<String>) -> Int,
) {
    /** The inputs of [target] (a box or subgraph id) that an inputs box would show, at [location]. */
    class Wanted(
        val target: String,
        val inputs: Map<String, String>,
        val location: SourceLocation?,
    )

    /** The inputs [wanted] in the subgraph [cluster] at [depth], added to its [children] and [links]. */
    private class Group(
        val cluster: String,
        val wanted: List<Wanted>,
        val depth: Int,
        val children: MutableList<Element>,
        val links: MutableList<Link>,
    )

    /** A box of the chain at the [rank] of the child it stands beside; [target] is null for a spacer. */
    private class Ranked(
        val rank: Int,
        val node: Node,
        val target: String?,
    )

    private val groups = mutableListOf<Group>()

    /** Asks for an inputs box for each of [wanted] that has inputs, in the subgraph [cluster]. */
    fun defer(
        cluster: String,
        wanted: List<Wanted>,
        depth: Int,
        children: MutableList<Element>,
        links: MutableList<Link>,
    ) {
        val some = wanted.filter { it.inputs.isNotEmpty() }
        if (some.isNotEmpty()) groups += Group(cluster, some, depth, children, links)
    }

    /** A point to [trim] back to. */
    fun mark(): Int = groups.size

    /** Forgets what was asked for since [mark]: the expansion that asked did not fit and is taken back. */
    fun trim(mark: Int) {
        while (groups.size > mark) groups.removeLast()
    }

    /** Adds the inputs boxes asked for, the subgraphs' in the order asked, as long as they fit. */
    fun draw() {
        groups.forEach(::draw)
        groups.clear()
    }

    private fun draw(group: Group) {
        val children = group.children
        // A child's rank is its position only while each child links to the next (no link was left out).
        val chained =
            (1..children.lastIndex).all { position ->
                group.links.any { it.from == children[position - 1].id && it.to == children[position].id }
            }
        if (!chained) return
        val boxes =
            group.wanted.mapNotNull { wanted ->
                val rank = children.indexOfFirst { it.id == wanted.target }
                val lines = StepInputs.lines(wanted.inputs)
                if (rank < 0 || lines.isEmpty()) return@mapNotNull null
                Ranked(
                    rank,
                    Node("${wanted.target}i", lines, Shape.ROUNDED, Style.INPUTS, wanted.location),
                    wanted.target,
                )
            }
        if (boxes.isEmpty()) return
        val top = if (boxes.first().rank > 0) Ranked(0, spacer("${group.cluster}sp0"), null) else null
        val last = children.lastIndex
        val bottom = if (boxes.last().rank < last) Ranked(last, spacer("${group.cluster}sp1"), null) else null
        val chain = listOfNotNull(top) + boxes + listOfNotNull(bottom)
        val invisible =
            chain.zipWithNext { above, below ->
                Link(above.node.id, below.node.id, kind = LinkKind.INVISIBLE, length = below.rank - above.rank)
            }
        val side = boxes.map { Link(it.node.id, checkNotNull(it.target), kind = LinkKind.SIDE) }
        // The export writes the side links as arrows, so they are counted too.
        val fits =
            budget.attempt {
                val nodes = chain.all { budget.takeNode(size(it.node.label), it.node.id, group.depth) }
                val links =
                    nodes && (invisible + side).all { budget.takeEdge(it.length - 1, it.from, it.to, group.depth) }
                Unit.takeIf { links }
            }
        if (fits == null) return
        // Each box written before its step, the spacers first and last, the chain before the arrows: the order in which
        // dagre was seen to put the boxes on the left (not promised: beside a called workflow's subgraph a box ends up
        // on its right, and the page draws the arrow from that side).
        for (box in boxes) children.add(children.indexOfFirst { it.id == box.target }, box.node)
        if (top != null) children.add(0, top.node)
        if (bottom != null) children += bottom.node
        group.links.addAll(0, invisible)
        group.links += side
    }

    private fun spacer(id: String) = Node(id, listOf(SPACE), Shape.ROUNDED, Style.SPACER)

    private companion object {
        /** A blank label as Mermaid accepts it. */
        const val SPACE = "#32;"
    }
}
