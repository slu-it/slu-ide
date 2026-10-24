package dev.sluide.git.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
