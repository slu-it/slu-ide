package dev.sluide.gitlog.branches

import dev.sluide.git.GitRefs
import dev.sluide.gitlog.data.BranchLeaf
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.operations.BranchActions
import dev.sluide.gitlog.operations.branchActions
import dev.sluide.gitlog.operations.mergeText
import dev.sluide.gitlog.operations.pullText
import dev.sluide.gitlog.operations.rebaseText
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem

/**
 * The context menu of the branches pane (ADRs 0040, 0041): its items act on the [selection] of the tree of [refs] as
 * [actions] allow ([menuActions]) through the [commands]; [filter], [copyNames], [rename] and [delete] are the pane's,
 * which its keys share. For a selection of stashes and the Stashes group only, the menu holds the [stash] items
 * instead (ADR 0107), acting as [stashActions] allow. [update] follows the selection and state.
 */
internal class BranchPaneMenu(
    private val selection: () -> List<BranchNode>,
    private val refs: () -> GitRefs?,
    private val actions: () -> BranchActions,
    private val commands: () -> BranchCommands,
    stashActions: () -> StashActions,
    filter: () -> Unit,
    copyNames: () -> Unit,
    rename: () -> Unit,
    delete: () -> Unit,
) {
    val filterItem = MenuItem(FILTER_ONE).apply { setOnAction { filter() } }
    val copyNameItem = MenuItem("Copy Name").apply { setOnAction { copyNames() } }
    val checkoutItem = MenuItem("Checkout").apply { setOnAction { actions().checkout?.let(commands().checkout) } }
    val rebaseItem =
        MenuItem(rebaseText(null, null)).apply {
            // A name with `_` would lose it as a mnemonic.
            isMnemonicParsing = false
            setOnAction { actions().rebase?.let(commands().rebase) }
        }
    val mergeItem =
        MenuItem(mergeText(null, null)).apply {
            isMnemonicParsing = false
            setOnAction { actions().merge?.let(commands().merge) }
        }
    val pullItem =
        MenuItem(pullText(null, null)).apply {
            isMnemonicParsing = false
            setOnAction { actions().pull?.let(commands().pull) }
        }
    val newBranchItem =
        MenuItem(NEW_BRANCH).apply {
            isMnemonicParsing = false
            setOnAction { actions().newBranchFrom?.let(commands().newBranchFrom) }
        }
    val renameItem = MenuItem("Rename…").apply { setOnAction { rename() } }
    val deleteItem = MenuItem("Delete").apply { setOnAction { delete() } }
    val fetchItem = MenuItem("Fetch").apply { setOnAction { actions().fetch?.let(commands().fetch) } }
    val compareItem =
        MenuItem(
            "Compare with Current",
        ).apply { setOnAction { actions().compare?.let(commands().compare) } }
    val workingTreeItem =
        MenuItem("Show Diff with Working Tree").apply {
            setOnAction { actions().workingTreeDiff?.let(commands().showWorkingTreeDiff) }
        }

    /** The items for stashes (ADR 0107). */
    val stash = StashMenuItems(stashActions, commands)

    /** The items for branches, remotes, tags and their groups. */
    private val refItems =
        listOf(
            filterItem,
            SeparatorMenuItem(),
            copyNameItem,
            SeparatorMenuItem(),
            compareItem,
            workingTreeItem,
            SeparatorMenuItem(),
            checkoutItem,
            rebaseItem,
            mergeItem,
            pullItem,
            newBranchItem,
            renameItem,
            deleteItem,
            SeparatorMenuItem(),
            fetchItem,
        )

    val contextMenu = ContextMenu(*refItems.toTypedArray())

    /** Names and enables the items for the selection now. */
    fun update() {
        val selected = selection()
        val shown = if (isStashSelection(selected)) stash.items else refItems
        if (contextMenu.items != shown) contextMenu.items.setAll(shown)
        stash.update()
        val count = selected.count { it is BranchLeaf }
        filterItem.text = if (count > 1) "Filter Log by $count Branches" else FILTER_ONE
        filterItem.isDisable = count == 0
        copyNameItem.isDisable = count == 0
        val actions = actions()
        val refs = refs()
        // The names of the refs it starts from and rebases onto even while writing or busy.
        val unrestricted = branchActions(selected, refs?.currentBranch)
        newBranchItem.text = unrestricted.newBranchFrom?.let { "New Branch from '${it.shortName}'…" } ?: NEW_BRANCH
        rebaseItem.text = rebaseMenuText(selected, refs)
        mergeItem.text = mergeMenuText(selected, refs)
        pullItem.text = pullMenuText(selected, refs)
        checkoutItem.isDisable = actions.checkout == null
        rebaseItem.isDisable = actions.rebase == null
        mergeItem.isDisable = actions.merge == null
        pullItem.isDisable = actions.pull == null
        newBranchItem.isDisable = actions.newBranchFrom == null
        renameItem.isDisable = actions.rename == null
        deleteItem.isDisable = actions.delete.isEmpty() && actions.deleteRemote.isEmpty()
        fetchItem.isDisable = actions.fetch == null
        compareItem.isDisable = actions.compare == null
        workingTreeItem.isDisable = actions.workingTreeDiff == null
    }

    private companion object {
        const val FILTER_ONE = "Filter Log by Branch"
        const val NEW_BRANCH = "New Branch…"
    }
}
