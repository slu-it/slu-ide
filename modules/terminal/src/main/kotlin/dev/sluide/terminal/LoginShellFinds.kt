package dev.sluide.terminal

import com.pty4j.PtyProcessBuilder
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = System.getLogger("dev.sluide.terminal.LoginShellFinds")

private const val CHECK_COLUMNS = 80
private const val CHECK_ROWS = 24

/**
 * Whether the user's login shell, started with [environment], finds [program] (`command -v`), which is where
 * terminals and command runs start it. A login shell may take a while with a slow profile, so this blocks and must not
 * run on the FX thread; false if it takes longer than [timeout] (the shell and what its profile started are killed),
 * if the shell cannot be started, or if the thread is interrupted. [program] must be a plain program name.
 *
 * The shell is interactive, as for every command (ADR 0087), and gets a pseudo terminal of its own, which makes it the
 * leader of a new session: an interactive shell sets up job control on its controlling terminal, and in the IDE's
 * session (started from a terminal) it would take that terminal over or stop the IDE with SIGTTIN. Its output is read
 * and dropped; a startup file that waits for input waits on that pseudo terminal until the timeout.
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
            // TERM and a size as in a terminal tab, so startup files that look at them take the same path.
            PtyProcessBuilder(command.toTypedArray())
                .setEnvironment(environment + ("TERM" to TERMINAL_TYPE))
                .setInitialColumns(CHECK_COLUMNS)
                .setInitialRows(CHECK_ROWS)
                .setRedirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            log.log(System.Logger.Level.DEBUG, "Cannot run ${command.first()}", e)
            return false
        }
    // A full pseudo terminal would block a talkative startup file; the read ends when the shell is gone. A platform
    // thread: pty4j reads in a native call, which would pin a virtual thread's carrier for the whole check.
    Thread.ofPlatform().daemon().name("sluide-login-shell-output").start {
        try {
            process.inputStream.use { it.transferTo(OutputStream.nullOutputStream()) }
        } catch (_: IOException) {
            // The pseudo terminal closed with the shell.
        }
    }
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
    // By pid: pty4j's processes have no handle of their own (toHandle() is unsupported).
    ProcessHandle.of(process.pid()).ifPresent { shell -> shell.descendants().forEach { it.destroyForcibly() } }
    process.destroyForcibly()
}
