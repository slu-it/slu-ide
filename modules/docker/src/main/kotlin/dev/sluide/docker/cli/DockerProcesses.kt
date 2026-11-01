package dev.sluide.docker.cli

import dev.sluide.backgroundtasks.NamedThreads
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

private val log = System.getLogger("dev.sluide.docker.cli.DockerProcesses")

/** How much of standard error is kept for messages and the log. */
private const val ERROR_BYTES = 4096

/** How much of standard error [DockerProcesses.run] keeps with `onErrors`: compose warns once per use of a variable. */
internal const val CAPTURED_ERROR_BYTES = 64 * 1024

/** How long a finished command waits for the rest of its output. */
private const val DRAIN_MILLIS = 5_000L

/** How many of the last lines of merged output a failure message shows. */
private const val MESSAGE_LINES = 5

private val commandNumbers = AtomicLong()

/** Marks the end of the progress lines of [DockerProcesses.run]. */
private val END_OF_OUTPUT = Any()

/** Whether a command reads (logged at DEBUG, failures at INFO) or changes something (INFO, failures at WARNING). */
internal enum class CommandKind { READ, WRITE }

/**
 * Runs [executable] with [environment] directly, without a shell (ADR 0117). Every command gets a process-wide number
 * for the log (`docker#7 start …`, `docker#7 exit 0 in 35 ms`), like git's commands (ADR 0060).
 */
