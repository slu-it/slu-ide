package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.tree.FileTreeItem
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent

/** Shortcut+C: copies the selected entries to the clipboard. */
internal val COPY_KEYS = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)

/** Shortcut+X: cuts the selected entries. */
internal val CUT_KEYS = KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN)

/** Shortcut+V: pastes the clipboard's files. */
internal val PASTE_KEYS = KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN)

/**
 * Shortcut+Shift+D: compares the two selected files. Also the window's Show Changes accelerator, which only runs for
 * the keys left unconsumed.
 */
internal val COMPARE_KEYS = KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)

/** What the keys of the explorer act on; each action returns whether it did something, consuming the key. */
internal class ExplorerKeyHost(
    /** The selected entries, in row order. */
    val selectedEntries: () -> List<FileTreeItem>,
    val canChange: (FileTreeItem) -> Boolean,
    /** Opens the regular files among the entries; returns how many. */
    val openFiles: (List<FileTreeItem>) -> Int,
    val refreshAll: () -> Unit,
    val rename: (FileTreeItem) -> Unit,
    val delete: (List<FileTreeItem>) -> Unit,
    val copy: (List<FileTreeItem>) -> Boolean,
    val cut: (List<FileTreeItem>) -> Boolean,
    val paste: (List<FileTreeItem>) -> Boolean,
    val cancelCut: () -> Boolean,
    /** Compares the entries if they are exactly two regular files; returns whether it did. */
    val compare: (List<FileTreeItem>) -> Boolean,
)

/**
 * The keys of the explorer [tree]: Enter opens the selected files, F5 refreshes, F2 renames the single selected entry,
 * Delete (or Shortcut+Backspace) deletes the selection, Shortcut+C / X / V copy, cut and paste, Escape ends a cut,
 * Shortcut+Shift+D compares two selected files (otherwise left to the window's Show Changes). A
 * running drag takes Escape first (its filter consumes it).
 */
internal fun installExplorerKeys(
    tree: Node,
    host: ExplorerKeyHost,
) {
    tree.addEventHandler(KeyEvent.KEY_PRESSED) { event ->
        val selected = host.selectedEntries()
        val single = selected.singleOrNull()?.takeIf(host.canChange)
        val handled =
            when {
                event.code == KeyCode.ENTER -> {
                    host.openFiles(selected) > 0
                }

                event.code == KeyCode.F5 -> {
                    host.refreshAll()
                    true
                }

                event.code == KeyCode.F2 && single != null -> {
                    host.rename(single)
                    true
                }

                // On macOS, Cmd+Backspace, as in the Finder: Mac keyboards often have no Delete key.
                (event.code == KeyCode.DELETE || (event.code == KeyCode.BACK_SPACE && event.isShortcutDown)) &&
                    selected.isNotEmpty() && selected.all(host.canChange) -> {
                    host.delete(selected)
                    true
                }

                COPY_KEYS.match(event) -> {
                    host.copy(selected)
                }

                CUT_KEYS.match(event) -> {
                    host.cut(selected)
                }

                PASTE_KEYS.match(event) -> {
                    host.paste(selected)
                }

                COMPARE_KEYS.match(event) -> {
                    host.compare(selected)
                }

                event.code == KeyCode.ESCAPE -> {
                    host.cancelCut()
                }

                else -> {
                    false
                }
            }
        if (handled) event.consume()
    }
}
