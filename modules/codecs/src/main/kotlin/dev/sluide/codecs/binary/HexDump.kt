package dev.sluide.codecs.binary

import java.util.Locale

/** Counts with a space between the thousands: `12 345`. */
internal fun Int.grouped(): String = "%,d".format(Locale.ROOT, this).replace(',', ' ')

/** The `hexdump -C` style view of binary data. */
internal object HexDump {
    const val LIMIT = 64 * 1024
    private const val PER_LINE = 16
    private const val HALF = 8
    private const val HEX_WIDTH = PER_LINE * 3

    /** Dumps at most [limit] bytes; a last line says how many are left out. */
    fun format(
        bytes: ByteArray,
        limit: Int = LIMIT,
    ): String {
        val shown = minOf(bytes.size, limit)
        val lines = ArrayList<String>()
        for (offset in 0 until shown step PER_LINE) {
            val end = minOf(offset + PER_LINE, shown)
            lines.add(line(bytes, offset, end))
        }
        if (bytes.size > shown) lines.add("… ${(bytes.size - shown).grouped()} more bytes")
        return lines.joinToString("\n")
    }

    private fun line(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): String {
        fun group(range: IntRange) = range.joinToString(" ") { "%02x".format(bytes[it].toInt() and 0xff) }
        val first = group(from until minOf(from + HALF, to))
        val hex = if (to > from + HALF) first + "  " + group(from + HALF until to) else first
        val ascii =
            (from until to).joinToString("") {
                val c = bytes[it].toInt() and 0xff
                if (c in 0x20..0x7e) c.toChar().toString() else "."
            }
        return "%08x  %s  |%s|".format(from, hex.padEnd(HEX_WIDTH), ascii)
    }
}
