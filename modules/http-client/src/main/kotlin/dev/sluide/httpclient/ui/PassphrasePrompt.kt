package dev.sluide.httpclient.ui

import dev.sluide.httpclient.env.SslConfig
import javafx.application.Platform
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.layout.VBox
import javafx.stage.Window
import java.util.concurrent.CompletableFuture

/**
 * Asks for the passphrase of a client certificate's key ([SslConfig]) on behalf of a worker thread: the [dialog] is
 * shown on the FX thread (through [runLater]) while the worker waits, so the FX thread never blocks. `null` means
 * cancelled. When the waiting worker is interrupted (e.g. the request was cancelled), the dialog closes and its answer
 * is ignored.
 */
internal class PassphrasePrompt(
    private val dialog: (SslConfig) -> Dialog<CharArray?> = ::passphraseDialog,
    private val runLater: (Runnable) -> Unit = Platform::runLater,
) {
    fun ask(config: SslConfig): CharArray? {
        // Waiting on the FX thread for a dialog shown on the FX thread would never end.
        if (Platform.isFxApplicationThread()) return null
        val answer = CompletableFuture<CharArray?>()
        var shown: Dialog<CharArray?>? = null
        runLater {
            if (answer.isDone) return@runLater
            try {
                val dialog = dialog(config).also { shown = it }
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
         * A dialog with a password field for the key file of [config], owned by the focused window; the field is
         * cleared once its text was taken.
         */
        fun passphraseDialog(config: SslConfig): Dialog<CharArray?> {
            val file = (config.clientCertificateKey ?: config.clientCertificate)?.path?.fileName
            val field = PasswordField().apply { styleClass += "http-passphrase" }
            return Dialog<CharArray?>().apply {
                Window.getWindows().firstOrNull { it.isFocused }?.let(::initOwner)
                title = "Client Certificate"
                headerText = "Passphrase for ${file ?: "the client certificate"}"
                dialogPane.content =
                    VBox(8.0, Label("The passphrase is kept in memory until the window closes."), field)
                dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
                setResultConverter { button ->
                    val passphrase = if (button == ButtonType.OK) field.text.toCharArray() else null
                    field.clear()
                    passphrase
                }
                setOnShown { field.requestFocus() }
            }
        }
    }
}
