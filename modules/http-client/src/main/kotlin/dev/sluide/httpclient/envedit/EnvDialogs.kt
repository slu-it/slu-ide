package dev.sluide.httpclient.envedit

import javafx.application.Platform
import javafx.beans.Observable
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Window
import java.nio.file.Files
import java.nio.file.Path

/** A variable or list entry to add: its [name] (empty for a list entry) and [type]. */
internal data class NewVariable(
    val name: String,
    val type: ValueType,
)

/** An auth configuration to add: [id], [grantType] and the [environments] to add it to. */
internal data class NewAuth(
    val id: String,
    val grantType: String,
    val environments: List<String>,
)

/**
 * The dialogs of the environment table. Each one shows what its `validate` finds wrong under the fields and disables
 * OK meanwhile (an empty text disables OK without a message); all return `null` when cancelled.
 */
internal object EnvDialogs {
    fun confirm(
        owner: Window?,
        message: String,
    ): Boolean =
        Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL)
            .apply {
                owner?.let(::initOwner)
                headerText = null
            }.showAndWait()
            .orElse(null) == ButtonType.OK

    fun askName(
        owner: Window?,
        title: String,
        initial: String,
        validate: (String) -> String?,
    ): String? {
        val field = TextField(initial).apply { prefColumnCount = COLUMNS }
        return Form<String>(owner, title).run {
            content(Label("Name:"), field)
            watch(field.textProperty()) { validate(field.text) }
            focusOnShow(field)
            show { field.text }
        }
    }

    /** Asks for a name (unless [withName] is false: then only for the type) and a type. */
    fun askVariable(
        owner: Window?,
        title: String,
        withName: Boolean,
        validate: (NewVariable) -> String?,
    ): NewVariable? {
        val field = TextField().apply { prefColumnCount = COLUMNS }
        val type = typeBox()
        return Form<NewVariable>(owner, title).run {
            if (withName) content(Label("Name:"), field)
            content(Label("Type:"), type)
            val answer = { NewVariable(if (withName) field.text else "", type.value) }
            watch(field.textProperty(), type.valueProperty()) { validate(answer()) }
            focusOnShow(if (withName) field else type)
            show(answer)
        }
    }

    fun askAuth(
        owner: Window?,
        environments: List<String>,
        validate: (NewAuth) -> String?,
    ): NewAuth? {
        val id = TextField().apply { prefColumnCount = COLUMNS }
        val grant =
            ComboBox<String>().apply {
                items.setAll(EnvSchema.GRANT_TYPES)
                value = items.first()
            }
        val boxes = environments.map { CheckBox(it).apply { isSelected = true } }
        return Form<NewAuth>(owner, "Add Auth Configuration").run {
            content(Label("Id:"), id, Label("Grant type:"), grant, Label("Environments:"), *boxes.toTypedArray())
            val answer = { NewAuth(id.text, grant.value, boxes.filter { it.isSelected }.map { it.text }) }
            val properties = listOf(id.textProperty(), grant.valueProperty()) + boxes.map { it.selectedProperty() }
            watch(*properties.toTypedArray()) { validate(answer()) }
            focusOnShow(id)
            show(answer)
        }
    }

    /** A file chosen in [folder], or `null`. */
    fun chooseFile(
        owner: Window?,
        folder: Path,
    ): Path? =
        FileChooser()
            .apply {
                title = "Choose a Certificate File"
                if (Files.isDirectory(folder)) initialDirectory = folder.toFile()
            }.showOpenDialog(owner)
            ?.toPath()

    private fun typeBox() =
        ComboBox<ValueType>().apply {
            items.setAll(EnvTexts.VARIABLE_TYPES)
            value = ValueType.TEXT
            setCellFactory { TypeListCell() }
            buttonCell = TypeListCell()
        }

    /** A dialog of fields above a line telling what is wrong with them. */
    private class Form<T>(
        owner: Window?,
        title: String,
    ) : Dialog<T>() {
        private val box = VBox(SPACING)
        private val problem = Label()

        init {
            owner?.let(::initOwner)
            this.title = title
            headerText = null
            dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
            dialogPane.content = box
        }

        fun content(vararg nodes: Node) {
            box.children.addAll(nodes)
        }

        fun watch(
            vararg properties: Observable,
            check: () -> String?,
        ) {
            val ok = dialogPane.lookupButton(ButtonType.OK)
            val update = {
                val found = check()
                problem.text = found
                ok.isDisable = found != null
            }
            properties.forEach { it.addListener { update() } }
            update()
        }

        fun focusOnShow(node: Node) {
            // Later, since the enabled default button takes the focus once the dialog is shown.
            setOnShown { Platform.runLater { node.requestFocus() } }
        }

        fun show(answer: () -> T): T? {
            box.children += problem
            setResultConverter { button -> if (button == ButtonType.OK) answer() else null }
            return showAndWait().orElse(null)
        }
    }

    private const val COLUMNS = 30
    private const val SPACING = 6.0
}
