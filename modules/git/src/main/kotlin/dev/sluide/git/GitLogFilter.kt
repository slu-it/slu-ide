package dev.sluide.git

/** The order of the git log (ADR 0039): git's `--date-order` or `--topo-order`. */
enum class GitLogOrder {
    DATE,
    TOPOLOGICAL,
}

/**
 * Which commits the git log shows and how (ADR 0039); the default shows all of them in date order.
 *
 * [text] matches the whole message (any line), a literal unless [regex] (then a POSIX extended regular expression),
 * ignoring case unless [matchCase]; it is trimmed, empty matches everything. [revisions] are full refnames or `HEAD`,
 * none meaning all branches, remote branches, tags and `HEAD`. [users] are author names or emails, each matching
 * either exactly but ignoring case, after `.mailmap`. [paths] are relative to the root, `/`-separated; a folder
 * includes everything below it.
 */
data class GitLogFilter(
    val text: String = "",
    val regex: Boolean = false,
    val matchCase: Boolean = false,
    val revisions: List<String> = emptyList(),
    val users: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val order: GitLogOrder = GitLogOrder.DATE,
    val firstParent: Boolean = false,
    val noMerges: Boolean = false,
) {
    /**
     * Whether nothing is filtered: no text, revisions, users or paths, neither first parents only nor no merges. The
     * order does not count, nor do [regex] and [matchCase] without a text.
     */
    val isDefault: Boolean
        get() =
            text.isBlank() && revisions.isEmpty() && users.isEmpty() && paths.isEmpty() && !firstParent && !noMerges

    /**
     * Whether the commits shown may have parents that are not shown and that git does not rewrite (a text, users or
     * no merges), so they get no graph.
     */
    val hidesCommits: Boolean
        get() = text.isNotBlank() || users.isNotEmpty() || noMerges
}

/** A file or folder of a commit's tree, by its path relative to the root (ADR 0039). */
data class GitTreePath(
    val path: String,
    val folder: Boolean,
)
