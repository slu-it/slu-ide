package dev.sluide.githubactions.mermaid

import dev.sluide.githubactions.SourceLocation
import dev.sluide.githubactions.mermaid.MermaidLabels.label

/** The inputs element of a `uses:` step or reusable workflow job: the `with:` that is set, as escaped lines. */
internal object StepInputs {
    const val MAX_INPUTS = 8

    /**
     * One `name: value` line per input, at most [MAX_INPUTS], then `+N more`; empty without inputs. A value shows its
     * first non-blank line, followed by ` …` if more non-blank lines follow.
     */
    fun lines(inputs: Map<String, String>): List<String> {
        val lines = inputs.entries.take(MAX_INPUTS).map { (name, value) -> label("$name: ${first(value)}".trimEnd()) }
        val more = inputs.size - MAX_INPUTS
        return if (more > 0) lines + label("+$more more") else lines
    }

    private fun first(value: String): String {
        val written =
            value
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        return written.firstOrNull().orEmpty() + if (written.size > 1) " …" else ""
    }
}

/**
 * The inputs elements a [WorkflowDrawing] wants, asked for with [defer] while it draws and added to their subgraphs by
 * [draw] once everything else is drawn: they come after every box and link that is needed, so they never cut anything,
 * and one that does not fit the [budget] is left out without a note. [size] is the budget size of a label.
 */
internal class PendingInputs(
    private val budget: Budget,
    private val size: (List<String>) -> Int,
) {
    /** The inputs of [target] (a box or subgraph id) that an inputs element would show, at [location]. */
    class Wanted(
        val target: String,
        val inputs: Map<String, String>,
        val location: SourceLocation?,
    )

    /** A [wanted] inputs element at [depth], added to the [children] and [links] of the subgraph it is in. */
    private class Entry(
        val wanted: Wanted,
        val depth: Int,
        val children: MutableList<Element>,
        val links: MutableList<Link>,
    )

    private val entries = mutableListOf<Entry>()

    /** Asks for an inputs element for each of [wanted] that has inputs. */
    fun defer(
        wanted: List<Wanted>,
        depth: Int,
        children: MutableList<Element>,
        links: MutableList<Link>,
    ) {
        for (one in wanted) if (one.inputs.isNotEmpty()) entries += Entry(one, depth, children, links)
    }

    /** A point to [trim] back to. */
    fun mark(): Int = entries.size

    /** Forgets what was asked for since [mark]: the expansion that asked did not fit and is taken back. */
    fun trim(mark: Int) {
        while (entries.size > mark) entries.removeLast()
    }

    /** Adds the inputs elements asked for, as many as fit, in the order asked. */
    fun draw() {
        for (one in entries) {
            val lines = StepInputs.lines(one.wanted.inputs)
            val id = "${one.wanted.target}i"
            if (lines.isEmpty() || !budget.takeOptional(size(lines), id, one.wanted.target, one.depth)) continue
            one.children += Node(id, lines, Shape.ROUNDED, Style.INPUTS, one.wanted.location)
            one.links += Link(id, one.wanted.target, dotted = true)
        }
        entries.clear()
    }
}
