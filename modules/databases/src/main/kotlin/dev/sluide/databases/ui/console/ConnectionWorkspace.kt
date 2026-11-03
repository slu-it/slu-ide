package dev.sluide.databases.ui.console

import dev.sluide.databases.result.TableRef
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.DatabasesTabModel
import dev.sluide.databases.ui.cancelFirst
import dev.sluide.databases.ui.result.ResultArea
import dev.sluide.databases.ui.tree.WorkspaceActions
import javafx.application.Platform
import javafx.beans.value.ObservableValue
import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.SplitPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.layout.StackPane
import javafx.stage.Window

/** One console of a workspace: its tab, editor pane and result area. */
internal class Console(
    val tab: Tab,
    val pane: ConsolePane,
    val results: ResultArea,
)

/**
 * The middle pane of a connected card (ADR 0121): console tabs over the selected console's result area. Consoles and
 * their text live as long as the window; a disconnect only closes the cursors of their results.
 */
class ConnectionWorkspace internal constructor(
    private val entry: ConnectionEntry,
    private val model: DatabasesTabModel,
    private val history: QueryHistory,
    private val pageSize: () -> Int,
    private val fetchAllCap: () -> Int,
    private val editorCss: ObservableValue<String>,
    private val window: () -> Window?,
    private val fx: (Runnable) -> Unit = Platform::runLater,
    /** Asks "Discard the unsubmitted edits?"; true to close the console. */
    private val confirmDiscard: () -> Boolean = { askDiscard(window()) },
    /** Whether a console's results have unsubmitted edits. */
    private val pendingEdits: (ResultArea) -> Boolean = { it.pendingEdits },
) {
    internal val consoles = mutableListOf<Console>()
    private var counter = 0

    /** A console tab is being closed: the pane may then select "+", which must not add a console. */
    private var closing = false

    private val plus = Tab("+").apply { isClosable = false }
    internal val tabs =
        TabPane(plus).apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.ALL_TABS
            styleClass += "console-tabs"
        }
    private val bottom = StackPane()

    val node: SplitPane =
        SplitPane(tabs, bottom).apply {
            orientation = Orientation.VERTICAL
            styleClass += "console-workspace"
            setDividerPositions(0.45)
        }

    private val selected: Console? get() = consoles.firstOrNull { it.tab === tabs.selectionModel.selectedItem }

    init {
        SplitPane.setResizableWithParent(tabs, true)
        add()
        tabs.selectionModel.selectedItemProperty().addListener { _, _, tab ->
            when {
                tab === plus && closing -> consoles.lastOrNull()?.let { tabs.selectionModel.select(it.tab) }
                tab === plus -> add()
                else -> showResults()
            }
        }
        entry.workspaceActions =
            object : WorkspaceActions {
                override fun openData(ref: TableRef) {
                    val console = selected ?: return
                    console.results.openData(ref)
                    bottom.children.setAll(console.results.node)
                    val first = node.dividers.firstOrNull()
                    if (first != null && first.position > MAX_DIVIDER) first.position = MAX_DIVIDER
                }

                override fun insertIntoConsole(text: String) = insert(text)
            }
        entry.unsubmittedEdits = { consoles.any { pendingEdits(it.results) } }
        entry.session.addListener { _, _, session -> if (session == null) consoles.forEach { it.results.close() } }
    }

    /** Adds a console after the last one and selects it. */
    internal fun add(): Console {
        counter++
        val title = if (counter == 1) "Console" else "Console $counter"
        val results = ResultArea(entry, pageSize, fetchAllCap, fx)
        val pane = ConsolePane(entry, model, history, pageSize, editorCss, results, fx)
        val tab = Tab(title, pane.node)
        val console = Console(tab, pane, results)
        tab.setOnCloseRequest { event ->
            if (pendingEdits(results) && !confirmDiscard()) event.consume() else closing = true
        }
        tab.setOnClosed {
            closing = false
            results.close()
            consoles.remove(console)
            updateClosable()
            showResults()
        }
        consoles += console
        tabs.tabs.add(tabs.tabs.size - 1, tab)
        updateClosable()
        tabs.selectionModel.select(tab)
        return console
    }

    private fun updateClosable() = consoles.forEach { it.tab.isClosable = consoles.size > 1 }

    private fun showResults() {
        val console = selected ?: return
        bottom.children.setAll(console.results.node as Node)
    }

    private fun insert(text: String) {
        val console = selected ?: return
        val editor = console.pane.editor
        val content = editor.text
        val caret = editor.caretOffset.coerceIn(0, content.length)
        val lineStart = content.lastIndexOf('\n', caret - 1) + 1
        val lineEnd = content.indexOf('\n', caret).let { if (it < 0) content.length else it }
        val empty = content.substring(lineStart, lineEnd).isBlank()
        if (empty) editor.replace(caret, caret, text) else editor.replace(lineEnd, lineEnd, "\n$text")
        editor.selectRange(editor.caretOffset, editor.caretOffset)
        editor.focus()
    }

    private companion object {
        const val MAX_DIVIDER = 0.8
    }
}

private fun askDiscard(owner: Window?): Boolean {
    val discard = ButtonType("Discard", ButtonData.OK_DONE)
    val alert =
        Alert(Alert.AlertType.CONFIRMATION, "Discard the unsubmitted edits?", discard, ButtonType.CANCEL).apply {
            owner?.let(::initOwner)
            title = "Close Console"
            headerText = null
            cancelFirst(this)
        }
    return alert.showAndWait().orElse(ButtonType.CANCEL) == discard
}
