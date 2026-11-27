package dev.sluide.git.ssh

import java.nio.file.Path

/**
 * Asks the user what ssh wants to know for a covered git call (ADR 0194); implemented by the git log, free of JavaFX.
 * Called on an askpass connection thread, never on the FX thread nor on the thread running git; [askPassphrase] and
 * [confirmHost] block until the user answers. On an interrupt while waiting (the session was closed: the operation was
 * cancelled or ended), the implementation closes its dialog and throws [InterruptedException].
 */
interface SshPromptUi {
    /** The passphrase of a key and what to do with it; `null` for Cancel. */
    fun askPassphrase(request: PassphraseRequest): PassphraseReply?

    /** Whether to connect to a host not in `known_hosts`; `false` for Cancel. */
    fun confirmHost(request: HostKeyRequest): Boolean

    /** Tells that a passphrase could not be saved, and why; does not block. */
    fun notSaved(reason: String)

    /** Tells that a key could not be added to the ssh agent, with ssh-add's [message]; does not block. */
    fun notAddedToAgent(message: String)
}

/**
 * ssh asks for the passphrase of [keyFile] (absolute, normalized). [notice] says why it asks again; [saveUnavailable] and
 * [agentUnavailable] are the reasons why "Save passphrase" or "Add to ssh agent" cannot be checked (`null`: they can).
 */
data class PassphraseRequest(
    val keyFile: Path,
    val notice: PassphraseNotice?,
    val saveUnavailable: String?,
    val agentUnavailable: String?,
)

/** Why the passphrase is asked for again. */
enum class PassphraseNotice {
    /** ssh asked again: the passphrase given before was wrong. */
    WRONG,

    /** The saved passphrase was not accepted, and was removed. */
    SAVED_REJECTED,
}

/** The user's answer: the [passphrase] (zeroed by its receiver after use) and whether to [save] it or [addToAgent]. */
class PassphraseReply(
    val passphrase: CharArray,
    val save: Boolean,
    val addToAgent: Boolean,
)

/** ssh asks whether to connect to a host not in `known_hosts`: its prompt [text] without the final question line. */
data class HostKeyRequest(
    val text: String,
)