internal class DockerProcesses(
    private val executable: Path,
    private val environment: Map<String, String>,
) {
    private val program = executable.fileName?.toString() ?: executable.toString()

    /**
     * Runs the CLI with [arguments] to its end and returns its standard output. With [onLine], standard error is
     * merged into it and each line goes to [onLine] on the calling thread as it comes (progress). Waits at most
     * [timeout] ([Duration.INFINITE]: no limit). Interrupting the calling thread (a cancelled task) stops the wait,
     * also while waiting for progress. When the call does not end normally (timeout, interrupt, [onLine] throws), the
     * CLI is killed.
     *
     * With [workingDir] the CLI runs there, with [extraEnvironment] laid over the environment. With [onErrors] (not
     * with [onLine]) standard error of a successful run, up to its first 64 KB and cut at a line end, is given to it.
     *
     * [display] is how the arguments are written to the log, e.g. with secrets masked.
     *
     * @throws DockerException when the CLI cannot start, exits non-zero, takes too long or the thread is interrupted;
     *   what [onLine] throws is passed on.
     */
    fun run(
        arguments: List<String>,
        kind: CommandKind,
        timeout: Duration,
        onLine: ((String) -> Unit)? = null,
        workingDir: Path? = null,
        extraEnvironment: Map<String, String> = emptyMap(),
        onErrors: ((String) -> Unit)? = null,
        display: List<String> = arguments,
    ): String {
        val errorLimit = if (onErrors != null) CAPTURED_ERROR_BYTES else ERROR_BYTES
        val number = commandNumbers.incrementAndGet()
        val name = "$program#$number"
        val shown = display.joinToString(" ").take(300)
        log.log(level(kind), "$name start [${Thread.currentThread().name}] $shown")
        val started = System.nanoTime()
        val process = start(arguments, mergeErrors = onLine != null, name, workingDir, extraEnvironment)
        val errors = ByteArrayOutputStream()
        val readsErrors = onLine == null
        val errorReader =
            if (readsErrors) {
                NamedThreads.virtual(
                    "$name-stderr",
                ) { keep(process.errorStream, errors, errorLimit) }
            } else {
                null
            }
        val output = StringBuilder()
        val lastLines = ArrayDeque<String>()
        val progress = if (onLine != null) LinkedBlockingQueue<Any>() else null
        val outputReader =
            NamedThreads.virtual("$name-stdout") {
                if (progress == null) {
                    val text = process.inputStream.bufferedReader().use { it.readText() }
                    synchronized(output) { output.append(text) }
                } else {
                    try {
                        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(progress::put) }
                    } catch (e: IOException) {
                        progress.put(e)
                    }
                    progress.put(END_OF_OUTPUT)
                }
            }
        var ended = false
        try {
            if (progress != null && onLine != null) {
                // Progress goes to the caller's thread as it comes; waiting for it can be interrupted, which kills
                // the CLI. The time limit counts from the end of the output.
                while (true) {
                    val item = progress.take()
                    if (item === END_OF_OUTPUT) break
                    if (item is IOException) throw item
                    val line = item as String
                    lastLines.addLast(line)
                    if (lastLines.size > MESSAGE_LINES) lastLines.removeFirst()
                    synchronized(output) { output.append(line).append('\n') }
                    onLine(line)
                }
            }
            val finished =
                if (timeout.isInfinite()) {
                    process.waitFor()
                    true
                } else {
                    process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                }
            if (!finished) {
                log.log(System.Logger.Level.WARNING, "$name timed out after $timeout: $shown")
                throw DockerException("$program ${arguments.firstOrNull() ?: ""} did not finish within $timeout")
            }
            outputReader.join(DRAIN_MILLIS)
            errorReader?.join(DRAIN_MILLIS)
            ended = true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            log.log(System.Logger.Level.INFO, "$name interrupted: $shown")
            throw DockerException("$program ${arguments.firstOrNull() ?: ""} was interrupted")
        } catch (e: IOException) {
            throw DockerException("Cannot read the output of $program: ${e.message}", cause = e)
        } finally {
            // Timed out, interrupted, unreadable, or onLine threw: the CLI must not live on.
            if (!ended) kill(process)
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        val exit = process.exitValue()
        val text = synchronized(output) { output.toString() }
        if (exit != 0) {
            val message =
                (if (onLine == null) errorText(errors) else lastLines.joinToString("\n"))
                    .trim()
                    .ifEmpty { "$program $shown exited with code $exit" }
            val failureLevel = if (kind == CommandKind.READ) System.Logger.Level.INFO else System.Logger.Level.WARNING
            log.log(failureLevel, "$name exit $exit in $millis ms: $shown stderr: ${oneLine(message)}")
            throw DockerException(message, exit)
        }
        log.log(level(kind), "$name exit 0 in $millis ms, ${text.length} chars")
        if (onErrors != null && onLine == null) onErrors(wholeLines(errorText(errors), errors.size() >= errorLimit))
        return text
    }

    /**
     * Starts the CLI with [arguments] as a stream: [consume] reads its standard output (with standard error merged if
     * [mergeErrors]) on a reader thread of its own until the end, and asks its second argument before handing on each
     * item: `false` once the handle was closed, so that what was still buffered is dropped. At the end [onEnd] gets
     * `null` for exit code 0, else a [DockerException] with the CLI's message, unless the handle was closed first.
     * Closing kills the CLI; a callback that runs at that moment may still finish, none starts afterwards.
     *
     * @throws DockerException if the CLI cannot be started.
     */
    fun stream(
        arguments: List<String>,
        mergeErrors: Boolean,
        consume: (Reader, open: () -> Boolean) -> Unit,
        onEnd: (Throwable?) -> Unit,
    ): AutoCloseable {
        val number = commandNumbers.incrementAndGet()
        val name = "$program#$number"
        val shown = arguments.joinToString(" ").take(300)
        log.log(System.Logger.Level.INFO, "$name stream start $shown")
        val started = System.nanoTime()
        val process = start(arguments, mergeErrors, name, null, emptyMap())
        // Set once, by whoever comes first: close() or the end of the stream; only that one acts.
        val done = AtomicBoolean()
        val open = { !done.get() }
        val errors = ByteArrayOutputStream()
        val errorReader =
            if (mergeErrors) {
                null
            } else {
                NamedThreads.virtual(
                    "$name-stderr",
                ) { keep(process.errorStream, errors, ERROR_BYTES) }
            }
        // A platform thread: the stream may read for as long as the window is open.
        Thread.ofPlatform().daemon().name("$name-stream").start {
            var failure: Throwable? = null
            try {
                process.inputStream.bufferedReader().use { reader -> consume(reader, open) }
            } catch (e: IOException) {
                if (open()) failure = e
            } catch (t: Throwable) {
                if (open()) {
                    log.log(System.Logger.Level.ERROR, "$name stream reader failed", t)
                    failure = t
                }
                kill(process)
            }
            val exit =
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    kill(process)
                    -1
                }
            errorReader?.join(DRAIN_MILLIS)
            val millis = (System.nanoTime() - started) / 1_000_000
            if (!done.compareAndSet(false, true)) {
                log.log(System.Logger.Level.INFO, "$name stream closed after $millis ms")
                return@start
            }
            val message = errorText(errors).trim().ifEmpty { "$program $shown exited with code $exit" }
            log.log(
                System.Logger.Level.INFO,
                "$name stream exit $exit after $millis ms${if (exit != 0) ": ${oneLine(message)}" else ""}",
            )
            onEnd(failure ?: if (exit == 0) null else DockerException(message, exit))
        }
        return AutoCloseable {
            if (done.compareAndSet(false, true)) kill(process)
        }
    }

    private fun start(
        arguments: List<String>,
        mergeErrors: Boolean,
        name: String,
        workingDir: Path?,
        extraEnvironment: Map<String, String>,
    ): Process =
        try {
            ProcessBuilder(listOf(executable.toString()) + arguments)
                .redirectErrorStream(mergeErrors)
                .redirectInput(ProcessBuilder.Redirect.from(NULL_FILE))
                .apply {
                    environment().clear()
                    environment().putAll(this@DockerProcesses.environment)
                    environment().putAll(extraEnvironment)
                    workingDir?.let { directory(it.toFile()) }
                }.start()
        } catch (e: IllegalArgumentException) {
            // ProcessBuilder refuses environment names with '=' and values with NUL.
            throw DockerException("Invalid environment for $executable: ${e.message}", cause = e)
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "$name cannot start $executable: ${e.message}")
            throw DockerException("Cannot run $executable: ${e.message}", cause = e)
        }

    private fun level(kind: CommandKind) =
        if (kind == CommandKind.READ) System.Logger.Level.DEBUG else System.Logger.Level.INFO

    private companion object {
        val NULL_FILE = java.io.File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")
    }
}

/** [text] without its last, probably cut line if [capped]. */
private fun wholeLines(
    text: String,
    capped: Boolean,
): String = if (capped) text.substring(0, text.lastIndexOf('\n') + 1) else text

/** Reads [input] to its end, keeping the first [limit] bytes in [target]. */
private fun keep(
    input: InputStream,
    target: ByteArrayOutputStream,
    limit: Int,
) {
    try {
        input.use {
            val buffer = ByteArray(1024)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                synchronized(
                    target,
                ) { target.write(buffer, 0, minOf(read, (limit - target.size()).coerceAtLeast(0))) }
            }
        }
    } catch (_: IOException) {
        // The process is gone.
    }
}

private fun errorText(errors: ByteArrayOutputStream) = synchronized(errors) { errors.toString(Charsets.UTF_8) }

private fun oneLine(text: String) = text.take(500).replace("\n", " | ")

/** Kills [process] and what it started, the descendants first, as they are lost with it. */
private fun kill(process: Process) {
    process.descendants().forEach { it.destroyForcibly() }
    process.destroyForcibly()
}
