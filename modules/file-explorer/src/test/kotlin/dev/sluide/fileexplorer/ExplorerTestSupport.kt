package dev.sluide.fileexplorer

import javafx.scene.control.TreeItem
import java.nio.file.Path

/** The top-level item of the opened folder; fails if there is none. */
internal val FileExplorerView.projectNode: TreeItem<Path> get() = checkNotNull(projectItem) { "no folder opened" }
