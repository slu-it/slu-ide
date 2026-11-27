package dev.sluide.git.ssh

import dev.sluide.git.askpass.AskpassRequest
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.lang.System.Logger.Level
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lets ssh ask the user through Thoreau's own dialogs during a covered git call (ADR 0194): each run of such an
 * operation [opens][open] a session, whose [environment][SshAccess.Prompting.environment] makes ssh run the askpass
 * helper, which relays each prompt over the server's socket. Prompts are told apart by their text ([sshPrompt]): an
 * unknown host goes to [SshPromptUi.confirmHost], a key's passphrase to [SshPassphrases], anything else gets no answer.
 * A singleton: the server starts on the first [open] and stops when Thoreau quits.
 */
@Component
class SshPrompts internal constructor(
    private val passphrases: SshPassphrases,
    /** Where the socket's folder is created. */
    private val temporaryFolder: Path,
    /** Where it is created instead if the socket's path would be too long there (macOS). */
    private val shortFolder: Path,
    /** Installs the helper; returns its wrapper script. */
    private val install: () -> Path,
) : DisposableBean {
    @Autowired
    internal constructor(passphrases: SshPassphrases) : this(
        passphrases,
        Path.of(System.getProperty("java.io.tmpdir")),
        Path.of("/tmp"),
        { AskpassFiles.install() },
    )

    private val random = SecureRandom()
    private val ids = AtomicLong()
    private val sessions = ConcurrentHashMap<SessionId, Session>()

    /** The server and the helper's script, once started; guarded by `this`. */
    private var running: Pair<AskpassServer, Path>? = null

    /** Whether the server is not started again: it could not start, or Thoreau quits. Guarded by `this`. */
    private var unavailable = false

    /**
     * A session for one run of a covered operation in the repository at [root] (relative key paths are resolved
     * against it), whose prompts [ui] answers; [onCancel] is called when the user cancels a prompt, before ssh gets "no
     * answer". [SshAccess.Batch] if the server cannot start. The first call installs the helper and starts the server:
     * file I/O, not on the FX thread. The caller closes the session when the run ends.
     */
    fun open(
        root: Path,
        ui: SshPromptUi,
        onCancel: () -> Unit,
    ): SshAccess {
        val (server, script) = started() ?: return SshAccess.Batch
        val token = ByteArray(32).also(random::nextBytes).let(HexFormat.of()::formatHex)
        val environment =
            mapOf(
                "SSH_ASKPASS" to script.toString(),
                "SSH_ASKPASS_REQUIRE" to "force",
                "SLUIDE_ASKPASS_SOCKET" to server.socket.toString(),
                "SLUIDE_ASKPASS_TOKEN" to token,
            )
        val session = Session(SessionId(ids.incrementAndGet()), token.toByteArray(), environment, root, ui, onCancel)
        sessions[session.id] = session
        return session
    }

    /** The running server and the helper's script, started on the first call; `null` if it cannot start. */
    private fun started(): Pair<AskpassServer, Path>? =
        synchronized(this) {
            running?.let { return it }
            if (unavailable) return null
            try {
                val script = install()
                Pair(AskpassServer.start(temporaryFolder, shortFolder, ::answer), script).also { running = it }
            } catch (e: IOException) {
                unavailable(e)
            } catch (e: UnsupportedOperationException) {
                unavailable(e)
            }
        }

    /** Gives the server up after [e], logged once. */
    private fun unavailable(e: Exception): Nothing? {
        unavailable = true
        // The class only: the message names folders of the user.
        log.log(Level.WARNING, "The askpass server cannot start (${e.javaClass.name}); ssh runs in batch mode")
        return null
    }

    /** Answers [request] on its connection thread: the bytes for ssh, `null` for none (also for an unknown token). */
    private fun answer(request: AskpassRequest): ByteArray? {
        val token = request.token.toByteArray()
        return sessions.values.firstOrNull { MessageDigest.isEqual(it.token, token) }?.answer(request)
    }

    /** How many sessions are open; closed ones are forgotten. */
    internal val openSessions: Int get() = sessions.size

    /** Closes every session and stops the server, removing its socket. */
    override fun destroy() {
        val server =
            synchronized(this) {
                unavailable = true
                running?.first.also { running = null }
            }
        sessions.values.forEach(Session::close)
        server?.close()
    }

    internal inner class Session(
        val id: SessionId,
        val token: ByteArray,
        override val environment: Map<String, String>,
        private val root: Path,
        private val ui: SshPromptUi,
        private val onCancel: () -> Unit,
    ) : SshAccess.Prompting {
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
         * What [ask] returned, while it blocked interruptible by [close]; `null` if the session was closed before, or
         * while it asked and [ask] gave up on the interrupt. The caller checks [closed] after.
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

        override fun succeeded() = passphrases.succeeded(id)

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
                waiting.forEach(Thread::interrupt)
            }
            sessions.remove(id)
            passphrases.closed(id)
        }
    }

    private class Answered<T>(
        val value: T,
    )

    private companion object {
        val log: System.Logger = System.getLogger(SshPrompts::class.java.name)

        /** [chars] as UTF-8; an unpaired surrogate becomes `?`. The encoder's buffer is zeroed. */
        fun utf8(chars: CharArray): ByteArray {
            val encoder =
                Charsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val buffer = encoder.encode(CharBuffer.wrap(chars))
            return ByteArray(buffer.remaining()).also { buffer.get(it) }.also { buffer.array().fill(0) }
        }
    }
}
