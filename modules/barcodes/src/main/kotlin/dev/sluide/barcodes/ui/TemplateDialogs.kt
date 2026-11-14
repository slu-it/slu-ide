package dev.sluide.barcodes.ui

import dev.sluide.barcodes.payload.WifiSecurity
import javafx.scene.Node
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ChoiceBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.stage.Window
import javafx.util.StringConverter
import org.kordamp.ikonli.javafx.FontIcon

/**
 * The dialogs of the Template menu. [wifiDialog] and [contactDialog] build a dialog that edits its form; [wifi] and
 * [contact] show it and return whether the user chose Insert. Building is separate from showing so that tests can
 * reach the controls without a blocking `showAndWait`.
 */
internal object TemplateDialogs {
    fun wifi(
        owner: Window?,
        form: WifiForm,
    ): Boolean = chose(wifiDialog(owner, form))

    fun contact(
        owner: Window?,
        form: ContactForm,
    ): Boolean = chose(contactDialog(owner, form))

    fun wifiDialog(
        owner: Window?,
        form: WifiForm,
    ): Dialog<ButtonType> =
        dialog(owner, "Wi-Fi Template", { form.canInsert }) { update ->
            val network = field("barcodes-wifi-network", form.ssid) { form.ssid = it }
            val security =
                ChoiceBox<WifiSecurity>().apply {
                    id = "barcodes-wifi-security"
                    items.setAll(WifiSecurity.entries)
                    converter =
                        object : StringConverter<WifiSecurity>() {
                            override fun toString(value: WifiSecurity?) = value?.title.orEmpty()

                            override fun fromString(string: String?): WifiSecurity? = null
                        }
                    value = form.security
                }
            val password = PasswordField().apply { id = "barcodes-wifi-password" }
            password.text = form.password
            val shown = TextField().apply { id = "barcodes-wifi-password-shown" }
            shown.textProperty().bindBidirectional(password.textProperty())
            val icon = FontIcon("mdi2e-eye")
            val show =
                ToggleButton().apply {
                    id = "barcodes-wifi-password-toggle"
                    graphic = icon
                    tooltip = Tooltip("Show")
                    isFocusTraversable = false
                }
            show.selectedProperty().subscribe { selected ->
                icon.iconLiteral = if (selected) "mdi2e-eye-off" else "mdi2e-eye"
                show.tooltip.text = if (selected) "Hide" else "Show"
            }
            shown.visibleProperty().bind(show.selectedProperty())
            password.visibleProperty().bind(show.selectedProperty().not())
            password.textProperty().addListener { _, _, text -> form.password = text }
            val hidden =
                CheckBox("Hidden network").apply {
                    id = "barcodes-wifi-hidden"
                    isSelected = form.hidden
                    selectedProperty().addListener { _, _, value -> form.hidden = value }
                }
            val passwordRow =
                HBox(4.0, StackPane(password, shown).apply { HBox.setHgrow(this, Priority.ALWAYS) }, show).apply {
                    id = "barcodes-wifi-password-row"
                    isDisable = !form.passwordEnabled
                }
            network.textProperty().addListener { _, _, _ -> update() }
            security.valueProperty().addListener { _, _, value ->
                form.security = value
                passwordRow.isDisable = !form.passwordEnabled
                update()
            }
            grid("Network" to network, "Security" to security, "Password" to passwordRow, "" to hidden)
        }

    fun contactDialog(
        owner: Window?,
        form: ContactForm,
    ): Dialog<ButtonType> =
        dialog(owner, "Contact Template", { form.canInsert }) { update ->
            fun contactField(
                key: String,
                initial: String,
                set: (String) -> Unit,
            ) = field("barcodes-contact-$key", initial, set).also {
                it.textProperty().addListener {
                    _,
                    _,
                    _,
                    ->
                    update()
                }
            }
            grid(
                "Name" to contactField("name", form.name) { form.name = it },
                "Organization" to contactField("organization", form.organization) { form.organization = it },
                "Phone" to contactField("phone", form.phone) { form.phone = it },
                "Email" to contactField("email", form.email) { form.email = it },
                "Website" to contactField("website", form.website) { form.website = it },
            )
        }

    private fun chose(dialog: Dialog<ButtonType>): Boolean =
        dialog.showAndWait().filter { it.buttonData == ButtonData.OK_DONE }.isPresent

    private fun field(
        id: String,
        initial: String,
        set: (String) -> Unit,
    ) = TextField(initial).apply {
        this.id = id
        textProperty().addListener { _, _, text -> set(text) }
    }

    private fun grid(vararg rows: Pair<String, Node>) =
        GridPane().apply {
            hgap = 8.0
            vgap = 8.0
            rows.forEachIndexed { row, (label, control) ->
                add(Label(label), 0, row)
                add(control, 1, row)
                GridPane.setHgrow(control, Priority.ALWAYS)
            }
            prefWidth = 380.0
            minHeight = Region.USE_PREF_SIZE
        }

    /** A dialog with Insert (default) and Cancel; [content] gets the function that refreshes Insert's state. */
    private fun dialog(
        owner: Window?,
        heading: String,
        canInsert: () -> Boolean,
        content: (update: () -> Unit) -> Node,
    ): Dialog<ButtonType> {
        val insert = ButtonType("Insert", ButtonData.OK_DONE)
        var refresh = {}
        val grid = content { refresh() }
        return Dialog<ButtonType>().apply {
            title = heading
            headerText = null
            if (owner != null) initOwner(owner)
            dialogPane.buttonTypes.addAll(insert, ButtonType.CANCEL)
            dialogPane.content = grid
            dialogPane.minHeight = Region.USE_PREF_SIZE
            val insertButton = dialogPane.lookupButton(insert)
            refresh = { insertButton.isDisable = !canInsert() }
            refresh()
        }
    }
}
