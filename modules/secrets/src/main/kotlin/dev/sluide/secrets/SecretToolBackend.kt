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
 * through stdin and stdout as base64, never into the arguments or the environment. A dismissed unlock prompt prints
 * nothing, exactly like a missing item (exit 1, empty stdout and stderr), so such a lookup is followed by `search`,
 * which lists the item without unlocking it: an item found is [KeyRead.Denied] ("Secret Service locked"), nothing
 * found is [KeyRead.Missing], the only case that leads to a new key. `clear` of a locked item also exits 1 without
 * a message, so `delete` confirms with `search`. Each call ends after [timeout] (an unlock prompt may be open).
 * Without the command, [unavailableReason] is "needs secret-tool". Tests pass their own attributes, or a fake command
 * on their own [path].
 *
 * Accepted limit: two Thoreau processes that find the item missing at the same moment can both store a key, and the
 * later one replaces the first (no lock file; ADR 0122). The stdin copy of the base64 key inside the process stream
 * buffer cannot be zeroed; it is short-lived and holds the same data as the pipe.
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
        if (done.exit == 1 && done.stdout.isEmpty() && done.stderr.isBlank()) return missingOrLocked()
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

    /**
     * A lookup in a locked collection whose unlock failed looks like a missing item (exit 1, no output); `search`
     * lists the item without unlocking, so only an empty search is [KeyRead.Missing].
     */
    private fun missingOrLocked(): KeyRead =
        when (val search = run(listOf("search") + attributeArguments(), null)) {
            is Outcome.Failed -> {
                KeyRead.Failed(search.reason)
            }

            is Outcome.Done -> {
                val found = search.stdout.isNotEmpty()
                search.stdout.fill(0)
                when {
                    search.exit != 0 -> KeyRead.Failed(failureReason(search))
                    found -> KeyRead.Denied("Secret Service locked")
                    else -> KeyRead.Missing
                }
            }
        }

    override fun delete(): Boolean =
        when (val outcome = run(listOf("clear") + attributeArguments(), null)) {
            is Outcome.Failed -> {
                false
            }

            is Outcome.Done -> {
                outcome.stdout.fill(0)
                when {
                    outcome.exit == 0 -> true
                    outcome.exit == 1 && outcome.stderr.isBlank() -> nothingLeft()
                    else -> false
                }
            }
        }

    private fun nothingLeft(): Boolean =
        when (val search = run(listOf("search") + attributeArguments(), null)) {
            is Outcome.Failed -> {
                false
            }

            is Outcome.Done -> {
                val empty = search.stdout.isEmpty()
                search.stdout.fill(0)
                search.exit == 0 && empty
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
        var process: Process? = null
        try {
            val started = ProcessBuilder(listOf(tool.path) + arguments).start()
            process = started
            val out = Collector(started.inputStream)
            val err = Collector(started.errorStream)
            out.start()
            err.start()
            try {
                started.outputStream.use {
                    if (stdin != null) it.write(stdin)
                    it.flush()
                }
            } catch (_: IOException) {
                // the tool may exit without reading; its exit code tells
            }
            if (!started.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(started)
                out.abandon()
                err.abandon()
                return Outcome.Failed("the Secret Service did not answer")
            }
            out.join(READER_JOIN_MS)
            err.join(READER_JOIN_MS)
            val stdout = out.bytes()
            val stderr = err.bytes()
            if (stdout == null || stderr == null) {
                stdout?.fill(0)
                kill(started)
                out.abandon()
                err.abandon()
                return Outcome.Failed("secret-tool output could not be read")
            }
            return Outcome.Done(started.exitValue(), stdout, String(stderr, Charsets.UTF_8))
        } catch (e: InterruptedException) {
            process?.let { kill(it) }
            Thread.currentThread().interrupt()
            return Outcome.Failed("interrupted")
        } catch (e: Exception) {
            process?.let { kill(it) }
            return Outcome.Failed(e.message?.take(MAX_REASON) ?: "secret-tool failed")
        }
    }

    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /**
     * Reads a stream to its end on its own thread, so neither pipe fills up while the other is waited on. [bytes] is
     * `null` while the thread runs or when reading threw: that is never to be taken for empty output.
     */
    private class Collector(
        private val stream: InputStream,
    ) : Thread("secret-tool-output") {
        @Volatile private var data: ByteArray? = null

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                data = stream.readAllBytes()
            } catch (_: IOException) {
                // data stays null
            }
        }

        fun bytes(): ByteArray? = if (isAlive) null else data

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
