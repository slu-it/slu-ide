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
 * Without the command, [unavailableReason] is "needs secret-tool". After the tool ended, each output reader gets
 * [readerJoin] (at least 1 ms: `join(0)` would wait for ever) to finish: a process that outlives it and keeps the
 * pipe open is a failed call. Whatever an abandoned call's readers collected is zeroed, also when they finish later.
 * Tests pass their own attributes, or a fake command on their own [path].
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
    private val readerJoin: Duration = Duration.ofSeconds(5),
    /** Called with every array that held key material or tool output, right after it was zeroed; for tests. */
    private val onZeroed: (ByteArray) -> Unit = {},
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
            (outcome as? Outcome.Done)?.stdout?.let(::zero)
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
            zero(text)
        }
    }

    override fun create(key: ByteArray): KeyCreate {
        when (val existing = read()) {
            is KeyRead.Found -> {
                zero(existing.key)
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
                    zero(outcome.stdout)
                    if (outcome.exit == 0) KeyCreate.Created else KeyCreate.Failed(failureReason(outcome))
                }
            }
        } finally {
            zero(input)
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
                zero(search.stdout)
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
                zero(outcome.stdout)
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
                zero(search.stdout)
                search.exit == 0 && empty
            }
        }

    private fun zero(array: ByteArray) {
        array.fill(0)
        onZeroed(array)
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
        var out: Collector? = null
        var err: Collector? = null
        try {
            val started = ProcessBuilder(listOf(tool.path) + arguments).start()
            process = started
            val outReader = Collector(started.inputStream, ::zero).also { out = it }
            val errReader = Collector(started.errorStream, ::zero).also { err = it }
            outReader.start()
            errReader.start()
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
                outReader.abandon()
                errReader.abandon()
                return Outcome.Failed("the Secret Service did not answer")
            }
            outReader.join(readerJoin.toMillis().coerceAtLeast(1))
            errReader.join(readerJoin.toMillis().coerceAtLeast(1))
            val stdout = outReader.bytes()
            val stderr = errReader.bytes()
            if (stdout == null || stderr == null) {
                kill(started)
                outReader.abandon()
                errReader.abandon()
                return Outcome.Failed("secret-tool output could not be read")
            }
            return Outcome.Done(started.exitValue(), stdout, String(stderr, Charsets.UTF_8))
        } catch (e: InterruptedException) {
            process?.let { kill(it) }
            out?.abandon()
            err?.abandon()
            Thread.currentThread().interrupt()
            return Outcome.Failed("interrupted")
        } catch (e: Exception) {
            process?.let { kill(it) }
            out?.abandon()
            err?.abandon()
            return Outcome.Failed(e.message?.take(MAX_REASON) ?: "secret-tool failed")
        }
    }

    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /**
     * Reads a stream to its end on its own thread, so neither pipe fills up while the other is waited on. [bytes] is
     * `null` while the thread runs or when reading threw: that is never to be taken for empty output. After [abandon]
     * what was or is read is handed to [zero] instead of kept, so a key the tool printed does not stay in memory.
     */
    private class Collector(
        private val stream: InputStream,
        private val zero: (ByteArray) -> Unit,
    ) : Thread("secret-tool-output") {
        @Volatile private var data: ByteArray? = null
        private var abandoned = false

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                val read = stream.readAllBytes()
                synchronized(this) {
                    if (abandoned) zero(read) else data = read
                }
            } catch (_: IOException) {
                // data stays null
            }
        }

        fun bytes(): ByteArray? = if (isAlive) null else data

        fun abandon() {
            synchronized(this) {
                abandoned = true
                data?.let(zero)
                data = null
            }
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
    }
}
