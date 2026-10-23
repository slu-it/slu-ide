package dev.sluide.githubactions

import java.net.URI

/** Where a box of a [WorkflowGraph] comes from: a [line] (1-based) of a file. */
sealed interface SourceLocation {
    val line: Int

    /** In the workflow's own repository, [path] relative to its root (`.github/workflows/ci.yml`), normalised. */
    data class Local(
        val path: String,
        override val line: Int,
    ) : SourceLocation

    /** In a file of another repository, whose page on the web at the commit read is [web]. */
    data class Remote(
        override val line: Int,
        val web: URI,
    ) : SourceLocation
}
