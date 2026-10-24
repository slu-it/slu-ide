package dev.sluide.terminal

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = System.getLogger("dev.sluide.terminal.LoginShellFinds")

/**
 * Whether the user's login shell, started with [environment], finds [program] (`command -v`), which is where
 * terminals and command runs start it. A login shell may take a while with a slow profile, so this blocks and must not
 * run on the FX thread; false if it takes longer than [timeout] (the shell and what its profile started are killed),
 * if the shell cannot be started, or if the thread is interrupted. [program] must be a plain program name.
 */
fun loginShellFinds(
    program: String,
    environment: Map<String, String>,
    timeout: Duration = 10.seconds,
): Boolean {
    val started = System.nanoTime()
    log.log(System.Logger.Level.INFO, "Checking whether $program is installed")
    val found = check(program, environment, timeout)
    val millis = (System.nanoTime() - started) / 1_000_000
    log.log(
        System.Logger.Level.INFO,
        "$program check finished in $millis ms: ${if (found) "installed" else "not installed"}",
    )
    return found
}

private fun check(
    program: String,
    environment: Map<String, String>,
    timeout: Duration,
): Boolean {
    val command = loginShellRunning("command -v $program", environment)
    val process =
        try {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(environment)
                }.start()
        } catch (e: IOException) {
            log.log(System.Logger.Level.DEBUG, "Cannot run ${command.first()}", e)
            return false
        }
    process.outputStream.close()
    try {
        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            stop(process)
            log.log(System.Logger.Level.WARNING, "${command.joinToString(" ")} took longer than $timeout")
            return false
        }
    } catch (_: InterruptedException) {
        stop(process)
        Thread.currentThread().interrupt()
        return false
    }
    return process.exitValue() == 0
}

/** Kills the shell and whatever its profile started; the descendants first, as they are lost with the shell. */
private fun stop(process: Process) {
    process.descendants().forEach { it.destroyForcibly() }
    process.destroyForcibly()
}
