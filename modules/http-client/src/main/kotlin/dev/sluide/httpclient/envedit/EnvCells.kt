package dev.sluide.httpclient.envedit

import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.control.TreeTableCell
import javafx.scene.control.TreeTableRow
import javafx.scene.input.KeyCode
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.util.StringConverter
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignL
import tools.jackson.databind.JsonNode

/** A line of the environment table: section headers stand out, and it has the context menu of its entry. */
internal class EnvRow(
    private val table: EnvironmentTable,
) : TreeTableRow<EnvEntry>() {
    override fun updateItem(
        item: EnvEntry?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        styleClass -= "env-section"
        if (!empty && item != null && item !is EnvEntry.Item) styleClass += "env-section"
        contextMenu = item?.takeUnless { empty }?.let { menu(table, it, null) }
    }
}

private fun menu(
    table: EnvironmentTable,
    entry: EnvEntry,
    env: String?,
): ContextMenu? = table.menuItems(entry, env).takeIf { it.isNotEmpty() }?.let { ContextMenu(*it.toTypedArray()) }

/** The name of a section or row. */
internal class NameCell : TreeTableCell<EnvEntry, EnvEntry>() {
    override fun updateItem(
        item: EnvEntry?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        text =
            when (val entry = item?.takeUnless { empty }) {
                null -> null
                EnvEntry.Variables -> "Variables"
                is EnvEntry.Auth -> "Auth · ${entry.group.id}"
                is EnvEntry.Certificate -> "Client certificate"
                is EnvEntry.Item -> entry.row.label
            }
    }
}

/**
 * The type of a row, Choice for fields with choices; the kind of configuration for an auth section. The type of a
 * variable the form can edit changes in a list while the cell is edited (double-click, Enter or F2).
 */
internal class TypeCell(
    private val table: EnvironmentTable,
) : TreeTableCell<EnvEntry, EnvEntry>() {
    /** Whether the edit running ended already. */
    private var done = false

    /** The list of types while the cell is edited, else `null`. */
    var choice: ComboBox<ValueType>? = null
        private set

    private val row: Row? get() = (item as? EnvEntry.Item)?.takeIf { it.variable }?.row

    override fun updateItem(
        item: EnvEntry?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        if (isEditing && choice != null) {
            text = null
            graphic = choice
            return
        }
        render()
    }

    private fun render() {
        graphic = null
        val entry = item?.takeUnless { isEmpty }
        isEditable = entry is EnvEntry.Item && entry.variable && !entry.row.readOnly && table.editable
        text =
            when (entry) {
                is EnvEntry.Auth -> EnvTexts.authSummary(entry.group)
                is EnvEntry.Item -> EnvTexts.typeLabel(entry.row)
                else -> null
            }
    }

    override fun startEdit() {
        val row = row?.takeIf { !it.readOnly && table.editable } ?: return
        super.startEdit()
        if (!isEditing) return
        done = false
        val box =
            ComboBox<ValueType>().apply {
                styleClass += "env-type"
                items.setAll(EnvTexts.VARIABLE_TYPES)
                converter = typeConverter
                value = row.type
                maxWidth = Double.MAX_VALUE
                valueProperty().addListener { _, _, type ->
                    if (done || type == null) return@addListener
                    done = true
                    // Not while the list is still busy with the selection: the change rebuilds the table.
                    Platform.runLater {
                        this@TypeCell.cancelEdit()
                        if (type != row.type) table.changeType(row, type)
                    }
                }
                setOnHidden {
                    Platform.runLater {
                        if (this@TypeCell.isEditing && !done) {
                            done = true
                            this@TypeCell.cancelEdit()
                        }
                    }
                }
                Platform.runLater { if (this@TypeCell.isEditing && !done && scene != null) show() }
            }
        choice = box
        text = null
        graphic = box
    }

    override fun cancelEdit() {
        super.cancelEdit()
        choice = null
        render()
    }

    private companion object {
        val typeConverter =
            object : StringConverter<ValueType>() {
                override fun toString(type: ValueType?) = type?.let(EnvTexts::typeLabel).orEmpty()

                override fun fromString(text: String?) = EnvTexts.VARIABLE_TYPES.firstOrNull { toString(it) == text }
            }
    }
}

/**
 * The value of a row in one environment, with the lock of its file. Double-click, Enter or F2 edits it: texts and
 * numbers in a field, choices in a list; a yes/no flips, and an object, list or `null` an environment lacks is
 * created.
 */
