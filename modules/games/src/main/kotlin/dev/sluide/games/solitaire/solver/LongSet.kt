package dev.sluide.games.solitaire.solver

/** A set of non-boxed longs (open addressing) that refuses to grow past [maxEntries]. */
internal class LongSet(
    private val maxEntries: Int = 2_000_000,
) {
    private var table = LongArray(1 shl 12)
    private var count = 0

    /** Adds [value]; false when it was there already. [full] turns true once [maxEntries] is reached. */
    fun add(value: Long): Boolean {
        val key = if (value == 0L) 1L else value
        if (count * 2 >= table.size) grow()
        val mask = table.size - 1
        var i = (key xor (key ushr 32)).toInt() and mask
        while (true) {
            val at = table[i]
            if (at == 0L) {
                table[i] = key
                count++
                return true
            }
            if (at == key) return false
            i = (i + 1) and mask
        }
    }

    val full: Boolean get() = count >= maxEntries

    private fun grow() {
        val old = table
        table = LongArray(old.size * 2)
        val mask = table.size - 1
        for (key in old) {
            if (key == 0L) continue
            var i = (key xor (key ushr 32)).toInt() and mask
            while (table[i] != 0L) i = (i + 1) and mask
            table[i] = key
        }
    }
}
