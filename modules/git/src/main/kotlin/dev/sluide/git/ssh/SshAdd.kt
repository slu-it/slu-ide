package dev.sluide.git.ssh

import dev.sluide.backgroundtasks.NamedThreads
import java.io.File
import java.io.IOException
import java.lang.System.Logger.Level
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Adds a key to the ssh agent with `ssh-add -- <key>` (ADR 0194), after the passphrase proved right. ssh-add asks for
 * the passphrase through the askpass helper: a [one-shot token][AskpassChannel.oneShot] answers its first prompt and
 * no later one, so a wrong passphrase ends the run instead of asking again. Blocks until ssh-add ends or [timeout]
 * passes; never on the FX thread. Never logs the passphrase; the outcome and an exception's class only.
 */
internal class SshAdd(
    private val channel: AskpassChannel,
    /** git's environment, which ssh-add shares with ssh: the agent socket. */
    private val environment: Map<String, String> = System.getenv(),
    private val command: String = "ssh-add",
    private val timeout: Duration = Duration.ofSeconds(30),
) {
    /** `null` if [keyFile] was added; otherwise what to tell the user. [passphrase] is the caller's, not zeroed here. */
    fun add(
        keyFile: Path,
        passphrase: CharArray,
    ): String? {
        val oneShot = channel.oneShot(passphrase) ?: return "Thoreau's askpass helper is not available."
        try {
            return run(keyFile, oneShot.environment)
        } finally {
            oneShot.close()
        }
    }

    private fun run(
        keyFile: Path,
        askpass: Map<String, String>,
    ): String? {
        val process =
            try {
                ProcessBuilder(command, "--", keyFile.toString())
                    .apply {
                        environment().putAll(this@SshAdd.environment)
                        environment().putAll(askpass)
                        redirectInput(File("/dev/null"))
                        redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    }.start()
            } catch (e: IOException) {
                log.log(Level.WARNING, "ssh-add cannot be started ({0})", e.javaClass.name)
                return "ssh-add was not found."
            }
        val errors = AtomicReference(ByteArray(0))
        val reader =
            NamedThreads.virtual("sluide-git-ssh-add-errors") {
                try {
                    // Closing the stream after 8 KiB ends a chatty ssh-add's writes (EPIPE) instead of blocking it.
                    process.errorStream.use { stream -> errors.set(stream.readNBytes(MAX_ERRORS)) }
                } catch (_: IOException) {
                    // Killed with the process: what was read stays.
                }
            }
        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(GRACE_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(TimeUnit.SECONDS.toMillis(GRACE_SECONDS))
        val exit = if (finished) process.exitValue() else null
        log.log(Level.INFO, "ssh-add ended: {0}", exit ?: "timeout")
        return when {
            exit == 0 -> null
            exit == null -> "ssh-add did not finish within ${timeout.seconds} s."
            else -> message(errors.get()).ifEmpty { "ssh-add exited with status $exit." }
        }
    }

    /** What ssh-add wrote to its standard error, trimmed (the helper's wrapper keeps the JVM's own notes out of it). */
    private fun message(errors: ByteArray): String = String(errors, Charsets.UTF_8).trim()

    private companion object {
        val log: System.Logger = System.getLogger(SshAdd::class.java.name)
        const val MAX_ERRORS = 8 * 1024
        const val GRACE_SECONDS = 2L
    }
}
