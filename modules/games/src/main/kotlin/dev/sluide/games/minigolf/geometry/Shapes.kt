package dev.sluide.games.minigolf.geometry

/** A straight line from [a] to [b]: a wall, a rail, or an edge of a moving obstacle. */
internal data class Segment(
    val a: Vec,
    val b: Vec,
) {
    val length: Double get() = a.distanceTo(b)

    /** The point at [t] along the segment, 0 = [a], 1 = [b]. */
    fun pointAt(t: Double) = a + (b - a) * t

    /** Where the point of the segment nearest to [p] lies, 0 = [a], 1 = [b]. */
    fun closestParameter(p: Vec): Double {
        val direction = b - a
        val lengthSquared = direction dot direction
        if (lengthSquared == 0.0) return 0.0
        return ((p - a) dot direction / lengthSquared).coerceIn(0.0, 1.0)
    }

    fun closestPoint(p: Vec) = pointAt(closestParameter(p))

    fun distanceTo(p: Vec) = closestPoint(p).distanceTo(p)
}

internal data class Circle(
    val center: Vec,
    val radius: Double,
) {
    fun contains(p: Vec) = p.distanceTo(center) <= radius
}

/** A closed polygon; the last point connects back to the first. */
internal data class Polygon(
    val points: List<Vec>,
) {
    init {
        require(points.size >= 3) { "a polygon needs at least 3 points, has ${points.size}" }
    }

    /** Whether [p] lies inside (even-odd rule); points exactly on an edge may count either way. */
    fun contains(p: Vec): Boolean {
        var inside = false
        var j = points.lastIndex
        for (i in points.indices) {
            val pi = points[i]
            val pj = points[j]
            if ((pi.y > p.y) != (pj.y > p.y) && p.x < (pj.x - pi.x) * (p.y - pi.y) / (pj.y - pi.y) + pi.x) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** The outline, one segment per side, the closing side last. */
    fun edges(): List<Segment> = points.indices.map { Segment(points[it], points[(it + 1) % points.size]) }
}
