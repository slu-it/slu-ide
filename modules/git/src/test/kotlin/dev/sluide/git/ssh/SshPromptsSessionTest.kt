package dev.sluide.git.ssh

import dev.sluide.git.askpass.AskpassRequest
import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A session's routing and lifetime, asked directly in the test JVM, without the helper. */
class SshPromptsSessionTest {
    @TempDir
    lateinit var folder: Path

    private val root by lazy { folder.resolve("repo").createDirectories() }
    private val channel by lazy { testChannel(folder) }
    private val passphrases by lazy { SpyPassphrases(channel) }
    private val prompts by lazy { SshPrompts(channel, passphrases) }
    private val threads = Executors.newVirtualThreadPerTaskExecutor()
    private val cancels = AtomicInteger()

    @AfterEach
    fun stop() {
        prompts.destroy()
        channel.destroy()
        threads.shutdownNow()
    }

    private fun open(ui: SshPromptUi) =
        assertIs<SshPrompts.Session>(prompts.open(root, ui) { cancels.incrementAndGet() })

    private fun request(prompt: String) = AskpassRequest("ignored here", 99L, "", prompt)

    /** [session]'s answer to [prompt] on a thread of its own: closing the session there interrupts that thread. */
    private fun answerElsewhere(
        session: SshPrompts.Session,
        prompt: String,
    ): ByteArray? =
        CompletableFuture.supplyAsync({ session.answer(request(prompt)) }, threads).get(10, TimeUnit.SECONDS)

    private val hostPrompt = recordedPrompt("unknown-host.txt")
    private val keyPrompt = recordedPrompt("key-passphrase.txt")

    @Test
    fun `Cancel of the host prompt cancels the operation once and answers nothing`() {
        val session = open(FakeUi(host = { false }))

        assertNull(session.answer(request(hostPrompt)))
        assertEquals(1, cancels.get())
    }

    @Test
    fun `other prompts and a key path that is no path get no answer, without the UI or a cancel`() {
        val ui = FakeUi(passphrase = { PassphraseReply("x".toCharArray(), false, false) }, host = { true })
        val session = open(ui)

        assertNull(session.answer(request("user@host's password: ")))
        assertNull(session.answer(request("Enter passphrase for key 'k\u0000': ")))

        assertEquals(0, ui.calls)
        assertEquals(0, cancels.get())
    }

    @Test
    fun `a closed session answers nothing and asks nobody, and is forgotten`() {
        val ui = FakeUi(host = { true })
        val session = open(ui)
        assertEquals(1, prompts.openSessions)

        session.close()

        assertNull(session.answer(request(hostPrompt)))
        assertEquals(0, ui.calls)
        assertEquals(0, prompts.openSessions)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `closing while the UI waits interrupts it, and the prompt gets no answer without a cancel`(host: Boolean) {
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val waiting = {
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (e: InterruptedException) {
                interrupted.set(true)
                throw e
            }
        }
        val ui =
            FakeUi(
                passphrase = { waiting().let { PassphraseReply("x".toCharArray(), false, false) } },
                host = { waiting().let { true } },
            )
        val session = open(ui)

        val prompt = if (host) hostPrompt else keyPrompt
        val answering = CompletableFuture.supplyAsync({ session.answer(request(prompt)) }, threads)
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the UI was asked")
        session.close()

        assertNull(answering.get(10, TimeUnit.SECONDS))
        assertTrue(interrupted.get())
        assertEquals(0, cancels.get())
    }

    @Test
    fun `a session closed while the user answered gives no answer, zeroes the passphrase and cancels nothing`() {
        val session = AtomicReference<SshPrompts.Session>()
        val passphrase = "secret".toCharArray()
        var reply: PassphraseReply? = PassphraseReply(passphrase, false, false)
        var connect = true
        val ui =
            FakeUi(
                passphrase = { session.get().close().let { reply } },
                host = { session.get().close().let { connect } },
            )

        session.set(open(ui))
        assertNull(answerElsewhere(session.get(), hostPrompt), "Connect")
        session.set(open(ui))
        assertNull(answerElsewhere(session.get(), keyPrompt), "a passphrase")
        assertContentEquals(CharArray(6), passphrase)
        connect = false
        reply = null
        session.set(open(ui))
        assertNull(answerElsewhere(session.get(), hostPrompt), "Cancel of the host")
        session.set(open(ui))
        assertNull(answerElsewhere(session.get(), keyPrompt), "Cancel of the passphrase")

        assertEquals(4, ui.calls)
        assertEquals(0, cancels.get())
    }

    @Test
    fun `the prompt's kind is logged at debug, never its text or the answer`() {
        val session = open(FakeUi(passphrase = { PassphraseReply("s3cr3t".toCharArray(), false, false) }))

        LogCapture(SshPrompts::class.java.name).use { log ->
            session.answer(request(keyPrompt))

            assertEquals(listOf("ssh asks: KeyPassphrase"), log.messages())
        }
    }

    @Test
    fun `a passphrase is answered without a cancel`() {
        val session = open(FakeUi(passphrase = { PassphraseReply("s3cr3t".toCharArray(), false, false) }))

        assertContentEquals("s3cr3t".toByteArray(), session.answer(request(keyPrompt)))
        assertEquals(0, cancels.get())
    }
}
