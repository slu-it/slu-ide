package dev.sluide.jshell.engine

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The last [limit] bytes of the agent's standard error, read until its end by [drain]. Every JVM here prints at least
 * "Picked up JAVA_TOOL_OPTIONS", and a snippet may print without end; only the tail is kept, for a failed start or a
 * crash.
 */
internal class StderrTail(
    private val limit: Int = 8 * 1024,
) {
    private val kept = ByteArray(limit)
    private var size = 0
    private val ended = CountDownLatch(1)

    /** Reads [input] until its end; runs on a thread of its own. */
    fun drain(input: InputStream) {
        try {
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                keep(buffer, count)
            }
        } catch (_: IOException) {
            // the process is gone: its tail is what was read
        } finally {
            ended.countDown()
        }
    }

    @Synchronized
    private fun keep(
        bytes: ByteArray,
        count: Int,
    ) {
        val fresh = minOf(count, limit)
        val old = minOf(size, limit - fresh)
        System.arraycopy(kept, size - old, kept, 0, old)
        System.arraycopy(bytes, count - fresh, kept, old, fresh)
        size = old + fresh
    }

    /** Waits up to [millis] for [drain] to reach the end; true if it did. */
    fun awaitEnd(millis: Long): Boolean = ended.await(millis, TimeUnit.MILLISECONDS)

    /** The kept bytes as UTF-8; a character cut at the start becomes U+FFFD. */
    @Synchronized
    fun text(): String = String(kept, 0, size, Charsets.UTF_8)
}
