package dev.sluide.git.ssh

import org.springframework.stereotype.Component
import java.nio.file.Path

/** One session of [SshPrompts]: the prompts of one run of a covered operation (ADR 0194). */
@JvmInline
internal value class SessionId(
    val value: Long,
)

/**
 * Answers ssh's passphrase prompts of the sessions of [SshPrompts] (ADR 0194). For now it asks the session's UI every
 * time and keeps nothing; keeping passphrases in memory, saving them and adding keys to the agent (through [channel]'s
 * [one-shot][AskpassChannel.oneShot] tokens for ssh-add) come next. Open for the spies of the tests.
 */
@Component
internal open class SshPassphrases(
    internal val channel: AskpassChannel,
) {
    /**
     * The passphrase of [keyFile] for the ssh process [connection] of [session], asked from [ui]; `null` for Cancel.
     * Runs on an askpass connection thread and blocks while the UI waits.
     */
    open fun answer(
        session: SessionId,
        connection: Long,
        keyFile: Path,
        ui: SshPromptUi,
    ): CharArray? {
        val request = PassphraseRequest(keyFile, notice = null, saveUnavailable = NOT_YET, agentUnavailable = NOT_YET)
        return ui.askPassphrase(request)?.passphrase
    }

    /**
     * A git process of [session] ended, with exit 0 if [success]: the passphrases given since the previous end belong
     * to it and are good only if it succeeded. [ui] is the session's, for [SshPromptUi.notSaved] and
     * [SshPromptUi.notAddedToAgent]. Known limit: ssh may give up on a key after three wrong answers and log in with
     * another identity; the process succeeds, and that key's last (wrong) answer counts as good.
     */
    open fun ended(
        session: SessionId,
        success: Boolean,
        ui: SshPromptUi,
    ) = Unit

    /** [session] ended. */
    open fun closed(session: SessionId) = Unit

    private companion object {
        const val NOT_YET = "not available yet"
    }
}
