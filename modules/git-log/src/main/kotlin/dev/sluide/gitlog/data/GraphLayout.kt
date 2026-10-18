package dev.sluide.gitlog.data

/** The number of lane colours; the colour indices of a [GraphRow] are below it. */
internal const val GRAPH_COLOURS = 8

/**
 * The most positions of the commit graph drawn in a row. Edges with both ends at or right of it are not even stored,
 * edges with one end left of it are (drawn cut off at the graph's right edge).
 */
internal const val MAX_LANES = 30

/**
 * The commit graph of one row (ADR 0036): the commit's dot at position [dot] in [colour], the upper edges from a
 * position at the row's top to one at its middle, and the lower edges from a position at the middle to one at the
 * bottom, each with a colour index. [width] is the number of positions the row uses; edges with both ends at or
 * right of [MAX_LANES] are left out.
 *
 * Kept for every loaded commit, so everything is stored in one array: first the upper edges, one value each, as the
 * upper edge with index i always starts at position i and ends there or at the dot (colour times 2, plus 1 if it ends
 * at the dot); then the lower edges as (from, to, colour) triples.
 */
internal class GraphRow(
    val dot: Int,
    val colour: Int,
    val width: Int,
    private val upperCount: Int,
    private val edges: IntArray,
) {
    val upperEdges: Int get() = upperCount
    val lowerEdges: Int get() = (edges.size - upperCount) / 3

    fun upperFrom(edge: Int) = edge

    fun upperTo(edge: Int) = if (edges[edge] and 1 == 1) dot else edge

    fun upperColour(edge: Int) = edges[edge] shr 1

    fun lowerFrom(edge: Int) = edges[upperCount + 3 * edge]

    fun lowerTo(edge: Int) = edges[upperCount + 3 * edge + 1]

    fun lowerColour(edge: Int) = edges[upperCount + 3 * edge + 2]

    override fun equals(other: Any?) =
        other is GraphRow &&
            dot == other.dot &&
            colour == other.colour &&
            width == other.width &&
            upperCount == other.upperCount &&
            edges.contentEquals(other.edges)

    override fun hashCode() = ((dot * 31 + colour) * 31 + width) * 31 + edges.contentHashCode()

    override fun toString() =
        if (this === NONE) {
            "GraphRow.NONE"
        } else {
            "GraphRow(dot=$dot, colour=$colour, width=$width, " +
                "upper=${(0 until upperEdges).map { "${upperFrom(it)}>${upperTo(it)}:${upperColour(it)}" }}, " +
                "lower=${(0 until lowerEdges).map { "${lowerFrom(it)}>${lowerTo(it)}:${lowerColour(it)}" }})"
        }

    companion object {
        /**
         * No graph at all: no dot, no edges, no positions; for rows whose parents may not be shown, under a filter
         * that hides commits (ADR 0039).
         */
        val NONE = GraphRow(-1, 0, 0, 0, IntArray(0))
    }
}

/**
 * Lays out the commit graph row by row (ADR 0036), for commits in an order that lists no parent before all its
 * children (git's date order). Holds the lanes crossing the boundary below the last row, so the rows of a further
 * page continue those of the pages before.
 */
internal class GraphLayout {
    /** The commit each lane expects next, left to right. */
    private var hashes = ArrayList<String>()
    private var colours = ArrayList<Int>()
    private var nextColour = 0

    /** The row of the commit [hash] with these [parents], the first parent first. */
    fun next(
        hash: String,
        parents: List<String>,
    ): GraphRow {
        val top = hashes.size
        val merging = BooleanArray(top) { hashes[it] == hash }
        val first = merging.indexOf(true)
        val dot = if (first >= 0) first else top
        val colour = if (first >= 0) colours[first] else newColour()
        // Upper edges are stored up to the last one with an end left of the cap.
        var upperCount = minOf(top, MAX_LANES)
        if (dot < MAX_LANES) {
            for (lane in top - 1 downTo MAX_LANES) {
                if (merging[lane]) {
                    upperCount = lane + 1
                    break
                }
            }
        }
        val edges = IntArray(upperCount + 3 * (top + parents.size))
        for (lane in 0 until upperCount) edges[lane] = colours[lane] * 2 + if (merging[lane]) 1 else 0
        var size = upperCount

        fun edge(
            from: Int,
            to: Int,
            colour: Int,
        ) {
            if (from >= MAX_LANES && to >= MAX_LANES) return
            edges[size++] = from
            edges[size++] = to
            edges[size++] = colour
        }

        val newHashes = ArrayList<String>(top + parents.size)
        val newColours = ArrayList<Int>(top + parents.size)
        // The lanes left of the dot do not expect this commit, as the dot is at the first one that does.
        for (lane in 0 until dot) {
            newHashes += hashes[lane]
            newColours += colours[lane]
            edge(lane, lane, colours[lane])
        }
        if (parents.isNotEmpty()) {
            newHashes += parents[0]
            newColours += colour
            edge(dot, dot, colour)
        }
        val passing = (0 until top).filter { !merging[it] }
        val joining = mutableListOf<String>()
        for (parent in parents.drop(1)) {
            if (passing.any { hashes[it] == parent }) {
                joining += parent
            } else {
                val newColour = newColour()
                edge(dot, newHashes.size, newColour)
                newHashes += parent
                newColours += newColour
            }
        }
        for (lane in passing) {
            if (lane < dot) continue
            edge(lane, newHashes.size, colours[lane])
            newHashes += hashes[lane]
            newColours += colours[lane]
        }
        for (parent in joining) {
            val lane = newHashes.indexOf(parent)
            edge(dot, lane, newColours[lane])
        }

        val width = maxOf(top, dot + 1, newHashes.size)
        hashes = newHashes
        colours = newColours
        return GraphRow(dot, colour, width, upperCount, edges.copyOf(size))
    }

    private fun newColour(): Int = nextColour.also { nextColour = (nextColour + 1) % GRAPH_COLOURS }
}
