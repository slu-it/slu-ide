package dev.sluide.languageservers.process

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.languageservers.ServerCommand
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val log = System.getLogger("dev.sluide.languageservers.process.ServerProcess")

/** Longest standard error line logged; servers print stack traces and JSON dumps there. */
internal const val STDERR_LINE_CHARS = 500

/** Standard error lines logged per process; the rest is read and dropped so that the pipe never blocks. */
internal const val STDERR_LINES = 2_000

/**
 * Starts servers as OS processes: an argument list through [ProcessBuilder], never a shell, standard input and output
 * as the LSP stream, standard error to the log at INFO (ADR 0060). Every process is listed in [LiveServerProcesses]
 * until it ends.
 */
internal object OsServerStarter : ServerStarter {
    override fun start(
        name: String,
        command: ServerCommand,
        root: Path,
    ): ServerProcess {
        val directory = command.workingDirectory ?: root
        val arguments = listOf(command.executable.toString()) + command.arguments
        val builder = ProcessBuilder(arguments).directory(directory.toFile())
        val environment = builder.environment()
        for ((key, value) in command.environment) {
            if (value == null) environment.remove(key) else environment[key] = value
        }
        val started = System.nanoTime()
        val process = builder.start()
        val handle = process.toHandle()
        LiveServerProcesses.add(handle)
        log.log(
            System.Logger.Level.INFO,
            "lsp $name start ${arguments.joinToString(" ").take(300)} (in $directory), pid ${process.pid()}",
        )
        NamedThreads.virtual("sluide-lsp-$name-stderr") { logLines(name, process.errorStream) }
        return OsServerProcess(name, process, started)
    }

    private fun logLines(
        name: String,
        errors: InputStream,
    ) {
        var count = 0
        try {
            errors.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    count++
                    if (count <= STDERR_LINES) {
                        log.log(System.Logger.Level.INFO, "lsp $name stderr: ${line.take(STDERR_LINE_CHARS)}")
                    } else if (count == STDERR_LINES + 1) {
                        log.log(System.Logger.Level.INFO, "lsp $name stderr: further lines not logged")
                    }
                }
            }
        } catch (_: IOException) {
            // The process was killed while its standard error was read: nothing more to log.
        }
    }
}

private class OsServerProcess(
    override val name: String,
    private val process: Process,
    private val started: Long,
) : ServerProcess {
    override val input: InputStream = process.inputStream
    override val output: OutputStream = process.outputStream

    override fun waitFor(): Int {
        val exit = process.waitFor()
        ended(exit)
        return exit
    }

    override fun waitFor(millis: Long): Boolean = process.waitFor(millis, TimeUnit.MILLISECONDS)

    override fun destroyTree() {
        destroyTree(process.toHandle())
        LiveServerProcesses.remove(process.toHandle())
    }

    private fun ended(exit: Int) {
        LiveServerProcesses.remove(process.toHandle())
        val millis = (System.nanoTime() - started) / 1_000_000
        log.log(System.Logger.Level.INFO, "lsp $name exit $exit after $millis ms")
    }
}
