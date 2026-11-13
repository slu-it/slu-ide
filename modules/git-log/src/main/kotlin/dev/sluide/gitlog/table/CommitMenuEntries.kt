package dev.sluide.gitlog.table

import dev.sluide.git.GitPick
import dev.sluide.gitlog.operations.pickTitle
import dev.sluide.gitlog.ui.GitLogIcons
import org.kordamp.ikonli.Ikon

/**
 * The fixed items of the commit table's context menu (ADR 0172), in display order; [section]s are told apart by
 * separators. Section 1 is the branch submenus at the commit, which the table builds itself.
 */
internal enum class CommitMenuEntry(
    val id: String,
    val icon: Ikon,
    val section: Int,
) {
    REVIEW("commit-review", GitLogIcons.review, 2),
    CHECKOUT_REVISION("commit-checkout-revision", GitLogIcons.checkout, 2),
    COPY_REVISION_NUMBER("commit-copy-revision-number", GitLogIcons.copy, 2),
    CHERRY_PICK("commit-cherry-pick", GitLogIcons.cherryPick, 3),
    REVERT("commit-revert", GitLogIcons.revert, 3),
    RESET("commit-reset", GitLogIcons.reset, 3),
    NEW_BRANCH("commit-new-branch", GitLogIcons.newBranch, 4),
    NEW_TAG("commit-new-tag", GitLogIcons.newTag, 4),
    COMPARE_WITH_LOCAL("commit-compare-with-local", GitLogIcons.workingTreeDiff, 5),
    EDIT_MESSAGE("commit-edit-message", GitLogIcons.editMessage, 5),
}

/** An [entry] the commit menu shows, with its [text]; it is greyed out unless [enabled]. */
internal data class ShownCommitEntry(
    val entry: CommitMenuEntry,
    val text: String,
    val enabled: Boolean,
)

/**
 * The sections of the commit menu below the branch submenus for [selected] rows, without empty ones. Several commits
 * show Review and Copy only; Reset needs a `HEAD` commit ([hasHead]) and Edit Commit Message also a current branch
 * ([onBranch]), else they are left out. [running] greys out everything that changes the repository, [busy] (a merge,
 * rebase or pick in progress) also Cherry-Pick, Revert and Edit Commit Message.
 */
internal fun commitMenuEntries(
    selected: Int,
    hasHead: Boolean,
    onBranch: Boolean,
    running: Boolean,
    busy: Boolean,
): List<List<ShownCommitEntry>> {
    if (selected < 1) return emptyList()
    val many = selected > 1
    val shown =
        CommitMenuEntry.entries.mapNotNull { entry ->
            if (!appliesTo(entry, many, hasHead, onBranch)) {
                null
            } else {
                ShownCommitEntry(entry, commitEntryText(entry, many), isEnabled(entry, running, busy))
            }
        }
    return shown
        .groupBy { it.entry.section }
        .toSortedMap()
        .values
        .toList()
}

private fun appliesTo(
    entry: CommitMenuEntry,
    many: Boolean,
    hasHead: Boolean,
    onBranch: Boolean,
): Boolean =
    when (entry) {
        CommitMenuEntry.REVIEW, CommitMenuEntry.COPY_REVISION_NUMBER -> true
        CommitMenuEntry.RESET -> !many && hasHead
        CommitMenuEntry.EDIT_MESSAGE -> !many && hasHead && onBranch
        else -> !many
    }

private fun isEnabled(
    entry: CommitMenuEntry,
    running: Boolean,
    busy: Boolean,
): Boolean =
    when (entry) {
        CommitMenuEntry.REVIEW,
        CommitMenuEntry.COPY_REVISION_NUMBER,
        CommitMenuEntry.COMPARE_WITH_LOCAL,
        -> true

        CommitMenuEntry.CHERRY_PICK, CommitMenuEntry.REVERT, CommitMenuEntry.EDIT_MESSAGE -> !running && !busy

        else -> !running
    }

private fun commitEntryText(
    entry: CommitMenuEntry,
    many: Boolean,
): String =
    when (entry) {
        CommitMenuEntry.REVIEW -> if (many) "Review Commits" else "Review Commit"
        CommitMenuEntry.CHECKOUT_REVISION -> "Checkout Revision"
        CommitMenuEntry.COPY_REVISION_NUMBER -> if (many) "Copy Revision Numbers" else "Copy Revision Number"
        CommitMenuEntry.CHERRY_PICK -> pickTitle(GitPick.CHERRY_PICK)
        CommitMenuEntry.REVERT -> pickTitle(GitPick.REVERT)
        CommitMenuEntry.RESET -> "Reset Current Branch to Here…"
        CommitMenuEntry.NEW_BRANCH -> "New Branch…"
        CommitMenuEntry.NEW_TAG -> "New Tag…"
        CommitMenuEntry.COMPARE_WITH_LOCAL -> "Compare with Local"
        CommitMenuEntry.EDIT_MESSAGE -> "Edit Commit Message…"
    }
