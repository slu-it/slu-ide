package dev.sluide.editor

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.Node
import java.nio.file.Path

/** A file shown in a tab together with the view that displays it. */
internal class OpenFile(
    val path: Path,
    val content: Node,
) {
    val name: String get() = (path.fileName ?: path).toString()
}

/** The files open in the editor area, in tab order, and the selected one. */
internal class OpenFiles {
    private val _files = FXCollections.observableArrayList<OpenFile>()
    private val _selected = ReadOnlyObjectWrapper<OpenFile?>()

    val files: ObservableList<OpenFile> = FXCollections.unmodifiableObservableList(_files)
    val selected: ReadOnlyObjectProperty<OpenFile?> = _selected.readOnlyProperty

    fun find(path: Path): OpenFile? = _files.firstOrNull { it.path == path }

    /** Adds a tab for [path] showing [content] and selects it. */
    fun open(
        path: Path,
        content: Node,
    ): OpenFile {
        require(find(path) == null) { "Already open: $path" }
        return OpenFile(path, content).also {
            _files += it
            _selected.set(it)
        }
    }

    fun select(file: OpenFile) {
        require(file in _files) { "Not open: ${file.path}" }
        _selected.set(file)
    }

    /** Removes [file]. If it was selected, its right neighbour is selected, otherwise its left one, if any. */
    fun close(file: OpenFile) {
        val index = _files.indexOf(file)
        if (index < 0) return
        _files.removeAt(index)
        if (_selected.get() === file) {
            _selected.set(_files.getOrNull(index) ?: _files.getOrNull(index - 1))
        }
    }

    fun closeAll() = closeAll(_files.toList(), keep = null)

    /** Removes [toClose] in one list change; if the selected file is among them, [keep] becomes selected. */
    private fun closeAll(
        toClose: Collection<OpenFile>,
        keep: OpenFile?,
    ) {
        if (toClose.isEmpty()) return
        _files.removeAll(toClose.toSet())
        if (_selected.get() in toClose) _selected.set(keep)
    }
}
