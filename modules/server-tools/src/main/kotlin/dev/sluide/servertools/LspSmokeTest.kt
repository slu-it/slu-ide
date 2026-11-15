package dev.sluide.servertools

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.languageservers.ServerCommand
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** At most this much of the version command's output is read. */
private const val MAX_VERSION_OUTPUT = 4096

/** A server's answer to `initialize` larger than this is refused. */
private const val MAX_MESSAGE_BYTES = 1_000_000

/** The `initialize` request of the handshake: no folder, no capabilities; enough for a server to answer. */
private const val INITIALIZE =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}"""

/** The most messages read while waiting for the `initialize` answer (log lines, progress, server requests). */
private const val MAX_MESSAGES = 1000

/** How long a killed server and its children are waited for. */
private val KILL_WAIT = 5.seconds

/**
 * Checks that a language server's executable works before it is installed (ADR 0144, ADR 0159, ADR 0179):
 * `<executable> <versionArguments>` (skipped when null: no version command) must print a first line starting with
 * [versionPrefix] within [versionTimeout] and exit 0, and the server's [command] (executable, arguments, environment
 * and working directory, created if missing; null: it cannot be started) must answer an `initialize` request within
 * [handshakeTimeout].
 * The messages show the commands with [commandName] (`` `ty version` ``, `` `tsc --version` ``) and name the tool by
 * [name] ("not a TypeScript version"). [check] blocks the calling thread and starts the processes from an argument
 * list in the executable's folder (the server in its command's working directory if it has one). The version command
 * gets a closed standard input; the server gets the request and keeps its standard input open until the answer
 * arrives or the time is up (ty ends at the end of its input, sometimes
 * before its answer is written). Both are killed with their descendants at the end.
 */
class LspSmokeTest(
    private val name: String,
    private val versionArguments: List<String>?,
    private val versionPrefix: String,
    private val command: (executable: Path) -> ServerCommand?,
    private val commandName: String = name,
    private val versionTimeout: Duration = 10.seconds,
    private val handshakeTimeout: Duration = 5.seconds,
) {
    private val versionCommand = "`${(listOf(commandName) + versionArguments.orEmpty()).joinToString(" ")}`"

    /** Null when [executable] works, else the reason it does not, for the user. */
    fun check(executable: Path): String? =
        (if (versionArguments == null) null else version(executable, versionArguments)) ?: handshake(executable)

    private fun version(
        executable: Path,
        versionArguments: List<String>,
    ): String? {
        val process =
            try {
                ProcessBuilder(listOf(executable.toString()) + versionArguments)
                    .directory(executable.parent?.toFile())
                    .redirectErrorStream(true)
                    .start()
            } catch (e: IOException) {
                return "cannot start it: ${e.message}"
            }
        try {
            process.outputStream.close()
            val output = readOff(process.inputStream) { String(it.readNBytes(MAX_VERSION_OUTPUT), Charsets.UTF_8) }
            val text =
                try {
                    output.get(versionTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    return "$versionCommand did not answer within ${describe(versionTimeout)}"
                } catch (e: ExecutionException) {
                    return "cannot read the output of $versionCommand: ${reason(e)}"
                }
            if (!process.waitFor(versionTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                return "$versionCommand did not end within ${describe(versionTimeout)}"
            }
            val firstLine =
                text
                    .lineSequence()
                    .firstOrNull()
                    ?.trim()
                    .orEmpty()
            if (process.exitValue() != 0) return "$versionCommand ended with exit code ${process.exitValue()}"
            if (!firstLine.startsWith(versionPrefix)) {
                return "$versionCommand printed \"${firstLine.take(80)}\", not a $name version"
            }
            return null
        } finally {
            kill(process)
        }
    }

    private fun handshake(executable: Path): String? {
        val launch = command(executable) ?: return "$name cannot be started from $executable"
        val serverCommand = "`${(listOf(commandName) + launch.arguments).joinToString(" ")}`"
        val process =
            try {
                // A server that names a working directory of its own (jdtls's data folder) may not have one yet.
                launch.workingDirectory?.let(Files::createDirectories)
                ProcessBuilder(listOf(launch.executable.toString()) + launch.arguments)
                    .directory((launch.workingDirectory ?: executable.parent)?.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .also { builder ->
                        for ((key, value) in launch.environment) {
                            if (value == null) builder.environment().remove(key) else builder.environment()[key] = value
                        }
                    }.start()
            } catch (e: IOException) {
                return "cannot start $serverCommand: ${e.message}"
            }
        try {
            val body = INITIALIZE.toByteArray(StandardCharsets.UTF_8)
            val answer = readOff(process.inputStream, ::readAnswer)
            try {
                // Written and flushed, not closed: ty ends when its input ends, maybe before it wrote the answer.
                val input = process.outputStream
                input.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                input.write(body)
                input.flush()
            } catch (_: IOException) {
                // Ended before reading: the missing answer says so.
            }
            val message =
                try {
                    answer.get(handshakeTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    return "$serverCommand did not answer within ${describe(handshakeTimeout)}"
                } catch (e: ExecutionException) {
                    return "$serverCommand gave no answer: ${reason(e)}"
                }
            return if (message.path("result").isObject) null else "$serverCommand gave no initialize result"
        } finally {
            kill(process)
        }
    }

    private fun <T> readOff(
        stream: InputStream,
        read: (InputStream) -> T,
    ): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        NamedThreads.virtual("$commandName-smoke-test") {
            try {
                result.complete(read(stream))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    private fun reason(e: ExecutionException) = e.cause?.message ?: e.cause?.javaClass?.simpleName ?: "unknown"

    private fun kill(process: Process) {
        val descendants = process.descendants().toList()
        descendants.forEach { it.destroyForcibly() }
        process.destroyForcibly()
        // Waits (bounded) until they are gone, so that the caller can delete what they wrote.
        val deadline = System.nanoTime() + KILL_WAIT.inWholeNanoseconds
        try {
            for (handle in descendants + process.toHandle()) {
                val left = (deadline - System.nanoTime()).coerceAtLeast(0)
                try {
                    handle.onExit().get(left, TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    return
                } catch (_: ExecutionException) {
                    // Gone.
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            process.outputStream.close()
        } catch (_: IOException) {
            // Already closed, or the process is gone.
        }
    }

    internal companion object {
        /**
         * Reads one LSP message (headers up to an empty line, then `Content-Length` bytes) from [input].
         *
         * @throws IOException if the stream ends first or the headers are not LSP's.
         */
        fun readMessage(input: InputStream): String {
            var length: Int? = null
            while (true) {
                val line = readHeaderLine(input)
                if (line.isEmpty()) break
                val (header, value) = line.split(':', limit = 2).takeIf { it.size == 2 } ?: continue
                if (header.trim().equals("Content-Length", ignoreCase = true)) length = value.trim().toIntOrNull()
            }
            val size = length ?: throw IOException("no Content-Length")
            if (size !in 0..MAX_MESSAGE_BYTES) throw IOException("Content-Length $size")
            val body = input.readNBytes(size)
            if (body.size < size) throw IOException("the answer ended early")
            return String(body, StandardCharsets.UTF_8)
        }

        private fun readHeaderLine(input: InputStream): String {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("the output ended")
                if (b == '\n'.code) return line.toString().removeSuffix("\r")
                if (line.length > 1000) throw IOException("a header line is too long")
                line.append(b.toChar())
            }
        }

        /**
         * Reads messages from [input] until the answer to request 1 (with `id` 1 and no `method`), skipping log
         * lines, progress and the server's own requests before it.
         *
         * @throws IOException if the stream ends first, a message is not LSP's or JSON, or too many come first.
         */
        fun readAnswer(input: InputStream): JsonNode {
            repeat(MAX_MESSAGES) {
                val node =
                    try {
                        JsonMapper.shared().readTree(readMessage(input))
                    } catch (e: JacksonException) {
                        throw IOException("a message is not JSON", e)
                    }
                if (node.path("id").asInt(-1) == 1 && !node.has("method")) return node
            }
            throw IOException("no answer among the first $MAX_MESSAGES messages")
        }

        /** [duration] for a message: in milliseconds below a second, else in whole seconds. */
        fun describe(duration: Duration): String =
            if (duration < 1.seconds) "${duration.inWholeMilliseconds} ms" else "${duration.inWholeSeconds} s"
    }
}
