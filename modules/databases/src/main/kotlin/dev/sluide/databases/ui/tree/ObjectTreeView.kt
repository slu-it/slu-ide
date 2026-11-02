package dev.sluide.databases.ui.tree

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.meta.DbObject
import dev.sluide.databases.meta.ObjectKind
import dev.sluide.databases.result.TableRef
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.DatabasesTabModel
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBase
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
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon

/**
 * The right pane of the Databases tab (ADR 0121): the objects of one connection as a tree of schemas, loaded per
 * schema when it is expanded. Reloads (Refresh, F5, a new session, the system-schemas toggle) keep expansion and
 * selection through [TreeSync]. Double click or Enter on a table or view opens its data. FX thread only.
 * [window] owns the alerts (the node's own window when it has none); [copyToClipboard] is the seam for Copy Name.
 */
internal class ObjectTreeView(
    private val entry: ConnectionEntry,
    private val model: DatabasesTabModel,
    private val cache: MetadataCache,
    internal val openScratch: (extension: String, text: String) -> Unit,
    private val window: () -> Window? = { null },
    private val copyToClipboard: (String) -> Unit = ::copyToSystemClipboard,
) {
    private val sync = TreeSync<NodeKey, TreeRow>(isSame = ObjectTreeModel::sameRow)

    /** The last schema list that loaded, kept while a reload runs or fails. */
    private var names: List<String>? = null
    private var namesError: String? = null
    private val schemaLoads = HashMap<String, Load<List<DbObject>>>()

    /** Schemas with a load under way; a reload forgets them, and its generation makes older replies fall away. */
    private val pending = HashSet<String>()
    private var generation = 0
    private var showSystem = entry.config.get().showSystemSchemas

    /** Shows a message with a title; replaced in tests. */
    var showInfo: (title: String, message: String) -> Unit = { title, message ->
        alert(Alert.AlertType.INFORMATION, title, message)
    }

    /** Shows an error message with a title; replaced in tests. */
    var showError: (title: String, message: String) -> Unit = { title, message ->
        alert(Alert.AlertType.ERROR, title, message)
    }

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
            isSelected = showSystem
            // The config change below reloads the tree, whoever made it.
            setOnAction { model.setShowSystemSchemas(entry, isSelected) }
        }

    val collapseButton =
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
            when {
                row is ObjectRow && row.obj.kind == ObjectKind.SCHEMA -> schemaExpanded(row.obj.name)

                // Rebuilds the whole tree, not just this table: the unchanged parts cost only key and identity checks.
                row != null && ObjectTreeModel.loadsChildrenOnExpand(row) -> rebuild()
            }
        }
        tree.addEventFilter(KeyEvent.KEY_PRESSED, ::keyPressed)
        // Also changed by the Edit dialog, not only by the toggle.
        entry.config.subscribe { config ->
            if (config.showSystemSchemas == showSystem) return@subscribe
            showSystem = config.showSystemSchemas
            systemToggle.isSelected = showSystem
            if (entry.session.get() != null) reload()
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
        cache.schemas(showSystem).whenComplete { loaded, error ->
            if (started != generation) return@whenComplete
            if (error == null) {
                names = loaded
                namesError = null
            } else {
                namesError = ObjectTreeModel.errorMessage(error)
            }
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

    /** Shows the current state; the selection stays on its node even when the items around it change. */
    private fun rebuild() {
        val selectedKey = tree.selectionModel.selectedItem?.let(sync::keyOf)
        sync.update(
            ObjectTreeModel.nodes(names, namesError, { schemaLoads[it] ?: Load.Loading }) {
                sync.itemOf(it)?.isExpanded ==
                    true
            },
        )
        val item = selectedKey?.let(sync::itemOf)
        if (item != null && tree.selectionModel.selectedItem !== item) tree.selectionModel.select(item)
    }

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

    /** The kind of database the session talks to; its quoting and its introspector. */
    private fun databaseKind(): DatabaseKind =
        entry.session
            .get()
            ?.config
            ?.kind ?: entry.config.get().kind

    private fun openData(obj: DbObject) {
        entry.workspaceActions?.openData(TableRef(databaseKind(), obj.schema, obj.name))
    }

    private fun showDdl(obj: DbObject) {
        cache.ddl(obj).whenComplete { ddl, error ->
            when {
                error != null -> showError(DDL_TITLE, ObjectTreeModel.errorMessage(error))
                ddl.isBlank() -> showInfo(DDL_TITLE, "${obj.name} no longer exists. Refresh the tree.")
                else -> openScratch("sql", ddl)
            }
        }
    }

    private fun alert(
        type: Alert.AlertType,
        title: String,
        message: String,
    ) {
        val owner = window() ?: node.scene?.window
        val dialog = Alert(type, message)
        dialog.title = title
        dialog.headerText = null
        owner?.let { dialog.initOwner(it) }
        dialog.show()
    }

    private fun menuItem(
        title: String,
        action: () -> Unit,
    ) = MenuItem(title).apply { setOnAction { action() } }

    private fun menuFor(obj: DbObject): List<MenuItem> {
        val kind = databaseKind()
        val actions =
            ObjectTreeModel.actions(obj.kind).map { action ->
                when (action) {
                    NodeAction.OPEN_DATA -> {
                        menuItem("Open Data") { openData(obj) }
                    }

                    NodeAction.SHOW_DDL -> {
                        menuItem("Show DDL") { showDdl(obj) }
                    }

                    NodeAction.COPY_NAME -> {
                        menuItem("Copy Name") { copyToClipboard(obj.name) }
                    }

                    NodeAction.COPY_QUALIFIED_NAME -> {
                        menuItem("Copy Qualified Name") { copyToClipboard(ObjectTreeModel.qualifiedName(kind, obj)) }
                    }

                    NodeAction.GENERATE_SELECT -> {
                        menuItem("Generate SELECT") {
                            entry.workspaceActions?.insertIntoConsole(ObjectTreeModel.generateSelect(kind, obj))
                        }
                    }
                }
            }
        val separator = if (actions.isEmpty()) emptyList() else listOf(SeparatorMenuItem())
        return actions + separator + menuItem("Refresh") { reload() }
    }

    private fun <B : ButtonBase> toolbarButton(
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
        private val icon = FontIcon().apply { styleClass += "databases-tree-icon" }
        private val label = Label()
        private val detail = Label().apply { styleClass += "databases-tree-detail" }
        private val box = HBox(label, detail).apply { styleClass += "databases-tree-row" }
        private val menu =
            ContextMenu().apply {
                setOnShowing { (item as? ObjectRow)?.let { items.setAll(menuFor(it.obj)) } }
            }

        init {
            // The tree view's own double click expands a table; a table opens its data instead.
            listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED).forEach {
                addEventFilter(it, ::doubleClicked)
            }
            selectedProperty().subscribe { _ -> restyle() }
            // The selection bar is grey while the tree has no focus, where the light error colour would not read.
            tree.focusedProperty().subscribe { _ -> restyle() }
        }

        private fun doubleClicked(event: MouseEvent) {
            val obj = (item as? ObjectRow)?.obj ?: return
            if (event.button != MouseButton.PRIMARY || event.clickCount != 2) return
            if (!ObjectTreeModel.opensData(obj) || onDisclosure(event)) return
            if (event.eventType == MouseEvent.MOUSE_CLICKED) openData(obj)
            event.consume()
        }

        /** A double click on the disclosure arrow only toggles the row. */
        private fun onDisclosure(event: MouseEvent): Boolean {
            var target = event.target as? Node
            while (target != null && target !== this) {
                if ("tree-disclosure-node" in target.styleClass) return true
                target = target.parent
            }
            return false
        }

        override fun updateItem(
            row: TreeRow?,
            empty: Boolean,
        ) {
            super.updateItem(row, empty)
            text = null
            if (empty || row == null) {
                graphic = null
                contextMenu = null
                restyle()
                return
            }
            label.text = ObjectTreeModel.label(row)
            val muted = ObjectTreeModel.detail(row)
            detail.text = muted
            detail.isVisible = muted.isNotEmpty()
            detail.isManaged = detail.isVisible
            val kind = (row as? ObjectRow)?.obj?.kind
            if (kind == null) {
                box.children.setAll(label, detail)
            } else {
                icon.iconLiteral = ObjectIcons.literal(kind)
                box.children.setAll(icon, label, detail)
            }
            graphic = box
            contextMenu = menu.takeIf { row is ObjectRow }
            restyle()
        }

        private fun restyle() {
            label.styleClass.removeAll(ROW_CLASSES)
            when (val row = item) {
                is ErrorRow -> {
                    label.styleClass += "databases-tree-error"
                    if (isSelected && tree.isFocused) label.styleClass += "databases-tree-error-selected"
                }

                is LoadingRow -> {
                    label.styleClass += "databases-tree-loading"
                }

                is ObjectRow -> {
                    if (row.obj.kind == ObjectKind.GROUP) label.styleClass += "databases-tree-group"
                }

                else -> {}
            }
        }
    }

    private companion object {
        const val DDL_TITLE = "Show DDL"
        val ROW_CLASSES =
            listOf(
                "databases-tree-error",
                "databases-tree-error-selected",
                "databases-tree-loading",
                "databases-tree-group",
            )
    }
}

private fun copyToSystemClipboard(text: String) {
    Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
}
