package dev.sluide.docker.run

import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.stage.Window
import java.nio.file.Path

/** The Run button's type; Run is the dialog's confirm, Cancel the other. */
private val RUN = ButtonType("Run", ButtonBar.ButtonData.OK_DONE)

/** Wraps [form] in the modal Run Container dialog over [owner]; the options to run, `null` if cancelled. */
internal fun showRunDialog(
    owner: () -> Window?,
    form: RunForm,
): RunOptions? {
    val dialog =
        Dialog<ButtonType>().apply {
            owner()?.let(::initOwner)
            title = "Run Container"
            headerText = null
            isResizable = true
            dialogPane.content = form.node
            dialogPane.buttonTypes.setAll(RUN, ButtonType.CANCEL)
            dialogPane.minWidth = 520.0
            dialogPane.minHeight = 420.0
            dialogPane.lookupButton(RUN).disableProperty().bind(Bindings.not(form.valid))
            setOnShown { Platform.runLater(form.name::requestFocus) }
        }
    return if (dialog.showAndWait().orElse(null) == RUN) form.options().withoutBlankRows() else null
}

/** The dialog for [initial] with the session's [networks] and [volumes] to pick from. */
internal fun askRunDialog(
    owner: () -> Window?,
    initial: RunOptions,
    networks: List<String>,
    volumes: List<String>,
    executable: String,
    projectFolder: Path?,
    home: Path,
): RunOptions? = showRunDialog(owner, RunForm(initial, networks, volumes, executable, projectFolder, home))
