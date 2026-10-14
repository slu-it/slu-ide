package dev.sluide.dockersandbox

import dev.sluide.terminal.loginShellRunning
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private val log = System.getLogger(SbxCheck::class.java.name)

/**
 * Whether `sbx` is installed: found by the user's login shell, which is where the sandboxes run it. Starts a login shell,
 * which may take a while with a slow profile, so this must not run on the FX thread.
 */
internal object SbxCheck {
    private val timeout = 10.seconds

    /** Whether `command -v sbx` succeeds in the login shell started with [environment]. */
    fun installed(environment: Map<String, String>): Boolean {
        val command = loginShellRunning("command -v sbx", environment)
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
}
