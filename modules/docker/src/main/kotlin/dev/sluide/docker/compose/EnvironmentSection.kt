package dev.sluide.docker.compose

import javafx.beans.property.BooleanProperty
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.control.TitledPane
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import java.nio.file.Path

/**
 * The Environment section of the Compose pane (ADR 0119), collapsed at first: the env files (Add…, Remove, Use
 * Default; the default is the `.env` beside the file), the variables as Name | Value rows with masked values until
 * Show values is on, and the warning line of the variables the configuration leaves unresolved. Changes go to the
 * model's options at once (it stores them and reads the configuration again). FX thread.
 */
internal class EnvironmentSection {
    val envFiles =
        ListView<String>().apply {
            styleClass += "compose-env-files"
            prefHeight = ENV_FILES_HEIGHT
        }

    private val envFilesPlaceholder = Label().apply { isWrapText = true }

    val addEnvFile = Button("Add…").apply { styleClass += "compose-env-add" }
    val removeEnvFile = Button("Remove").apply { styleClass += "compose-env-remove" }
    val useDefault =
        Button("Use Default").apply {
            styleClass += "compose-env-default"
            tooltip = Tooltip("Read the .env beside the file, as compose does by itself")
        }

    val showValues = CheckBox("Show values").apply { styleClass += "compose-show-values" }
    val addVariable = Button("Add").apply { styleClass += "compose-variable-add" }

    /** The rows of the variables table, in order. */
    private val rows = mutableListOf<VariableRow>()
    private val rowBox = VBox().apply { styleClass += "compose-variables" }

    val unresolved =
        Label().apply {
            styleClass += "compose-unresolved"
            isWrapText = true
            isVisible = false
            isManaged = false
        }

    val node =
        TitledPane(
            TITLE,
            VBox(
                header(Label("Env files"), addEnvFile, removeEnvFile, useDefault),
                envFiles,
                header(Label("Variables"), showValues, addVariable),
                HBox(columnLabel("Name"), columnLabel("Value")).apply { styleClass += "compose-variables-header" },
                rowBox,
                unresolved,
            ).apply { styleClass += "compose-environment-box" },
        ).apply {
            styleClass += "compose-environment"
            isExpanded = false
            isAnimated = false
        }

    /** Asks for an env file in the compose file's folder (a file chooser owned by the window); tests replace it. */
    var chooseEnvFile: (folder: Path) -> Path? = { null }

    private var model: ComposePaneModel? = null

    /** Connects the section to [model], whose file is in [folder]. */
    fun bind(
        model: ComposePaneModel,
        folder: () -> Path,
    ) {
        this.model = model
        envFiles.placeholder = envFilesPlaceholder
        model.options
            .get()
            .variables
            .forEach { addRow(it.name, it.value) }
        model.options.subscribe { _ -> showEnvFiles(model) }
        model.defaultEnvExists.subscribe { _ -> showEnvFiles(model) }
        model.config.subscribe { config ->
            val names = (config as? ConfigLoad.Loaded)?.config?.unresolvedVariables.orEmpty()
            val text = unresolvedNote(names)
            unresolved.text = text ?: ""
            unresolved.isVisible = text != null
            unresolved.isManaged = text != null
            node.text = if (names.isEmpty()) TITLE else "$TITLE (⚠ ${names.size} not set)"
        }
        removeEnvFile.disableProperty().bind(envFiles.selectionModel.selectedItemProperty().isNull)
        addEnvFile.setOnAction {
            val chosen = chooseEnvFile(folder()) ?: return@setOnAction
            val entry = envFileEntry(folder(), chosen)
            model.update { it.copy(envFiles = withEnvFile(it.envFiles, entry, model.defaultEnvExists.get())) }
        }
        removeEnvFile.setOnAction {
            val selected = envFiles.selectionModel.selectedItem ?: return@setOnAction
            model.update { it.copy(envFiles = it.envFiles.orEmpty() - selected) }
        }
        useDefault.setOnAction { model.update { it.copy(envFiles = null) } }
        addVariable.setOnAction { addRow("", "").nameField.requestFocus() }
    }

    private fun showEnvFiles(model: ComposePaneModel) {
        val files = model.options.get().envFiles
        if (envFiles.items != files.orEmpty()) envFiles.items.setAll(files.orEmpty())
        envFilesPlaceholder.text = envFilesPlaceholder(files, model.defaultEnvExists.get())
        useDefault.isDisable = files == null
    }

    private fun addRow(
        name: String,
        value: String,
    ): VariableRow {
        lateinit var row: VariableRow
        row =
            VariableRow(name, value, showValues.selectedProperty(), ::variablesChanged) {
                rows -= row
                rowBox.children -= row.node
                variablesChanged()
            }
        rows += row
        rowBox.children += row.node
        return row
    }

    private fun variablesChanged() {
        val variables = variablesOf(rows.map { it.nameField.text.trim() to it.valueField.text })
        model?.update { it.copy(variables = variables) }
    }

    private fun header(vararg nodes: javafx.scene.Node) =
        HBox(*nodes).apply {
            styleClass += "compose-env-header"
            alignment = Pos.CENTER_LEFT
            (nodes.first() as Label).apply {
                minWidth = Region.USE_PREF_SIZE
                HBox.setHgrow(this, Priority.ALWAYS)
                maxWidth = Double.MAX_VALUE
            }
        }

    private fun columnLabel(text: String) =
        Label(text).apply {
            styleClass += "compose-column-label"
            maxWidth = Double.MAX_VALUE
            HBox.setHgrow(this, Priority.ALWAYS)
        }

    private companion object {
        const val TITLE = "Environment"
        const val ENV_FILES_HEIGHT = 72.0
    }
}

/**
 * One row of the variables: the name (marked when it is no valid name, which is then not used), the value, masked
 * until [showValues], and ✕ ([onRemove]). Every edit calls [onChange].
 */
private class VariableRow(
    name: String,
    value: String,
    showValues: BooleanProperty,
    onChange: () -> Unit,
    onRemove: () -> Unit,
) {
    val nameField =
        TextField(name).apply {
            styleClass += "compose-variable-name"
            promptText = "NAME"
            HBox.setHgrow(this, Priority.ALWAYS)
        }

    val valueField = TextField(value).apply { styleClass += "compose-variable-value" }

    private val masked =
        PasswordField().apply {
            styleClass += "compose-variable-masked"
            textProperty().bindBidirectional(valueField.textProperty())
        }

    private val remove =
        Button("✕").apply {
            styleClass += "compose-variable-remove"
            tooltip = Tooltip("Remove the variable")
            setOnAction { onRemove() }
        }

    val node =
        HBox(
            nameField,
            StackPane(valueField, masked).apply { HBox.setHgrow(this, Priority.ALWAYS) },
            remove,
        ).apply {
            styleClass += "compose-variable-row"
            alignment = Pos.CENTER_LEFT
        }

    init {
        valueField.visibleProperty().bind(showValues)
        masked.visibleProperty().bind(showValues.not())
        nameField.textProperty().subscribe { text ->
            val invalid = text.isNotBlank() && !isValidVariableName(text.trim())
            nameField.styleClass.remove(INVALID)
            if (invalid) nameField.styleClass += INVALID
        }
        nameField.textProperty().addListener { _ -> onChange() }
        valueField.textProperty().addListener { _ -> onChange() }
    }

    private companion object {
        const val INVALID = "compose-invalid"
    }
}
