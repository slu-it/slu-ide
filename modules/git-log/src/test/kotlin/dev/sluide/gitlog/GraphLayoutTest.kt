package dev.sluide.gitlog

import kotlin.test.Test
import kotlin.test.assertEquals

class GraphLayoutTest {
    /** An edge (from, to, colour). */
    private data class Edge(
        val from: Int,
        val to: Int,
        val colour: Int,
    )

    private fun GraphRow.upper() =
        (0 until upperEdges).map { Edge(upperFrom(it), upperTo(it), upperColour(it)) }.toSet()

    private fun GraphRow.lower() =
        (0 until lowerEdges).map { Edge(lowerFrom(it), lowerTo(it), lowerColour(it)) }.toSet()

    private fun assertRow(
        row: GraphRow,
        dot: Int,
        colour: Int,
        width: Int,
        upper: Set<Edge>,
        lower: Set<Edge>,
    ) {
        assertEquals(dot, row.dot, "dot of $row")
        assertEquals(colour, row.colour, "colour of $row")
        assertEquals(width, row.width, "width of $row")
        assertEquals(upper, row.upper(), "upper edges of $row")
        assertEquals(lower, row.lower(), "lower edges of $row")
    }

    /** Lays out commits given as hash to parents, newest first. */
    private fun layOut(vararg commits: Pair<String, List<String>>): List<GraphRow> {
        val layout = GraphLayout()
        return commits.map { (hash, parents) -> layout.next(hash, parents) }
    }

    @Test
    fun `a linear history is one lane with straight edges`() {
        val (a, b, c) = layOut("a" to listOf("b"), "b" to listOf("c"), "c" to emptyList())

        assertRow(a, dot = 0, colour = 0, width = 1, upper = setOf(), lower = setOf(Edge(0, 0, 0)))
        assertRow(b, dot = 0, colour = 0, width = 1, upper = setOf(Edge(0, 0, 0)), lower = setOf(Edge(0, 0, 0)))
        assertRow(c, dot = 0, colour = 0, width = 1, upper = setOf(Edge(0, 0, 0)), lower = setOf())
    }

    @Test
    fun `a merge opens a second lane that converges back at the fork`() {
        val (merge, feature, main, fork) =
            layOut("m" to listOf("b", "f"), "f" to listOf("a"), "b" to listOf("a"), "a" to emptyList())

        assertRow(merge, 0, 0, 2, upper = setOf(), lower = setOf(Edge(0, 0, 0), Edge(0, 1, 1)))
        assertRow(
            feature,
            1,
            1,
            2,
            upper = setOf(Edge(0, 0, 0), Edge(1, 1, 1)),
            lower = setOf(Edge(0, 0, 0), Edge(1, 1, 1)),
        )
        assertRow(
            main,
            0,
            0,
            2,
            upper = setOf(Edge(0, 0, 0), Edge(1, 1, 1)),
            lower = setOf(Edge(0, 0, 0), Edge(1, 1, 1)),
        )
        assertRow(fork, 0, 0, 2, upper = setOf(Edge(0, 0, 0), Edge(1, 0, 1)), lower = setOf())
    }

    @Test
    fun `two tips forking from the same commit converge there`() {
        val (x, y, fork) = layOut("x" to listOf("a"), "y" to listOf("a"), "a" to listOf("z"))

        assertRow(x, 0, 0, 1, upper = setOf(), lower = setOf(Edge(0, 0, 0)))
        assertRow(y, 1, 1, 2, upper = setOf(Edge(0, 0, 0)), lower = setOf(Edge(0, 0, 0), Edge(1, 1, 1)))
        assertRow(fork, 0, 0, 2, upper = setOf(Edge(0, 0, 0), Edge(1, 0, 1)), lower = setOf(Edge(0, 0, 0)))
    }

