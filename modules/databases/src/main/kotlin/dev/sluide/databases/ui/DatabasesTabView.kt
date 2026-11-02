package dev.sluide.databases.ui

import dev.sluide.databases.DatabasesPanes
import dev.sluide.databases.connect.DatabaseKind
import javafx.beans.InvalidationListener
import javafx.beans.binding.Bindings
import javafx.beans.value.ChangeListener
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.SplitPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/** Where the dividers start: the cards' and the tree's widths in pixels; the middle gets the rest. */
private const val CARDS_WIDTH = 240.0
private const val TREE_WIDTH = 280.0

/**
 * The Databases tab's three-way split (ADR 0121): the cards of [panes] left, the selected card's workspace in the middle
 * (while it is connected; else a placeholder with Connect), its tree right. A card's workspace and tree are created
 * by [panes] the first time they are shown connected and kept on the entry, so they survive switches, a closed tab
 * and reconnects. The middle grows with the window; the dividers keep their place while the nodes switch.
 */
internal class DatabasesTabView(
    private val model: DatabasesTabModel,
    private val panes: DatabasesPanes,
) {
    private val middle = StackPane().apply { styleClass += "databases-middle" }
    private val right = StackPane().apply { styleClass += "databases-right" }
    private val left = panes.cards(model)

    val node: SplitPane =
        SplitPane(left, middle, right).apply {
            styleClass += "databases-tab"
            orientation = Orientation.HORIZONTAL
            stylesheets += checkNotNull(DatabasesTabView::class.java.getResource("databases-tab.css")).toExternalForm()
        }

    /** The entry whose properties [refresh] listens to. */
    private var watched: ConnectionEntry? = null
    private val refresh = InvalidationListener { refresh() }
    private val selectionChanged = ChangeListener<ConnectionEntry?> { _, _, entry -> watch(entry) }

    init {
        SplitPane.setResizableWithParent(left, false)
        SplitPane.setResizableWithParent(right, false)
        placeDividersOnceSized()
        model.selected.addListener(selectionChanged)
        watch(model.selected.get())
    }

    /** The node shown in the middle; for tests. */
    internal val middleNode: Node? get() = middle.children.firstOrNull()

    /** The node shown on the right; for tests. */
    internal val rightNode: Node? get() = right.children.firstOrNull()

    /** Stops following the model; the entries' nodes are left to the next view. */
    fun dispose() {
        model.selected.removeListener(selectionChanged)
        watch(null)
        middle.children.clear()
        right.children.clear()
    }

    private fun watch(entry: ConnectionEntry?) {
        watched?.let { old ->
            old.status.removeListener(refresh)
            old.error.removeListener(refresh)
            old.config.removeListener(refresh)
        }
        watched = entry
        entry?.let {
            it.status.addListener(refresh)
            it.error.addListener(refresh)
            it.config.addListener(refresh)
        }
        refresh()
    }

    private fun refresh() {
        val entry = watched
        val connected = entry?.status?.get() == ConnectionStatus.CONNECTED
        val center =
            when {
                entry == null -> placeholder("Add a connection with +")
                connected -> entry.workspaceNode ?: panes.workspace(entry).also { entry.workspaceNode = it }
                else -> notConnected(entry)
            }
        val side =
            if (entry != null && connected) {
                entry.treeNode ?: panes.tree(entry).also { entry.treeNode = it }
            } else {
                placeholder("Connect to browse")
            }
        middle.children.setAll(center)
        right.children.setAll(side)
    }

    private fun notConnected(entry: ConnectionEntry): Node {
        val config = entry.config.get()
        val connecting = entry.status.get() == ConnectionStatus.CONNECTING
        val name = Label(config.name).apply { styleClass += "databases-card-name" }
        val state = Label(if (connecting) "Connecting…" else "Not connected")
        val error =
            Label(entry.error.get()).apply {
                styleClass += "databases-error"
                isWrapText = true
                isVisible = text.isNotEmpty()
                isManaged = isVisible
            }
        val connect =
            Button("Connect").apply {
                isDisable = connecting
                setOnAction { model.connect(entry) }
            }
        return VBox(8.0, name, state, error, connect).apply {
            styleClass += "databases-not-connected"
            alignment = Pos.CENTER
            maxWidth = 480.0
            padding = Insets(16.0)
        }
    }

    private fun placeholder(text: String): Node =
        Label(text).apply {
            styleClass += "databases-placeholder"
            isWrapText = true
        }

    /** Puts the dividers at [CARDS_WIDTH] and [TREE_WIDTH] once the split pane has its first width. */
    private fun placeDividersOnceSized() {
        lateinit var listener: InvalidationListener
        listener =
            InvalidationListener {
                val width = node.width
                if (width <= 0) return@InvalidationListener
                node.widthProperty().removeListener(listener)
                val first = (CARDS_WIDTH / width).coerceIn(0.1, 0.4)
                val second = (1 - TREE_WIDTH / width).coerceIn(first + 0.1, 0.9)
                node.setDividerPositions(first, second)
            }
        node.widthProperty().addListener(listener)
    }
}

/** The panes until the console and tree packages replace them. */
internal object PlaceholderPanes {
    @Suppress("UNUSED_PARAMETER")
    fun workspace(entry: ConnectionEntry): Node =
        Label("Console (coming)").apply {
            styleClass +=
                "databases-placeholder"
        }

    fun tree(entry: ConnectionEntry): Node =
        Label("Objects of ${entry.config.get().name} (coming)").apply { styleClass += "databases-placeholder" }
}
