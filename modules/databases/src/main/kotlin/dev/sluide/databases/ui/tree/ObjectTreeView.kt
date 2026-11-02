package dev.sluide.databases.ui.tree

import dev.sluide.databases.meta.DbObject
import dev.sluide.databases.meta.ObjectKind
import dev.sluide.databases.result.TableRef
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.DatabasesTabModel
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon

/**
 * The right pane of the Databases tab (ADR 0121): the objects of one connection as a tree of schemas, loaded per
 * schema when it is expanded. Reloads (Refresh, F5, a new session, the system-schemas toggle) keep expansion and
 * selection through [TreeSync]. Double click or Enter on a table or view opens its data. FX thread only.
 */
internal class ObjectTreeView(
    private val entry: ConnectionEntry,
    private val model: DatabasesTabModel,
    private val cache: MetadataCache,
    private val openScratch: (extension: String, text: String) -> Unit,
) {
    private val sync = TreeSync<NodeKey, TreeRow>()
    private var schemas: Load<List<String>> = Load.Loading
    private val schemaLoads = HashMap<String, Load<List<DbObject>>>()

    /** Schemas with a load under way; a reload forgets them, and its generation makes older replies fall away. */
    private val pending = HashSet<String>()
    private var generation = 0

    val tree: TreeView<TreeRow> =
        TreeView(sync.root).apply {
            isShowRoot = false
            styleClass += "databases-tree-view"
            setCellFactory { ObjectCell() }
            VBox.setVgrow(this, Priority.ALWAYS)
        }

    val refreshButton =
        toolbarButton(Button(), "mdi2r-refresh", "Refresh (F5)").apply { setOnAction { reload() } }

    val systemToggle =
        toolbarButton(ToggleButton(), "mdi2e-eye-outline", "Show System Schemas").apply {
            id = "databases-tree-system"
            isSelected = entry.config.get().showSystemSchemas
            setOnAction {
                model.setShowSystemSchemas(entry, isSelected)
                reload()
            }
        }

    private val collapseButton =
        toolbarButton(Button(), "mdi2c-collapse-all-outline", "Collapse All").apply {
            setOnAction { sync.root.children.forEach(::collapse) }
        }

    val node: VBox =
        VBox(
            HBox(refreshButton, systemToggle, collapseButton).apply { styleClass += "databases-tree-toolbar" },
            tree,
        ).apply {
            styleClass += "databases-tree"
            minWidth = 0.0
            stylesheets += checkNotNull(ObjectTreeView::class.java.getResource("object-tree.css")).toExternalForm()
        }

    init {
        sync.root.isExpanded = true
        sync.root.addEventHandler(TreeItem.branchExpandedEvent<TreeRow>()) { event ->
            val row = event.treeItem.value
            if (row is ObjectRow && row.obj.kind == ObjectKind.SCHEMA) schemaExpanded(row.obj.name)
        }
        tree.addEventFilter(KeyEvent.KEY_PRESSED, ::keyPressed)
        entry.config.subscribe { config ->
            if (systemToggle.isSelected !=
                config.showSystemSchemas
            ) {
                systemToggle.isSelected = config.showSystemSchemas
            }
        }
        // Fires at once for the current session (the first load) and again for every reconnect.
        entry.session.subscribe { session -> if (session != null) reload() }
    }

    /** Refresh: drops the cache, reloads the schema list and every expanded schema; the rest loads on expand. */
    fun reload() {
        generation++
        pending.clear()
        cache.refresh()
        val expanded = schemaLoads.keys.filter { isExpanded(it) }.toSet()
        schemaLoads.keys.retainAll(expanded)
        loadSchemas()
        expanded.forEach(::loadSchema)
    }

    private fun isExpanded(schema: String) = sync.itemOf(ObjectTreeModel.schemaKey(schema))?.isExpanded == true

    private fun loadSchemas() {
        val started = generation
        cache.schemas(entry.config.get().showSystemSchemas).whenComplete { names, error ->
            if (started != generation) return@whenComplete
            schemas = if (error == null) Load.Loaded(names) else Load.Failed(ObjectTreeModel.errorMessage(error))
            rebuild()
        }
    }

    private fun loadSchema(name: String) {
        if (!pending.add(name)) return
        val started = generation
        schemaLoads.putIfAbsent(name, Load.Loading)
        cache.schema(name).whenComplete { objects, error ->
            if (started != generation) return@whenComplete
            pending.remove(name)
            schemaLoads[name] =
                if (error == null) Load.Loaded(objects) else Load.Failed(ObjectTreeModel.errorMessage(error))
            rebuild()
        }
    }

    private fun schemaExpanded(name: String) {
        val load = schemaLoads[name]
        if (load is Load.Loaded || name in pending) return
        schemaLoads[name] = Load.Loading
        rebuild()
        loadSchema(name)
    }

    private fun rebuild() = sync.update(ObjectTreeModel.nodes(schemas) { schemaLoads[it] ?: Load.Loading })

    private fun collapse(item: TreeItem<TreeRow>) {
        item.isExpanded = false
        item.children.forEach(::collapse)
    }

    private fun keyPressed(event: KeyEvent) {
        when (event.code) {
            KeyCode.F5 -> {
                reload()
            }

            KeyCode.ENTER -> {
                val item = tree.selectionModel.selectedItem ?: return
                val obj = (item.value as? ObjectRow)?.obj
                if (obj != null && ObjectTreeModel.opensData(obj)) {
                    openData(obj)
                } else if (!item.isLeaf) {
                    item.isExpanded = !item.isExpanded
                }
            }

            else -> {
                return
            }
        }
        event.consume()
    }

    private fun openData(obj: DbObject) {
        entry.workspaceActions?.openData(TableRef(entry.config.get().kind, obj.schema, obj.name))
    }

    private fun showDdl(obj: DbObject) {
        cache.ddl(obj).whenComplete { ddl, error ->
            when {
                error != null -> alert(Alert.AlertType.ERROR, ObjectTreeModel.errorMessage(error))
                ddl.isBlank() -> alert(Alert.AlertType.INFORMATION, "${obj.name} no longer exists. Refresh the tree.")
                else -> openScratch("sql", ddl)
            }
        }
    }

    private fun alert(
        type: Alert.AlertType,
        message: String,
    ) {
        Alert(type, message).apply {
            headerText = null
            node.scene
                ?.window
                ?.let { initOwner(it) }
            show()
        }
    }

    private fun copy(text: String) {
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
    }

    private fun menuItem(
        title: String,
        action: () -> Unit,
    ) = MenuItem(title).apply { setOnAction { action() } }

    private fun menuFor(obj: DbObject): List<MenuItem> {
        val kind = entry.config.get().kind
        val items =
            ObjectTreeModel.actions(obj.kind).map { action ->
                when (action) {
                    NodeAction.OPEN_DATA -> {
                        menuItem("Open Data") { openData(obj) }
                    }

                    NodeAction.SHOW_DDL -> {
                        menuItem("Show DDL") { showDdl(obj) }
                    }

                    NodeAction.COPY_NAME -> {
                        menuItem("Copy Name") { copy(obj.name) }
                    }

                    NodeAction.COPY_QUALIFIED_NAME -> {
                        menuItem("Copy Qualified Name") { copy(ObjectTreeModel.qualifiedName(kind, obj)) }
                    }

                    NodeAction.GENERATE_SELECT -> {
                        menuItem("Generate SELECT") {
                            entry.workspaceActions?.insertIntoConsole(ObjectTreeModel.generateSelect(kind, obj))
                        }
                    }
                }
            }
        return items + SeparatorMenuItem() + menuItem("Refresh") { reload() }
    }

    private fun <B : javafx.scene.control.ButtonBase> toolbarButton(
        button: B,
        icon: String,
        tip: String,
    ): B =
        button.apply {
            graphic = FontIcon(icon).apply { styleClass += "databases-tree-icon" }
            tooltip = Tooltip(tip)
            isFocusTraversable = false
            styleClass += "databases-tree-button"
        }

    /** A row: icon, label, muted detail; Loading and Error rows have no icon and no menu. */
    private inner class ObjectCell : TreeCell<TreeRow>() {
        private val label = Label()
        private val detail = Label().apply { styleClass += "databases-tree-detail" }
        private val box = HBox(label, detail).apply { styleClass += "databases-tree-row" }
        private val menu =
            ContextMenu().apply {
                setOnShowing { (item as? ObjectRow)?.let { items.setAll(menuFor(it.obj)) } }
            }

        init {
            // The tree view's own double click expands a table; a table opens its data instead.
            addEventFilter(MouseEvent.ANY) { event ->
                val obj = (item as? ObjectRow)?.obj
                if (event.button != MouseButton.PRIMARY || event.clickCount != 2 || obj == null) return@addEventFilter
                if (!ObjectTreeModel.opensData(obj)) return@addEventFilter
                if (event.eventType == MouseEvent.MOUSE_CLICKED) openData(obj)
                if (event.eventType != MouseEvent.MOUSE_MOVED) event.consume()
            }
        }

        override fun updateItem(
            row: TreeRow?,
            empty: Boolean,
        ) {
            super.updateItem(row, empty)
            text = null
            label.styleClass.removeAll(ROW_CLASSES)
            if (empty || row == null) {
                graphic = null
                contextMenu = null
                return
            }
            label.text = ObjectTreeModel.label(row)
            val muted = ObjectTreeModel.detail(row)
            detail.text = muted
            detail.isVisible = muted.isNotEmpty()
            detail.isManaged = detail.isVisible
            when (row) {
                is ErrorRow -> label.styleClass += "databases-tree-error"
                is LoadingRow -> label.styleClass += "databases-tree-loading"
                is ObjectRow -> if (row.obj.kind == ObjectKind.GROUP) label.styleClass += "databases-tree-group"
            }
            val kind = (row as? ObjectRow)?.obj?.kind
            box.children.setAll(
                *listOfNotNull(
                    kind?.let { ObjectIcons.icon(it) },
                    label,
                    detail,
                ).toTypedArray(),
            )
            graphic = box
            contextMenu = menu.takeIf { row is ObjectRow }
        }
    }

    private companion object {
        val ROW_CLASSES = listOf("databases-tree-error", "databases-tree-loading", "databases-tree-group")
    }
}
