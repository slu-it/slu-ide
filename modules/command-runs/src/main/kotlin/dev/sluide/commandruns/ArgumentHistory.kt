package dev.sluide.commandruns

import javafx.beans.property.BooleanProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import java.nio.file.Path

/**
 * The run-argument history of one view's file (ADR 0091); [file] and [projectFolder] are read on every call (the file
 * can move). [entries] are those last read: [reload] after the file moved. FX thread.
 */
class ArgumentHistory(
    private val histories: ArgumentHistories,
    private val file: () -> Path,
    private val projectFolder: () -> Path?,
) {
    private val items = FXCollections.observableArrayList<String>()

    /** The arguments, newest first. */
    val entries: ObservableList<String> = FXCollections.unmodifiableObservableList(items)

    /** Whether the history sections of all run panes are expanded. */
    val expanded: BooleanProperty get() = histories.expanded

    /** Reads the history of the file again. */
    fun reload() = show(histories.entries(file(), projectFolder()))

    /** Puts [arguments] (trimmed, unless blank) on top of the history. */
    fun record(arguments: String) = show(histories.record(file(), projectFolder(), arguments))

    /** Removes [entry] from the history. */
    fun remove(entry: String) = show(histories.remove(file(), projectFolder(), entry))

    private fun show(entries: List<String>) {
        if (entries != items) items.setAll(entries)
    }
}
