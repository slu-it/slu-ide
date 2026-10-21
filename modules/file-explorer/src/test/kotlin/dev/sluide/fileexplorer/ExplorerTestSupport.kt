package dev.sluide.fileexplorer

import javafx.scene.control.TreeItem
import java.nio.file.Path

/** The top-level item of the opened folder; fails if there is none. */
internal val FileExplorerView.projectNode: TreeItem<Path> get() = checkNotNull(projectItem) { "no folder opened" }

/** Selects only [item]; the selection is multiple, so a plain `select` would add to it. */
internal fun FileExplorerView.selectOnly(item: TreeItem<Path>) {
    selectionModel.clearSelection()
    selectionModel.select(item)
}
