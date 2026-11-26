package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Waits up to [timeout] for [process] to end, or until [cancellation] (if any) is cancelled; whether it ended. A
 * process that ended by the time it was cancelled counts as ended, so its result stands.
 *
 * Not with [Process.onExit]: the JDK completes it on the common fork-join pool, and while that pool is busy (it has
 * one thread less than the machine has cores) git would seem to run until the timeout, though it has long ended.
 * [Process.waitFor] is woken by the JDK's own reaper thread; the cancellation is polled.
 */
internal fun awaitEnd(
    process: Process,
    timeout: Duration,
    cancellation: GitCancellation?,
): Boolean {
    if (cancellation == null) return process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    while (!cancellation.cancelled) {
        val left = deadline - System.nanoTime()
        if (left <= 0) break
        if (process.waitFor(minOf(left, POLL.inWholeNanoseconds), TimeUnit.NANOSECONDS)) return true
    }
    return !process.isAlive
}

/**
 * Waits until [handle] has ended or [deadline] (of [System.nanoTime]) has passed; whether it ended. Polls rather than
 * waiting for [ProcessHandle.onExit], which the common fork-join pool completes ([awaitEnd]).
 */
internal fun awaitEndBy(
    handle: ProcessHandle,
    deadline: Long,
): Boolean {
    while (handle.isAlive) {
        val left = deadline - System.nanoTime()
        if (left <= 0) return false
        TimeUnit.NANOSECONDS.sleep(minOf(left, POLL.inWholeNanoseconds))
    }
    return true
}

/** How often a wait for a process looks whether it was cancelled, or whether a process not started here has ended. */
private val POLL = 20.milliseconds

/**
 * Copies [input] to [output] up to [limit] bytes; `false` (with [limit] bytes copied) if [input] has more, which are
 * not read.
 */
internal fun copyAtMost(
    input: InputStream,
    output: OutputStream,
    limit: Long,
): Boolean {
    val buffer = ByteArray(8192)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return true
        if (copied + read > limit) {
            output.write(buffer, 0, (limit - copied).toInt())
            return false
        }
        output.write(buffer, 0, read)
        copied += read
    }
}

/**
 * Reads git's standard output [stream] into [output], at most [limit] bytes if given; [overflow] when it has more,
 * which are not read. A stream closed under it ends the read with what came: only stopping git closes it (a cancel,
 * a timeout, an interrupt, an overflow), and its result is then thrown away.
 */
internal fun readOutput(
    stream: InputStream,
    output: OutputStream,
    limit: Long?,
    overflow: () -> Unit,
) {
    val whole =
        try {
            if (limit == null) {
                stream.transferTo(output)
                true
            } else {
                copyAtMost(stream, output, limit)
            }
        } catch (_: IOException) {
            return
        }
    if (!whole) overflow()
}

/**
 * Reads git's standard error [stream] to its end into [errors], at most [limit] bytes of it if given. A stream closed
 * under it ends the read with what came, as for [readOutput].
 */
internal fun keepErrors(
    stream: InputStream,
    errors: OutputStream,
    limit: Long?,
) {
    try {
        if (limit == null || copyAtMost(stream, errors, limit)) {
            stream.transferTo(errors)
        } else {
            stream.transferTo(OutputStream.nullOutputStream())
        }
    } catch (_: IOException) {
        // Closed by stopping git.
    }
}
