package dev.sluide.secrets

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The key in the Secret Service through `secret-tool` (libsecret-tools; ADR 0122): an item with [label] and
 * [attributes], `secret-tool lookup`, `store --label` and `clear` run as [command], found on [path]. The key goes
 * through stdin and stdout as base64, never into the arguments or the environment. Exit 1 with empty stdout and
 * stderr is [KeyRead.Missing]; each call ends after [timeout] (an unlock prompt may be open). Without the command,
 * [unavailableReason] is "needs secret-tool". Tests pass their own attributes, or a fake command on their own [path].
 */
internal class SecretToolBackend(
    private val attributes: Map<String, String> = mapOf("application" to "sluide", "name" to "safe-storage"),
    private val label: String = "Thoreau Safe Storage",
    private val command: String = "secret-tool",
    private val timeout: Duration = Duration.ofSeconds(120),
    private val path: String? = System.getenv("PATH"),
) : KeyBackend {
    override val storeName = "Secret Service (secret-tool)"

    private val executable: File? by lazy { findExecutable() }

    override fun unavailableReason(): String? = if (executable == null) "needs secret-tool" else null

    override fun read(): KeyRead {
        val outcome = run(listOf("lookup") + attributeArguments(), null)
        try {
            return when (outcome) {
                is Outcome.Failed -> KeyRead.Failed(outcome.reason)
                is Outcome.Done -> readResult(outcome)
            }
        } finally {
            (outcome as? Outcome.Done)?.stdout?.fill(0)
        }
    }

    private fun readResult(done: Outcome.Done): KeyRead {
        if (done.exit == 0) return decode(done.stdout)
        if (done.exit == 1 && done.stdout.isEmpty() && done.stderr.isBlank()) return KeyRead.Missing
        if (done.stderr.contains("dismiss", ignoreCase = true) || done.stderr.contains("cancel", ignoreCase = true)) {
            return KeyRead.Denied("Secret Service access denied")
        }
        return KeyRead.Failed(failureReason(done))
    }

    private fun decode(stdout: ByteArray): KeyRead {
        val end = if (stdout.isNotEmpty() && stdout.last() == '\n'.code.toByte()) stdout.size - 1 else stdout.size
        val text = stdout.copyOf(end)
        try {
            val key = Base64.getDecoder().decode(text)
            if (key.isEmpty()) return KeyRead.Failed(UNREADABLE)
            return KeyRead.Found(key)
        } catch (_: IllegalArgumentException) {
            return KeyRead.Failed(UNREADABLE)
        } finally {
            text.fill(0)
        }
    }

    override fun create(key: ByteArray): KeyCreate {
        when (val existing = read()) {
            is KeyRead.Found -> {
                existing.key.fill(0)
                return KeyCreate.AlreadyExists
            }

            is KeyRead.Denied -> {
                return KeyCreate.Failed(existing.reason)
            }

            is KeyRead.Failed -> {
                return KeyCreate.Failed(existing.reason)
            }

            KeyRead.Missing -> {
                Unit
            }
        }
        val input = Base64.getEncoder().encode(key)
        try {
            return when (val outcome = run(listOf("store", "--label=$label") + attributeArguments(), input)) {
                is Outcome.Failed -> {
                    KeyCreate.Failed(outcome.reason)
                }

                is Outcome.Done -> {
                    outcome.stdout.fill(0)
                    if (outcome.exit == 0) KeyCreate.Created else KeyCreate.Failed(failureReason(outcome))
                }
            }
        } finally {
            input.fill(0)
        }
    }

    override fun delete(): Boolean =
        when (val outcome = run(listOf("clear") + attributeArguments(), null)) {
            is Outcome.Failed -> {
                false
            }

            is Outcome.Done -> {
                outcome.stdout.fill(0)
                outcome.exit == 0
            }
        }

    private fun attributeArguments() = attributes.flatMap { listOf(it.key, it.value) }

    private fun failureReason(done: Outcome.Done): String {
        val line =
            done.stderr
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
        return if (line == null) "secret-tool failed (exit ${done.exit})" else line.take(MAX_REASON)
    }

    private fun findExecutable(): File? =
        path
            ?.split(File.pathSeparator)
            ?.filter { it.isNotEmpty() }
            ?.map { File(it, command) }
            ?.firstOrNull { it.isFile && it.canExecute() }
            ?.absoluteFile

    private sealed interface Outcome {
        class Done(
            val exit: Int,
            val stdout: ByteArray,
            val stderr: String,
        ) : Outcome

        class Failed(
            val reason: String,
        ) : Outcome
    }

    private fun run(
        arguments: List<String>,
        stdin: ByteArray?,
    ): Outcome {
        val tool = executable ?: return Outcome.Failed("needs secret-tool")
        try {
            val process = ProcessBuilder(listOf(tool.path) + arguments).start()
            val out = Collector(process.inputStream)
            val err = Collector(process.errorStream)
            out.start()
            err.start()
            try {
                process.outputStream.use { if (stdin != null) it.write(stdin) }
            } catch (_: IOException) {
                // the tool may exit without reading; its exit code tells
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                out.abandon()
                err.abandon()
                return Outcome.Failed("the Secret Service did not answer")
            }
            out.join(READER_JOIN_MS)
            err.join(READER_JOIN_MS)
            return Outcome.Done(process.exitValue(), out.bytes(), String(err.bytes(), Charsets.UTF_8))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Outcome.Failed("interrupted")
        } catch (e: Exception) {
            return Outcome.Failed(e.message?.take(MAX_REASON) ?: "secret-tool failed")
        }
    }

    /** Reads a stream to its end on its own thread, so neither pipe fills up while the other is waited on. */
    private class Collector(
        private val stream: InputStream,
    ) : Thread("secret-tool-output") {
        @Volatile private var data = ByteArray(0)

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                data = stream.readAllBytes()
            } catch (_: IOException) {
                // closed on abandon
            }
        }

        fun bytes(): ByteArray = data

        fun abandon() {
            try {
                stream.close()
            } catch (_: IOException) {
                // nothing left to release
            }
        }
    }

    private companion object {
        const val UNREADABLE = "unreadable key in the Secret Service"
        const val MAX_REASON = 200
        const val READER_JOIN_MS = 5_000L
    }
}
