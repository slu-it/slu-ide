package dev.sluide.gitlog.actions

import dev.sluide.git.ssh.HostKeyRequest
import dev.sluide.git.ssh.PassphraseReply
import dev.sluide.git.ssh.PassphraseRequest
import dev.sluide.git.ssh.SshPromptUi
import dev.sluide.gitlog.operations.cancelFirst
import dev.sluide.gitlog.operations.gitMessage
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Window
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** A dialog that [FxSshPromptUi] shows and closes; a seam so that the thread handling can be tested without JavaFX. */
internal interface PromptDialog<T> {
    /** Shows it on the FX thread and waits for the answer; `null` if it was dismissed. */
    fun showAndWait(): T?

    /** Closes it, on the FX thread. */
    fun close()
}

/** [Dialog] as a [PromptDialog]. */
internal fun <T : Any> Dialog<T?>.asPrompt(): PromptDialog<T> =
    object : PromptDialog<T> {
        override fun showAndWait(): T? = this@asPrompt.showAndWait().orElse(null)

        override fun close() = this@asPrompt.close()
    }

/**
 * Asks for what ssh wants to know during a git call through modal dialogs (ADR 0194): the passphrase of a key and
 * whether to trust an unknown host. Called on askpass connection threads, which wait while the dialog is shown on the
 * FX thread (through [runLater]), so that neither the FX thread nor the thread running git blocks. [owner] is the window
 * the dialogs belong to and [operation] says what runs ("Fetching origin…"); both are read on the FX thread when the
 * dialog is made. When the waiting thread is interrupted (the operation ended or was cancelled), the dialog is closed
 * and the interrupt is passed on. Called on the FX thread, which would wait on itself, it answers Cancel at once.
 * Neither passphrase nor host text is ever logged.
 */
internal class FxSshPromptUi(
    private val owner: () -> Window?,
    private val operation: () -> String,
    private val passphraseDialog: (PassphraseRequest, String, Window?) -> PromptDialog<PassphraseReply> =
        { request, line, window -> newPassphraseDialog(request, line, window).asPrompt() },
    private val hostDialog: (HostKeyRequest, String, Window?) -> PromptDialog<Boolean> =
        { request, line, window -> newHostDialog(request, line, window).asPrompt() },
    private val infoAlert: (Window?, String, String) -> Unit = ::showInfo,
    private val runLater: (Runnable) -> Unit = Platform::runLater,
) : SshPromptUi {
    override fun askPassphrase(request: PassphraseRequest): PassphraseReply? =
        ask { window, line -> passphraseDialog(request, line, window) }

    override fun confirmHost(request: HostKeyRequest): Boolean =
        ask { window, line -> hostDialog(request, line, window) } == true

    override fun notSaved(reason: String) = tell("Passphrase Not Saved", reason)

    override fun notAddedToAgent(message: String) = tell("Key Not Added to SSH Agent", message)

    private fun tell(
        title: String,
        text: String,
    ) = runLater { infoAlert(owner(), title, text) }

    private fun <T : Any> ask(make: (Window?, String) -> PromptDialog<T>): T? {
        // Waiting on the FX thread for a dialog shown on the FX thread would never end.
        if (Platform.isFxApplicationThread()) return null
        val answer = CompletableFuture<T?>()
        var shown: PromptDialog<T>? = null
        runLater {
            if (answer.isDone) return@runLater
            try {
                val dialog = make(owner(), operation()).also { shown = it }
                val given = dialog.showAndWait()
                // Too late for anyone to take it: the waiting thread was interrupted meanwhile.
                if (!answer.complete(given) && given is PassphraseReply) given.passphrase.fill('\u0000')
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
        } catch (e: ExecutionException) {
            // The dialog could not be made or shown: as Cancel; the message could hold the prompt's text.
            return null
        }
    }
}

private fun showInfo(
    owner: Window?,
    title: String,
    text: String,
) {
    Alert(Alert.AlertType.INFORMATION)
        .apply {
            owner?.let(::initOwner)
            this.title = title
            headerText = null
            contentText = text
        }.show()
}

/**
 * The passphrase dialog: asks for the key of [request] while [operation] runs. Unlock (the default) needs a
 * passphrase; the reply carries "Save passphrase" and "Add to ssh agent" unless they are disabled; the field is
 * cleared once its text was taken. A dismissed dialog gives `null`, as Cancel does.
 */
internal fun newPassphraseDialog(
    request: PassphraseRequest,
    operation: String,
    owner: Window?,
): Dialog<PassphraseReply?> {
    val field = PasswordField().apply { id = "ssh-passphrase" }
    val save =
        CheckBox(checkBoxLabel("Save passphrase", request.saveUnavailable)).apply {
            id = "ssh-save-passphrase"
            isDisable = request.saveUnavailable != null
        }
    val agent =
        CheckBox(checkBoxLabel("Add to ssh agent", request.agentUnavailable)).apply {
            id = "ssh-add-to-agent"
            isDisable = request.agentUnavailable != null
        }
    val unlock = ButtonType("Unlock", ButtonData.OK_DONE)
    return Dialog<PassphraseReply?>().apply {
        owner?.let(::initOwner)
        title = "SSH Key Passphrase"
        headerText = null
        val content = VBox(CONTENT_SPACING, wrapped(passphraseQuestion(request.keyFile)), wrapped(operation))
        request.notice?.let { content.children += wrapped(noticeText(it)).apply { id = "ssh-notice" } }
        content.children +=
            listOf(field, save, agent, wrapped("Kept in memory until Thoreau quits; saved encrypted when checked."))
        content.minHeight = Region.USE_PREF_SIZE
        dialogPane.content = content
        dialogPane.minHeight = Region.USE_PREF_SIZE
        dialogPane.buttonTypes.setAll(unlock, ButtonType.CANCEL)
        (dialogPane.lookupButton(unlock) as Button)
            .disableProperty()
            .bind(Bindings.createBooleanBinding({ unlockDisabled(field.text) }, field.textProperty()))
        setResultConverter { button ->
            val reply =
                if (button == unlock) {
                    PassphraseReply(field.text.toCharArray(), save.isSelected, agent.isSelected)
                } else {
                    null
                }
            field.clear()
            reply
        }
        setOnShown { field.requestFocus() }
    }
}

/**
 * The dialog that asks whether to connect to an unknown host: ssh's [request] text and what Connect does, while
 * [operation] runs. Cancel is the default and has the focus: trusting a host is a security decision, and Enter must
 * never make it. A dismissed dialog gives `null`, as Cancel does.
 */
internal fun newHostDialog(
    request: HostKeyRequest,
    operation: String,
    owner: Window?,
): Dialog<Boolean?> {
    val connect = ButtonType("Connect", ButtonData.OK_DONE)
    return Dialog<Boolean?>().apply {
        headerText = null
        val content =
            VBox(
                CONTENT_SPACING,
                gitMessage(request.text).apply { id = "ssh-host-text" },
                wrapped("Connect adds the host to your known_hosts file."),
                wrapped(operation),
            )
        content.minHeight = Region.USE_PREF_SIZE
        dialogPane.content = content
        dialogPane.minHeight = Region.USE_PREF_SIZE
        dialogPane.buttonTypes.setAll(connect, ButtonType.CANCEL)
        cancelFirst(this, owner, "Unknown SSH Host")
        setResultConverter { button -> button == connect }
    }
}

private fun wrapped(text: String) =
    Label(text).apply {
        isWrapText = true
        maxWidth = TEXT_WIDTH
        minHeight = Region.USE_PREF_SIZE
    }

private const val TEXT_WIDTH = 460.0
private const val CONTENT_SPACING = 8.0
