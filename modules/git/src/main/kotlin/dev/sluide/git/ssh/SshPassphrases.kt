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
 * time and keeps nothing; keeping passphrases in memory, saving them and adding keys to the agent come next. Open for
 * the spies of the tests.
 */
@Component
internal open class SshPassphrases {
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

    /** A git process of [session] exited 0: the passphrases it was given are good. */
    open fun succeeded(session: SessionId) = Unit

    /** [session] ended. */
    open fun closed(session: SessionId) = Unit

    private companion object {
        const val NOT_YET = "not available yet"
    }
}
