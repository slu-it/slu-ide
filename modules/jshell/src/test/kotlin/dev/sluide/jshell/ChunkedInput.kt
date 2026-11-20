package dev.sluide.jshell

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * A stream fed in chunks, like a pipe the agent writes to: a read blocks until a chunk is there, and `available()`
 * counts every byte already fed, so what one [feed] put in is all buffered at once for a reader.
 */
internal class ChunkedInput : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var current = ByteArray(0)
    private var position = 0

    @Volatile private var ended = false

    fun feed(text: String) = chunks.put(text.toByteArray(Charsets.UTF_8))

    /** Ends the stream after what was fed. */
    fun end() = chunks.put(END)

    /** Makes the read after what was fed fail, as a pipe can. */
    fun breakOff() = chunks.put(BROKEN)

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        while (position >= current.size) {
            if (ended) return -1
            val next = chunks.take()
            if (next === END) {
                ended = true
                return -1
            }
            if (next === BROKEN) throw IOException("Broken pipe")
            current = next
            position = 0
        }
        val count = minOf(len, current.size - position)
        System.arraycopy(current, position, b, off, count)
        position += count
        return count
    }

    override fun available(): Int = current.size - position + chunks.sumOf { it.size }

    private companion object {
        val END = ByteArray(0)
        val BROKEN = ByteArray(0)
    }
}
