package dev.sluide.git.ssh

import dev.sluide.git.askpass.AskpassRequest
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.lang.System.Logger.Level
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The askpass channel underneath [SshPrompts] and [SshPassphrases] (ADR 0194): the helper's files, the socket server
 * and the tokens registered on it. Each [registration][register] gets a random token and the environment that makes
 * ssh (or ssh-add) run the helper with it; the server hands a request to the registration with its token, compared with
 * `MessageDigest.isEqual`, and answers an unknown token with "none". The server starts on the first registration; if it
 * cannot start, the channel is unavailable (logged once) and registrations return `null`. A singleton; stopped when
 * Thoreau quits.
 */
@Component
internal class AskpassChannel(
    /** Where the socket's folder is created. */
    private val temporaryFolder: Path,
    /** Where it is created instead if the socket's path would be too long there (macOS). */
    private val shortFolder: Path,
    /** Accepts the server's next connection: [ServerSocketChannel.accept], replaced in tests. */
    private val accept: (ServerSocketChannel) -> SocketChannel = ServerSocketChannel::accept,
    /** Installs the helper; returns its wrapper script. */
    private val install: () -> Path,
) : DisposableBean {
    @Autowired
    constructor() : this(
        Path.of(System.getProperty("java.io.tmpdir")),
        Path.of("/tmp"),
        install = { AskpassFiles.install() },
    )

    private val random = SecureRandom()
    private val registrations = CopyOnWriteArrayList<Registration>()

    /** The server and the helper's script, once started; guarded by `this`. */
    private var running: Pair<AskpassServer, Path>? = null

    /** Whether the server is not started again: it could not start, or Thoreau quits. Guarded by `this`. */
    private var unavailable = false

    /**
     * Registers [handle] for the requests that come with a new token; `null` if the channel is unavailable. The first
     * call installs the helper and starts the server: file I/O, not on the FX thread. [handle] runs on a connection
     * thread and returns the answer's bytes (zeroed after they were sent) or `null` for "none".
     */
    fun register(handle: (AskpassRequest) -> ByteArray?): Registration? {
        val (server, script) = started() ?: return null
        val token = ByteArray(32).also(random::nextBytes).let(HexFormat.of()::formatHex)
        val environment =
            mapOf(
                "SSH_ASKPASS" to script.toString(),
                "SSH_ASKPASS_REQUIRE" to "force",
                "SLUIDE_ASKPASS_SOCKET" to server.socket.toString(),
                "SLUIDE_ASKPASS_TOKEN" to token,
            )
        return Registration(token.toByteArray(), environment, handle).also { registrations += it }
    }

    /**
     * A token for one ssh-add run: it answers the first prompt that starts with `Enter passphrase for ` with a copy of
     * [answer], and every other or later prompt "none" (ssh-add asks again without limit after a wrong passphrase).
     * `null` if the channel is unavailable. Closing it zeroes the copy and unregisters the token.
     */
    fun oneShot(answer: CharArray): OneShot? {
        val copy = answer.copyOf()
        val used = AtomicBoolean()
        val registration =
            register { request ->
                if (request.prompt.startsWith(PASSPHRASE_PROMPT) && used.compareAndSet(false, true)) {
                    utf8(copy).also { copy.fill('\u0000') }
                } else {
                    null
                }
            }
        if (registration == null) copy.fill('\u0000')
        return registration?.let { OneShot(it, copy) }
    }

    /** The running server and the helper's script, started on the first call; `null` if it cannot start. */
    private fun started(): Pair<AskpassServer, Path>? =
        synchronized(this) {
            running?.let { return it }
            if (unavailable) return null
            try {
                val script = install()
                val server = AskpassServer.start(temporaryFolder, shortFolder, accept, ::failed, ::answer)
                Pair(server, script).also { running = it }
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
        return registrations.firstOrNull { MessageDigest.isEqual(it.token, token) }?.let { it.handle(request) }
    }

    /** How many tokens are registered. */
    internal val registered: Int get() = registrations.size

    /** The server could not accept and has stopped: the channel is unavailable from now on. */
    private fun failed() {
        synchronized(this) {
            unavailable = true
            running = null
        }
    }

    /** Stops the server and removes its socket; no channel is started again. */
    override fun destroy() {
        val server =
            synchronized(this) {
                unavailable = true
                running?.first.also { running = null }
            }
        server?.close()
    }

    /** A token on the channel: [environment] makes ssh ask through it; [close] unregisters it. */
    inner class Registration internal constructor(
        internal val token: ByteArray,
        val environment: Map<String, String>,
        internal val handle: (AskpassRequest) -> ByteArray?,
    ) : AutoCloseable {
        override fun close() {
            registrations.remove(this)
        }
    }

    /** One ssh-add run's token ([oneShot]): [environment] for ssh-add; [close] zeroes the answer and unregisters. */
    class OneShot internal constructor(
        private val registration: Registration,
        /** The copy of the passphrase; zeroed once it was sent, and on close. */
        internal val answer: CharArray,
    ) : AutoCloseable {
        val environment: Map<String, String> get() = registration.environment

        override fun close() {
            answer.fill('\u0000')
            registration.close()
        }
    }

    private companion object {
        val log: System.Logger = System.getLogger(AskpassChannel::class.java.name)

        /** The start of ssh-add's passphrase prompt (`Enter passphrase for k1: `, OpenSSH 10.0). */
        const val PASSPHRASE_PROMPT = "Enter passphrase for "
    }
}

/**
 * [chars] as UTF-8, written into a buffer sized for the worst case so that no copies are left behind; an unpaired
 * surrogate becomes `?`. The buffer is zeroed.
 */
internal fun utf8(chars: CharArray): ByteArray {
    val encoder =
        Charsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val buffer = ByteBuffer.allocate((chars.size * encoder.maxBytesPerChar()).toInt())
    encoder.encode(CharBuffer.wrap(chars), buffer, true)
    encoder.flush(buffer)
    return buffer.array().copyOf(buffer.position()).also { buffer.array().fill(0) }
}
