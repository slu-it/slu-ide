package dev.sluide.git.ssh

import dev.sluide.git.askpass.AskpassRequest
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.lang.System.Logger.Level
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lets ssh ask the user through Thoreau's own dialogs during a covered git call (ADR 0194): each run of such an
 * operation [opens][open] a session, whose [environment][SshAccess.Prompting.environment] makes ssh run the askpass
 * helper, which relays each prompt over the [AskpassChannel]. Prompts are told apart by their text ([sshPrompt]): an
 * unknown host goes to [SshPromptUi.confirmHost], a key's passphrase to [SshPassphrases], anything else gets no answer.
 * A singleton; its sessions are closed when Thoreau quits.
 */
@Component
class SshPrompts
    @Autowired
    internal constructor(
        private val channel: AskpassChannel,
        private val passphrases: SshPassphrases,
    ) : DisposableBean {
        private val ids = AtomicLong()
        private val sessions = ConcurrentHashMap<SessionId, Session>()

        /**
         * A session for one run of a covered operation in the repository at [root] (relative key paths are resolved
         * against it), whose prompts [ui] answers; [onCancel] is called when the user cancels a prompt, before ssh gets
         * "no answer". [SshAccess.Batch] if the askpass channel is unavailable. The first call installs the helper and
         * starts the server: file I/O, not on the FX thread. The caller closes the session when the run ends.
         *
         * [onCancel] must set the caller's own "the user cancelled" flag (besides cancelling the operation), and the
         * caller decides the outcome by that flag, never by the git result's `cancelled`: ssh fails at once on "no
         * answer", so git may exit 128 before the cancellation's poll stops it, and the result then says failed, not
         * cancelled.
         */
        fun open(
            root: Path,
            ui: SshPromptUi,
            onCancel: () -> Unit,
        ): SshAccess {
            val session = Session(SessionId(ids.incrementAndGet()), root, ui, onCancel)
            if (!session.register()) return SshAccess.Batch
            sessions[session.id] = session
            return session
        }

        /** How many sessions are open; closed ones are forgotten. */
        internal val openSessions: Int get() = sessions.size

        /** Closes every session. */
        override fun destroy() {
            sessions.values.forEach(Session::close)
        }

        internal inner class Session(
            val id: SessionId,
            private val root: Path,
            private val ui: SshPromptUi,
            private val onCancel: () -> Unit,
        ) : SshAccess.Prompting {
            /** The session's token on the channel; set before the session is handed out. */
            private lateinit var registration: AskpassChannel.Registration

            /** Takes a token on the channel for this session; `false` if the channel is unavailable. */
            fun register(): Boolean {
                registration = channel.register(::answer) ?: return false
                // Before the environment reaches git: the first prompt needs the session known.
                passphrases.opened(id)
                return true
            }

            override val environment: Map<String, String> get() = registration.environment

            @Volatile
            private var closed = false

            /** The connection threads waiting on [ui]; guarded by `this`. */
            private val waiting = HashSet<Thread>()

            fun answer(request: AskpassRequest): ByteArray? {
                val prompt = sshPrompt(request.prompt)
                log.log(Level.DEBUG) { "ssh asks: ${prompt.javaClass.simpleName}" }
                return when (prompt) {
                    is SshPrompt.UnknownHost -> {
                        val confirmed = asking { ui.confirmHost(HostKeyRequest(prompt.text)) } ?: return null
                        if (!confirmed.value) cancel()
                        if (closed || !confirmed.value) null else "yes".toByteArray()
                    }

                    is SshPrompt.KeyPassphrase -> {
                        val file = keyFile(root, prompt.keyFile) ?: return null
                        val asked = asking { passphrases.answer(id, request.sshPid, file, ui) } ?: return null
                        val passphrase = asked.value
                        if (passphrase == null) cancel()
                        try {
                            if (closed || passphrase == null) null else utf8(passphrase)
                        } finally {
                            passphrase?.fill('\u0000')
                        }
                    }

                    SshPrompt.Other -> {
                        null
                    }
                }
            }

            /** The user cancelled the prompt: the operation is cancelled, unless the session has ended anyway. */
            private fun cancel() {
                if (!closed) onCancel()
            }

            /**
             * What [ask] returned, while it blocked interruptible by [close]; `null` if the session was closed before,
             * or while it asked and [ask] gave up on the interrupt. The caller checks [closed] after.
             */
            private fun <T> asking(ask: () -> T): Answered<T>? {
                val thread = Thread.currentThread()
                synchronized(this) {
                    if (closed) return null
                    waiting += thread
                }
                return try {
                    Answered(ask())
                } catch (_: InterruptedException) {
                    null
                } finally {
                    synchronized(this) { waiting -= thread }
                }
            }

            override fun ended(success: Boolean) = passphrases.ended(id, success, ui)

            override fun close() {
                synchronized(this) {
                    if (closed) return
                    closed = true
                    waiting.forEach(Thread::interrupt)
                }
                registration.close()
                sessions.remove(id)
                passphrases.closed(id)
            }
        }

        private class Answered<T>(
            val value: T,
        )

        private companion object {
            val log: System.Logger = System.getLogger(SshPrompts::class.java.name)
        }
    }
