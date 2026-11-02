package dev.sluide.databases.ui.result

import javafx.collections.FXCollections
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent

private const val MAX_LINES = 1000

/** The Output tab: a read-only list of [OutputLine]s, newest at the bottom, at most 1000. */
internal class OutputLog(
    private val clipboard: (String) -> Unit,
) {
    private val lines = FXCollections.observableArrayList<OutputLine>()
    val list = ListView(lines)

    init {
        list.styleClass += "result-output"
        list.selectionModel.selectionMode = SelectionMode.MULTIPLE
        list.setCellFactory { LineCell() }
        val copy = MenuItem("Copy").apply { setOnAction { copy() } }
        list.contextMenu = javafx.scene.control.ContextMenu(copy)
        val key = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)
        list.addEventFilter(KeyEvent.KEY_PRESSED) {
            if (key.match(it)) {
                copy()
                it.consume()
            }
        }
    }

    val texts: List<String> get() = lines.map { it.text }

    fun add(line: OutputLine) {
        lines += line
        if (lines.size > MAX_LINES) lines.remove(0, lines.size - MAX_LINES)
        list.scrollTo(lines.size - 1)
    }

    fun copy() {
        val text =
            list.selectionModel.selectedIndices
                .sorted()
                .joinToString("\n") { lines[it].text }
        if (text.isNotEmpty()) clipboard(text)
    }

    private class LineCell : ListCell<OutputLine>() {
        override fun updateItem(
            item: OutputLine?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            styleClass.remove("result-error")
            text = if (empty || item == null) null else item.text
            if (item?.error == true) styleClass += "result-error"
        }
    }
}
