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
