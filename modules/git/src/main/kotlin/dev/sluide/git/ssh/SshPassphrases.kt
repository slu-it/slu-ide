package dev.sluide.git.ssh

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/** One session of [SshPrompts]: the prompts of one run of a covered operation (ADR 0194). */
@JvmInline
internal value class SessionId(
    val value: Long,
)

/**
 * Answers ssh's passphrase prompts of the sessions of [SshPrompts] (ADR 0194): from what an earlier process of the
 * session was given, from memory (per key file, until Thoreau quits), from the saved passphrases, or from the session's
 * UI. An answer counts as good only after a git process that used it exited 0: then it goes into memory, is saved if
 * the user checked "Save passphrase" and added to the ssh agent if they checked "Add to ssh agent". When ssh asks for
 * the same key again on the same connection, the answer it got was wrong. Open for the spies of the tests.
 *
 * All state is guarded by one lock that is never held while the UI, the secret store or ssh-add runs.
 */
@Component
internal open class SshPassphrases internal constructor(
    internal val channel: AskpassChannel,
    private val saved: SavedSshPassphrases,
    /** git's environment: whether there is an ssh agent. */
    private val environment: Map<String, String>,
    private val agent: SshAdd,
    /** Runs a commit off the calling thread. */
    private val launch: (Runnable) -> Unit,
) : DisposableBean {
    @Autowired
    constructor(channel: AskpassChannel, saved: SavedSshPassphrases) :
        this(channel, saved, System.getenv(), SshAdd(channel), { NamedThreads.virtual(THREAD, it) })

    /** Called with every passphrase array after it was zeroed; tests check that they do not stay in memory. */
    internal var onZeroed: (CharArray) -> Unit = {}

    private enum class Source { MEMORY, SAVED, ENTERED }

    /** The answer given for a key in this session; [used]: the current git process of the session got it. */
    private class Pending(
        val chars: CharArray,
        val source: Source,
        var save: Boolean,
        var addToAgent: Boolean,
        var used: Boolean,
    )

    private class SessionState {
        val pending = HashMap<Path, Pending>()

        /** How often ssh asked for a key on a connection (ssh's pid). */
        val asked = HashMap<Pair<Long, Path>, Int>()
    }

    /** What a commit does off the thread of [ended]; the array is its own copy. */
    private class Commit(
        val keyFile: Path,
        val chars: CharArray,
        val save: Boolean,
        val addToAgent: Boolean,
    )

    private val lock = Any()
    private val memory = HashMap<Path, CharArray>()
    private val sessions = HashMap<SessionId, SessionState>()

    /**
     * [session] is registered and may ask: called once, before its environment reaches git. Only an opened session
     * gets answers, so that a call that comes after [closed] or [destroy] cannot bring state back.
     */
    open fun opened(session: SessionId) {
        synchronized(lock) { sessions.putIfAbsent(session, SessionState()) }
    }

    /** How many sessions have state; closed ones leave none. */
    internal val openSessions: Int get() = synchronized(lock) { sessions.size }

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
        val retry =
            synchronized(lock) {
                val state = sessions[session] ?: return null
                val times = (state.asked[connection to keyFile] ?: 0) + 1
                state.asked[connection to keyFile] = times
                if (times > 1) {
                    wrong(state, keyFile)
                } else {
                    reuse(state, keyFile)?.let { return it }
                    null
                }
            }
        val notice =
            if (retry != null) {
                retry.passphrase?.let {
                    // The saved value was rejected: gone, so that no later session offers it again; a newer one stays.
                    saved.removeRejected(keyFile, it)
                    zero(it)
                }
                retry.notice
            } else {
                fromSaved(session, keyFile)?.let { return it }
                null
            }
        return ask(session, keyFile, notice, ui)
    }

    /** What ssh asking again meant: the dropped answer's [source], and for a saved one a copy of it. */
    private class Retry(
        val source: Source?,
        val passphrase: CharArray?,
    ) {
        val notice: PassphraseNotice? =
            when (source) {
                Source.SAVED -> PassphraseNotice.SAVED_REJECTED
                Source.MEMORY, Source.ENTERED -> PassphraseNotice.WRONG
                null -> null
            }
    }

    /** The session's pending answer for [keyFile] was wrong: dropped, also from memory. */
    private fun wrong(
        state: SessionState,
        keyFile: Path,
    ): Retry {
        val pending = state.pending.remove(keyFile) ?: return Retry(null, null)
        // Only the same value: another session may have remembered a newer one meanwhile.
        if (memory[keyFile] contentEquals pending.chars) zero(memory.remove(keyFile))
        val copy = if (pending.source == Source.SAVED) pending.chars.copyOf() else null
        zero(pending.chars)
        return Retry(pending.source, copy)
    }

    /** A copy of the session's pending answer, or of the remembered one, which then becomes pending. Under [lock]. */
    private fun reuse(
        state: SessionState,
        keyFile: Path,
    ): CharArray? {
        state.pending[keyFile]?.let {
            it.used = true
            return it.chars.copyOf()
        }
        val remembered = memory[keyFile] ?: return null
        state.pending[keyFile] =
            Pending(remembered.copyOf(), Source.MEMORY, save = false, addToAgent = false, used = true)
        return remembered.copyOf()
    }

    /** The saved passphrase, pending for the session from now on; `null` if none is saved or readable. */
    private fun fromSaved(
        session: SessionId,
        keyFile: Path,
    ): CharArray? {
        val loaded = saved.load(keyFile)
        if (loaded !is Decrypted.Plain) return null
        val plain = loaded.value
        return synchronized(lock) {
            val state = sessions[session]
            if (state == null) {
                zero(plain)
                null
            } else {
                state.pending[keyFile] = Pending(plain, Source.SAVED, save = false, addToAgent = false, used = true)
                plain.copyOf()
            }
        }
    }

    private fun ask(
        session: SessionId,
        keyFile: Path,
        notice: PassphraseNotice?,
        ui: SshPromptUi,
    ): CharArray? {
        // The operation may have ended while the saved value was read: nobody is waiting for a dialog.
        if (synchronized(lock) { session !in sessions }) return null
        val saveUnavailable = saved.unavailableReason()
        val agentUnavailable = agentUnavailable(keyFile)
        val reply =
            ui.askPassphrase(PassphraseRequest(keyFile, notice, saveUnavailable, agentUnavailable)) ?: return null
        val chars = reply.passphrase.copyOf()
        reply.passphrase.fill('\u0000')
        return synchronized(lock) {
            val state = sessions[session]
            if (state == null) {
                zero(chars)
                return null
            }
            val pending =
                Pending(
                    chars,
                    Source.ENTERED,
                    save = reply.save && saveUnavailable == null,
                    addToAgent = reply.addToAgent && agentUnavailable == null,
                    used = true,
                )
            state.pending[keyFile] = pending
            chars.copyOf()
        }
    }

    private fun agentUnavailable(keyFile: Path): String? =
        when {
            environment["SSH_AUTH_SOCK"].isNullOrEmpty() -> "no ssh agent"
            !Files.isRegularFile(keyFile) -> "key file not found"
            else -> null
        }

    /**
     * A git process of [session] ended, with exit 0 if [success]: the passphrases given since the previous end belong
     * to it and are good only if it succeeded. [ui] is the session's, for [SshPromptUi.notSaved] and
     * [SshPromptUi.notAddedToAgent]. Called on the thread that ran git: remembering is quick, saving and ssh-add run on
     * a virtual thread. Known limit: ssh may give up on a key after three wrong answers and log in with another
     * identity; the process succeeds, and that key's last (wrong) answer counts as good.
     */
    open fun ended(
        session: SessionId,
        success: Boolean,
        ui: SshPromptUi,
    ) {
        val commits = ArrayList<Commit>()
        synchronized(lock) {
            val state = sessions[session] ?: return
            for ((keyFile, pending) in state.pending) {
                if (!pending.used) continue
                pending.used = false
                if (!success) continue
                zero(memory.put(keyFile, pending.chars.copyOf()))
                val save = pending.save
                if (save ||
                    pending.addToAgent
                ) {
                    commits += Commit(keyFile, pending.chars.copyOf(), save, pending.addToAgent)
                }
                // Once: the flags act on this success only.
                pending.save = false
                pending.addToAgent = false
            }
        }
        if (commits.isNotEmpty()) launch { commits.forEach { commit(it, ui) } }
    }

    /** Saves and adds to the agent as the user asked; zeroes the commit's copy. */
    private fun commit(
        commit: Commit,
        ui: SshPromptUi,
    ) {
        try {
            if (commit.save) {
                val saved = saved.save(commit.keyFile, commit.chars)
                if (saved is Encrypted.Failed) ui.notSaved(saved.reason)
            }
            if (commit.addToAgent) agent.add(commit.keyFile, commit.chars)?.let(ui::notAddedToAgent)
        } finally {
            zero(commit.chars)
        }
    }

    /** [session] ended: its answers are zeroed, and later calls for it get none. */
    open fun closed(session: SessionId) {
        synchronized(lock) {
            sessions.remove(session)?.let { state -> state.pending.values.forEach { zero(it.chars) } }
        }
    }

    /** Thoreau quits: zeroes what is in memory. */
    override fun destroy() {
        synchronized(lock) {
            memory.values.forEach(::zero)
            memory.clear()
            sessions.values.forEach { state -> state.pending.values.forEach { zero(it.chars) } }
            sessions.clear()
        }
    }

    private fun zero(chars: CharArray?) {
        chars ?: return
        chars.fill('\u0000')
        onZeroed(chars)
    }

    private companion object {
        const val THREAD = "sluide-git-ssh-passphrase"
    }
}