    @Test
    fun `a merge of a parent another lane expects joins that lane instead of opening one`() {
        val (tip, merge, next) = layOut("t" to listOf("s"), "m" to listOf("b", "s"), "b" to listOf("s"))

        assertRow(tip, 0, 0, 1, upper = setOf(), lower = setOf(Edge(0, 0, 0)))
        assertRow(
            merge,
            1,
            1,
            2,
            upper = setOf(Edge(0, 0, 0)),
            lower = setOf(Edge(0, 0, 0), Edge(1, 1, 1), Edge(1, 0, 0)),
        )
        assertRow(
            next,
            1,
            1,
            2,
            upper = setOf(Edge(0, 0, 0), Edge(1, 1, 1)),
            lower = setOf(Edge(0, 0, 0), Edge(1, 1, 1)),
        )
    }

    @Test
    fun `an octopus merge opens a lane for every further parent`() {
        val (octopus, c) = layOut("o" to listOf("a", "b", "c"), "c" to listOf("a"))

        assertRow(octopus, 0, 0, 3, upper = setOf(), lower = setOf(Edge(0, 0, 0), Edge(0, 1, 1), Edge(0, 2, 2)))
        assertRow(
            c,
            2,
            2,
            3,
            upper = setOf(Edge(0, 0, 0), Edge(1, 1, 1), Edge(2, 2, 2)),
            lower = setOf(Edge(0, 0, 0), Edge(1, 1, 1), Edge(2, 2, 2)),
        )
    }

    @Test
    fun `a root commit ends its lane and the lanes right of it shift left`() {
        val (_, _, root, s) = layOut("a" to listOf("r"), "b" to listOf("s"), "r" to emptyList(), "s" to emptyList())

        assertRow(root, 0, 0, 2, upper = setOf(Edge(0, 0, 0), Edge(1, 1, 1)), lower = setOf(Edge(1, 0, 1)))
        assertRow(s, 0, 1, 1, upper = setOf(Edge(0, 0, 1)), lower = setOf())
    }

    @Test
    fun `a tip of an unrelated history is appended with a new colour`() {
        val (_, x, b, y) = layOut("a" to listOf("b"), "x" to listOf("y"), "b" to emptyList(), "y" to emptyList())

        assertRow(x, 1, 1, 2, upper = setOf(Edge(0, 0, 0)), lower = setOf(Edge(0, 0, 0), Edge(1, 1, 1)))
        assertRow(b, 0, 0, 2, upper = setOf(Edge(0, 0, 0), Edge(1, 1, 1)), lower = setOf(Edge(1, 0, 1)))
        assertRow(y, 0, 1, 1, upper = setOf(Edge(0, 0, 1)), lower = setOf())

        // After everything ended, the next history starts at the left again.
        val layout = GraphLayout()
        layout.next("a", emptyList())
        assertRow(layout.next("z", emptyList()), 0, 1, 1, upper = setOf(), lower = setOf())
    }

    @Test
    fun `the width counts the positions at the top, the middle and the bottom`() {
        // A lone commit next to a lane: only the middle has two positions.
        val (_, lone) = layOut("a" to listOf("b"), "z" to emptyList())
        assertEquals(2, lone.width)
        assertEquals(1, lone.dot)
        assertEquals(setOf(Edge(0, 0, 0)), lone.lower())
    }

    @Test
    fun `colours repeat after eight lanes`() {
        val layout = GraphLayout()
        val tips = (1..9).map { layout.next("t$it", listOf("base")) }

        assertEquals((0 until GRAPH_COLOURS).toList() + 0, tips.map { it.colour })
        assertEquals(9, tips.last().width)
    }

