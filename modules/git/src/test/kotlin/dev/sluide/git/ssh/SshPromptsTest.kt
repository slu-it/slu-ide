package dev.sluide.git.ssh

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The askpass channel end to end without git: the real server, the real helper jar from the build on this JVM's java,
 * and a stand-in ssh ([FakeSsh]) that runs it with a session's environment.
 */
class SshPromptsTest {
    @TempDir
    lateinit var folder: Path

    private val root by lazy { folder.resolve("repo").createDirectories() }
    private val ssh by lazy { FakeSsh(folder) }
    private val channel by lazy { testChannel(folder) }
    private val passphrases by lazy { SpyPassphrases(channel) }
    private val prompts by lazy { SshPrompts(channel, passphrases) }

    /** What fails on a connection thread is logged there; a prompt must end in a reply, never in such a failure. */
    private val failures = LogCapture(NamedThreads::class.java.name)

    @AfterEach
    fun stop() {
        prompts.destroy()
        channel.destroy()
        failures.close()
        assertEquals(emptyList(), failures.messages(), "no connection thread failed")
    }

    private fun open(
        ui: SshPromptUi,
        onCancel: () -> Unit = {},
    ) = assertIs<SshAccess.Prompting>(prompts.open(root, ui, onCancel))

    @Test
    fun `a passphrase reaches ssh byte for byte, asked for the key in the repository, and the array is zeroed`() {
        val passphrase = "päss w'o\"rd \$HOME \\ `x` €".toCharArray()
        val ui = FakeUi(passphrase = { PassphraseReply(passphrase, save = false, addToAgent = false) })
        val session = open(ui)
        ssh.prompt(recordedPrompt("key-passphrase.txt"))

        val exit = ssh.run(session.environment)

        assertEquals(0, exit)
        assertEquals(0, ssh.askpassExit)
        assertContentEquals("päss w'o\"rd \$HOME \\ `x` €\n".toByteArray(), ssh.answer)
        assertEquals(
            listOf(PassphraseRequest(root.resolve("k1"), null, "not available yet", "not available yet")),
            ui.passphraseRequests,
        )
        assertTrue(passphrase.all { it == '\u0000' }, "zeroed after it was sent")
        assertEquals(listOf(ssh.sshPid), passphrases.connections, "the connection is the ssh process")
    }

    @Test
    fun `the prompts are asked on a virtual askpass thread`() {
        val ui = FakeUi(host = { true })
        ssh.prompt(recordedPrompt("unknown-host.txt"))

        ssh.run(open(ui).environment)

        val thread = ui.threads.single()
        assertEquals("sluide-git-askpass-prompt", thread.name)
        assertTrue(thread.isVirtual)
    }

