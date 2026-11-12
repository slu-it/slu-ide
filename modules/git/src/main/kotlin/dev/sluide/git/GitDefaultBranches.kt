package dev.sluide.git

import dev.sluide.git.cli.BranchRef

private const val LOCAL = "refs/heads/"

private const val REMOTE = "refs/remotes/"

/** The names of default branches, in any case (as the git log's protected branches). */
private val DEFAULT_NAMES = listOf("main", "master")

/**
 * The full refnames of the default branches among [refs], whose commits are never reworded: for each of the
 * [remotes], what its `refs/remotes/<remote>/HEAD` points at, or without one its `main` and `master`; the
 * local `main` and `master`, the names in any case; and the local branches of the same name as the remote one
 * they track (a local `develop` tracking `origin/develop`, which `origin/HEAD` points at), also the current
 * branch. A branch of another name that tracks one (`fix-123` made from `origin/main`) is no default branch.
 */
internal fun defaultBranches(
    refs: List<BranchRef>,
    remotes: List<String>,
): List<String> {
    fun named(prefix: String) =
        refs.map { it.name }.filter { name ->
            name.startsWith(prefix) &&
                DEFAULT_NAMES.any { it.equals(name.removePrefix(prefix), ignoreCase = true) }
        }
    val remote =
        remotes.flatMap { remote ->
            val head = refs.firstOrNull { it.name == "$REMOTE$remote/HEAD" && it.target.isNotEmpty() }
            if (head != null) listOf(head.target) else named("$REMOTE$remote/")
        }
    val tracking =
        refs
            .filter { ref ->
                ref.name.startsWith(LOCAL) &&
                    ref.upstream in remote &&
                    remotes.any { ref.upstream == "$REMOTE$it/${ref.name.removePrefix(LOCAL)}" }
            }.map { it.name }
    return (remote + named(LOCAL) + tracking).distinct()
}

/**
 * The [defaultBranches] among [refs] that exist and are not symbolic, best first, as the review's base candidates
 * (ADR 0168): those of the remote of [upstream] first (a remote branch's full refname, empty for none: the reviewed
 * head's upstream, or the head itself if it is a remote branch), then `origin`'s, then the other remotes' in name
 * order; for each the target of its `HEAD`, else its `main`, then its `master`. Then the local `main` and `master`,
 * then the local branches that track a default branch of the same name.
 */
internal fun reviewBases(
    refs: List<BranchRef>,
    remotes: List<String>,
    upstream: String,
): List<String> {
    val upstreamRemote = remoteOf(upstream, remotes)
    val ordered = remotes.sortedWith(compareBy({ it != upstreamRemote }, { it != "origin" }, { it }))
    val existing = refs.filter { it.target.isEmpty() }.mapTo(HashSet()) { it.name }

    fun nameRank(
        ref: String,
        prefix: String,
    ) = DEFAULT_NAMES.indexOfFirst { it.equals(ref.removePrefix(prefix), ignoreCase = true) }

    // A stable sort: the target of a remote's HEAD, without a default name, stays its remote's first.
    return defaultBranches(refs, ordered).filter { it in existing }.sortedWith(
        compareBy(
            { ref ->
                when {
                    ref.startsWith(REMOTE) -> 0
                    nameRank(ref, LOCAL) >= 0 -> 1
                    else -> 2
                }
            },
            { ref -> remoteOf(ref, ordered)?.let(ordered::indexOf) ?: ordered.size },
            { ref -> remoteOf(ref, ordered)?.let { nameRank(ref, "$REMOTE$it/") } ?: nameRank(ref, LOCAL) },
        ),
    )
}

/** The one of [remotes] whose remote branch [ref] (a full refname) is, the longest name if several match. */
private fun remoteOf(
    ref: String,
    remotes: List<String>,
): String? = remotes.filter { ref.startsWith("$REMOTE$it/") }.maxByOrNull { it.length }
