package dev.sluide.gitlog.operations

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.gitlog.data.BranchFolder
import dev.sluide.gitlog.data.BranchGroup
import dev.sluide.gitlog.data.BranchLeaf
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.data.BranchRemote
import dev.sluide.gitlog.data.REMOTE

/** What the branches pane can do with its selection (ADR 0041); `null` (or empty) where it cannot. */
internal data class BranchActions(
    /** Checks out the one selected branch or tag; not `HEAD`, the current branch, folders or several. */
    val checkout: RefOperation?,
    /** The one selected ref, to start a new branch from. */
    val newBranchFrom: GitRef?,
    /** The one selected local branch, to rename. */
    val rename: GitRef?,
    /** The selected local branches and tags, if that is all the selection holds and none is current. */
    val delete: List<GitRef>,
    /** The remote of the one selected remote, folder of a remote or remote branch, to fetch (ADR 0043). */
    val fetch: String? = null,
    /** The one selected branch, remote branch or tag to compare with `HEAD`; not `HEAD` or the current branch (ADR 0044). */
    val compare: GitRef? = null,
    /** The one selected ref, also `HEAD` or the current branch, to compare with the working tree (ADR 0044). */
    val workingTreeDiff: GitRef? = null,
    /** Rebases the current branch onto the one selected branch or remote branch (ADR 0063); not onto `HEAD`'s commit. */
    val rebase: RebaseBranch? = null,
    /** The one selected branch or remote branch to merge into the current branch (ADR 0105); as [rebase] allows. */
    val merge: GitRef? = null,
    /**
     * The one selected remote branch to pull into the current branch (ADR 0106): of a known remote, not
     * `<remote>/HEAD`, with a current branch that has a commit; also at `HEAD`'s commit, as the fetch may bring more.
     */
    val pull: GitRef? = null,
    /**
     * The selected remote branches with their remotes, to delete there (ADR 0078), if that is all the selection holds
     * and the remote of each is known; never `<remote>/HEAD` or a protected branch ([isProtectedBranch]).
     */
    val deleteRemote: List<RemoteBranch> = emptyList(),
)

/** The text of the rebase item: for [current] onto [target] (short names), or generic if either is not known. */
internal fun rebaseText(
    current: String?,
    target: String?,
): String = if (current == null || target == null) "Rebase Current Branch onto…" else "Rebase '$current' onto '$target'"

/** The text of the merge item: [target] into [current] (short names), or generic if either is not known. */
internal fun mergeText(
    current: String?,
    target: String?,
): String = if (current == null || target == null) "Merge into Current Branch…" else "Merge '$target' into '$current'…"

/** The text of the pull item: [target] into [current] (short names), or generic if either is not known. */
internal fun pullText(
    current: String?,
    target: String?,
): String = if (current == null || target == null) "Pull into Current Branch…" else "Pull '$target' into '$current'…"

/**
 * The [BranchActions] for the [selected] nodes of the branches pane; [currentBranch] is the checked-out branch's full
 * refname, whose remote branch of the same name is not checked out either (it would check out the current branch).
 * What is fetched is the one of the [remotes] (all names, `git remote`) that the selected node is in or is; a remote
 * branch is deleted on the one it is in.
 */
