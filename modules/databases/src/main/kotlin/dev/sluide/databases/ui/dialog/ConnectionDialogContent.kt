package dev.sluide.databases.ui.dialog

import dev.sluide.databases.connect.CardColor
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.SslMode
import dev.sluide.databases.ui.color
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.shape.Circle
import javafx.stage.FileChooser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** A row of the driver properties table. */
internal class PropertyRow(
    key: String,
    value: String,
) {
    val key = SimpleStringProperty(key)
    val value = SimpleStringProperty(value)
}

/**
 * The content of [ConnectionDialog]: the form's controls and their wiring to [ConnectionForm]. Built apart from the
 * `Dialog` so a test can show it in a scene. The controls are internal for the same reason.
 */
internal class ConnectionDialogContent(
    initial: ConnectionConfig,
    isNew: Boolean,
    existingNames: Set<String>,
    private val tester: ConnectionTester,
) {
    private var base = ConnectionForm.of(initial, isNew, existingNames)
    private var currentKind = initial.kind
    private var updating = true
    private var testRun = 0

    /** The first problem of the form, or `null`; the dialog disables OK while it is set. */
    val problem = SimpleStringProperty()

    val nameField = TextField(initial.name).apply { id = "nameField" }
    val kindBox =
        ComboBox(FXCollections.observableArrayList(*DatabaseKind.entries.toTypedArray())).apply {
            id = "kindBox"
            value = initial.kind
            setCellFactory { labelCell { it.label } }
            buttonCell = labelCell { it.label }
        }
    val hostField = TextField(initial.host).apply { id = "hostField" }
    val portField = TextField(initial.port.toString()).apply { id = "portField" }
    val databaseField = TextField(initial.database).apply { id = "databaseField" }
    val userField = TextField(initial.user).apply { id = "userField" }
    val passwordField =
        PasswordField().apply {
            id = "passwordField"
            promptText = "Asked on connect when empty"
        }

    val readOnlyBox =
        CheckBox("Read-only").apply {
            id = "readOnlyBox"
            isSelected = initial.readOnly
        }
    val commitBox =
        ComboBox(FXCollections.observableArrayList(*CommitMode.entries.toTypedArray())).apply {
            id = "commitBox"
            value = initial.commitMode
            setCellFactory { labelCell { it.name.lowercase().replaceFirstChar(Char::titlecase) } }
            buttonCell = labelCell { it.name.lowercase().replaceFirstChar(Char::titlecase) }
        }
    val sslBox =
        ComboBox(FXCollections.observableArrayList(*SslMode.entries.toTypedArray())).apply {
            id = "sslBox"
            value = initial.sslMode
            setCellFactory { labelCell { sslLabel(it) } }
            buttonCell = labelCell { sslLabel(it) }
        }
    val sslHint =
        hint("MariaDB has no Prefer: it connects without TLS.").apply {
            id = "sslHint"
            isManaged = false
            isVisible = false
        }
    val caFileField = TextField(initial.caFile.orEmpty()).apply { id = "caFileField" }
    val browseButton = Button("Browse…").apply { id = "browseButton" }
    private val swatchGroup = ToggleGroup()
    val colorToggles: Map<CardColor, ToggleButton> =
        CardColor.entries.associateWith { swatch(it) }

    val customUrlBox =
        CheckBox("Custom URL").apply {
            id = "customUrlBox"
            isSelected = initial.customUrl != null
        }
    val urlField = TextField(initial.customUrl.orEmpty()).apply { id = "urlField" }
    val propertyRows =
        FXCollections.observableArrayList<PropertyRow> { arrayOf(it.key, it.value) }.apply {
            initial.driverProperties.forEach { (k, v) -> add(PropertyRow(k, v)) }
        }
    val propertiesTable = TableView(propertyRows).apply { id = "propertiesTable" }
    val addPropertyButton = Button("Add").apply { id = "addPropertyButton" }
    val removePropertyButton = Button("Remove").apply { id = "removePropertyButton" }

    val testButton = Button("Test Connection").apply { id = "testButton" }
    val testResultLabel =
        Label().apply {
            id = "testResultLabel"
            maxWidth = Double.MAX_VALUE
        }
    private val problemLabel =
        Label().apply {
            id = "problemLabel"
            styleClass += "connection-error"
        }

    val node: VBox

    init {
        propertiesTable.apply {
            isEditable = true
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            placeholder = Label("No driver properties")
            prefHeight = 140.0
            columns +=
                TableColumn<PropertyRow, String>("Key").apply {
                    setCellValueFactory { it.value.key }
                    cellFactory = TextFieldTableCell.forTableColumn()
                    setOnEditCommit { it.rowValue.key.set(it.newValue) }
                }
            columns +=
                TableColumn<PropertyRow, String>("Value").apply {
                    setCellValueFactory { it.value.value }
                    cellFactory = TextFieldTableCell.forTableColumn()
                    setOnEditCommit { it.rowValue.value.set(it.newValue) }
                }
        }
        removePropertyButton.disableProperty().bind(propertiesTable.selectionModel.selectedItemProperty().isNull)
        addPropertyButton.setOnAction {
            propertyRows += PropertyRow("", "")
            propertiesTable.selectionModel.selectLast()
            propertiesTable.edit(propertyRows.size - 1, propertiesTable.columns[0])
        }
        removePropertyButton.setOnAction {
            propertiesTable.selectionModel.selectedItem?.let { propertyRows.remove(it) }
        }
        browseButton.setOnAction {
            val file = FileChooser().apply { title = "CA Certificate" }.showOpenDialog(node.scene?.window)
            if (file != null) caFileField.text = file.absolutePath
        }
        testButton.setOnAction { runTest() }
        testButton.disableProperty().bind(problem.isNotNull)

        customUrlBox.selectedProperty().addListener { _, _, on ->
            if (updating) return@addListener
            if (on) {
                urlField.text = readForm().generatedUrl()
            }
            refresh()
        }
        kindBox.valueProperty().addListener { _, _, kind ->
            if (updating || kind == null) return@addListener
            val switched = readForm(currentKind).withKind(kind)
            currentKind = kind
            portField.text = switched.portText
            refresh()
        }
        listOf(nameField, hostField, portField, databaseField, userField, caFileField, urlField).forEach {
            it.textProperty().addListener { _, _, _ -> refresh() }
        }
        readOnlyBox.selectedProperty().addListener { _, _, _ -> refresh() }
        listOf(commitBox.valueProperty(), sslBox.valueProperty()).forEach { it.addListener { _, _, _ -> refresh() } }
        propertyRows.addListener(ListChangeListener { refresh() })

        node =
            VBox(
                8.0,
                TabPane(
                    tab("General", generalGrid()),
                    tab("Options", optionsBox()),
                    tab("Advanced", advancedBox()),
                ).apply { tabs.forEach { it.isClosable = false } },
                HBox(8.0, testButton, testResultLabel).apply {
                    alignment = Pos.CENTER_LEFT
                    HBox.setHgrow(testResultLabel, Priority.ALWAYS)
                },
                problemLabel,
            ).apply {
                padding = Insets(4.0)
                prefWidth = 520.0
                stylesheets +=
                    ConnectionDialogContent::class.java
                        .getResource("/dev/sluide/databases/ui/connection-dialog.css")!!
                        .toExternalForm()
            }
        updating = false
        refresh()
    }

    /** Puts the focus where typing starts: Name for a new connection, Password when editing. */
    fun focusInitial() {
        (if (base.ownName == null) nameField else passwordField).requestFocus()
    }

    /** The result of OK: the config and the typed password, `null` when empty or the form is invalid. */
    fun edit(): ConnectionEdit? {
        val form = readForm()
        if (form.validation() != null) return null
        return ConnectionEdit(form.toConfig(), password())
    }

    private fun password(): CharArray? = passwordField.text.takeIf { it.isNotEmpty() }?.toCharArray()

    private fun readForm(kind: DatabaseKind = kindBox.value): ConnectionForm =
        base.copy(
            name = nameField.text,
            kind = kind,
            host = hostField.text,
            portText = portField.text,
            database = databaseField.text,
            user = userField.text,
            readOnly = readOnlyBox.isSelected,
            commitMode = commitBox.value,
            sslMode = sslBox.value,
            caFile = caFileField.text,
            customUrl = if (customUrlBox.isSelected) urlField.text else null,
            driverProperties = propertyRows.map { it.key.get().orEmpty() to it.value.get().orEmpty() },
            color = colorToggles.entries.firstOrNull { it.value.isSelected }?.key ?: CardColor.NONE,
        )

    /** Mirrors the controls into the form: the generated URL, the hints and the problem line. */
    private fun refresh() {
        if (updating) return
        val form = readForm()
        val custom = customUrlBox.isSelected
        urlField.isEditable = custom
        if (!custom) urlField.text = form.generatedUrl()
        sslHint.isVisible = form.kind == DatabaseKind.MARIADB && form.sslMode == SslMode.PREFER
        sslHint.isManaged = sslHint.isVisible
        val message = form.validation()
        problem.set(message)
        problemLabel.text = message.orEmpty()
    }

    private fun runTest() {
        val form = readForm()
        if (form.validation() != null) return
        val run = ++testRun
        testResultLabel.styleClass.remove("connection-error")
        testResultLabel.tooltip = null
        testResultLabel.text = "Connecting…"
        testResultLabel.graphic = ProgressIndicator().apply { setPrefSize(16.0, 16.0) }
        val secret = password()
        val future =
            try {
                tester.test(form.toConfig(), secret)
            } catch (e: RuntimeException) {
                CompletableFuture.failedFuture(e)
            }
        future.whenComplete { version, error ->
            secret?.fill('\u0000')
            Platform.runLater {
                if (run == testRun) showTestResult(version, error)
            }
        }
    }

    private fun showTestResult(
        version: String?,
        error: Throwable?,
    ) {
        testResultLabel.graphic = null
        if (error == null) {
            testResultLabel.text = "Connected: $version"
            return
        }
        val cause = if (error is CompletionException && error.cause != null) error.cause!! else error
        val full = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        testResultLabel.text =
            full
                .lines()
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .trim()
        testResultLabel.tooltip = Tooltip(full)
        testResultLabel.styleClass += "connection-error"
    }

    private fun generalGrid(): Node =
        form(
            "Name" to nameField,
            "Type" to kindBox,
            "Host" to hostField,
            "Port" to portField,
            "Database" to databaseField,
            "User" to userField,
            "Password" to passwordField,
        )

    private fun optionsBox(): Node {
        val colors = HBox(6.0).apply { children += colorToggles.values }
        return VBox(
            8.0,
            readOnlyBox,
            hint("The server refuses changes"),
            form("Commit mode" to commitBox, "SSL mode" to sslBox, "CA file" to HBox(6.0, caFileField, browseButton)),
            sslHint,
            form("Card color" to colors),
        ).apply {
            padding = Insets(12.0)
            HBox.setHgrow(caFileField, Priority.ALWAYS)
        }
    }

    private fun advancedBox(): Node =
        VBox(
            8.0,
            customUrlBox,
            urlField,
            Label("Driver properties"),
            propertiesTable,
            HBox(6.0, addPropertyButton, removePropertyButton),
        ).apply { padding = Insets(12.0) }

    private fun form(vararg rows: Pair<String, Node>): GridPane =
        GridPane().apply {
            hgap = 8.0
            vgap = 8.0
            padding = Insets(12.0)
            rows.forEachIndexed { index, (label, field) ->
                add(Label(label), 0, index)
                add(field, 1, index)
                GridPane.setHgrow(field, Priority.ALWAYS)
                if (field is TextField ||
                    field is ComboBox<*>
                ) {
                    (field as javafx.scene.layout.Region).maxWidth = Double.MAX_VALUE
                }
            }
        }

    private fun tab(
        title: String,
        content: Node,
    ) = Tab(title, content)

    private fun swatch(kind: CardColor): ToggleButton {
        val name = kind.name.lowercase().replaceFirstChar(Char::titlecase)
        return ToggleButton().apply {
            id = "color$name"
            tooltip = Tooltip(name)
            toggleGroup = swatchGroup
            isSelected = kind == base.color
            val fill = color(kind)
            if (fill == null) text = "None" else graphic = Circle(7.0, fill)
            selectedProperty().addListener { _, _, on ->
                if (!on && swatchGroup.selectedToggle == null) isSelected = true else refresh()
            }
        }
    }

    private fun hint(text: String) = Label(text).apply { styleClass += "connection-hint" }

    private fun sslLabel(mode: SslMode) =
        when (mode) {
            SslMode.DISABLE -> "Disable"
            SslMode.PREFER -> "Prefer"
            SslMode.REQUIRE -> "Require"
            SslMode.VERIFY_CA -> "Verify CA"
            SslMode.VERIFY_FULL -> "Verify Full"
        }

    private fun <T> labelCell(text: (T) -> String) =
        object : javafx.scene.control.ListCell<T>() {
            override fun updateItem(
                item: T?,
                empty: Boolean,
            ) {
                super.updateItem(item, empty)
                this.text = if (empty || item == null) null else text(item)
            }
        }
}
