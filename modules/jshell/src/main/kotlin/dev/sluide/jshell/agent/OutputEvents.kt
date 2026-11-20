package dev.sluide.jshell.agent

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * The execution JVM's output bytes as UTF-8 text, one [onText] per write; a character split between two writes is
 * kept for the next one, malformed bytes become U+FFFD.
 */
internal class OutputEvents(
    private val onText: (String) -> Unit,
) : OutputStream() {
    private val decoder =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteBuffer.allocate(0)

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    @Synchronized
    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        val input = ByteBuffer.allocate(pending.remaining() + len)
        input.put(pending).put(b, off, len).flip()
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pending = ByteBuffer.allocate(input.remaining()).put(input)
        pending.flip()
        out.flip()
        if (out.hasRemaining()) onText(out.toString())
    }
}

internal const val EXIT_MARK = "\u0000sluide-exit "
private val EXIT_REPORT = Regex("${EXIT_MARK}[^0-9-]*(-?\\d+)\n?")

/**
 * The start snippet's exit report in the execution JVM's standard error: [EXIT_MARK] then the logged message of
 * `Runtime.exit` ("Runtime.exit() called with status: 3"). The status (null if none is in [text]) and [text] without
 * the report.
 */
internal fun exitReport(text: String): Pair<Int?, String> {
    val match = EXIT_REPORT.find(text) ?: return null to text
    return match.groupValues[1].toInt() to text.removeRange(match.range)
}
