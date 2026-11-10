package dev.sluide.encryptedfiles

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.encryptedfiles.ui.NEW_PASSWORD_WARNING
import dev.sluide.encryptedfiles.ui.showPasswordDialog
import javafx.application.Platform
import javafx.stage.Window
import java.io.IOException

private val log = System.getLogger("dev.sluide.encryptedfiles.NewPasswordDialog")

private const val CREATE_THREAD = "Create encrypted file"
private const val CREATE_FAILED = "Cannot create the file."

/**
 * Asks for a new password (Password and Repeat, a strength hint, the warning that it is stored nowhere) in a modal
 * dialog owned by [owner] and runs [work] with it on a logged background thread while the dialog says "Creating…" with
 * its buttons disabled. [work] does the key derivation and creates the file; it may throw an [IOException], whose
 * message the dialog then shows (any other failure shows a fixed text), and the dialog stays for another try. The
 * password array is zeroed after [work] returns or throws, also when its thread cannot start. Returns `true` if [work]
 * succeeded (the dialog closed), `false` on Cancel. FX thread; returns when the dialog is closed.
 */
fun newPasswordDialog(
    owner: Window?,
    title: String,
    work: (CharArray) -> Unit,
): Boolean =
    showPasswordDialog(owner, title, false, NEW_PASSWORD_WARNING, "Create", "Creating…") { _, new, done ->
        try {
            NamedThreads.virtual(CREATE_THREAD) {
                val error =
                    try {
                        work(new)
                        null
                    } catch (e: IOException) {
                        e.message ?: CREATE_FAILED
                    } catch (e: Throwable) {
                        // Only the type: a message could hold content.
                        log.log(System.Logger.Level.WARNING, "Cannot create an encrypted file: ${e.javaClass.name}")
                        CREATE_FAILED
                    } finally {
                        new.fill('\u0000')
                    }
                Platform.runLater { done(error, false) }
            }
        } catch (e: Throwable) {
            new.fill('\u0000')
            log.log(System.Logger.Level.WARNING, "Cannot start creating an encrypted file: ${e.javaClass.name}")
            done(CREATE_FAILED, false)
        }
    }
