package dev.sluide.codecs.codec

/** The value of an ASCII hex digit (`0-9 a-f A-F`), or -1; unlike `Character.digit` it rejects other scripts' digits. */
internal fun hexValue(c: Char): Int =
    when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

/** The value of the four ASCII hex digits at [from], or null when they are not four hex digits before [end]. */
internal fun hex4(
    text: String,
    from: Int,
    end: Int,
): Int? = if (from + 4 > end) null else hexNumber(text, from, from + 4)

/** The value of the ASCII hex digits in `text[from, to)`, or null when there are none or one is no hex digit. */
internal fun hexNumber(
    text: String,
    from: Int,
    to: Int,
): Int? {
    if (from >= to) return null
    var value = 0
    for (k in from until to) {
        val d = hexValue(text[k])
        if (d < 0) return null
        value = value * 16 + d
    }
    return value
}
