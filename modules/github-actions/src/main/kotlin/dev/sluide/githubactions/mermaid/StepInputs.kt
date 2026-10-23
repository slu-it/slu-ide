package dev.sluide.githubactions.mermaid

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
