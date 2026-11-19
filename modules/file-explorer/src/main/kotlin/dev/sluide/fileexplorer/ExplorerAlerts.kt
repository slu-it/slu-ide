package dev.sluide.fileexplorer

import javafx.scene.control.Alert
import javafx.stage.Window

/** Shows [message] in an alert of [type] without a header, over [owner], and waits until it is closed. */
internal fun showAlert(
    owner: Window?,
    type: Alert.AlertType,
    message: String,
) {
    Alert(type, message)
        .apply {
            owner?.let(::initOwner)
            headerText = null
        }.showAndWait()
}
