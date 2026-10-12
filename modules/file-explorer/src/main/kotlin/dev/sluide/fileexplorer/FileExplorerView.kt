package dev.sluide.fileexplorer

import javafx.scene.control.TreeCell
import javafx.scene.control.TreeView
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Tree view showing the complete content of a folder, including hidden files. */
@Component
class FileExplorerView : TreeView<Path>() {

    init {
        setCellFactory { PathCell() }
    }

    /** Shows [root] and its content; `null` clears the view. */
    fun open(root: Path?) {
        this.root = root?.let { FileTreeItem(it).apply { isExpanded = true } }
    }

    private class PathCell : TreeCell<Path>() {
        override fun updateItem(item: Path?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else (item.fileName ?: item).toString()
        }
    }
}