    @Test
    fun `laying out in chunks gives the same rows as all at once`() {
        val history =
            listOf(
                "m" to listOf("b", "f"),
                "t" to listOf("s"),
                "f" to listOf("g"),
                "o" to listOf("b", "g", "s"),
                "b" to listOf("a"),
                "g" to listOf("a"),
                "s" to listOf("a", "r"),
                "a" to listOf("r"),
                "x" to emptyList(),
                "r" to emptyList(),
            )
        val whole = GraphLayout().let { layout -> history.map { (hash, parents) -> layout.next(hash, parents) } }

        for (split in 1 until history.size) {
            val layout = GraphLayout()
            val first = history.take(split).map { (hash, parents) -> layout.next(hash, parents) }
            val rest = history.drop(split).map { (hash, parents) -> layout.next(hash, parents) }
            assertEquals(whole, first + rest, "split at $split")
        }
        assertEquals(whole, layOut(*history.toTypedArray()))
    }

    @Test
    fun `a merge parent expected right of the dot is joined at that lane's shifted position`() {
        // Lanes: b (colour 0), s (colour 1). The octopus opens a lane for n, which pushes s one position right.
        val (_, _, octopus) = layOut("t" to listOf("b"), "u" to listOf("s"), "b" to listOf("c", "n", "s"))

        assertRow(
            octopus,
            0,
            0,
            3,
            upper = setOf(Edge(0, 0, 0), Edge(1, 1, 1)),
            lower = setOf(Edge(0, 0, 0), Edge(0, 1, 2), Edge(1, 2, 1), Edge(0, 2, 1)),
        )
    }

    @Test
    fun `a merge that opens a lane pushes the lanes right of it one position right`() {
        val (_, _, merge, next) =
            layOut(
                "t" to listOf("b"),
                "u" to listOf("x"),
                "b" to listOf("c", "d"),
                "x" to listOf("y"),
            )

        assertRow(
            merge,
            0,
            0,
            3,
            upper = setOf(Edge(0, 0, 0), Edge(1, 1, 1)),
            lower = setOf(Edge(0, 0, 0), Edge(0, 1, 2), Edge(1, 2, 1)),
        )
        assertRow(
            next,
            2,
            1,
            3,
            upper = setOf(Edge(0, 0, 0), Edge(1, 1, 2), Edge(2, 2, 1)),
            lower = setOf(Edge(0, 0, 0), Edge(1, 1, 2), Edge(2, 2, 1)),
        )
    }

    @Test
    fun `edges with both ends beyond the lane cap are not stored, those with one end inside are`() {
        val layout = GraphLayout()
        // 36 tips, each on its own lane (0 to 35), expecting p0 to p35.
        repeat(36) { layout.next("t$it", listOf("p$it")) }

        // p3 merges p35: the join edge goes from the dot into lane 35.
        val merge = layout.next("p3", listOf("q", "p35"))
        assertEquals(36, merge.width)
        assertEquals((0 until MAX_LANES).map { Edge(it, it, it % GRAPH_COLOURS) }.toSet(), merge.upper())
        assertEquals(
            (0 until MAX_LANES).map { Edge(it, it, it % GRAPH_COLOURS) }.toSet() + Edge(3, 35, 35 % GRAPH_COLOURS),
            merge.lower(),
        )

        // A root at 0 ends its lane: lane 30 shifts into the drawn part, the lanes right of it are left out.
        val root = layout.next("p0", emptyList())
        assertEquals(MAX_LANES, root.upperEdges)
        assertEquals((1..MAX_LANES).map { Edge(it, it - 1, it % GRAPH_COLOURS) }.toSet(), root.lower())

        // Lanes 2 and 33 both expect q: lane 33 converges into the dot at 2 and is kept, the straight lanes between 30
        // and 33 above it are stored too (upper edges are stored by position), those below are not.
        val wide = GraphLayout()
        repeat(MAX_LANES + 5) { wide.next("t$it", listOf(if (it == 2 || it == 33) "q" else "p$it")) }
        val converging = wide.next("q", listOf("r"))
        assertEquals(34, converging.upperEdges)
        assertEquals(Edge(33, 2, 33 % GRAPH_COLOURS), converging.upper().single { it.from == 33 })
        assertEquals(setOf(Edge(29, 29, 29 % GRAPH_COLOURS)), converging.lower().filter { it.from >= 29 }.toSet())
    }
}
