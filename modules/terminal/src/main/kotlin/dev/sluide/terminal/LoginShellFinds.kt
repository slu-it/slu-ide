package dev.sluide.terminal

import com.pty4j.PtyProcessBuilder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = System.getLogger("dev.sluide.terminal.LoginShellFinds")

private const val CHECK_COLUMNS = 80
private const val CHECK_ROWS = 24

/** What [loginShellPrints] keeps of the shell's output at most; startup files may be talkative. */
private const val MAX_OUTPUT_BYTES = 1 shl 20

/** How long [loginShellPrints] waits for the rest of the output once the shell has ended. */
private const val OUTPUT_DRAIN_MILLIS = 2_000L

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
    val found =
        runLoginShell("command -v $program", environment, timeout, OutputStream.nullOutputStream(), drain = false) == 0
    val millis = (System.nanoTime() - started) / 1_000_000
    log.log(
        System.Logger.Level.INFO,
        "$program check finished in $millis ms: ${if (found) "installed" else "not installed"}",
    )
    return found
}

/**
 * What [script] prints when the user's login shell, started with [environment], runs it: the same interactive login
 * shell in a pseudo terminal of its own as [loginShellFinds], so the script sees what a terminal tab sees (`PATH`,
 * `DOCKER_HOST`, …). Only the script's output is returned, without what the startup files print around it: markers
 * printed before and after the script delimit it. Carriage returns of the pseudo terminal are removed, so lines end with
 * `\n`; standard error is mixed in, as in a terminal. The script's exit code does not count.
 *
 * Blocks, so not on the FX thread; `null` if the shell takes longer than [timeout] (the shell and what its profile
 * started are killed), cannot be started, the thread is interrupted, or the markers are missing (a startup file that
 * `exec`s another program). The script should use syntax that the user's shell understands: plain commands, single
 * quotes and `printf` work in sh, bash, zsh and fish.
 */
fun loginShellPrints(
    script: String,
    environment: Map<String, String>,
    timeout: Duration = 10.seconds,
): String? {
    val nonce = UUID.randomUUID().toString().replace("-", "")
    // The markers are printed in two halves, so a shell echoing the command line (set -x) cannot fake them.
    val begin = "__sluide_begin_${nonce}__"
    val end = "__sluide_end_${nonce}__"
    val command =
        "printf '%s%s\\n' '__sluide_' 'begin_${nonce}__'\n$script\nprintf '\\n%s%s\\n' '__sluide_' 'end_${nonce}__'"
    val output = ByteArrayOutputStream()
    val started = System.nanoTime()
    val exit = runLoginShell(command, environment, timeout, BoundedOutput(output, MAX_OUTPUT_BYTES), drain = true)
    val millis = (System.nanoTime() - started) / 1_000_000
    if (exit == null) return null
    val printed = between(output.toString(Charsets.UTF_8).replace("\r", ""), begin, end)
    log.log(
        System.Logger.Level.INFO,
        "Login shell script finished in $millis ms: ${if (printed == null) "no output markers" else "printed ${printed.length} characters"}",
    )
    return printed
}

/** The text between the line [begin] and the line [end], without the newline that [loginShellPrints] adds before it. */
internal fun between(
    output: String,
    begin: String,
    end: String,
): String? {
    val start = output.indexOf("$begin\n").takeIf { it >= 0 }?.plus(begin.length + 1) ?: return null
    val stop = output.indexOf("\n$end", start).takeIf { it >= 0 } ?: return null
    return output.substring(start, stop)
}

/**
 * Runs [command] in the interactive login shell of [environment] in a pseudo terminal of its own, copying everything it
 * prints to [output]. The shell's exit code, or `null` if it could not be started, took longer than [timeout] (it is
 * killed with what its profile started) or the thread was interrupted. With [drain], [output] has all the shell printed
 * when this returns a code, unless a process the shell left behind keeps the pseudo terminal open past a short wait.
 */
private fun runLoginShell(
    command: String,
    environment: Map<String, String>,
    timeout: Duration,
    output: OutputStream,
    drain: Boolean,
): Int? {
    val shellCommand = loginShellRunning(command, environment)
    val process =
        try {
            // TERM and a size as in a terminal tab, so startup files that look at them take the same path.
            PtyProcessBuilder(shellCommand.toTypedArray())
                .setEnvironment(environment + ("TERM" to TERMINAL_TYPE))
                .setInitialColumns(CHECK_COLUMNS)
                .setInitialRows(CHECK_ROWS)
                .setRedirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            log.log(System.Logger.Level.DEBUG, "Cannot run ${shellCommand.first()}", e)
            return null
        }
    // A full pseudo terminal would block a talkative startup file; the read ends when the shell is gone. A platform
    // thread: pty4j reads in a native call, which would pin a virtual thread's carrier for the whole check.
    val reader =
        Thread.ofPlatform().daemon().name("sluide-login-shell-output").start {
            copy(process.inputStream, output)
        }
    try {
        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            stop(process)
            log.log(System.Logger.Level.WARNING, "${shellCommand.joinToString(" ")} took longer than $timeout")
            return null
        }
        // The rest of the output is still in the pseudo terminal; the read ends with it.
        if (drain) reader.join(OUTPUT_DRAIN_MILLIS)
    } catch (_: InterruptedException) {
        stop(process)
        Thread.currentThread().interrupt()
        return null
    }
    return process.exitValue()
}

private fun copy(
    input: InputStream,
    output: OutputStream,
) {
    try {
        input.use { it.transferTo(output) }
    } catch (_: IOException) {
        // The pseudo terminal closed with the shell.
    }
}

/** Keeps the first [limit] bytes written to [target] and drops the rest, so that reading never stops. */
private class BoundedOutput(
    private val target: ByteArrayOutputStream,
    private val limit: Int,
) : OutputStream() {
    override fun write(b: Int) {
        synchronized(target) { if (target.size() < limit) target.write(b) }
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        synchronized(target) {
            val room = (limit - target.size()).coerceAtLeast(0)
            target.write(b, off, minOf(len, room))
        }
    }
}

/** Kills the shell and whatever its profile started; the descendants first, as they are lost with the shell. */
private fun stop(process: Process) {
    // By pid: pty4j's processes have no handle of their own (toHandle() is unsupported).
    ProcessHandle.of(process.pid()).ifPresent { shell -> shell.descendants().forEach { it.destroyForcibly() } }
    process.destroyForcibly()
}
