package dev.sluide.games.solitaire.solver

import java.util.PriorityQueue

/** The positions of one game as [BestFirst] sees them: [S] is a search state that is never changed after creation. */
internal interface SearchSpace<S> {
    fun hash(state: S): Long

    fun isWon(state: S): Boolean

    /** The state as a few bytes: the search keeps queued positions only in this form. */
    fun pack(state: S): ByteArray

    fun unpack(packed: ByteArray): S

    /**
     * Calls [emit] for every successor of [state] (at [depth] moves from the root) with the abstract moves that lead
     * to it and its priority (lower is expanded first). Pruning here must be sound for [BestFirst] to prove anything.
     */
    fun expand(
        state: S,
        depth: Int,
        emit: (child: S, ops: IntArray, priority: Int) -> Unit,
    )
}

internal sealed interface BestFirstResult {
    /** The abstract moves from the root to a won position, in order. */
    class Found(
        val ops: IntArray,
    ) : BestFirstResult

    /** Every reachable position was expanded: no line exists, as far as the space prunes soundly. */
    data object Exhausted : BestFirstResult

    /** Budget, cancel or the position cap. */
    data object Aborted : BestFirstResult
}

/**
 * Best-first search with a table of the 64-bit hashes of the positions seen (a collision can only lose a line). A
 * queued position is kept as [SearchSpace.pack]ed bytes and dropped once expanded, so memory is about 200 bytes per
 * position seen (the node, its moves, the packed state, the hash); [maxPositions] bounds the positions seen.
 */
internal class BestFirst<S : Any>(
    private val space: SearchSpace<S>,
    private val budget: SearchBudget,
    private val maxPositions: Int,
) {
    private class Node<S>(
        var packed: ByteArray?,
        val parent: Node<S>?,
        val ops: IntArray,
        val depth: Int,
        val priority: Int,
        val seq: Int,
    )

    /** Searches from [root], reached from the start by [rootOps]. */
    fun run(
        root: S,
        rootOps: IntArray,
    ): BestFirstResult {
        if (space.isWon(root)) return BestFirstResult.Found(rootOps)
        val visited = LongSet(maxPositions)
        val queue = PriorityQueue<Node<S>>(1024, compareBy<Node<S>>({ it.priority }, { it.seq }))
        visited.add(space.hash(root))
        var seq = 0
        queue.add(Node(space.pack(root), null, rootOps, 0, 0, seq++))
        var found: Node<S>? = null
        while (found == null) {
            val node = queue.poll() ?: return BestFirstResult.Exhausted
            if (budget.tick()) return BestFirstResult.Aborted
            val state = space.unpack(node.packed ?: continue)
            node.packed = null
            space.expand(state, node.depth) { child, ops, priority ->
                if (found == null && visited.add(space.hash(child))) {
                    val next = Node(space.pack(child), node, ops, node.depth + 1, priority, seq++)
                    if (space.isWon(child)) found = next else queue.add(next)
                }
            }
            if (found == null && visited.full) return BestFirstResult.Aborted
        }
        return BestFirstResult.Found(opsOf(found))
    }

    private fun opsOf(last: Node<S>): IntArray {
        val chunks = ArrayList<IntArray>()
        var node: Node<S>? = last
        while (node != null) {
            chunks += node.ops
            node = node.parent
        }
        val all = IntArray(chunks.sumOf { it.size })
        var at = 0
        for (chunk in chunks.asReversed()) {
            chunk.copyInto(all, at)
            at += chunk.size
        }
        return all
    }
}
