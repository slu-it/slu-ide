package dev.sluide.gitlog.branches

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.actions.RefActions
import dev.sluide.gitlog.area.CommitDiffs
import dev.sluide.gitlog.area.CompareBar
import dev.sluide.gitlog.filter.FilterBar
import dev.sluide.gitlog.operations.BranchActions
import dev.sluide.gitlog.operations.Fetch
import dev.sluide.gitlog.operations.RefOperation
import dev.sluide.gitlog.operations.branchActions
import dev.sluide.gitlog.operations.rebaseText

/** What the branch menus (of the branches pane, and of the commit table) do; shared so that both do the same. */
internal class BranchCommands {
    /** Filters the log by the given full refnames (or `HEAD`). */
    var filter: (List<String>) -> Unit = {}

    /** Checks out the selected branch or tag. */
    var checkout: (RefOperation) -> Unit = {}

    /** Asks for a new branch starting at the given ref. */
    var newBranchFrom: (GitRef) -> Unit = {}

    /** Asks for a new name of the given local branch (F2). */
    var rename: (GitRef) -> Unit = {}

    /** Asks whether to delete the given local branches and tags (Delete). */
    var delete: (List<GitRef>) -> Unit = {}

    /** Fetches the given remote. */
    var fetch: (String) -> Unit = {}

    /** Compares the given branch, remote branch or tag with `HEAD` in the log. */
    var compare: (GitRef) -> Unit = {}

    /** Shows how the working tree differs from the commit of the given ref. */
    var showWorkingTreeDiff: (GitRef) -> Unit = {}

    /** Rebases the current branch as the operation says (ADR 0063). */
    var rebase: (RefOperation) -> Unit = {}
}

/** The [BranchCommands] that start [actions], compare through [compareBar], show [diffs] and filter [filterBar]. */
internal fun branchCommands(
    actions: RefActions,
    compareBar: CompareBar,
    diffs: CommitDiffs,
    filterBar: FilterBar,
): BranchCommands =
    BranchCommands().apply {
        filter = { names ->
            // Filtering by branches ends a comparison, which would hide them.
            compareBar.stopComparing()
            filterBar.setBranches(names)
        }
        checkout = { operation -> actions.start(operation) }
        newBranchFrom = { ref -> actions.newBranch(ref.name, ref.shortName) }
        compare = compareBar::compareWith
        showWorkingTreeDiff = { ref -> diffs.showWorkingTreeDiff(ref.name, ref.shortName) }
        rename = actions::renameBranch
        delete = actions::deleteRefs
        fetch = { remote -> actions.start(Fetch(remote)) }
        rebase = { operation -> actions.start(operation) }
    }

/**
 * What the menu can do with the [selected] nodes of [refs]: nothing that changes the repository while [writing], no
 * rebase while [busy] (a rebase, cherry-pick or revert waits for its next step), no comparison with `HEAD` while it
 * has no commit. Comparing and showing the diff with the working tree only read, so [writing] leaves them.
 */
internal fun menuActions(
    selected: List<BranchNode>,
    refs: GitRefs?,
    remotes: List<String>,
    writing: Boolean,
    busy: Boolean,
): BranchActions {
    val head = refs?.refs.orEmpty().firstOrNull { it.kind == GitRefKind.HEAD }
    val all = branchActions(selected, refs?.currentBranch, remotes, head?.commit)
    val reads = if (head != null) all else all.copy(compare = null)
    return when {
        writing -> {
            BranchActions(
                null,
                null,
                null,
                emptyList(),
                compare = reads.compare,
                workingTreeDiff = reads.workingTreeDiff,
            )
        }

        busy -> {
            reads.copy(rebase = null)
        }

        else -> {
            reads
        }
    }
}

/** The leaf for [ref] as the tree of [refs] has it: [BranchLeaf.current] follows the checked-out branch of [refs]. */
internal fun branchLeaf(
    ref: GitRef,
    refs: GitRefs?,
): BranchLeaf {
    val current = refs?.currentBranch
    val group =
        when (ref.kind) {
            GitRefKind.HEAD -> null
            GitRefKind.LOCAL_BRANCH -> LOCAL
            GitRefKind.REMOTE_BRANCH -> REMOTE
            GitRefKind.TAG -> TAGS
        }
    val isCurrent = if (ref.kind == GitRefKind.HEAD) current != null else ref.name == current
    return BranchLeaf(ref.shortName, listOfNotNull(group, ref.shortName).joinToString("/"), ref, isCurrent)
}

/**
 * The text of the rebase item for the [selected] nodes: the current branch onto the one selected local or remote
 * branch that is not current, whatever its commit; generic without a current branch or such a selection.
 */
internal fun rebaseMenuText(
    selected: List<BranchNode>,
    refs: GitRefs?,
): String {
    val leaf = selected.singleOrNull() as? BranchLeaf
    val target =
        leaf?.ref?.takeIf {
            !leaf.current && (it.kind == GitRefKind.LOCAL_BRANCH || it.kind == GitRefKind.REMOTE_BRANCH)
        }
    return rebaseText(refs?.currentBranch?.removePrefix("refs/heads/"), target?.shortName)
}