internal class EnvValueCell(
    private val table: EnvironmentTable,
    private val env: String,
) : TreeTableCell<EnvEntry, EnvEntry>() {
    /** Whether the edit running ended already, so that losing the focus afterwards does not commit it again. */
    private var done = false

    /** Whether a file chooser of the edit is open, which takes the focus without ending the edit. */
    private var browsing = false

    private var editor: Node? = null

    private val row: Row? get() = (item as? EnvEntry.Item)?.row

    init {
        styleClass += "env-value-cell"
    }

    override fun updateItem(
        item: EnvEntry?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        if (isEditing && editor != null) {
            text = null
            graphic = editor
            return
        }
        render()
    }

    private fun render() {
        styleClass.removeAll(STATES)
        text = null
        graphic = null
        tooltip = null
        contextMenu = null
        val row = row?.takeUnless { isEmpty }
        if (row == null) {
            isEditable = false
            return
        }
        val cell = row.cells[env] ?: Cell.UNDEFINED
        val editable = table.canEdit(row, env)
        isEditable = editable
        when {
            env in table.table.readOnlyEnvironments -> {
                styleClass += "env-readonly-value"
            }

            env !in row.applicable -> {
                styleClass += "env-not-applicable"
                tooltip = Tooltip("Does not apply to $env")
            }

            row.readOnly -> {
                styleClass += "env-readonly-value"
                tooltip = Tooltip("Edit in the JSON source")
            }
        }
        val value = cell.value
        val content =
            if (value != null && value.isBoolean) {
                CheckBox().apply {
                    styleClass += "env-yes-no"
                    isSelected = value.booleanValue()
                    isDisable = !editable
                    setOnAction { table.setValue(env, row.path, EnvJson.nodes.booleanNode(isSelected)) }
                }
            } else {
                valueLabel(cell)
            }
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        graphic =
            HBox(content, spacer).apply {
                styleClass += "env-value-box"
                alignment = Pos.CENTER_LEFT
                cell.side?.let { children += lock(row, cell, it) }
            }
        contextMenu = menu(table, EnvEntry.Item(row, false), env)
    }

    private fun valueLabel(cell: Cell): Label {
        val masked = EnvTexts.isMasked(cell, table.showValues)
        return Label(EnvTexts.display(cell, masked)).apply {
            styleClass += "env-value"
            minWidth = 0.0
            val value = cell.value
            when {
                value == null -> styleClass += "env-undefined"
                masked -> styleClass += "env-masked"
                value.isNull -> styleClass += "env-null"
                value.isContainer -> styleClass += "env-summary"
            }
        }
    }

    private fun lock(
        row: Row,
        cell: Cell,
        side: FileSide,
    ): Button {
        val icon =
            if (side == FileSide.PRIVATE) {
                FontIcon(MaterialDesignL.LOCK).apply { styleClass += "env-lock-icon-private" }
            } else {
                FontIcon(MaterialDesignL.LOCK_OPEN_VARIANT_OUTLINE)
            }
        icon.styleClass.add(0, "env-lock-icon")
        return Button(null, icon).apply {
            styleClass += "env-lock"
            if (side == FileSide.PRIVATE) styleClass += "env-lock-private"
            isFocusTraversable = false
            isDisable = !table.canMove(row, env)
            val where = if (side == FileSide.PRIVATE) "Private file" else "Public file"
            tooltip = Tooltip(if (cell.overridesPublic) "$where, overrides the public value" else where)
            setOnAction { table.setSide(env, row.path, side.other) }
        }
    }

    override fun startEdit() {
        val row = row ?: return
        if (!table.canEdit(row, env)) return
        val cell = row.cells[env] ?: Cell.UNDEFINED
        val value = cell.value
        val immediate: JsonNode? =
            when {
                row.type == ValueType.YES_NO -> EnvJson.nodes.booleanNode(value?.booleanValue() != true)
                value == null && row.type in CREATED -> EnvValues.emptyValue(row.type)
                else -> null
            }
        if (immediate != null) {
            // Not while the table is still starting the edit: the change rebuilds it.
            Platform.runLater {
                treeTableView?.edit(-1, null)
                table.setValue(env, row.path, immediate)
            }
            return
        }
        if (row.type != ValueType.TEXT && row.type != ValueType.NUMBER) return
        super.startEdit()
        if (!isEditing) return
        done = false
        val field = textField(row, cell)
        editor =
            when {
                row.choices != null -> {
                    choiceEditor(row, cell)
                }

                EnvSchema.certificatePart(row.path) != null -> {
                    HBox(field, browseButton(row, field)).apply {
                        HBox.setHgrow(field, Priority.ALWAYS)
                        alignment = Pos.CENTER_LEFT
                    }
                }

                else -> {
                    field
                }
            }
        text = null
        graphic = editor
        if (row.choices == null) {
            field.requestFocus()
            field.selectAll()
        }
    }

    override fun cancelEdit() {
        super.cancelEdit()
        editor = null
        render()
    }

    private fun textField(
        row: Row,
        cell: Cell,
    ) = TextField(EnvTexts.editText(cell)).apply {
        styleClass += "env-editor"
        if (row.type == ValueType.NUMBER) {
            textProperty().subscribe { text ->
                styleClass -= "env-invalid"
                if (EnvJson.number(text) == null) styleClass += "env-invalid"
            }
        }
        setOnAction { event ->
            event.consume()
            commit(row, text, focusLost = false)
        }
        setOnKeyPressed { event ->
            if (event.code == KeyCode.ESCAPE) {
                event.consume()
                done = true
                this@EnvValueCell.cancelEdit()
            }
        }
        focusedProperty().addListener { _, _, focused ->
            if (!focused && this@EnvValueCell.isEditing && !done && !browsing) commit(row, text, focusLost = true)
        }
    }

    /** Commits [text]; a number that is not one stays in the field, or is dropped when the field lost the focus. */
    private fun commit(
        row: Row,
        text: String,
        focusLost: Boolean,
    ) {
        if (done) return
        val value =
            if (row.type == ValueType.NUMBER) {
                EnvJson.number(text) ?: run {
                    if (focusLost) {
                        done = true
                        cancelEdit()
                    }
                    return
                }
            } else {
                EnvJson.nodes.stringNode(text)
            }
        done = true
        cancelEdit()
        table.setValue(env, row.path, value)
    }

    private fun choiceEditor(
        row: Row,
        cell: Cell,
    ) = ComboBox<String>().apply {
        styleClass += "env-choice"
        items.setAll(row.choices.orEmpty())
        converter = choiceConverter
        value = cell.value?.takeIf { it.isString }?.stringValue() ?: ""
        maxWidth = Double.MAX_VALUE
        valueProperty().addListener { _, _, choice ->
            if (done || choice == null) return@addListener
            done = true
            Platform.runLater {
                this@EnvValueCell.cancelEdit()
                table.setValue(env, row.path, EnvJson.nodes.stringNode(choice))
            }
        }
        setOnHidden {
            Platform.runLater {
                if (this@EnvValueCell.isEditing && !done) {
                    done = true
                    this@EnvValueCell.cancelEdit()
                }
            }
        }
        Platform.runLater { if (this@EnvValueCell.isEditing && !done && scene != null) show() }
    }

    private fun browseButton(
        row: Row,
        field: TextField,
    ) = Button("…").apply {
        styleClass += "env-browse"
        isFocusTraversable = false
        tooltip = Tooltip("Choose the file")
        setOnAction {
            browsing = true
            val chosen =
                try {
                    table.chooseCertificate()
                } finally {
                    browsing = false
                }
            if (chosen == null) {
                field.requestFocus()
            } else {
                done = true
                this@EnvValueCell.cancelEdit()
                table.setValue(env, row.path, EnvJson.nodes.stringNode(chosen))
            }
        }
    }

    private companion object {
        val STATES = listOf("env-readonly-value", "env-not-applicable")

        /** Types whose value an environment lacking it gets at once when edited: their empty value. */
        val CREATED = setOf(ValueType.OBJECT, ValueType.LIST, ValueType.NULL)

        val choiceConverter =
            object : StringConverter<String>() {
                override fun toString(choice: String?) = if (choice.isNullOrEmpty()) "(none)" else choice

                override fun fromString(text: String?) = if (text == "(none)") "" else text
            }
    }
}

/** Lists value types by their labels. */
internal class TypeListCell : ListCell<ValueType>() {
    override fun updateItem(
        item: ValueType?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        text = if (empty || item == null) null else EnvTexts.typeLabel(item)
    }
}
