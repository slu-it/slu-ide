package dev.sluide.fileexplorer

import javafx.collections.ObservableList
import javafx.scene.control.TreeItem
import java.nio.file.Files
import java.nio.file.Path

/**
 * A tree node for a file or folder. The children of a folder are read from disk the first
 * time they are requested, so deep trees and symlink cycles are never traversed eagerly.
 * [onChildrenLoaded] is called when that happens, for this item and every descendant.
 */
class FileTreeItem(
    path: Path,
    private val onChildrenLoaded: (FileTreeItem) -> Unit = {},
) : TreeItem<Path>(path) {
    private val directory: Boolean = Files.isDirectory(path)
    private var childrenLoaded = false

    override fun isLeaf(): Boolean = !directory

    override fun getChildren(): ObservableList<TreeItem<Path>> {
        val children = super.getChildren()
        if (directory && !childrenLoaded) {
            childrenLoaded = true
            children.setAll(listDirectory(value).map(::child))
            onChildrenLoaded(this)
        }
        return children
    }

    /**
     * Reads the folder again if its children were loaded. Unchanged children are kept as they are (with their
     * expansion, loaded children and selection); new entries are inserted in sort order and gone ones removed.
     */
    fun refresh() {
        if (!childrenLoaded) return
        val children = super.getChildren()
        val current = children.associateBy { it.value }
        val listed =
            listDirectory(value).map { path ->
                val existing = current[path] as? FileTreeItem
                // A file that became a folder (or the other way round) needs a new item.
                if (existing != null && existing.directory == Files.isDirectory(path)) existing else child(path)
            }
        val keep = listed.toSet()
        children.removeIf { it !in keep }
        listed.forEachIndexed { index, item ->
            if (children.getOrNull(index) !== item) children.add(index, item)
        }
    }

    /** This item and all descendants whose children are loaded, parents before their children. */
    fun loadedFolders(): List<FileTreeItem> =
        if (!childrenLoaded) {
            emptyList()
        } else {
            listOf(this) + super.getChildren().filterIsInstance<FileTreeItem>().flatMap { it.loadedFolders() }
        }

    private fun child(path: Path) = FileTreeItem(path, onChildrenLoaded)
}
