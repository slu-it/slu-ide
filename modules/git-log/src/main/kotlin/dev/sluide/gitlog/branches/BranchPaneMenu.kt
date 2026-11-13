package dev.sluide.gitlog.branches

import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.operations.BranchActions
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem

/**
 * The context menu of the branches pane (ADRs 0040, 0041, 0171): it shows the [sections] of entries that apply to the
 * [selection] and acts as [actions] allow ([menuActions]) through the [commands]. For a selection of stashes and the
 * Stashes group only, the menu holds the [stash] items instead (ADR 0107), acting as [stashActions] allow. [update]
 * follows the selection and state; the menu is [isEmpty] for a selection with nothing to do, and must not be shown.
 */
internal class BranchPaneMenu(
    private val selection: () -> List<BranchNode>,
    private val sections: () -> List<List<ShownEntry>>,
    private val actions: () -> BranchActions,
    private val commands: () -> BranchCommands,
    stashActions: () -> StashActions,
) {
    /** The items for stashes (ADR 0107). */
    val stash = StashMenuItems(stashActions, commands)

    private val entryItems =
        BranchMenuEntry.entries.associateWith { entry ->
            val none = BranchActions(null, null, null, emptyList())
            branchMenuItem(entry, entryText(entry, none, emptyList(), null)) { runEntry(entry, actions(), commands()) }
        }

    val contextMenu = ContextMenu()

    /** The entries of the sections in [contextMenu] now; `null` while it holds the stash items or nothing yet. */
    private var shownLayout: List<List<BranchMenuEntry>>? = null

    /** Whether there is nothing to show for the selection now, as of the last [update]. */
    val isEmpty: Boolean get() = contextMenu.items.isEmpty()

    /** The item of [entry]; it is in the menu only while the entry applies to the selection. */
    fun item(entry: BranchMenuEntry): MenuItem = entryItems.getValue(entry)

    /** Chooses, names and enables the items for the selection now. */
    fun update() {
        stash.update()
        if (isStashSelection(selection())) {
            shownLayout = null
            if (contextMenu.items != stash.items) contextMenu.items.setAll(stash.items)
            return
        }
        val shown = sections()
        val byEntry = shown.flatten().associateBy { it.entry }
        entryItems.forEach { (entry, item) ->
            val state = byEntry[entry]
            if (state != null) item.text = state.text
            item.isDisable = state == null || !state.enabled
        }
        // Rebuilt only when the entries change, so that a state flip does not rebuild a popup that is open.
        val layout = shown.map { section -> section.map { it.entry } }
        if (layout == shownLayout) return
        shownLayout = layout
        contextMenu.items.setAll(
            shown.flatMapIndexed { index, section ->
                (if (index > 0) listOf(SeparatorMenuItem()) else emptyList()) + section.map { item(it.entry) }
            },
        )
    }
}
