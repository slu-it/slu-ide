package dev.sluide.githubactions.model

/**
 * A job's `strategy.matrix`: [axes] (name to value texts, YAML null as null), the [include] and [exclude] entries, or
 * the parts written as expressions ([expression] for the whole matrix).
 */
internal data class Matrix(
    val expression: String?,
    val axes: Map<String, List<String?>>,
    val include: List<Map<String, String?>>,
    val exclude: List<Map<String, String?>>,
    val includeExpression: String?,
    val excludeExpression: String?,
) {
    /** True when an expression decides the combinations, so they are only known when the workflow runs. */
    val isDynamic: Boolean
        get() =
            expression != null ||
                includeExpression != null ||
                excludeExpression != null ||
                axes.values.any { it.size == 1 && isExpression(it[0]) }

    /**
     * The number of jobs the matrix makes, as GitHub counts them: the cross product of [axes]; [exclude] removes the
     * combinations that match all of an entry's keys; an [include] entry extends every remaining combination whose
     * axis values it does not contradict, and adds a combination of its own when there is none. Values are compared
     * as text. Null when [isDynamic] or the cross product is over [MAX_COUNTED].
     */
    fun combinations(): Int? {
        if (isDynamic) return null
        val values = axes.values.toList()
        // No axes: only the include entries make combinations.
        val product =
            if (values.isEmpty()) {
                0L
            } else {
                values.fold(1L) { total, axis ->
                    (total * axis.size).coerceAtMost(MAX_COUNTED + 1L)
                }
            }
        if (product > MAX_COUNTED) return null
        val names = axes.keys.toList()
        val included = BooleanArray(include.size)
        var remaining = 0
        val combination = arrayOfNulls<String>(names.size)
        for (index in 0 until product.toInt()) {
            var rest = index
            for (axis in values.indices.reversed()) {
                combination[axis] = values[axis][rest % values[axis].size]
                rest /= values[axis].size
            }
            val value = { key: String -> names.indexOf(key).let { if (it < 0) Absent else combination[it] } }
            if (exclude.any { entry ->
                    entry.isNotEmpty() && entry.all { (key, wanted) -> value(key) == wanted }
                }
            ) {
                continue
            }
            remaining++
            for ((entry, candidate) in include.withIndex()) {
                if (!included[entry]) {
                    included[entry] = candidate.all { (key, wanted) -> key !in axes || value(key) == wanted }
                }
            }
        }
        return remaining + included.count { !it }
    }

    /** A fragment for a job's title: `matrix os×java (6)`, `matrix (dynamic)` or `matrix os (dynamic)`. */
    fun title(): String {
        val names = axes.keys.joinToString("×")
        val count = if (isDynamic) "dynamic" else combinations()?.toString() ?: "over $MAX_COUNTED"
        return listOf("matrix", names, "($count)").filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Marks a key that is no axis: never equal to an entry's value, which is a text or null. */
    private object Absent

    companion object {
        /** GitHub allows 256 jobs per matrix; larger cross products are not enumerated. */
        const val MAX_COUNTED = 65_536

        private fun isExpression(value: String?) =
            value != null && value.trim().let { it.startsWith("\${{") && it.endsWith("}}") }
    }
}
