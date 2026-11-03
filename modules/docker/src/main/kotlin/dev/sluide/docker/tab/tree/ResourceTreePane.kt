package dev.sluide.docker.tab.tree

import dev.sluide.docker.model.ResourceKind
import dev.sluide.docker.tab.DockerTabModel
import dev.sluide.docker.tab.RowActions
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

/**
 * The left side of the Docker tab: a filter field above the resource tree. The tree follows the model's rows through
 * [TreeSync], so reloads keep expansion and selection; selecting a row selects it in the model and back. Rows have
 * the [actions] of their kind in a context menu. FX thread only.
 */
internal class ResourceTreePane(
    private val model: DockerTabModel,
    private val actions: RowActions,
) {
    val filter =
        TextField().apply {
            promptText = "Filter"
            styleClass += "docker-filter"
            HBox.setHgrow(this, Priority.ALWAYS)
        }

    private val sync = TreeSync<ResourceKey, TreeRow>()

    val tree =
        TreeView(sync.root).apply {
            isShowRoot = false
            styleClass += "docker-tree"
            setCellFactory { ResourceCell() }
            VBox.setVgrow(this, Priority.ALWAYS)
        }

    val node = VBox(HBox(filter).apply { styleClass += "docker-filter-bar" }, tree).apply { minWidth = 0.0 }

    private var syncing = false

    init {
        filter.textProperty().bindBidirectional(model.filter)
        model.tree.subscribe { nodes ->
            syncing = true
            try {
                sync.update(nodes.map { it.toSyncNode() })
                showSelection()
            } finally {
                syncing = false
            }
        }
        model.selection.addListener { _ -> if (!syncing) showSelection() }
        tree.selectionModel.selectedItemProperty().addListener { _, _, item ->
            if (!syncing) model.select(item?.value?.key)
        }
    }

    /** Selects the model's selection in the tree, expanding the rows above it. */
    private fun showSelection() {
        val item = model.selection.get()?.let(sync::itemOf)
        if (item == null) {
            if (tree.selectionModel.selectedItem != null) tree.selectionModel.clearSelection()
            return
        }
        if (tree.selectionModel.selectedItem === item) return
        var parent = item.parent
        while (parent != null) {
            parent.isExpanded = true
            parent = parent.parent
        }
        val wasSyncing = syncing
        syncing = true
        try {
            tree.selectionModel.select(item)
        } finally {
            syncing = wasSyncing
        }
        tree.getRow(item).takeIf { it >= 0 }?.let { tree.scrollTo(it) }
    }

    /** A row: a state dot for containers, the label, its detail dimmed; the row's actions as context menu. */
    private inner class ResourceCell : TreeCell<TreeRow>() {
        private val dot = Region().apply { styleClass += "docker-state-dot" }
        private val label = Label()
        private val detail = Label().apply { styleClass += "docker-row-detail" }
        private val box = HBox(dot, label, detail).apply { styleClass += "docker-row" }
        private val menu =
            ContextMenu().apply {
                setOnShowing {
                    items.setAll(
                        actions.of(item).map { action ->
                            MenuItem(action.title).apply {
                                isDisable = !action.enabled
                                setOnAction { action.run() }
                            }
                        },
                    )
                }
            }

        override fun updateItem(
            item: TreeRow?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = null
            label.styleClass.removeAll(ROW_CLASSES)
            dot.styleClass.removeAll(STATE_CLASSES)
            if (empty || item == null) {
                graphic = null
                tooltip = null
                contextMenu = null
                return
            }
            label.text = item.label
            detail.text = item.detail
            detail.isVisible = item.detail.isNotEmpty()
            detail.isManaged = detail.isVisible
            val container = (item as? ContainerRow)?.container
            dot.isVisible = container != null
            dot.isManaged = container != null
            container?.let { dot.styleClass += stateClass(it.state) }
            if (item is GroupRow) label.styleClass += "docker-group"
            if (item is ProjectRow && item.project.local) label.styleClass += "docker-project-local"
            tooltip = item.tooltip?.let(::Tooltip)
            graphic = box
            contextMenu = menu.takeIf { item !is ProjectRow && (item as? GroupRow)?.kind != ResourceKind.CONTAINER }
        }
    }

    private companion object {
        val ROW_CLASSES = listOf("docker-group", "docker-project-local")
        val STATE_CLASSES =
            listOf("docker-state-running", "docker-state-paused", "docker-state-restarting", "docker-state-stopped")
    }
}
