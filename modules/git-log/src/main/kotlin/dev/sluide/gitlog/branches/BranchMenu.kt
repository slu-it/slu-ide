package dev.sluide.gitlog.branches

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitStash
import dev.sluide.gitlog.actions.RefActions
import dev.sluide.gitlog.area.CommitDiffs
import dev.sluide.gitlog.area.CompareBar
import dev.sluide.gitlog.data.BranchLeaf
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.data.LOCAL
import dev.sluide.gitlog.data.REMOTE
import dev.sluide.gitlog.data.TAGS
import dev.sluide.gitlog.filter.FilterBar
import dev.sluide.gitlog.operations.BranchActions
import dev.sluide.gitlog.operations.Fetch
import dev.sluide.gitlog.operations.PopStash
import dev.sluide.gitlog.operations.RefOperation
import dev.sluide.gitlog.operations.RemoteBranch
import dev.sluide.gitlog.operations.branchActions
import dev.sluide.gitlog.operations.dropConfirmation
import dev.sluide.gitlog.operations.mergeText
import dev.sluide.gitlog.operations.pullText
import dev.sluide.gitlog.operations.rebaseText
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import org.kordamp.ikonli.javafx.FontIcon

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

    /** Asks whether to delete the given remote branches on their remotes (Delete, ADR 0078). */
    var deleteRemote: (List<RemoteBranch>) -> Unit = {}

    /** Fetches the given remote. */
    var fetch: (String) -> Unit = {}

    /** Reviews the branch with the given full refname like a pull request (ADR 0168). */
    var review: (String) -> Unit = {}

    /** Compares the given branch, remote branch or tag with `HEAD` in the log. */
    var compare: (GitRef) -> Unit = {}

    /** Shows how the working tree differs from the commit of the given ref. */
    var showWorkingTreeDiff: (GitRef) -> Unit = {}

    /** Rebases the current branch as the operation says (ADR 0063). */
    var rebase: (RefOperation) -> Unit = {}

    /** Asks how to merge the given branch or remote branch into the current branch (ADR 0105). */
    var merge: (GitRef) -> Unit = {}

    /** Opens the Pull dialog with the given remote branch chosen (ADR 0106). */
    var pull: (GitRef) -> Unit = {}

    /** Pops the given stash, or applies it if not `pop` (ADR 0107). */
    var popStash: (GitStash, pop: Boolean) -> Unit = { _, _ -> }

    /** Asks whether to drop the given stash and drops it. */
    var dropStash: (GitStash) -> Unit = {}

    /** Asks how to stash the local changes and stashes them. */
    var stashChanges: () -> Unit = {}
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
        deleteRemote = actions::deleteRemoteBranches
        fetch = { remote -> actions.start(Fetch(remote)) }
        rebase = { operation -> actions.start(operation) }
        merge = actions::merge
        pull = { ref -> actions.showPull(ref.name) }
        popStash = { stash, pop -> actions.start(PopStash(stash, pop)) }
        dropStash = { stash -> actions.confirmAndStart(dropConfirmation(stash)) }
        stashChanges = { actions.showStashChanges() }
    }

/**
 * What the menu can do with the [selected] nodes of [refs]: nothing that changes the repository while [writing], no
 * rebase, merge or pull while [busy] (a merge, rebase, cherry-pick or revert waits for its next step), no comparison
 * with `HEAD` while it has no commit. Comparing and showing the diff with the working tree only read, so [writing]
 * leaves them.
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
                review = reads.review,
                workingTreeDiff = reads.workingTreeDiff,
            )
        }

        busy -> {
            reads.copy(rebase = null, merge = null, pull = null)
        }

        else -> {
            reads
        }
    }
}

/**
 * Deletes what [actions] allow with [commands]: the local branches and tags, or the remote branches; `false` if
 * neither.
 */
