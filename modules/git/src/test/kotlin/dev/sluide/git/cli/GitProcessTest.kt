package dev.sluide.git.cli

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.function.ThrowingSupplier
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Duration.ofSeconds
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitProcessTest {
    /** Whether all of [size] bytes were copied under [limit], and how many bytes arrived. */
    private fun copy(
        size: Int,
        limit: Long,
    ): Pair<Boolean, Int> {
        val output = ByteArrayOutputStream()
        val complete = copyAtMost(ByteArrayInputStream(ByteArray(size) { 'a'.code.toByte() }), output, limit)
        return complete to output.size()
    }

    @Test
    fun `input up to the limit is copied whole, one byte more is not`() {
        for (limit in listOf(0, 1, 100, 8192, 20_000)) {
            for (size in listOf(0, 1, limit - 1, limit).filter { it in 0..limit }) {
                assertEquals(true to size, copy(size, limit.toLong()), "$size bytes, limit $limit")
            }
            assertEquals(false to limit, copy(limit + 1, limit.toLong()), "limit + 1, limit $limit")
            assertEquals(false to limit, copy(limit + 30_000, limit.toLong()), "far above, limit $limit")
        }
    }

    /**
     * A real [BufferedInputStream] of twice [chunk] that is closed after its first read, as stopping git
     * (`destroyForcibly`) closes its pipes under a reader; its next read then throws "Stream closed", as the JDK's does.
     */
    private fun closedAfterFirstRead(chunk: ByteArray): InputStream {
        val real = BufferedInputStream(ByteArrayInputStream(chunk + chunk), chunk.size)
        return object : FilterInputStream(real) {
            private var reads = 0

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                if (reads++ == 1) real.close()
                return super.read(buffer, offset, minOf(length, chunk.size))
            }
        }
    }

    private val chunk = ByteArray(100) { 'o'.code.toByte() }

    @Test
    fun `git's output read with or without a limit ends quietly with what came when git is stopped under it`() {
        for (limit in listOf(null, 1_000L)) {
            val output = ByteArrayOutputStream()
            var overflowed = false

            readOutput(closedAfterFirstRead(chunk), output, limit) { overflowed = true }

            assertEquals(chunk.size, output.size(), "limit $limit")
            assertFalse(overflowed, "limit $limit")
        }
    }

    @Test
    fun `git's errors read with or without a limit end quietly with what came when git is stopped under it`() {
        for (limit in listOf(null, 1_000L, 10L)) {
            val errors = ByteArrayOutputStream()

            keepErrors(closedAfterFirstRead(chunk), errors, limit)

            assertEquals(minOf(chunk.size.toLong(), limit ?: Long.MAX_VALUE).toInt(), errors.size(), "limit $limit")
        }
    }

    @Test
    fun `awaitEndBy sees a process that ended, also after its deadline, and one that ends before it`() {
        val ended = ProcessBuilder("sh", "-c", "exit 0").start()
        assumeTrue(ended.waitFor(10, TimeUnit.SECONDS), "no sh")
        val ends = ProcessBuilder("sh", "-c", "read x").start()
        try {
            assertTrue(awaitEndBy(ended.toHandle(), System.nanoTime() - 1_000_000_000), "ended, deadline long passed")
            ends.outputStream.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            assertTimeoutPreemptively(
                ofSeconds(40),
                ThrowingSupplier { assertTrue(awaitEndBy(ends.toHandle(), deadline)) },
            )
        } finally {
            ends.destroyForcibly()
        }
    }

    @Test
    fun `awaitEndBy gives up at its deadline for a process that keeps running`() {
        val running = ProcessBuilder("sh", "-c", "read x").start()
        try {
            val started = System.nanoTime()

            val ended =
                assertTimeoutPreemptively(
                    ofSeconds(20),
                    ThrowingSupplier { awaitEndBy(running.toHandle(), System.nanoTime() + 200_000_000) },
                )

            assertFalse(ended)
            assertTrue(System.nanoTime() - started >= 200_000_000, "not before the deadline")
            assertFalse(awaitEndBy(running.toHandle(), System.nanoTime() - 1), "a deadline that has passed")
        } finally {
            running.destroyForcibly()
        }
    }
}
