package dev.sluide.git.ssh

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.git.askpass.AskpassProtocol
import dev.sluide.git.askpass.AskpassRequest
import java.io.IOException
import java.lang.System.Logger.Level
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The askpass helper's other end (ADR 0194): a Unix domain socket in a fresh folder only the user may enter, one
 * request per connection, each handled on a virtual thread of its own by [handle], which returns the answer's bytes
 * (zeroed here after they were sent) or `null` for "no answer".
 */
internal class AskpassServer private constructor(
    /** The socket's path, for `SLUIDE_ASKPASS_SOCKET`. */
    val socket: Path,
    private val channel: ServerSocketChannel,
    private val handle: (AskpassRequest) -> ByteArray?,
    /** Accepts the next connection: [ServerSocketChannel.accept], replaced in tests. */
    private val accept: (ServerSocketChannel) -> SocketChannel,
    /** Told when accepting failed and the server stopped. */
    private val onFailure: () -> Unit,
) : AutoCloseable {
    /** The thread accepting connections; it ends when the server is [closed][close]. */
    internal val acceptor: Thread = NamedThreads.virtual("sluide-git-askpass") { acceptAll() }

    private fun acceptAll() {
        try {
            while (true) {
                val connection = accept(channel)
                NamedThreads.virtual("sluide-git-askpass-prompt") { serve(connection) }
            }
        } catch (_: ClosedChannelException) {
            // Closed (also AsynchronousCloseException): Thoreau quits.
        } catch (e: IOException) {
            // E.g. out of file descriptors. Closing resets the connections waiting in the backlog: their helpers end
            // with no answer instead of waiting for an accept that never comes.
            log.log(Level.WARNING, "The askpass server stops (${e.javaClass.name}); ssh runs in batch mode")
            close()
            onFailure()
        }
    }

    private fun serve(connection: SocketChannel) {
        connection.use {
            try {
                // The helper closes its side after the request; more than a request may be is none.
                val bytes = Channels.newInputStream(connection).readNBytes(AskpassProtocol.MAX_BYTES + 1)
                val answer = AskpassProtocol.decodeRequest(bytes)?.let(handle)
                val reply = AskpassProtocol.encodeReply(answer)
                answer?.fill(0)
                val buffer = ByteBuffer.wrap(reply)
                try {
                    while (buffer.hasRemaining()) connection.write(buffer)
                } finally {
                    reply.fill(0)
                }
            } catch (_: IOException) {
                // The helper is gone (ssh or git was stopped): nobody waits for the reply.
            }
        }
    }

    /** Stops accepting and removes the socket and its folder; connections being served end on their own. */
    override fun close() {
        channel.close()
        Files.deleteIfExists(socket)
        Files.deleteIfExists(socket.parent)
    }

    companion object {
        /** The longest socket path in bytes: macOS allows 104 (`sun_path`), Linux 108; with a margin. */
        const val MAX_SOCKET_PATH = 100

        /**
         * Starts a server whose socket is in a new owner-only folder in [temporaryFolder], or in [shortFolder] if the
         * socket's path could be longer than [MAX_SOCKET_PATH] bytes there.
         *
         * @throws IOException if the folder cannot be created or the socket not bound
         */
        fun start(
            temporaryFolder: Path,
            shortFolder: Path,
            accept: (ServerSocketChannel) -> SocketChannel = ServerSocketChannel::accept,
            onFailure: () -> Unit = {},
            handle: (AskpassRequest) -> ByteArray?,
        ): AskpassServer {
            // The folder's name ends in a random number of up to 20 digits: its longest socket path decides.
            val longest = temporaryFolder.resolve(PREFIX + "9".repeat(20)).resolve(SOCKET)
            val parent = if (longest.toString().toByteArray().size > MAX_SOCKET_PATH) shortFolder else temporaryFolder
            val folder = privateFolder(parent)
            val socket = folder.resolve(SOCKET)
            val channel =
                try {
                    ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
                } catch (e: IOException) {
                    Files.deleteIfExists(folder)
                    throw e
                }
            return AskpassServer(socket, channel, handle, accept, onFailure)
        }

        private fun privateFolder(parent: Path): Path =
            Files.createTempDirectory(
                parent,
                PREFIX,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )

        private const val PREFIX = "sluide-askpass-"
        private const val SOCKET = "socket"

        private val log: System.Logger = System.getLogger(AskpassServer::class.java.name)
    }
}
