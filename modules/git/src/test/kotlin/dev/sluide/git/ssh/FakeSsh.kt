package dev.sluide.git.ssh

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * A stand-in `ssh` in [bin] that asks its askpass as OpenSSH does (checked with OpenSSH 10.0p2, spike P0 of ADR 0194:
 * one argument, the prompt text; the answer read from its standard output; any exit but 0 is no answer and ssh fails,
 * 255). It records the askpass's output and exit value and its own pid, then, with `FAKE_SSH_REPO` set, serves git's
 * fetch from that repository as ssh would reach the server's `git-upload-pack`.
 */
internal class FakeSsh(
    folder: Path,
) {
    val bin: Path = folder.resolve("fake-ssh-bin").createDirectories()
    private val prompt = folder.resolve("fake-ssh-prompt")
    private val output = folder.resolve("fake-ssh-output")
    private val exit = folder.resolve("fake-ssh-exit")
    private val pid = folder.resolve("fake-ssh-pid")

    init {
        bin.resolve("ssh").apply {
            writeText(
                "#!/bin/sh\n" +
                    "\"\$SSH_ASKPASS\" \"\$(cat '$prompt')\" > '$output'\n" +
                    "status=\$?\n" +
                    "echo \$\$ > '$pid'\n" +
                    // Written last: once it is there, the askpass has ended.
                    "echo \$status > '$exit'\n" +
                    "[ \$status = 0 ] || { echo 'Host key verification failed.' >&2; exit 255; }\n" +
                    "[ -n \"\$FAKE_SSH_REPO\" ] && exec git-upload-pack \"\$FAKE_SSH_REPO\"\n" +
                    "exit 0\n",
            )
            toFile().setExecutable(true)
        }
    }

    /** The prompt the next run passes to the askpass. */
    fun prompt(text: String) {
        prompt.writeText(text)
        exit.toFile().delete()
    }

    /** Starts the fake ssh with [environment] added to the test's, as git would with a session's. */
    fun start(environment: Map<String, String>): Process =
        ProcessBuilder(bin.resolve("ssh").toString())
            .apply { environment().putAll(environment) }
            .redirectErrorStream(true)
            .start()

    /** Runs the fake ssh to its end; its exit value. */
    fun run(environment: Map<String, String>): Int {
        val process = start(environment)
        process.inputStream.readAllBytes()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the fake ssh ended")
        return process.exitValue()
    }

    /** Whether the askpass of the last run has ended. */
    val askpassEnded: Boolean get() = exit.exists()

    /** The askpass's exit value. */
    val askpassExit: Int get() = exit.readText().trim().toInt()

    /** What the askpass wrote to ssh. */
    val answer: ByteArray get() = output.readBytes()

    /** The fake ssh's pid of the last run. */
    val sshPid: Long get() = pid.readText().trim().toLong()
}

/** A UI that records its calls and answers with [passphrase] and [host]. */
internal open class FakeUi(
    private val passphrase: () -> PassphraseReply? = { null },
    private val host: () -> Boolean = { false },
) : SshPromptUi {
    val passphraseRequests = CopyOnWriteArrayList<PassphraseRequest>()
    val hostRequests = CopyOnWriteArrayList<HostKeyRequest>()
    val threads = CopyOnWriteArrayList<Thread>()

    override fun askPassphrase(request: PassphraseRequest): PassphraseReply? {
        passphraseRequests += request
        threads += Thread.currentThread()
        return passphrase()
    }

    override fun confirmHost(request: HostKeyRequest): Boolean {
        hostRequests += request
        threads += Thread.currentThread()
        return host()
    }

    override fun notSaved(reason: String) = Unit

    override fun notAddedToAgent(message: String) = Unit

    val calls: Int get() = passphraseRequests.size + hostRequests.size
}
