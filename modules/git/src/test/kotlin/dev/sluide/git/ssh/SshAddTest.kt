package dev.sluide.git.ssh

import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SshAdd] with a stand-in `ssh-add` script that asks its askpass as OpenSSH's does (spike P0 of ADR 0194): first
 * `Enter passphrase for <path>: `, then, as after a wrong passphrase, the recorded `Bad passphrase, try again for k1: `
 * (ssh-add asks again without limit). The real helper and server run underneath.
 */
class SshAddTest {
    @TempDir
    lateinit var folder: Path

    private val channel by lazy { testChannel(folder) }
    private val record by lazy { folder.resolve("record").createDirectories() }
    private val key get() = folder.resolve("keys/my key")

    @AfterEach
    fun stop() {
        channel.destroy()
    }

    /** A script that runs [body] and nothing before it (no helper start): for what the test must do at once. */
    private fun rawScript(body: String): String {
        val file = folder.resolve("fake-ssh-add")
        file.writeText("#!/bin/sh\n$body\n")
        file.toFile().setExecutable(true)
        return file.toString()
    }

    /** A script that records what it got and asks its askpass twice, then runs [rest]; its path. */
    private fun script(rest: String): String {
        val file = folder.resolve("fake-ssh-add")
        record.resolve("bad-prompt").writeText(recordedPrompt("ssh-add-bad-passphrase.txt"))
        file.writeText(
            "#!/bin/sh\n" +
                "printf '%s\\n' \"\$@\" > '$record/args'\n" +
                "cat > '$record/stdin'\n" +
                "echo \"\$FAKE_MARK \$SSH_ASKPASS_REQUIRE\" > '$record/environment'\n" +
                "\"\$SSH_ASKPASS\" \"Enter passphrase for \$2: \" > '$record/out1'\n" +
                "echo \$? > '$record/exit1'\n" +
                "\"\$SSH_ASKPASS\" \"\$(cat '$record/bad-prompt')\" > '$record/out2'\n" +
                "echo \$? > '$record/exit2'\n" +
                rest +
                "\n",
        )
        file.toFile().setExecutable(true)
        return file.toString()
    }

    private fun add(
        script: String,
        timeout: Duration = Duration.ofSeconds(20),
        passphrase: String = "päss 'w\"rd",
    ) = SshAdd(channel, mapOf("FAKE_MARK" to "from git's environment"), script, timeout)
        .add(key, passphrase.toCharArray())

    @Test
    fun `the first prompt gets the passphrase and the second none, in the helper's environment`() {
        assertNull(add(script("exit 0")))

        assertContentEquals("päss 'w\"rd\n".toByteArray(), (record.resolve("out1")).readBytes())
        assertEquals("0", record.resolve("exit1").readText().trim())
        assertEquals("1", record.resolve("exit2").readText().trim())
        assertEquals("", record.resolve("out2").readText())
        assertEquals("from git's environment force", record.resolve("environment").readText().trim())
    }

    @Test
    fun `ssh-add gets the end of options marker and the key path, and no standard input`() {
        add(script("exit 0"))

        assertEquals(
            listOf("--", key.toString()),
            record
                .resolve("args")
                .readText()
                .trimEnd()
                .split("\n"),
        )
        assertEquals("", record.resolve("stdin").readText())
    }

    @Test
    fun `a failure gives ssh-add's message`() {
        val message = add(script("echo 'Error loading key: bad format' >&2; exit 1"))

        assertEquals("Error loading key: bad format", message)
    }

    @Test
    fun `a failure without a message names the exit status`() {
        assertEquals("ssh-add exited with status 3.", add(script("exit 3")))
    }

    @Test
    fun `the message is cut at 8 KiB`() {
        val message = add(script("head -c 300000 /dev/zero | tr '\\0' 'x' >&2; exit 1"))
        // Up to 8 KiB are read; a JVM note about JAVA_TOOL_OPTIONS (some environments) takes a share, then is dropped.
        assertTrue(message!!.length in 6000..8192, "cut: ${message.length}")
    }

