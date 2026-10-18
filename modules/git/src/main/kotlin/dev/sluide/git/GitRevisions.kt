package dev.sluide.git

import dev.sluide.git.cli.GitCli

/** What a revision (a hash, branch, tag or anything git understands, such as `HEAD~3`) names (ADR 0045). */
sealed interface GitRevisionResolution {
    /** A commit, by its full object name; annotated tags are peeled to the commit they tag. */
    data class Commit(
        val hash: String,
    ) : GitRevisionResolution

    /** No commit: an unknown revision, or an object that is not a commit and does not lead to one. */
    data object Missing : GitRevisionResolution

    /** A short hash that several objects start with. */
    data object Ambiguous : GitRevisionResolution
}

/**
 * Parses what `git cat-file --batch-check` says about the two lines [GitCli.resolveRevisions] gives per revision:
 * `<revision>^{commit}`, then `<revision>`. Git reports a short hash that several commits start with as `missing` for
 * the first line (the peel fails) but as `ambiguous` for the second; a short hash that names one commit among other
 * objects is found by the first. A revision that takes the rest of the line, such as `:/Fix` (the youngest commit whose
 * message matches), is found by the second only.
 */
internal object GitRevisionParser {
    /** A found object: `<name> <type> <size>`. */
    private val COMMIT = Regex("([0-9a-f]{40}|[0-9a-f]{64}) commit \\d+")

    /** The resolutions of [count] revisions in order; `null` if the output is cut short or unexpected. */
    fun parse(
        output: String,
        count: Int,
    ): List<GitRevisionResolution>? {
        // Only lines ended by a line break are complete.
        val lines = output.split('\n').dropLast(1)
        if (lines.size < count * 2) return null
        return (0 until count).map { index -> resolution(lines[index * 2], lines[index * 2 + 1]) ?: return null }
    }

    private fun resolution(
        peeled: String,
        plain: String,
    ): GitRevisionResolution? {
        COMMIT.matchEntire(peeled)?.let { return GitRevisionResolution.Commit(it.groupValues[1]) }
        // Git echoes the line it could not resolve, so only the end of the line is git's.
        if (!peeled.endsWith(" missing") && !peeled.endsWith(" ambiguous")) return null
        // A revision whose syntax takes the rest of the line (`:/Fix`) reads `^{commit}` as part of it.
        COMMIT.matchEntire(plain)?.let { return GitRevisionResolution.Commit(it.groupValues[1]) }
        return if (plain.endsWith(" ambiguous")) GitRevisionResolution.Ambiguous else GitRevisionResolution.Missing
    }
}
