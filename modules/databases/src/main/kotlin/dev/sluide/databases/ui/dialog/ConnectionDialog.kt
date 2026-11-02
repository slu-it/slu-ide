package dev.sluide.databases.ui.dialog

import dev.sluide.databases.connect.ConnectionConfig
import javafx.application.Platform
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.stage.Window
import java.util.concurrent.CompletableFuture

/**
 * Tries a connection for the dialog's Test Connection button. Completes with the server's product and version
 * ("PostgreSQL 17.2"), or exceptionally with the connect error. Runs off the FX thread. The dialog zeroes [password]
 * (its own copy) when the future completes, so the tester must not use it afterwards.
 */
fun interface ConnectionTester {
    fun test(
        config: ConnectionConfig,
        password: CharArray?,
    ): CompletableFuture<String>
}

/**
 * What the dialog returns. [password] is the one typed for this session (`null` when left empty); it goes into the
 * in-memory password cache and is never stored. `equals` compares the array by identity.
 */
data class ConnectionEdit(
    val config: ConnectionConfig,
    val password: CharArray?,
)

/** The modal Add/Edit connection dialog (ADR 0121); its content is [ConnectionDialogContent]. */
object ConnectionDialog {
    /**
     * Shows the dialog modally (FX thread). Null when cancelled. The title is "Add PostgreSQL Connection" or
     * "Edit Connection 'name'".
     */
    fun show(
        owner: Window?,
        initial: ConnectionConfig,
        isNew: Boolean,
        existingNames: Set<String>,
        tester: ConnectionTester,
    ): ConnectionEdit? {
        val content = ConnectionDialogContent(initial, isNew, existingNames, tester)
        val okType = ButtonType(if (isNew) "Add" else "Save", ButtonData.OK_DONE)
        val dialog =
            Dialog<ConnectionEdit>().apply {
                if (owner != null) initOwner(owner)
                title = if (isNew) "Add ${initial.kind.label} Connection" else "Edit Connection '${initial.name}'"
                headerText = null
                dialogPane.content = content.node
                dialogPane.buttonTypes.addAll(okType, ButtonType.CANCEL)
                dialogPane.lookupButton(okType).disableProperty().bind(content.problem.isNotNull)
                setResultConverter {
                    val edit = if (it == okType) content.edit() else null
                    content.passwordField.clear()
                    edit
                }
                setOnShown { Platform.runLater { content.focusInitial() } }
            }
        return dialog.showAndWait().orElse(null)
    }
}