internal fun branchActions(
    selected: List<BranchNode>,
    currentBranch: String? = null,
    remotes: List<String> = emptyList(),
    headCommit: String? = null,
): BranchActions {
    val single = selected.singleOrNull() as? BranchLeaf
    val ref = single?.ref
    val checkout =
        when {
            single == null || single.current -> {
                null
            }

            ref?.kind == GitRefKind.LOCAL_BRANCH -> {
                CheckoutBranch(ref.shortName)
            }

            ref?.kind == GitRefKind.REMOTE_BRANCH -> {
                CheckoutRemote(ref).takeIf {
                    "refs/heads/${it.local}" !=
                        currentBranch
                }
            }

            ref?.kind == GitRefKind.TAG -> {
                CheckoutRevision(ref.name, ref.shortName)
            }

            else -> {
                null
            }
        }
    val deletable =
        selected.isNotEmpty() &&
            selected.all { node ->
                node is BranchLeaf &&
                    !node.current &&
                    (node.ref.kind == GitRefKind.LOCAL_BRANCH || node.ref.kind == GitRefKind.TAG)
            }
    val remoteBranches =
        selected.map { node ->
            (node as? BranchLeaf)
                ?.takeIf { it.ref.kind == GitRefKind.REMOTE_BRANCH }
                ?.let { leaf -> remoteOf(leaf, remotes)?.let { RemoteBranch(it, leaf.ref) } }
                ?.takeIf { it.ref.shortName != "${it.remote}/HEAD" && !isProtectedBranch(it.nameOnRemote) }
        }
    val deleteRemote = if (remoteBranches.all { it != null }) remoteBranches.filterNotNull() else emptyList()
    val fetch = selected.singleOrNull()?.let { remoteOf(it, remotes) }
    val current = currentBranch?.removePrefix("refs/heads/")
    val rebase =
        if (current != null && headCommit != null && single?.current == false &&
            (ref?.kind == GitRefKind.LOCAL_BRANCH || ref?.kind == GitRefKind.REMOTE_BRANCH) &&
            ref.commit != headCommit
        ) {
            RebaseBranch(current, ref.name, ref.shortName)
        } else {
            null
        }
    return BranchActions(
        checkout,
        ref,
        ref?.takeIf { it.kind == GitRefKind.LOCAL_BRANCH },
        if (deletable) selected.map { (it as BranchLeaf).ref } else emptyList(),
        fetch,
        compare = ref?.takeIf { single?.current == false && it.kind != GitRefKind.HEAD },
        workingTreeDiff = ref,
        rebase = rebase,
        merge = ref.takeIf { rebase != null },
        pull = ref?.takeIf { pullable(it, single?.current == true, current, headCommit, remotes) },
        deleteRemote = deleteRemote,
    )
}

/**
 * Whether [ref] (not [current]ly checked out) can be pulled into the current branch [branch] (`null` when detached)
 * whose `HEAD` is at [headCommit]: a remote branch of one of the [remotes] that is not `<remote>/HEAD`.
 */
private fun pullable(
    ref: GitRef,
    current: Boolean,
    branch: String?,
    headCommit: String?,
    remotes: List<String>,
): Boolean {
    if (current || branch == null || headCommit == null || ref.kind != GitRefKind.REMOTE_BRANCH) return false
    val remote = remoteOfBranch(ref.shortName, remotes) ?: return false
    return ref.shortName != "$remote/HEAD"
}

/**
 * The remote of the remote branch [shortName] (`origin/main`) among the [remotes]: the longest whose name and `/`
 * start it (`team/a` for `team/a/main`); `null` for none, e.g. a remote that was removed.
 */
internal fun remoteOfBranch(
    shortName: String,
    remotes: List<String>,
): String? = remotes.filter { shortName.startsWith("$it/") }.maxByOrNull { it.length }

/**
 * The remote of [node] among the [remotes], the longest that matches: a remote or folder node below Remote is that
 * remote or in it if its name (`team/a` for `Remote/team/a`) equals the remote's or starts with it and `/`; a remote
 * branch is in it if its short name starts with the remote's and `/`. `null` for none: a remote that was removed, or
 * a first segment that is no remote (`team` with only the remote `team/a`).
 */
private fun remoteOf(
    node: BranchNode,
    remotes: List<String>,
): String? {
    val (name, parent) =
        when (node) {
            is BranchRemote, is BranchFolder -> {
                (node.path.takeIf { it.startsWith("$REMOTE/") }?.removePrefix("$REMOTE/") ?: return null) to true
            }

            is BranchLeaf -> {
                (node.ref.shortName.takeIf { node.ref.kind == GitRefKind.REMOTE_BRANCH } ?: return null) to false
            }

            is BranchGroup -> {
                return null
            }
        }
    return remotes.filter { (parent && name == it) || name.startsWith("$it/") }.maxByOrNull { it.length }
}
