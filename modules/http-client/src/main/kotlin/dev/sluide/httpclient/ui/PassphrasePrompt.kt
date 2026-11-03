package dev.sluide.httpclient.ui

import dev.sluide.httpclient.env.SslConfig
import dev.sluide.httpclient.send.PassphraseAnswer
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.layout.VBox
import javafx.stage.Window
import java.util.concurrent.CompletableFuture

/**
 * Asks for the passphrase of a client certificate's key ([SslConfig]) on behalf of a worker thread: the [dialog] is
 * shown on the FX thread (through [runLater]) while the worker waits, so the FX thread never blocks. `null` means
 * cancelled. "Save passphrase" starts checked with `saveChecked` and is disabled when `saveReason` says why nothing can
 * be saved. When the waiting worker is interrupted (e.g. the request was cancelled), the dialog closes and its answer
 * is ignored.
 */
internal class PassphrasePrompt(
    private val dialog: (SslConfig, String?, Boolean) -> Dialog<PassphraseAnswer?> = ::passphraseDialog,
    private val runLater: (Runnable) -> Unit = Platform::runLater,
) {
    fun ask(
        config: SslConfig,
        saveReason: String?,
        saveChecked: Boolean,
    ): PassphraseAnswer? {
        // Waiting on the FX thread for a dialog shown on the FX thread would never end.
        if (Platform.isFxApplicationThread()) return null
        val answer = CompletableFuture<PassphraseAnswer?>()
        var shown: Dialog<PassphraseAnswer?>? = null
        runLater {
            if (answer.isDone) return@runLater
            try {
                val dialog = dialog(config, saveReason, saveChecked).also { shown = it }
                answer.complete(dialog.showAndWait().orElse(null))
            } catch (e: RuntimeException) {
                answer.completeExceptionally(e)
            }
        }
        try {
            return answer.get()
        } catch (e: InterruptedException) {
            answer.cancel(false)
            runLater { shown?.close() }
            throw e
        }
    }

    companion object {
        /**
         * A dialog with a password field for the key file of [config] and "Save passphrase" (checked with
         * [saveChecked], disabled with [saveReason]), owned by the focused window; the field is cleared once its text
         * was taken.
         */
        fun passphraseDialog(
            config: SslConfig,
            saveReason: String?,
            saveChecked: Boolean,
        ): Dialog<PassphraseAnswer?> {
            val file = (config.clientCertificateKey ?: config.clientCertificate)?.path?.fileName
            val field = PasswordField().apply { styleClass += "http-passphrase" }
            val save =
                CheckBox(if (saveReason == null) "Save passphrase" else "Save passphrase ($saveReason)").apply {
                    styleClass += "http-save-passphrase"
                    isSelected = saveChecked
                    isDisable = saveReason != null
                }
            return Dialog<PassphraseAnswer?>().apply {
                Window.getWindows().firstOrNull { it.isFocused }?.let(::initOwner)
                title = "Client Certificate"
                headerText = "Passphrase for ${file ?: "the client certificate"}"
                dialogPane.content =
                    VBox(
                        8.0,
                        Label("Kept in memory until the window closes; saved encrypted when checked."),
                        field,
                        save,
                    )
                dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
                setResultConverter { button ->
                    val passphrase =
                        if (button ==
                            ButtonType.OK
                        ) {
                            PassphraseAnswer(field.text.toCharArray(), save.isSelected)
                        } else {
                            null
                        }
                    field.clear()
                    passphrase
                }
                setOnShown { field.requestFocus() }
            }
        }

        /** Says that a passphrase the user asked to save stays in memory only, and why; owned by the focused window. */
        fun notSavedAlert(reason: String): Alert =
            Alert(Alert.AlertType.WARNING).apply {
                Window.getWindows().firstOrNull { it.isFocused }?.let(::initOwner)
                title = "Passphrase not saved"
                headerText = null
                contentText = "The passphrase stays in memory until the window closes: $reason."
            }
    }
}
