package dev.sluide.git.parse

import dev.sluide.git.GitHunk

/** Reads the hunk headers (`@@ -a[,b] +c[,d] @@`) of `git diff -U0` output; everything else is ignored. */
internal object GitDiffParser {
    private val header = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    fun parse(output: String): List<GitHunk> =
        output
            .lineSequence()
            .mapNotNull(header::find)
            .map { match ->
                val (oldStart, oldCount, newStart, newCount) = match.destructured
                GitHunk(lines(oldStart.toInt(), oldCount), lines(newStart.toInt(), newCount))
            }.toList()

    /** A missing count means one line; with a count of 0, git gives the line before the change as start. */
    private fun lines(
        start: Int,
        count: String,
    ): IntRange {
        val lines = count.ifEmpty { "1" }.toInt()
        val first = if (lines == 0) start else start - 1
        return first until first + lines
    }
}
