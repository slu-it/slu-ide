package dev.sluide.gitlog.branches

import dev.sluide.git.GitRefs
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.operations.BranchActions
import dev.sluide.gitlog.ui.GitLogIcons
import org.kordamp.ikonli.Ikon

/** The items of the branch menus (ADR 0171), in display order; [section]s are told apart by separators. */
internal enum class BranchMenuEntry(
    val id: String,
    val icon: Ikon,
    val section: Int,
) {
    NEW_BRANCH("branch-new-branch", GitLogIcons.newBranch, 1),
    REVIEW("branch-review", GitLogIcons.review, 1),
    CHECKOUT("branch-checkout", GitLogIcons.checkout, 2),
    RENAME("branch-rename", GitLogIcons.rename, 2),
    DELETE("branch-delete", GitLogIcons.delete, 2),
    REBASE("branch-rebase", GitLogIcons.rebase, 3),
    MERGE("branch-merge", GitLogIcons.merge, 3),
    PULL("branch-pull", GitLogIcons.pull, 3),
    COMPARE("branch-compare", GitLogIcons.compare, 4),
    WORKING_TREE_DIFF("branch-working-tree-diff", GitLogIcons.workingTreeDiff, 4),
    FETCH("branch-fetch", GitLogIcons.fetch, 5),
}

/** An [entry] the menu shows, with its [text]; it is greyed out unless [enabled]. */
internal data class ShownEntry(
    val entry: BranchMenuEntry,
    val text: String,
    val enabled: Boolean,
)

/**
 * The sections of the branch menu for the [selected] nodes, without empty ones. An entry is left out when it never
 * applies to the selection (what [menuActions] gives without [writing] and [busy]); it is shown disabled when it
 * applies but [writing] or [busy] blocks it for now. Fetch is for a remote or a folder of remote branches only.
 */
internal fun branchMenuEntries(
    selected: List<BranchNode>,
    refs: GitRefs?,
    remotes: List<String>,
    writing: Boolean,
    busy: Boolean,
): List<List<ShownEntry>> {
    val unrestricted = menuActions(selected, refs, remotes, writing = false, busy = false)
    val allowed = menuActions(selected, refs, remotes, writing, busy)
    val shown =
        BranchMenuEntry.entries.mapNotNull { entry ->
            if (!applies(entry, unrestricted)) {
                null
            } else {
                ShownEntry(entry, entryText(entry, unrestricted, selected, refs), applies(entry, allowed))
            }
        }
    return shown
        .groupBy { it.entry.section }
        .toSortedMap()
        .values
        .toList()
}

private fun applies(
    entry: BranchMenuEntry,
    actions: BranchActions,
): Boolean =
    when (entry) {
        BranchMenuEntry.NEW_BRANCH -> actions.newBranchFrom != null
        BranchMenuEntry.REVIEW -> actions.review != null
        BranchMenuEntry.CHECKOUT -> actions.checkout != null
        BranchMenuEntry.RENAME -> actions.rename != null
        BranchMenuEntry.DELETE -> actions.delete.isNotEmpty() || actions.deleteRemote.isNotEmpty()
        BranchMenuEntry.REBASE -> actions.rebase != null
        BranchMenuEntry.MERGE -> actions.merge != null
        BranchMenuEntry.PULL -> actions.pull != null
        BranchMenuEntry.COMPARE -> actions.compare != null
        BranchMenuEntry.WORKING_TREE_DIFF -> actions.workingTreeDiff != null
        BranchMenuEntry.FETCH -> actions.fetch != null
    }

/** The text of [entry] for the [selected] nodes; generic where it names a ref and there is none. */
internal fun entryText(
    entry: BranchMenuEntry,
    actions: BranchActions,
    selected: List<BranchNode>,
    refs: GitRefs?,
): String =
    when (entry) {
        BranchMenuEntry.NEW_BRANCH -> {
            actions.newBranchFrom?.let { "New Branch from '${it.shortName}'…" }
                ?: "New Branch…"
        }

        BranchMenuEntry.REVIEW -> {
            "Review Branch"
        }

        BranchMenuEntry.CHECKOUT -> {
            "Checkout"
        }

        BranchMenuEntry.RENAME -> {
            "Rename…"
        }

        BranchMenuEntry.DELETE -> {
            "Delete"
        }

        BranchMenuEntry.REBASE -> {
            rebaseMenuText(selected, refs)
        }

        BranchMenuEntry.MERGE -> {
            mergeMenuText(selected, refs)
        }

        BranchMenuEntry.PULL -> {
            pullMenuText(selected, refs)
        }

        BranchMenuEntry.COMPARE -> {
            "Compare with Current"
        }

        BranchMenuEntry.WORKING_TREE_DIFF -> {
            "Show Diff with Working Tree"
        }

        BranchMenuEntry.FETCH -> {
            "Fetch"
        }
    }

/** Does what [entry] says with what [actions] allow, through [commands]; nothing if it is not allowed. */
internal fun runEntry(
    entry: BranchMenuEntry,
    actions: BranchActions,
    commands: BranchCommands,
) {
    when (entry) {
        BranchMenuEntry.NEW_BRANCH -> actions.newBranchFrom?.let(commands.newBranchFrom)
        BranchMenuEntry.REVIEW -> actions.review?.let { commands.review(it.name) }
        BranchMenuEntry.CHECKOUT -> actions.checkout?.let(commands.checkout)
        BranchMenuEntry.RENAME -> actions.rename?.let(commands.rename)
        BranchMenuEntry.DELETE -> deleteWith(actions, commands)
        BranchMenuEntry.REBASE -> actions.rebase?.let(commands.rebase)
        BranchMenuEntry.MERGE -> actions.merge?.let(commands.merge)
        BranchMenuEntry.PULL -> actions.pull?.let(commands.pull)
        BranchMenuEntry.COMPARE -> actions.compare?.let(commands.compare)
        BranchMenuEntry.WORKING_TREE_DIFF -> actions.workingTreeDiff?.let(commands.showWorkingTreeDiff)
        BranchMenuEntry.FETCH -> actions.fetch?.let(commands.fetch)
    }
}
