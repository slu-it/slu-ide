package dev.sluide.git.ssh

import dev.sluide.git.askpass.Askpass
import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The channel's tokens, availability and one-shot answers, asked by the helper's logic in the test JVM. */
class AskpassChannelTest {
    @TempDir
    lateinit var folder: Path

    private val channel by lazy { testChannel(folder) }
    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    @AfterEach
    fun stop() {
        channel.destroy()
        threads.shutdownNow()
    }

    /** What the helper gets for [prompt] with [environment] (bounded): its exit value and output. */
    private fun ask(
        environment: Map<String, String>,
        prompt: String,
    ): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val exit =
            threads
                .submit<Int> {
                    Askpass.ask(
                        arrayOf(prompt),
                        environment,
                        1L,
                        output,
                    )
                }.get(10, TimeUnit.SECONDS)
        return exit to output.toString(Charsets.UTF_8)
    }

    @Test
    fun `a registration gets the requests with its token, an unknown or unregistered token gets none`() {
        val prompts = mutableListOf<String>()
        val registration =
            assertNotNull(
                channel.register {
                    prompts += it.prompt
                    "yes".toByteArray()
                },
            )
        val token = registration.environment.getValue("SLUIDE_ASKPASS_TOKEN")
        val wrong = token.dropLast(1) + (if (token.last() == '0') '1' else '0')

        assertEquals(0 to "yes\n", ask(registration.environment, "p"))
        assertEquals(1 to "", ask(registration.environment + ("SLUIDE_ASKPASS_TOKEN" to wrong), "q"))
        assertEquals(1, channel.registered)
        registration.close()
        assertEquals(1 to "", ask(registration.environment, "r"))

        assertEquals(listOf("p"), prompts)
        assertEquals(0, channel.registered)
    }

    @Test
    fun `a one-shot answers the first passphrase prompt with a copy, then none, and is gone when closed`() {
        val answer = "päss phrase".toCharArray()
        val shot = assertNotNull(channel.oneShot(answer))

        assertEquals(1 to "", ask(shot.environment, "Bad PIN: "), "another prompt first")
        assertEquals(0 to "päss phrase\n", ask(shot.environment, "Enter passphrase for k1: "))
        assertEquals(1 to "", ask(shot.environment, "Enter passphrase for k1: "), "ssh-add asks again")
        assertContentEquals("päss phrase".toCharArray(), answer, "the caller's array is its own")
        assertContentEquals(CharArray(answer.size), shot.answer, "the copy is zeroed once sent")

        shot.close()
        assertEquals(0, channel.registered)
    }

    @Test
    fun `closing an unused one-shot zeroes its copy`() {
        val shot = assertNotNull(channel.oneShot("secret".toCharArray()))

        shot.close()

        assertContentEquals(CharArray(6), shot.answer)
    }

    @Test
    fun `a one-shot answers a fake ssh-add once, through the real helper`() {
        val shot = assertNotNull(channel.oneShot("secret".toCharArray()))
        val bin = folder.resolve("bin").createDirectories()
        // As ssh-add does (OpenSSH 10.0, spike P0): the passphrase prompt, and after a wrong answer another one.
        bin.resolve("ssh-add").apply {
            writeText(
                "#!/bin/sh\n" +
                    "\"\$SSH_ASKPASS\" 'Enter passphrase for k1: ' > '${folder.resolve("first")}'\n" +
                    "echo \$? >> '${folder.resolve("exits")}'\n" +
                    "\"\$SSH_ASKPASS\" 'Bad passphrase, try again for k1: ' > '${folder.resolve("second")}'\n" +
                    "echo \$? >> '${folder.resolve("exits")}'\n",
            )
            toFile().setExecutable(true)
        }
        val environment = shot.environment + ("PATH" to "$bin:${System.getenv("PATH")}")

        // Found on the PATH given, as a shell finds it (ProcessBuilder would look in this JVM's PATH).
        val process =
            ProcessBuilder(
                "/bin/sh",
                "-c",
                "ssh-add -- k1",
            ).apply { environment().putAll(environment) }.start()

        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        assertEquals(
            listOf("0", "1"),
            folder
                .resolve("exits")
                .readText()
                .lines()
                .filter(String::isNotEmpty),
        )
        assertContentEquals("secret\n".toByteArray(), folder.resolve("first").readBytes())
        assertEquals(0, folder.resolve("second").readBytes().size)
        shot.close()
    }

    @Test
    fun `a channel that cannot start is unavailable, tried and logged once with the exception's class only`() {
        val file = folder.resolve("not-a-folder").also { it.writeText("") }
        val installs = AtomicInteger()
        val failing =
            AskpassChannel(file, file) {
                installs.incrementAndGet()
                AskpassFiles.install(folder.resolve("helper"))
            }

        LogCapture(AskpassChannel::class.java.name).use { log ->
            assertNull(failing.register { null })
            assertNull(failing.oneShot("x".toCharArray()))

            val line = log.messages().single()
            assertTrue(Regex("\\(java\\.nio\\.file\\.\\w*Exception\\)").containsMatchIn(line), line)
            assertFalse(file.toString() in line, line)
            assertEquals(1, installs.get())
        }
    }

    @Test
    fun `a helper that cannot be installed for want of POSIX permissions makes the channel unavailable`() {
        val unsupported = AskpassChannel(folder, SHORT) { throw UnsupportedOperationException() }

        LogCapture(AskpassChannel::class.java.name).use { log ->
            assertNull(unsupported.register { null })
            assertNull(unsupported.register { null })

            assertEquals(1, log.messages().count { "UnsupportedOperationException" in it })
        }
    }

    @Test
    fun `a server that can no longer accept makes the channel unavailable`() {
        val failing = CountDownLatch(1)
        val stopping =
            AskpassChannel(folder, SHORT, accept = {
                failing.await()
                throw IOException("Too many open files")
            }) { AskpassFiles.install(folder.resolve("helper")) }
        try {
            assertNotNull(stopping.register { null })

            failing.countDown()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (stopping.register { null } != null) {
                assertTrue(System.nanoTime() < deadline, "still available")
                Thread.sleep(10)
            }
        } finally {
            stopping.destroy()
        }
    }

    @Test
    fun `stopping removes the socket and makes the channel unavailable`() {
        val registration = assertNotNull(channel.register { null })
        val socket = Path.of(registration.environment.getValue("SLUIDE_ASKPASS_SOCKET"))

        channel.destroy()

        assertFalse(socket.parent.exists())
        assertNull(channel.register { null })
    }

    @Test
    fun `the bean installs the helper below the home folder and puts the socket into the temporary folder`() {
        val home = folder.resolve("home").createDirectories()
        // Short enough for a socket on every OS.
        val temporary = Files.createTempDirectory(SHORT, "t")
        val saved = System.getProperty("user.home") to System.getProperty("java.io.tmpdir")
        System.setProperty("user.home", home.toString())
        System.setProperty("java.io.tmpdir", temporary.toString())
        val bean =
            try {
                AskpassChannel()
            } finally {
                System.setProperty("java.io.tmpdir", saved.second)
            }
        try {
            val registration = assertNotNull(bean.register { null })

            val script = Path.of(registration.environment.getValue("SSH_ASKPASS"))
            assertEquals(home.resolve(".sluide/git/askpass"), script.parent.parent)
            assertTrue(script.exists())
            // The Linux wrapper decodes the prompt as UTF-8 whatever the locale.
            assertEquals(System.getProperty("os.name").startsWith("Linux"), "LC_ALL=C.UTF-8 " in script.readText())
            assertEquals(temporary, Path.of(registration.environment.getValue("SLUIDE_ASKPASS_SOCKET")).parent.parent)
        } finally {
            System.setProperty("user.home", saved.first)
            bean.destroy()
            temporary.toFile().deleteRecursively()
        }
    }

    @Test
    fun `utf8 encodes every character, an unpaired surrogate as a question mark`() {
        assertContentEquals("aä€😀".toByteArray(), utf8("aä€😀".toCharArray()))
        assertContentEquals("x?".toByteArray(), utf8(charArrayOf('x', '\ud83d')))
    }
}