    @Test
    fun `an unknown host shows ssh's text without the question and Connect answers yes`() {
        val cancelled = AtomicBoolean()
        val ui = FakeUi(host = { true })
        val session = open(ui) { cancelled.set(true) }
        ssh.prompt(recordedPrompt("unknown-host.txt"))

        val exit = ssh.run(session.environment)

        assertEquals(0, exit)
        assertContentEquals("yes\n".toByteArray(), ssh.answer)
        assertEquals(
            listOf(HostKeyRequest(recordedPrompt("unknown-host.txt").substringBeforeLast('\n'))),
            ui.hostRequests,
        )
        assertFalse(cancelled.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown-host.txt", "key-passphrase.txt"])
    fun `Cancel calls onCancel before ssh gets no answer`(prompt: String) {
        val askpassEndedAtCancel = CopyOnWriteArrayList<Boolean>()
        val ui = FakeUi()
        val session = open(ui) { askpassEndedAtCancel += ssh.askpassEnded }
        ssh.prompt(recordedPrompt(prompt))

        val exit = ssh.run(session.environment)

        assertEquals(255, exit)
        assertEquals(1, ssh.askpassExit)
        assertEquals(0, ssh.answer.size)
        assertEquals(listOf(false), askpassEndedAtCancel, "called once, while the askpass still waited")
        assertEquals(1, ui.calls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown-host-reprompt.txt", "ssh-add-passphrase.txt"])
    fun `other prompts get no answer, without the UI and without a cancel`(prompt: String) {
        val cancels = AtomicInteger()
        val ui = FakeUi(passphrase = { PassphraseReply("x".toCharArray(), false, false) }, host = { true })
        val session = open(ui) { cancels.incrementAndGet() }
        ssh.prompt(recordedPrompt(prompt))

        ssh.run(session.environment)

        assertEquals(1, ssh.askpassExit)
        assertEquals(0, ui.calls)
        assertEquals(0, cancels.get())
    }

    @Test
    fun `a wrong token gets no answer, without the UI`() {
        val ui = FakeUi(host = { true })
        val session = open(ui)
        ssh.prompt(recordedPrompt("unknown-host.txt"))

        val token = session.environment.getValue("SLUIDE_ASKPASS_TOKEN")
        val wrong = token.dropLast(1) + (if (token.last() == '0') '1' else '0')
        ssh.run(session.environment + ("SLUIDE_ASKPASS_TOKEN" to wrong))

        assertEquals(1, ssh.askpassExit)
        assertEquals(0, ui.calls)
    }

    @Test
    fun `closing the session interrupts the waiting UI, ssh gets no answer, and the session ends once`() {
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val cancels = AtomicInteger()
        val ui =
            FakeUi(host = {
                entered.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (e: InterruptedException) {
                    interrupted.set(true)
                    throw e
                }
                true
            })
        val session = open(ui) { cancels.incrementAndGet() }
        ssh.prompt(recordedPrompt("unknown-host.txt"))
        val process = ssh.start(session.environment)
        assertTrue(entered.await(20, TimeUnit.SECONDS), "the UI was asked")

        session.close()
        session.close()

        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        assertTrue(interrupted.get(), "the UI's thread was interrupted")
        assertEquals(1, ssh.askpassExit)
        assertEquals(0, cancels.get(), "no cancel: the operation ended anyway")
        assertEquals(1, passphrases.closed.size)
        // A closed session answers nothing more, without asking.
        ssh.prompt(recordedPrompt("unknown-host.txt"))
        ssh.run(session.environment)
        assertEquals(1, ssh.askpassExit)
        assertEquals(1, ui.calls)
    }

    @Test
    fun `two sessions at once each get their own prompts`() {
        val both = CountDownLatch(2)
        val waiting = {
            both.countDown()
            both.await(20, TimeUnit.SECONDS)
        }
        val connecting = FakeUi(host = { waiting().also { assertTrue(it) } })
        val refusing = FakeUi(host = { !waiting() })
        val cancelled = CopyOnWriteArrayList<String>()
        val first = open(connecting) { cancelled += "connecting" }
        val second = open(refusing) { cancelled += "refusing" }
        assertNotEquals(first.environment["SLUIDE_ASKPASS_TOKEN"], second.environment["SLUIDE_ASKPASS_TOKEN"])
        val otherSsh = FakeSsh(folder.resolve("other").createDirectories())
        ssh.prompt(recordedPrompt("unknown-host.txt"))
        otherSsh.prompt(recordedPrompt("unknown-host.txt"))

        val running =
            CompletableFuture.supplyAsync(
                { ssh.run(first.environment) },
                Executors.newVirtualThreadPerTaskExecutor(),
            )
        val otherExit = otherSsh.run(second.environment)

        assertEquals(0, running.get(25, TimeUnit.SECONDS))
        assertEquals(255, otherExit)
        assertContentEquals("yes\n".toByteArray(), ssh.answer)
        assertEquals(1, otherSsh.askpassExit)
        assertEquals(1, connecting.calls)
        assertEquals(1, refusing.calls)
        assertEquals(listOf("refusing"), cancelled)
    }

    @Test
    fun `the session's environment runs the installed helper over the server's socket with its own token`() {
        val session = open(FakeUi())

        val environment = session.environment

        assertEquals(
            setOf("SSH_ASKPASS", "SSH_ASKPASS_REQUIRE", "SLUIDE_ASKPASS_SOCKET", "SLUIDE_ASKPASS_TOKEN"),
            environment.keys,
        )
        assertEquals("force", environment["SSH_ASKPASS_REQUIRE"])
        assertEquals(AskpassFiles.install(folder.resolve("helper")).toString(), environment["SSH_ASKPASS"])
        val socket = Path.of(environment.getValue("SLUIDE_ASKPASS_SOCKET"))
        assertTrue(socket.exists())
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.parent)))
        assertTrue(Regex("[0-9a-f]{64}").matches(environment.getValue("SLUIDE_ASKPASS_TOKEN")))
        assertEquals(socket, Path.of(open(FakeUi()).environment.getValue("SLUIDE_ASKPASS_SOCKET")), "one server")
    }

    @Test
    fun `each end of a git process and the close reach the passphrases with the session and its UI`() {
        val ui = FakeUi()
        val first = open(ui)
        val second = open(FakeUi())

        first.ended(true)
        first.ended(false)
        second.close()

        assertEquals(listOf(true, false), passphrases.ended.map { it.success })
        assertEquals(listOf(ui, ui), passphrases.ended.map { it.ui })
        assertEquals(
            1,
            passphrases.ended
                .map { it.session }
                .distinct()
                .size,
        )
        assertNotEquals(passphrases.ended.first().session, passphrases.closed.single())
    }

    @Test
    fun `an unavailable channel leaves ssh in batch mode`() {
        val file = folder.resolve("not-a-folder").also { it.writeText("") }
        val unavailable = AskpassChannel(file, file) { AskpassFiles.install(folder.resolve("helper")) }

        assertEquals(SshAccess.Batch, SshPrompts(unavailable, passphrases).open(root, FakeUi()) {})
    }

    @Test
    fun `stopping closes the sessions and unregisters their tokens`() {
        open(FakeUi())
        open(FakeUi())

        prompts.destroy()

        assertEquals(2, passphrases.closed.size)
        assertEquals(0, prompts.openSessions)
        assertEquals(0, channel.registered)
    }
}
