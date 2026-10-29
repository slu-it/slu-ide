package dev.sluide.gitlog.branches

import dev.sluide.git.GitStash
import dev.sluide.gitlog.data.BranchGroup
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.data.BranchStash
import dev.sluide.gitlog.data.STASHES
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the branches pane's menu can do with stashes (ADR 0107); `null` (or `false`) where it cannot: [pop], [apply],
 * [drop] and [copy] the one selected stash, [stashChanges] the local changes.
 */
internal data class StashActions(
    val pop: GitStash? = null,
    val apply: GitStash? = null,
    val drop: GitStash? = null,
    val copy: GitStash? = null,
    val stashChanges: Boolean = false,
)

/** Whether the [selected] nodes are only stashes and the Stashes group, for which the menu shows the stash items. */
internal fun isStashSelection(selected: List<BranchNode>): Boolean =
    selected.isNotEmpty() && selected.all { it is BranchStash || (it is BranchGroup && it.name == STASHES) }

/**
 * The [StashActions] for the [selected] nodes: exactly one stash to pop, apply, drop or copy; nothing that changes the
 * repository while [writing]; no pop, apply or new stash while [busy] (git is in the middle of something, or files have
 * conflicts), a drop then still, as it changes no files.
 */
internal fun stashActions(
    selected: List<BranchNode>,
    writing: Boolean,
    busy: Boolean,
): StashActions {
    if (!isStashSelection(selected)) return StashActions()
    val stash = (selected.singleOrNull() as? BranchStash)?.stash
    val changes = !writing && !busy
    return StashActions(
        pop = stash.takeIf { changes },
        apply = stash.takeIf { changes },
        drop = stash.takeIf { !writing },
        copy = stash,
        stashChanges = changes,
    )
}

/** The stash items of the branches pane's menu, acting through the [commands] on what [actions] allow now. */
internal class StashMenuItems(
    private val actions: () -> StashActions,
    private val commands: () -> BranchCommands,
) {
    val popItem = MenuItem("Pop Stash").apply { setOnAction { actions().pop?.let { commands().popStash(it, true) } } }
    val applyItem =
        MenuItem("Apply Stash").apply { setOnAction { actions().apply?.let { commands().popStash(it, false) } } }
    val dropItem = MenuItem("Drop Stash…").apply { setOnAction { actions().drop?.let(commands().dropStash) } }
    val copyMessageItem =
        MenuItem("Copy Message").apply {
            setOnAction {
                actions().copy?.let { stash ->
                    Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(stash.message) })
                }
            }
        }
    val stashChangesItem =
        MenuItem("Stash Changes…").apply { setOnAction { if (actions().stashChanges) commands().stashChanges() } }

    /** The items in their order. */
    val items =
        listOf(
            popItem,
            applyItem,
            dropItem,
            SeparatorMenuItem(),
            copyMessageItem,
            SeparatorMenuItem(),
            stashChangesItem,
        )

    /** Enables the items as [actions] allow now. */
    fun update() {
        val actions = actions()
        popItem.isDisable = actions.pop == null
        applyItem.isDisable = actions.apply == null
        dropItem.isDisable = actions.drop == null
        copyMessageItem.isDisable = actions.copy == null
        stashChangesItem.isDisable = !actions.stashChanges
    }
}

/** The tooltip of a stash in the branches pane: "stash@{0}, made 28/10/2026, 14:03" (the date as the log shows it). */
internal fun stashTooltip(
    stash: GitStash,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(zone)
    return "${stash.name}, made ${format.format(Instant.ofEpochSecond(stash.time))}"
}
