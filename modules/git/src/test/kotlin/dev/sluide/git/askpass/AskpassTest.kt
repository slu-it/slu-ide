package dev.sluide.git.askpass

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The helper's logic in the test JVM, against a stand-in server on a Unix domain socket. */
class AskpassTest {
    @TempDir
    lateinit var folder: Path

    private val socket by lazy { folder.resolve("s") }
    private val server by lazy {
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
    }
    private val environment by lazy {
        mapOf("SLUIDE_ASKPASS_SOCKET" to socket.toString(), "SLUIDE_ASKPASS_TOKEN" to "c0ffee")
    }
    private val written = ByteArrayOutputStream()

    // Buffered, so that the answer reaches ssh only when the helper flushes it.
    private val output = BufferedOutputStream(written, 64 * 1024)

    // Threads of their own: the common pool may have a single thread on a small runner.
    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    @AfterEach
    fun closeServer() {
        server.close()
        threads.shutdownNow()
    }

    /** Accepts one connection, reads the request to its end, then writes [reply] (or closes without one). */
    private fun serve(reply: ByteArray?): CompletableFuture<ByteArray> {
        val listening = server
        return CompletableFuture.supplyAsync({
            listening.accept().use { connection ->
                val request = Channels.newInputStream(connection).readAllBytes()
                reply?.let { connection.write(ByteBuffer.wrap(it)) }
                request
            }
        }, threads)
    }

    /** Bounded: a helper waiting for a reply that never comes fails the test instead of hanging it. */
    private fun ask(
        args: Array<String> = arrayOf("Enter passphrase for key 'k1': "),
        variables: Map<String, String> = environment,
    ) = CompletableFuture
        .supplyAsync(
            { Askpass.ask(args, variables, 4242L, output) },
            threads,
        ).get(10, TimeUnit.SECONDS)

    @Test
    fun `the parent pid is the process that started the helper`() {
        assertEquals(
            ProcessHandle
                .current()
                .parent()
                .get()
                .pid(),
            Askpass.parentPid(),
        )
    }

    @Test
    fun `prints the answer and a line break and exits 0, after sending the prompt with token and ssh's pid`() {
        val answer = "päss w'o\"rd \$HOME \\".toByteArray()
        val request = serve(AskpassProtocol.encodeReply(answer))

        val exit = ask()

        assertEquals(0, exit)
        assertContentEquals(answer + '\n'.code.toByte(), written.toByteArray())
        val sent = assertNotNull(AskpassProtocol.decodeRequest(request.get(5, TimeUnit.SECONDS)))
        assertEquals("c0ffee", sent.token)
        assertEquals(4242L, sent.sshPid)
        assertEquals("", sent.promptVariable)
        assertEquals("Enter passphrase for key 'k1': ", sent.prompt)
    }

    @Test
    fun `passes SSH_ASKPASS_PROMPT on when ssh sets it`() {
        val request = serve(AskpassProtocol.encodeReply(null))

        ask(variables = environment + ("SSH_ASKPASS_PROMPT" to "confirm"))

        assertEquals("confirm", AskpassProtocol.decodeRequest(request.get(5, TimeUnit.SECONDS))?.promptVariable)
    }

    @Test
    fun `no answer exits 1 and prints nothing`() {
        serve(AskpassProtocol.encodeReply(null))

        assertEquals(1, ask())
        assertEquals(0, written.size())
    }

    @Test
    fun `a server that closes without a reply, or replies with no reply, exits 1`() {
        serve(null)
        assertEquals(1, ask())

        serve(byteArrayOf(1, 0, 0, 0, 9, 'y'.code.toByte()))
        assertEquals(1, ask())
        assertEquals(0, written.size())
    }

    @Test
    fun `exits 1 without a server at the socket`() {
        assertEquals(1, ask(variables = environment + ("SLUIDE_ASKPASS_SOCKET" to folder.resolve("none").toString())))
    }

    @Test
    fun `exits 1 without connecting when the argument or a variable is missing, or the prompt is too long`() {
        // A connection made all the same is counted before it is closed, so the helper's read ends after the count.
        val connections = AtomicInteger()
        val listening = server
        val accepting =
            Thread.ofVirtual().start {
                try {
                    while (true) listening.accept().use { connections.incrementAndGet() }
                } catch (_: IOException) {
                    // closed after the test
                }
            }

        assertEquals(1, ask(args = emptyArray()))
        assertEquals(1, ask(variables = environment - "SLUIDE_ASKPASS_SOCKET"))
        assertEquals(1, ask(variables = environment - "SLUIDE_ASKPASS_TOKEN"))
        assertEquals(1, ask(args = arrayOf("x".repeat(AskpassProtocol.MAX_BYTES))))

        server.close()
        accepting.join(5_000)
        assertEquals(0, connections.get(), "no connection was made")
        assertEquals(0, written.size())
    }
}
