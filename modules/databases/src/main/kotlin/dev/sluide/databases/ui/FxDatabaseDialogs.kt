package dev.sluide.databases.ui

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.JdbcUrls
import dev.sluide.databases.ui.dialog.ConnectionDialog
import dev.sluide.databases.ui.dialog.ConnectionEdit
import dev.sluide.databases.ui.dialog.ConnectionTester
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.layout.VBox
import javafx.stage.Window

/** The [DatabaseDialogs] as JavaFX dialogs owned by the tab's window ([owner]). */
internal class FxDatabaseDialogs(
    private val owner: () -> Window?,
) : DatabaseDialogs {
    override fun editConnection(
        initial: ConnectionConfig,
        isNew: Boolean,
        existingNames: Set<String>,
        tester: ConnectionTester,
        saveReason: String?,
    ): ConnectionEdit? = ConnectionDialog.show(owner(), initial, isNew, existingNames, tester)

    override fun askPassword(
        config: ConnectionConfig,
        saveReason: String?,
        saveChecked: Boolean,
    ): PasswordAnswer? {
        val field = PasswordField().apply { styleClass += "database-password" }
        val connect = ButtonType("Connect", ButtonData.OK_DONE)
        val dialog =
            Dialog<CharArray?>().apply {
                owner()?.let(::initOwner)
                title = "Connect to ${config.name}"
                headerText = null
                dialogPane.content =
                    VBox(
                        8.0,
                        Label(JdbcUrls.display(config)),
                        field,
                        Label("The password is kept in memory until the app quits."),
                    )
                dialogPane.buttonTypes.setAll(connect, ButtonType.CANCEL)
                setResultConverter { button ->
                    val password = if (button == connect) field.text.toCharArray() else null
                    field.clear()
                    password
                }
                setOnShown { Platform.runLater(field::requestFocus) }
            }
        return dialog.showAndWait().orElse(null)?.let { PasswordAnswer(it, save = false) }
    }

    override fun confirmDelete(name: String): Boolean {
        val delete = ButtonType("Delete", ButtonData.OK_DONE)
        val text =
            "Delete the connection $name? Its settings are removed; the database itself is not touched. " +
                "This cannot be undone."
        return ask("Delete Connection", text, delete) == delete
    }

    override fun askTransactionEnd(name: String): TransactionEnd? {
        val commit = ButtonType("Commit", ButtonData.OK_DONE)
        val rollBack = ButtonType("Roll Back", ButtonData.OTHER)
        return when (ask("Uncommitted Transaction", "$name has an uncommitted transaction.", commit, rollBack)) {
            commit -> TransactionEnd.COMMIT
            rollBack -> TransactionEnd.ROLLBACK
            else -> null
        }
    }

    override fun confirmDisconnect(
        name: String,
        reasons: List<String>,
    ): Boolean {
        val disconnect = ButtonType("Disconnect", ButtonData.OK_DONE)
        val text = (listOf("Disconnect $name?") + reasons).joinToString("\n")
        return ask("Disconnect", text, disconnect) == disconnect
    }

    override fun showError(
        title: String,
        message: String,
    ) {
        Alert(Alert.AlertType.ERROR, message)
            .apply {
                owner()?.let(::initOwner)
                this.title = title
                headerText = null
            }.showAndWait()
    }

    /** A question with [buttons] and Cancel, the default and focused button; a dismissed dialog counts as Cancel. */
    private fun ask(
        title: String,
        text: String,
        vararg buttons: ButtonType,
    ): ButtonType? {
        val alert =
            Alert(Alert.AlertType.CONFIRMATION, text, *buttons, ButtonType.CANCEL).apply {
                owner()?.let(::initOwner)
                this.title = title
                headerText = null
                cancelFirst(this)
            }
        return alert.showAndWait().orElse(null)?.takeIf { it != ButtonType.CANCEL }
    }
}

/** Makes Cancel the default button of [alert], focused once shown, so Enter keeps everything (like git-log's). */
internal fun cancelFirst(alert: Alert) {
    val cancel = alert.dialogPane.lookupButton(ButtonType.CANCEL) as Button
    alert.dialogPane.buttonTypes
        .filter { it != ButtonType.CANCEL }
        .forEach { (alert.dialogPane.lookupButton(it) as Button).isDefaultButton = false }
    cancel.isDefaultButton = true
    // Later, since the dialog focuses a button of its own once shown.
    alert.setOnShown { Platform.runLater(cancel::requestFocus) }
}
