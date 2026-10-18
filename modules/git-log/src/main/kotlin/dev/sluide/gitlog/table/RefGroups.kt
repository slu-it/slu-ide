package dev.sluide.gitlog.table

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind

/**
 * The labels of a row's [refs] grouped as the git log shows them (ADR 0037), first the one to show:
 * - a local branch and the remote branches of the same name form one group, `origin & upstream & main`;
 * - `HEAD` joins the group of [currentBranch] (a full refname) if that branch is among [refs], else it is a group of
 *   its own, `HEAD` (detached);
 * - a remote branch without a local one of its name is a group of its own, `origin/main`, and so is every tag.
 *
 * Ordered: the group with `HEAD`, then those of local branches, remote branches and tags, each by name.
 */
internal fun refGroups(
    refs: List<GitRef>,
    currentBranch: String?,
): List<String> {
    val head = refs.any { it.kind == GitRefKind.HEAD }
    val locals = refs.filter { it.kind == GitRefKind.LOCAL_BRANCH }
    val remotes = refs.filter { it.kind == GitRefKind.REMOTE_BRANCH }
    val localNames = locals.map { it.shortName }.toSet()

    // A remote branch is <remote>/<branch>, split at the first "/" (remote names with a "/" are ambiguous); one
    // without a "/" (git-svn's refs/remotes/trunk) is a group of its own.
    fun branchOf(remote: GitRef) = remote.shortName.substringAfter('/', missingDelimiterValue = "")
    val remotesByBranch = remotes.groupBy(::branchOf)
    val groups =
        locals.map { local ->
            val remoteNames = remotesByBranch[local.shortName].orEmpty().map { it.shortName.substringBefore('/') }
            RefGroup(
                label = (remoteNames.sorted() + local.shortName).joinToString(" & "),
                kind = GitRefKind.LOCAL_BRANCH,
                name = local.shortName,
                head = head && local.name == currentBranch,
            )
        } +
            remotes
                .filter { branchOf(it).isEmpty() || branchOf(it) !in localNames }
                .map { RefGroup(it.shortName, GitRefKind.REMOTE_BRANCH, it.shortName) } +
            refs.filter { it.kind == GitRefKind.TAG }.map { RefGroup(it.shortName, GitRefKind.TAG, it.shortName) }
    val alone = head && groups.none { it.head }
    val detached = listOfNotNull(RefGroup("HEAD", GitRefKind.HEAD, "HEAD", head = true).takeIf { alone })
    return (detached + groups)
        .sortedWith(compareBy({ !it.head }, { it.kind }, { it.name }))
        .map { it.label }
}

/** What a row's labels say: the first of the [groups], and `+N` for N more. */
internal fun refLabelText(groups: List<String>): String =
    when (groups.size) {
        0 -> ""
        1 -> groups.single()
        else -> "${groups.first()} +${groups.size - 1}"
    }

private data class RefGroup(
    val label: String,
    val kind: GitRefKind,
    val name: String,
    val head: Boolean = false,
)
