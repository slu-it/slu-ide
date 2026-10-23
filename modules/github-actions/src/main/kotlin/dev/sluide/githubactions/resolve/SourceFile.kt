package dev.sluide.githubactions.resolve

import dev.sluide.githubactions.SourceLocation
import java.net.URI

/** A file whose lines are drawn: where a box from one of its [line][at]s comes from. */
internal sealed interface SourceFile {
    /** Where [line] (1-based, null if unknown) of this file is, or null if it cannot be shown. */
    fun at(line: Int?): SourceLocation?

    /** [path] in the workflow's own repository, relative to its root. */
    data class Local(
        val path: String,
    ) : SourceFile {
        override fun at(line: Int?) = line?.let { SourceLocation.Local(path, it) }
    }

    /** A remote file whose page at the commit read is [web] (none: its lines cannot be shown). */
    data class Remote(
        val web: URI?,
    ) : SourceFile {
        override fun at(line: Int?) = if (line != null && web != null) SourceLocation.Remote(line, web) else null
    }
}
