package dev.sluide.httpclient.envedit

import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeTableColumn
import javafx.scene.control.TreeTableView
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import tools.jackson.databind.JsonNode
import java.nio.file.Path

/**
 * The visual half of an environment file's view: the [EnvTable] of a document as a tree table with one column per
 * environment, and the buttons below it. Every action turns into an [EnvEdit] of the [document], handed to [apply];
 * what the model refuses goes to [fail] with its message. The table shows a new document only through [show].
 */
internal class EnvironmentTable(
    private val apply: (EnvEdit) -> Unit,
    private val fail: (String) -> Unit,
) {
    var document = EnvDocument(null, null)
        private set

    /** Whether the form can change the files; when not, it only shows them. */
    var editable = false
        private set

    val table: EnvTable get() = document.table

    /** Whether private values are shown instead of [EnvTexts.MASK]. */
    var showValues = false
        set(value) {
            field = value
            tree.refresh()
        }

    /** The folder of the private file: certificate files are chosen there and written relative to it. */
    var privateFolder: Path = Path.of("").toAbsolutePath()

    private val window get() = tree.scene?.window

    /** Asks whether to go on; shows a dialog, tests replace it, since it blocks. */
    internal var confirm: (String) -> Boolean = { EnvDialogs.confirm(window, it) }

    /** Asks for a name (title, initial name, validation); tests replace it, since it blocks. */
    internal var askName: (String, String, (String) -> String?) -> String? =
        { title, initial, validate -> EnvDialogs.askName(window, title, initial, validate) }

    /** Asks for a variable (title, whether with a name, validation); tests replace it, since it blocks. */
    internal var askVariable: (String, Boolean, (NewVariable) -> String?) -> NewVariable? =
        { title, withName, validate -> EnvDialogs.askVariable(window, title, withName, validate) }

    /** Asks for an auth configuration (environments offered, validation); tests replace it, since it blocks. */
    internal var askAuth: (List<String>, (NewAuth) -> String?) -> NewAuth? =
        { environments, validate -> EnvDialogs.askAuth(window, environments, validate) }

    /** Asks for a certificate file, starting in the given folder; tests replace it, since it blocks. */
    internal var chooseFile: (Path) -> Path? = { EnvDialogs.chooseFile(window, it) }

    private val root = TreeItem<EnvEntry>()

    /** The keys of the lines the user collapsed; all others are expanded. */
    private val collapsed = mutableSetOf<Any>()

    /** The key of the line to select after the next [show], e.g. a variable just added. */
    private var selectNext: Any? = null

    private var columnsOf: Pair<List<String>, Set<String>>? = null

    val tree =
        TreeTableView(root).apply {
            styleClass += "env-table"
            isShowRoot = false
            isEditable = true
            selectionModel.isCellSelectionEnabled = true
            setRowFactory { EnvRow(this@EnvironmentTable) }
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if ((event.code == KeyCode.ENTER || event.code == KeyCode.F2) && editingCell == null) {
                    val focused = focusModel.focusedCell
                    if (focused != null && focused.row >= 0 && focused.tableColumn != null) {
                        edit(focused.row, focused.tableColumn)
                        event.consume()
                    }
                }
            }
            selectionModel.selectedItemProperty().addListener { _ -> updateButtons() }
            widthProperty().addListener { _ -> fitColumns() }
        }

    private val nameColumn =
        column("Name", "name", NAME_WIDTH).apply { setCellFactory { NameCell() } }

    private val typeColumn =
        column("Type", "type", TYPE_WIDTH).apply { setCellFactory { TypeCell(this@EnvironmentTable) } }

    val addVariableButton = button("+ Variable", "env-add-variable") { addVariable() }
    val removeButton = button("− Remove", "env-remove") { removeSelected() }
    val addAuthButton = button("+ Auth configuration…", "env-add-auth") { addAuth() }
    val addCertificateButton = button("+ Client certificate", "env-add-certificate") { addCertificate() }

    val node =
        BorderPane(tree).apply {
            styleClass += "env-form"
            minWidth = 0.0
            bottom =
                HBox(addVariableButton, removeButton, addAuthButton, addCertificateButton).apply {
                    styleClass += "env-buttons"
                }
        }

    /**
     * Shows [document], keeping the lines expanded and selected and the scroll position; [editable] tells whether the
     * form may change the files.
     */
    fun show(
        document: EnvDocument,
        editable: Boolean,
    ) {
        val selected = selectNext?.let { it to null } ?: selection()
        selectNext = null
        val top = flow()?.firstVisibleCell?.index
        if (tree.editingCell != null) tree.edit(-1, null)
        this.document = document
        this.editable = editable
        updateColumns()
        tree.placeholder = Label(table.error?.let { "The environment files cannot be shown: $it" } ?: "No environments")
        val items = mutableMapOf<Any, TreeItem<EnvEntry>>()
        root.children.setAll(sections(items))
        tree.selectionModel.clearSelection()
        tree.refresh()
        if (top != null && top > 0) tree.scrollTo(top)
        selected?.let { (key, columnId) ->
            val row = items[key]?.let(tree::getRow)?.takeIf { it >= 0 } ?: return@let
            tree.selectionModel.select(row, tree.columns.firstOrNull { it.id == columnId } ?: nameColumn)
        }
        updateButtons()
    }

    private fun selection(): Pair<Any, String?>? {
        val cell = tree.selectionModel.selectedCells.firstOrNull() ?: return null
        val key = cell.treeItem?.value?.key ?: return null
        return key to cell.tableColumn?.id
    }

    private fun flow() = tree.lookup(".virtual-flow") as? VirtualFlow<*>

    private fun sections(items: MutableMap<Any, TreeItem<EnvEntry>>): List<TreeItem<EnvEntry>> {
        if (table.error != null) return emptyList()

        fun rowItem(
            row: Row,
            variable: Boolean,
        ): TreeItem<EnvEntry> = item(EnvEntry.Item(row, variable), row.children.map { rowItem(it, variable) }, items)
        return listOf(item(EnvEntry.Variables, table.variables.map { rowItem(it, true) }, items)) +
            table.auth.map { group -> item(EnvEntry.Auth(group), group.rows.map { rowItem(it, false) }, items) } +
            listOfNotNull(
                table.certificate?.let { rows ->
                    item(EnvEntry.Certificate(rows), rows.map { rowItem(it, false) }, items)
                },
            )
    }

    private fun item(
        entry: EnvEntry,
        children: List<TreeItem<EnvEntry>>,
        items: MutableMap<Any, TreeItem<EnvEntry>>,
    ): TreeItem<EnvEntry> =
        TreeItem(entry).apply {
            this.children.setAll(children)
            isExpanded = entry.key !in collapsed
            expandedProperty().addListener { _, _, expanded ->
                if (expanded) collapsed -= entry.key else collapsed += entry.key
            }
            items[entry.key] = this
        }

    private fun updateColumns() {
        val columns = table.environments to table.readOnlyEnvironments
        if (columns == columnsOf) return
        columnsOf = columns
        tree.columns.setAll(listOf(nameColumn, typeColumn) + table.environments.map(::environmentColumn))
        fitColumns()
    }

    /**
     * Sizes the columns to the table's width: Name takes up to [NAME_WIDTH] (less in a narrow table), the environments
     * share the rest, each at least [ENV_MIN_WIDTH] wide (scrolling sideways then). Columns the user resized keep their
     * width until the table's width changes.
     */
    private fun fitColumns() {
        val width = tree.width
        if (width <= 0.0) return
        val environments = tree.columns.drop(2)
        val (name, each) = columnWidths(width, typeColumn.prefWidth, environments.size)
        nameColumn.prefWidth = name
        if (each != null) environments.forEach { it.prefWidth = each }
    }

    private fun environmentColumn(env: String) =
        column(env, "env:$env", ENV_WIDTH).apply {
            userData = env
            styleClass += "env-column"
            if (env in table.readOnlyEnvironments) styleClass += "env-readonly"
            setCellFactory { EnvValueCell(this@EnvironmentTable, env) }
            contextMenu =
                ContextMenu(
                    menuItem("Rename…") { renameEnvironment(env) },
                    menuItem("Remove…") { removeEnvironment(env) },
                ).apply { setOnShowing { items.forEach { it.isDisable = !editable } } }
        }

    private fun column(
        title: String,
        id: String,
        width: Double,
    ) = TreeTableColumn<EnvEntry, EnvEntry>(title).apply {
        this.id = id
        prefWidth = width
        isSortable = false
        setCellValueFactory { it.value.valueProperty() }
    }

    private fun updateButtons() {
        val environments = editableEnvironments()
        addVariableButton.isDisable = !editable || environments.isEmpty()
        addAuthButton.isDisable = !editable || environments.isEmpty()
        addCertificateButton.isDisable =
            !editable || environments.none { it !in EnvTexts.certificateEnvironments(table) }
        removeButton.isDisable = !editable || selectedEntry().let { it == null || it == EnvEntry.Variables }
    }

    private fun editableEnvironments() = table.environments.filterNot { it in table.readOnlyEnvironments }

    private fun selectedEntry(): EnvEntry? = tree.selectionModel.selectedItem?.value

    /** Whether the value of [row] in [env] can be edited in the form. */
    fun canEdit(
        row: Row,
        env: String,
    ): Boolean = editable && env !in table.readOnlyEnvironments && env in row.applicable && !row.readOnly

    /** Whether the value of [row] in [env] can be moved to the other file. */
    fun canMove(
        row: Row,
        env: String,
    ): Boolean = canEdit(row, env) && !row.sideFixed && row.cells[env]?.side != null

    fun setValue(
        env: String,
        path: ValuePath,
        value: JsonNode,
    ) = run { it.setValue(env, path, value) }

    fun setSide(
        env: String,
        path: ValuePath,
        side: FileSide,
    ) = run { it.setSide(env, path, side) }

    /** Gives [row] the [type], after asking if that replaces values by empty ones. */
    fun changeType(
        row: Row,
        type: ValueType,
    ) {
        val lossy =
            try {
                document.isLossy(row.path, type)
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        val question =
            "Values of \"${row.path}\" that are no ${EnvTexts.typeLabel(type)} are replaced by an empty value. " +
                "Change the type?"
        if (!lossy || confirm(question)) run { it.setType(row.path, type) }
    }

    /** A certificate file chosen by the user, as it is written into the file; `null` if cancelled. */
    fun chooseCertificate(): String? = chooseFile(privateFolder)?.let { EnvTexts.certificatePath(privateFolder, it) }

    /** The context menu of [entry], in the column of [env] if any. Empty while the form cannot change the files. */
    fun menuItems(
        entry: EnvEntry,
        env: String?,
    ): List<MenuItem> {
        if (!editable) return emptyList()
        val environments = editableEnvironments()
        return when (entry) {
            EnvEntry.Variables -> {
                listOf(menuItem("Add Variable…") { addVariable() })
            }

            is EnvEntry.Auth -> {
                val group = entry.group
                val having = EnvTexts.authEnvironments(group)
                listOf(menuItem("Remove configuration") { removeAuth(group) }) +
                    environments.filter { it !in having }.map { target ->
                        menuItem("Add to $target") {
                            run { it.addAuth(group.id, EnvTexts.grantType(group), listOf(target)) }
                        }
                    }
            }

            is EnvEntry.Certificate -> {
                val having = EnvTexts.certificateEnvironments(table)
                environments.map { target ->
                    if (target in having) {
                        menuItem("Remove from $target") { run { it.removeCertificate(target) } }
                    } else {
                        menuItem("Add to $target") { run { it.addCertificate(listOf(target)) } }
                    }
                }
            }

            is EnvEntry.Item -> {
                cellItems(entry.row, env) + menuItem("Remove row") { removeRow(entry.row) }
            }
        }
    }

    private fun cellItems(
        row: Row,
        env: String?,
    ): List<MenuItem> {
        if (env == null || !canEdit(row, env)) return emptyList()
        val cell = row.cells[env] ?: Cell.UNDEFINED
        val remove = menuItem("Remove from $env") { run { it.remove(env, row.path) } }.takeIf { cell.value != null }
        val move =
            cell.side?.takeIf { canMove(row, env) }?.let { side ->
                menuItem(if (side == FileSide.PUBLIC) "Make private" else "Make public") {
                    run { it.setSide(env, row.path, side.other) }
                }
            }
        return listOfNotNull(remove, move)
    }

    fun addEnvironment() {
        if (!editable) return
        val name = askName("Add Environment", "") { name -> problem { document.addEnvironment(name) } } ?: return
        run { it.addEnvironment(name) }
    }

    private fun renameEnvironment(env: String) {
        val name =
            askName("Rename Environment", env) { name ->
                if (name == env) "" else problem { document.renameEnvironment(env, name) }
            } ?: return
        run { it.renameEnvironment(env, name) }
    }

    private fun removeEnvironment(env: String) {
        if (confirm("Remove the environment \"$env\" from both files?")) run { it.removeEnvironment(env) }
    }

    /**
     * Adds a variable to the selected object of the Variables section, or to the environments; on a selected list, an
     * entry to the list.
     */
    private fun addVariable() {
        val entry = selectedEntry() as? EnvEntry.Item
        val row = entry?.row?.takeIf { entry.variable && !it.readOnly }
        if (row?.type == ValueType.LIST) {
            val answer = askVariable("Add Entry to ${row.path}", false) { null } ?: return
            run { it.appendListEntry(row.path, answer.type) }
            return
        }
        val parent = row?.path?.takeIf { row.type == ValueType.OBJECT } ?: ValuePath.ROOT
        val title = if (parent.isRoot) "Add Variable" else "Add Variable to $parent"
        val answer =
            askVariable(title, true) { variable ->
                problem { document.addVariable(parent, variable.name, variable.type) }
            }
                ?: return
        selectNext = parent.key(answer.name)
        run { it.addVariable(parent, answer.name, answer.type) }
    }

    private fun addAuth() {
        val answer =
            askAuth(editableEnvironments()) { auth ->
                if (auth.environments.isEmpty()) {
                    "Choose at least one environment"
                } else {
                    problem { document.addAuth(auth.id, auth.grantType, auth.environments) }
                }
            } ?: return
        selectNext = "auth:${answer.id}"
        run { it.addAuth(answer.id, answer.grantType, answer.environments) }
    }

    private fun addCertificate() {
        val missing = editableEnvironments().filterNot { it in EnvTexts.certificateEnvironments(table) }
        if (missing.isNotEmpty()) run { it.addCertificate(missing) }
    }

    private fun removeSelected() {
        when (val entry = selectedEntry()) {
            is EnvEntry.Item -> {
                removeRow(entry.row)
            }

            is EnvEntry.Auth -> {
                removeAuth(entry.group)
            }

            is EnvEntry.Certificate -> {
                val having = EnvTexts.certificateEnvironments(table)
                if (confirm("Remove the client certificate from all environments?")) {
                    run { document ->
                        chain(
                            document,
                            having.map { env ->
                                { d: EnvDocument -> d.removeCertificate(env) }
                            },
                        )
                    }
                }
            }

            else -> {}
        }
    }

    private fun removeRow(row: Row) {
        if (confirm("Remove \"${row.path}\" from all environments?")) run { it.removeRow(row.path) }
    }

    private fun removeAuth(group: AuthGroup) {
        if (confirm("Remove the auth configuration \"${group.id}\" from all environments?")) {
            run { it.removeAuth(group.id) }
        }
    }

    /** Hands the edit [operation] makes of the document to [apply], or what the model refused to [fail]. */
    private fun run(operation: (EnvDocument) -> EnvEdit) {
        if (!editable) return
        val edit =
            try {
                operation(document)
            } catch (e: IllegalArgumentException) {
                return fail(e.message ?: "The files cannot be changed this way")
            } catch (e: IllegalStateException) {
                return fail(e.message ?: "The files cannot be changed this way")
            }
        if (!edit.isEmpty) apply(edit)
    }

    /** What the model finds wrong with [operation], `null` if nothing. */
    private fun problem(operation: () -> Unit): String? =
        try {
            operation()
            null
        } catch (e: IllegalArgumentException) {
            e.message ?: "Not possible"
        } catch (e: IllegalStateException) {
            e.message ?: "Not possible"
        }

    private fun button(
        text: String,
        styleClass: String,
        action: () -> Unit,
    ) = Button(text).apply {
        this.styleClass += styleClass
        setOnAction { action() }
    }

    private fun menuItem(
        text: String,
        action: () -> Unit,
    ) = MenuItem(text).apply { setOnAction { action() } }

    internal companion object {
        /**
         * The widths of the Name column and of each of [envCount] environment columns (`null` without any) in a table
         * of [width] with a Type column of [typeWidth]; see [fitColumns].
         */
        fun columnWidths(
            width: Double,
            typeWidth: Double,
            envCount: Int,
        ): Pair<Double, Double?> {
            val name = (width * NAME_SHARE).coerceIn(NAME_MIN_WIDTH, NAME_WIDTH)
            if (envCount == 0) return name to null
            val rest = width - name - typeWidth - SCROLL_BAR_ALLOWANCE
            return name to maxOf(ENV_MIN_WIDTH, rest / envCount)
        }

        /** Wide enough for "Custom Request Parameters" inside an auth section. */
        const val NAME_WIDTH = 250.0
        const val NAME_MIN_WIDTH = 160.0
        const val NAME_SHARE = 0.3
        const val TYPE_WIDTH = 110.0
        const val ENV_WIDTH = 170.0
        const val ENV_MIN_WIDTH = 140.0
        const val SCROLL_BAR_ALLOWANCE = 20.0
    }
}
