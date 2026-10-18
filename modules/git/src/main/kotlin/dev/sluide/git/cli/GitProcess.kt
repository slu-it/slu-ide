package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

/**
 * Waits up to [timeout] for [process] to end, or until [cancellation] (if any) is cancelled; whether it ended. A
 * process that ended by the time it was cancelled counts as ended, so its result stands.
 */
internal fun awaitEnd(
    process: Process,
    timeout: Duration,
    cancellation: GitCancellation?,
): Boolean {
    if (cancellation == null) return process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    try {
        CompletableFuture
            .anyOf(process.onExit(), cancellation.signal)
            .get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        // Still running, unless it ended just now: told below.
    }
    return !process.isAlive
}

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
