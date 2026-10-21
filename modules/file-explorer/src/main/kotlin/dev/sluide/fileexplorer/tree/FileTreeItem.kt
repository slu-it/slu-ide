package dev.sluide.fileexplorer.tree

import javafx.beans.property.SimpleObjectProperty
import javafx.collections.ObservableList
import javafx.scene.control.TreeItem
import java.nio.file.Files
import java.nio.file.Path

/**
 * A tree node for a file or folder. The children of a folder are read from disk the first
 * time they are requested, so deep trees and symlink cycles are never traversed eagerly.
 * [onChildrenLoaded] is called when that happens, for this item and every descendant.
 */
internal class FileTreeItem(
    path: Path,
    /** Shown instead of the name of the folder, for a top-level folder with a given name; `null` for the others. */
    val label: String? = null,
    private val onChildrenLoaded: (FileTreeItem) -> Unit = {},
) : TreeItem<Path>(path) {
    private val directory: Boolean = Files.isDirectory(path)
    private var childrenLoaded = false

    /** Whether this is a folder; decided when the item is created. */
    val isDirectory: Boolean get() = directory

    /**
     * Cached result of the explorer's check whether this file can be opened (it may read the file); `null` until it
     * is checked, and again after the file changed. The cells listen to it to update their icon.
     */
    internal val readable = SimpleObjectProperty<Boolean?>(null)

    override fun isLeaf(): Boolean = !directory

    /** Whether the children of this folder have been read. */
    val isLoaded: Boolean get() = childrenLoaded

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

    /**
     * Forgets [readable] of the files among the children that are in [changed], or of all of them if this folder is
     * (events were lost): they may have become readable or binary.
     */
    fun forgetReadability(changed: Collection<Path>) {
        val all = value in changed
        super
            .getChildren()
            .filterIsInstance<FileTreeItem>()
            .filter { !it.directory && (all || it.value in changed) }
            .forEach { it.readable.value = null }
    }

    /** This item and all descendants whose children are loaded, parents before their children. */
    fun loadedFolders(): List<FileTreeItem> =
        if (!childrenLoaded) {
            emptyList()
        } else {
            listOf(this) + super.getChildren().filterIsInstance<FileTreeItem>().flatMap { it.loadedFolders() }
        }

    private fun child(path: Path) = FileTreeItem(path, onChildrenLoaded = onChildrenLoaded)
}
