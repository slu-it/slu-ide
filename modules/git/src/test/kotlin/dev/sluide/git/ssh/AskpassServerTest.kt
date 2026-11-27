package dev.sluide.git.ssh

import dev.sluide.git.askpass.Askpass
import dev.sluide.git.askpass.AskpassRequest
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The server in the test JVM, asked by the helper's logic ([Askpass.ask]) or by hand. */
class AskpassServerTest {
    @TempDir
    lateinit var folder: Path

    /** Bounded: a server that never replies fails the test instead of hanging it. */
    private fun ask(
        server: AskpassServer,
        prompt: String = "prompt",
    ): Pair<Int, ByteArray> {
        val output = ByteArrayOutputStream()
        val variables = mapOf("SLUIDE_ASKPASS_SOCKET" to server.socket.toString(), "SLUIDE_ASKPASS_TOKEN" to "t")
        val thread = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val exit =
                thread
                    .submit<Int> {
                        Askpass.ask(
                            arrayOf(prompt),
                            variables,
                            7L,
                            output,
                        )
                    }.get(10, TimeUnit.SECONDS)
            return exit to output.toByteArray()
        } finally {
            // Interrupted, a helper still waiting for its reply ends (an interruptible channel).
            thread.shutdownNow()
        }
    }

    @Test
    fun `hands each request to the handler and sends its answer, then zeroes it`() {
        val requests = CopyOnWriteArrayList<AskpassRequest>()
        val answers = CopyOnWriteArrayList<ByteArray>()
        AskpassServer
            .start(folder, SHORT) { request ->
                requests += request
                "yes".toByteArray().also { answers += it }
            }.use { server ->
                assertEquals(
                    0 to "yes\n".toByteArray().toList(),
                    ask(server, "Are you sure?").let {
                        it.first to
                            it.second.toList()
                    },
                )

                assertEquals(listOf("t" to "Are you sure?"), requests.map { it.token to it.prompt })
                assertEquals(7L, requests.single().sshPid)
                assertContentEquals(ByteArray(3), answers.single(), "zeroed after it was sent")
            }
    }

    @Test
    fun `no answer from the handler, and bytes that are no request, get no answer`() {
        val handled = CopyOnWriteArrayList<AskpassRequest>()
        AskpassServer
            .start(folder, SHORT) {
                handled += it
                null
            }.use { server ->
                assertEquals(1, ask(server).first)

                val reply =
                    SocketChannel.open(UnixDomainSocketAddress.of(server.socket)).use { channel ->
                        channel.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
                        channel.shutdownOutput()
                        Channels.newInputStream(channel).readAllBytes()
                    }

                assertContentEquals(byteArrayOf(0), reply, "\"none\", not a closed connection")
                assertEquals(1, handled.size, "only the request")
            }
    }

    @Test
    fun `closing stops accepting and removes the socket and its folder`() {
        val server = AskpassServer.start(folder, SHORT) { null }

        server.close()

        server.acceptor.join(5_000)
        assertFalse(server.acceptor.isAlive, "the accept loop ended")
        assertFalse(server.socket.parent.exists())
    }

    @Test
    fun `the socket goes into the short folder when its path could exceed 100 bytes`() {
        // Below /tmp, which is short on every OS (the temporary folder of macOS is not).
        val base = Files.createTempDirectory(SHORT, "ap")
        try {
            val short = base.resolve("short").createDirectories()
            // A folder name of up to 20 digits and "/socket" follow: "/sluide-askpass-" + 20 + "/socket" is 43 bytes.
            val fitting = base.resolve("f".repeat(100 - 43 - base.toString().length - 1)).createDirectories()
            val tooLong = base.resolve("g".repeat(100 - 43 - base.toString().length)).createDirectories()

            AskpassServer.start(fitting, short) { null }.use { assertEquals(fitting, it.socket.parent.parent) }
            AskpassServer.start(tooLong, short) { null }.use { assertEquals(short, it.socket.parent.parent) }
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a socket that cannot be bound fails the start and leaves no folder`() {
        // Too long for a socket path anywhere (Linux 108, macOS 104 bytes), also in the short folder.
        val long = folder.resolve("h".repeat(120)).createDirectories()

        assertFailsWith<IOException> { AskpassServer.start(long, long) { null } }

        assertEquals(emptyList(), Files.list(long).use { it.toList() })
    }
}

/** Where the tests' sockets go when the temporary folder is too long for one (macOS). */
internal val SHORT: Path = Path.of("/tmp")
