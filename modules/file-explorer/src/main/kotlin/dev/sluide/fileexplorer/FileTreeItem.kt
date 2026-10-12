package dev.sluide.fileexplorer

import javafx.collections.ObservableList
import javafx.scene.control.TreeItem
import java.nio.file.Files
import java.nio.file.Path

/**
 * A tree node for a file or folder. The children of a folder are read from disk the first
 * time they are requested, so deep trees and symlink cycles are never traversed eagerly.
 */
class FileTreeItem(path: Path) : TreeItem<Path>(path) {

    private val directory: Boolean = Files.isDirectory(path)
    private var childrenLoaded = false

    override fun isLeaf(): Boolean = !directory

    override fun getChildren(): ObservableList<TreeItem<Path>> {
        val children = super.getChildren()
        if (directory && !childrenLoaded) {
            childrenLoaded = true
            children.setAll(listDirectory(value).map(::FileTreeItem))
        }
        return children
    }
}
