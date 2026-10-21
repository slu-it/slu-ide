package dev.sluide.editor

import javafx.scene.control.Alert
import javafx.stage.Window
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

/** Why a file could not be saved, as a sentence for the error message. */
internal fun saveFailureReason(e: IOException): String =
    when (e) {
        is NoSuchFileException -> "Its folder does not exist."
        is AccessDeniedException -> "Access denied."
        is FileSystemException -> e.reason ?: e.toString()
        else -> e.message ?: e.toString()
    }

/** Shows [message] in an error dialog owned by [owner] and waits until it is closed; call it on the FX thread. */
internal fun showErrorAlert(
    owner: Window?,
    message: String,
) {
    Alert(Alert.AlertType.ERROR, message)
        .apply {
            owner?.let(::initOwner)
            headerText = null
        }.showAndWait()
}
