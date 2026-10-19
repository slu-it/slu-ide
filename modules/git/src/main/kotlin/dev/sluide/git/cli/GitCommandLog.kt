package dev.sluide.git.cli

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private val log = System.getLogger(GitCli::class.java.name)

private const val FX_THREAD = "JavaFX Application Thread"
private const val MAX_ARGUMENTS = 300
private const val MAX_MESSAGE = 500

/** How much of the standard error of a read is kept, for the log only (ADR 0060). */
internal const val STDERR_KEEP = 4096

/** Where the lines of [GitCommandLog] go; replaced in tests. */
internal object GitCommandLogSink {
    @Volatile
    var sink: (System.Logger.Level, String, Throwable?) -> Unit = { level, message, error ->
        if (error == null) log.log(level, message) else log.log(level, message, error)
    }

    /** Logging must never break a git call: whatever the sink or the formatting throws is dropped. */
    inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // Nothing sensible left to do for a failing log.
        }
    }
}

/** Numbers git commands process-wide (`git#42`) and writes their start and end to the log (ADR 0060). */
internal object GitCommandLog {
    private val counter = AtomicLong()

    fun start(
        arguments: List<String>,
        directory: Path?,
        inputSize: Int?,
        write: Boolean,
    ): Entry {
        val id = counter.incrementAndGet()
        val entry =
            try {
                Entry(id, truncate(arguments.joinToString(" "), MAX_ARGUMENTS), write)
            } catch (_: RuntimeException) {
                Entry(id, "?", write)
            }
        GitCommandLogSink.safely {
            val thread = Thread.currentThread()
            val onFx = thread.name == FX_THREAD
            val where = directory?.let { " (in $it)" } ?: ""
            val stdin = inputSize?.let { " (stdin $it bytes)" } ?: ""
            val line = "${entry.name} start [${threadLabel(thread)}] ${entry.arguments}$where$stdin"
            if (onFx) {
                GitCommandLogSink.sink(System.Logger.Level.WARNING, "$line on the JavaFX thread", null)
            } else {
                GitCommandLogSink.sink(System.Logger.Level.INFO, line, null)
            }
        }
        return entry
    }

    /** Copies the first [STDERR_KEEP] bytes of [stream] into [into] and discards the rest, so the pipe never fills. */
    fun keepStart(
        stream: InputStream,
        into: ByteArrayOutputStream,
    ) {
        val buffer = ByteArray(8192)
        var kept = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return
            val take = minOf(read, STDERR_KEEP - kept)
            if (take > 0) {
                synchronized(into) { into.write(buffer, 0, take) }
                kept += take
            }
        }
    }

    /** The name of [thread], or for an unnamed one (a virtual thread) its description. */
    internal fun threadLabel(thread: Thread): String = thread.name.ifEmpty { thread.toString() }

    internal fun truncate(
        text: String,
        max: Int,
    ): String = if (text.length <= max) text else text.take(max) + "…"

    internal fun size(bytes: Int): String =
        if (bytes < 1024) "$bytes bytes" else String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)

    /** The message of git as one short line: first lines, newlines shown as ` | `. */
    internal fun message(text: String): String =
        truncate(
            text
                .trim()
                .lines()
                .filter { it.isNotBlank() }
                .joinToString(" | ") { it.trim() },
            MAX_MESSAGE,
        )

    class Entry(
        val id: Long,
        val arguments: String,
        private val write: Boolean,
    ) {
        private val startedAt = System.nanoTime()
        val name get() = "git#$id"
        private val millis get() = (System.nanoTime() - startedAt) / 1_000_000

        private fun warn(
            what: String,
            error: Throwable? = null,
        ) = log(System.Logger.Level.WARNING, what, error)

        private fun log(
            level: System.Logger.Level,
            what: String,
            error: Throwable? = null,
        ) = GitCommandLogSink.safely {
            GitCommandLogSink.sink(level, "$name $what: $arguments (after $millis ms)", error)
        }

        fun startFailed(error: Throwable) = warn("could not be started", error)

        fun timedOut(timeout: kotlin.time.Duration) = warn("took longer than $timeout")

        /**
         * A write the user stopped is a warning; a read stopped is routine, e.g. a diff overtaken by a newer request
         * (ADR 0062).
         */
        fun cancelled() = if (write) warn("cancelled") else log(System.Logger.Level.INFO, "cancelled")

        /** A write is a warning; a read is routine, e.g. stopped as its window closes. */
        fun interrupted() = if (write) warn("interrupted") else log(System.Logger.Level.INFO, "interrupted")

        /** Logs the end of a command that ran to its exit; [errors] are the standard error and [output] the output. */
        fun exited(
            exit: Int,
            output: ByteArray,
            errors: ByteArray,
            complete: Boolean,
            overflowed: Boolean,
        ) = GitCommandLogSink.safely {
            val stderr = errors.toString(Charsets.UTF_8)
            var line = "$name exit $exit in $millis ms, ${size(output.size)}"
            if (!write && exit != 0 && stderr.isNotBlank()) line += " stderr: ${message(stderr)}"
            GitCommandLogSink.sink(System.Logger.Level.INFO, line, null)
            if (overflowed) warn("wrote more output than allowed")
            if (!complete && !overflowed) warn("output was not read to the end")
            if (write && exit != 0) {
                val said = stderr.ifBlank { output.toString(Charsets.UTF_8) }
                warn("failed with exit $exit" + if (said.isBlank()) "" else ": ${message(said)}")
            }
        }
    }
}
