package dev.sluide.fileexplorer

import javafx.scene.control.TreeCell
import javafx.scene.control.TreeView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/** Tree view showing the complete content of a folder, including hidden files. */
@Component
class FileExplorerView : TreeView<Path>() {
    /** Called with a file the user wants to open (double-click or Enter). Never called for folders. */
    var onOpenFile: ((Path) -> Unit)? = null

    init {
        setCellFactory { PathCell() }
        addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            val selected = selectionModel.selectedItem?.value
            if (event.code == KeyCode.ENTER && selected != null && requestOpen(selected)) event.consume()
        }
    }

    /** Shows [root] and its content; `null` clears the view. */
    fun open(root: Path?) {
        this.root = root?.let { FileTreeItem(it).apply { isExpanded = true } }
    }

    /** Passes [path] to [onOpenFile] if it is a regular file; returns whether it did. */
    internal fun requestOpen(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        onOpenFile?.invoke(path)
        return true
    }

    private inner class PathCell : TreeCell<Path>() {
        init {
            setOnMouseClicked { event ->
                val path = item
                if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && !isEmpty && path != null) {
                    requestOpen(path)
                }
            }
        }

        override fun updateItem(
            item: Path?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else (item.fileName ?: item).toString()
        }
    }
}
