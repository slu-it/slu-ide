package dev.sluide.games.solitaire.solver

import java.util.PriorityQueue

/**
 * Spider by stages: the moves between two deals are searched best-first for at most [nodesPerStage] positions
 * (starting from every position of the beam), the [width] best positions that can deal become the beam of the next
 * stage after their row is dealt. A stage also ends when its table of positions seen reaches [maxPositions], so the
 * memory of a run is that of one stage. Incomplete by design (it never proves anything); [SpiderSearch] widens it
 * until it wins or the budget ends, and only then falls back to the exhaustive [BestFirst].
 */
internal class SpiderBeam(
    private val space: SpiderSpace,
    private val budget: SearchBudget,
    private val nodesPerStage: Int,
    private val width: Int,
    private val maxPositions: Int,
) {
    private class Node(
        var packed: ByteArray?,
        val parent: Node?,
        val op: Int,
        val priority: Int,
    )

    /** The moves from [root] to a won position, or null when the beam is lost or the search stopped. */
    fun run(root: SpiderState): IntArray? {
        var beam = listOf(Node(root.pack(), null, NO_OP, 0))
        while (beam.isNotEmpty()) {
            // A deal cannot be undone, so positions of different stages never repeat: one table per stage.
            val visited = LongSet(maxPositions)
            for (node in beam) visited.add(SpiderState.unpack(node.packed ?: continue, root.stock).hash())
            val queue = PriorityQueue<Node>(256) { a, b -> a.priority.compareTo(b.priority) }
            queue.addAll(beam)
            val dealable = PriorityQueue<Node>(width + 1) { a, b -> b.priority.compareTo(a.priority) }
            var nodes = 0
            while (nodes++ < nodesPerStage) {
                val node = queue.poll() ?: break
                if (budget.tick()) return null
                if (visited.full) break
                val state = SpiderState.unpack(node.packed ?: continue, root.stock)
                var won: Node? = null
                space.moves(state) { child, ops, priority ->
                    if (won == null && visited.add(child.hash())) {
                        val next = Node(child.pack(), node, ops[0], priority)
                        if (child.isWon()) won = next else queue.add(next)
                    }
                }
                won?.let { return opsOf(it) }
                if (space.canDeal(state)) {
                    dealable.add(node)
                    if (dealable.size > width) dealable.poll().packed = null
                } else {
                    node.packed = null
                }
            }
            beam = dealtFrom(dealable.sortedBy { it.priority }, root.stock)
        }
        return null
    }

    private fun dealtFrom(
        nodes: List<Node>,
        stock: ByteArray,
    ): List<Node> {
        val next = ArrayList<Node>()
        val seen = HashSet<Long>()
        for (node in nodes) {
            val child = SpiderState.unpack(node.packed ?: continue, stock).deal()
            node.packed = null
            if (seen.add(child.hash())) next += Node(child.pack(), node, SpOp.of(SpOp.DEAL), space.priority(child))
        }
        return next
    }

    private fun opsOf(last: Node): IntArray {
        val ops = ArrayList<Int>()
        var node: Node? = last
        while (node != null) {
            if (node.op != NO_OP) ops += node.op
            node = node.parent
        }
        return ops.asReversed().toIntArray()
    }

    private companion object {
        const val NO_OP = -1
    }
}