    @Test
    fun `a hanging ssh-add is stopped after the timeout and reported`() {
        val message = add(rawScript("exec sleep 60"), timeout = Duration.ofSeconds(1))

        assertEquals("ssh-add did not finish within 1 s.", message)
    }

    @Test
    fun `a message that a left-behind process keeps the error pipe open for is still read`() {
        // The child holds stderr for 1 s after ssh-add ended; the script waits 0.3 s so that the reader is blocked in
        // read (docs/testing.md, "Output held open by a left-behind process").
        val message = add(rawScript("echo 'Bad passphrase' >&2; (sleep 1) & sleep 0.3; exit 1"))

        assertEquals("Bad passphrase", message)
    }

    @Test
    fun `a process that keeps the error pipe open for long does not hold the result back`() {
        // The child holds stderr for 5 s; the message cannot be read before that, and ssh-add's result is not
        // held for it: after the 2 s grace the exit status is reported.
        val message = add(rawScript("echo 'Bad passphrase' >&2; (sleep 5) & sleep 0.3; exit 1"))

        assertEquals("ssh-add exited with status 1.", message)
    }

    @Test
    fun `an ssh-add that is not there is reported`() {
        assertEquals("ssh-add was not found.", add(folder.resolve("no-such-ssh-add").toString()))
    }

    @Test
    fun `without the askpass channel nothing is run`() {
        val down = AskpassChannel(folder, SHORT) { throw IOException("no helper") }

        val message = SshAdd(down, emptyMap(), script("exit 0")).add(key, "x".toCharArray())

        assertEquals("Thoreau's askpass helper is not available.", message)
        assertTrue(!record.resolve("args").exists(), "ssh-add was not started")
        down.destroy()
    }

    @Test
    fun `the token is unregistered after the run`() {
        add(script("exit 0"))

        assertEquals(0, channel.registered)
    }

    @Test
    fun `a stopped ssh-add that ignores the first signal is killed`() {
        val pidFile = record.resolve("pid")
        val message =
            add(
                rawScript("echo \$\$ > '$pidFile'; trap '' TERM; while true; do sleep 0.1; done"),
                timeout = Duration.ofSeconds(1),
            )

        assertEquals("ssh-add did not finish within 1 s.", message)
        val pid = pidFile.readText().trim().toLong()
        val alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        assertFalse(alive, "ssh-add is gone")
    }

    @Test
    fun `a timed-out ssh-add is stopped politely first and given a grace to finish`() {
        val term = record.resolve("term")
        val message =
            add(
                rawScript("trap \"sleep 0.5; echo stopped > '$term'; exit 0\" TERM; while true; do sleep 0.1; done"),
                timeout = Duration.ofSeconds(1),
            )

        assertEquals("ssh-add did not finish within 1 s.", message)
        assertTrue(term.exists(), "its stop handler ran to its end before any forced kill")
    }

    @Test
    fun `what ssh-add writes to its standard output is discarded`() {
        // A pipe nobody reads fills at 64 KiB, and ssh-add would block there.
        val message = add(script("head -c 300000 /dev/zero; exit 0"), timeout = Duration.ofSeconds(10))

        assertNull(message)
    }

    @Test
    fun `the log names the outcome and the exception's class, never the passphrase or a path`() {
        LogCapture(SshAdd::class.java.name).use { log ->
            add(script("exit 0"), passphrase = "hunter2")
            add(folder.resolve("no-such-ssh-add").toString(), passphrase = "hunter2")
            add(rawScript("exec sleep 60"), timeout = Duration.ofSeconds(1), passphrase = "hunter2")

            assertEquals(3, log.messages().size, "${log.messages()}")
            assertEquals("ssh-add ended: timeout", log.messages()[2])
            assertEquals("ssh-add ended: 0", log.messages()[0])
            assertTrue("java.io.IOException" in log.messages()[1], log.messages()[1])
            assertTrue(log.messages().none { "hunter2" in it || folder.toString() in it })
        }
    }
}
