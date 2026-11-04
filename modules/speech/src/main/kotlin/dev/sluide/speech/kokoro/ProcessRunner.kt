package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.SpeechException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** What a finished (or timed out) process printed. */
internal class ProcessResult(
    val stdout: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
)

/** Runs one process with a text as its input; the seam that lets tests do without espeak-ng. */
internal fun interface ProcessRunner {
    /**
     * Blocks; kills the process after [timeout] and reports `timedOut`. Throws [InterruptedException] when interrupted
     * (the process is killed first) and [SpeechException] when the process cannot be started or used.
     */
    @Throws(InterruptedException::class, SpeechException::class)
    fun run(
        command: List<String>,
        stdin: String,
        timeout: Duration,
    ): ProcessResult

    companion object {
        /** Starts a real process: [stdin] as UTF-8 input, stdout read fully as UTF-8, stderr discarded. */
        val Default =
            ProcessRunner { command, stdin, timeout ->
                val process =
                    try {
                        ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    } catch (e: IOException) {
                        throw SpeechException("${command.first()} cannot be started: ${e.message}", e)
                    }
                var output = ByteArray(0)
                val reader =
                    Thread({ output = process.inputStream.readAllBytes() }, "espeak-output").apply { isDaemon = true }
                try {
                    reader.start()
                    // A process that exits before reading everything breaks the pipe; its exit code tells more.
                    val writeFailure =
                        try {
                            process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
                            null
                        } catch (e: IOException) {
                            e
                        }
                    if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(1, TimeUnit.SECONDS)
                        return@ProcessRunner ProcessResult("", -1, timedOut = true)
                    }
                    reader.join(2000)
                    if (reader.isAlive) {
                        throw SpeechException("${command.first()} failed: its output did not end")
                    }
                    if (writeFailure != null && process.exitValue() == 0) {
                        throw SpeechException(
                            "${command.first()} stopped reading its input: ${writeFailure.message}",
                            writeFailure,
                        )
                    }
                    ProcessResult(String(output, Charsets.UTF_8), process.exitValue())
                } catch (e: InterruptedException) {
                    process.destroyForcibly()
                    throw e
                } catch (e: SpeechException) {
                    process.destroyForcibly()
                    throw e
                }
            }
    }
}