internal fun deleteWith(
    actions: BranchActions,
    commands: BranchCommands,
): Boolean {
    when {
        actions.delete.isNotEmpty() -> commands.delete(actions.delete)
        actions.deleteRemote.isNotEmpty() -> commands.deleteRemote(actions.deleteRemote)
        else -> return false
    }
    return true
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
): String = rebaseText(refs?.currentBranch?.removePrefix("refs/heads/"), menuTarget(selected))

/** The text of the merge item for the [selected] nodes, named as [rebaseMenuText] names them (ADR 0105). */
internal fun mergeMenuText(
    selected: List<BranchNode>,
    refs: GitRefs?,
): String = mergeText(refs?.currentBranch?.removePrefix("refs/heads/"), menuTarget(selected))

/**
 * The text of the pull item for the [selected] nodes (ADR 0106): the one selected remote branch into the current
 * branch; generic without a current branch or such a selection.
 */
internal fun pullMenuText(
    selected: List<BranchNode>,
    refs: GitRefs?,
): String {
    val target = (selected.singleOrNull() as? BranchLeaf)?.ref?.takeIf { it.kind == GitRefKind.REMOTE_BRANCH }
    return pullText(refs?.currentBranch?.removePrefix("refs/heads/"), target?.shortName)
}

/** The short name of the one selected local or remote branch that is not current; `null` for any other selection. */
private fun menuTarget(selected: List<BranchNode>): String? {
    val leaf = selected.singleOrNull() as? BranchLeaf
    return leaf
        ?.ref
        ?.takeIf { !leaf.current && (it.kind == GitRefKind.LOCAL_BRANCH || it.kind == GitRefKind.REMOTE_BRANCH) }
        ?.shortName
}

/**
 * The submenu of the log's context menu for the branch or remote branch [ref]: the entries of the branches pane for
 * that one branch ([branchMenuEntries]), as the state of [refs], [remotes], [writing] and [busy] allows now;
 * build it when the menu opens.
 */
internal fun branchMenu(
    ref: GitRef,
    refs: GitRefs?,
    remotes: List<String>,
    writing: Boolean,
    busy: Boolean,
    commands: BranchCommands,
): Menu {
    val selected = listOf(branchLeaf(ref, refs))
    val actions = menuActions(selected, refs, remotes, writing, busy)
    val items =
        branchMenuEntries(selected, refs, remotes, writing, busy).flatMapIndexed { index, section ->
            (if (index > 0) listOf(SeparatorMenuItem()) else emptyList()) +
                section.map { shown ->
                    branchMenuItem(shown.entry, shown.text) { runEntry(shown.entry, actions, commands) }
                        .apply { isDisable = !shown.enabled }
                }
        }
    return Menu(ref.shortName).apply {
        styleClass += "git-branch-menu"
        isMnemonicParsing = false
        this.items.setAll(items)
    }
}

/**
 * The icon's colour in a context menu, which only the scene root's stylesheets reach: it follows the highlighted item
 * (ADR 0009, `docs/ui-conventions.md`).
 */
internal const val BRANCH_MENU_ICON_STYLE = "-fx-icon-color: -fx-text-background-color;"

/** The menu item of [entry] with its icon and [text]; a ref name with `_` would lose it as a mnemonic. */
internal fun branchMenuItem(
    entry: BranchMenuEntry,
    text: String,
    action: () -> Unit,
): MenuItem =
    MenuItem(text, FontIcon(entry.icon).apply { style = BRANCH_MENU_ICON_STYLE }).apply {
        id = entry.id
        isMnemonicParsing = false
        setOnAction { action() }
    }

/** What the table asks for the branches at a commit ([CommitTable.branchMenus][dev.sluide.gitlog.table.CommitTable]). */
internal fun branchMenus(
    pages: GitLogPages,
    writing: () -> Boolean,
    busy: () -> Boolean,
    commands: BranchCommands,
): (List<GitRef>) -> List<Menu> =
    { refs ->
        refs.map { branchMenu(it, pages.refs, pages.remotes.orEmpty(), writing(), busy(), commands) }
    }
