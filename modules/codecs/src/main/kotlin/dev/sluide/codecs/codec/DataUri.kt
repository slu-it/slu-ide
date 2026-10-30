package dev.sluide.codecs.codec

/** Reads the head of a `data:` URI (RFC 2397). */
internal object DataUri {
    /** [payloadStart] is the index of the first character after the comma. */
    data class Parsed(
        val mime: String,
        val base64: Boolean,
        val payloadStart: Int,
    )

    /** `null` when [text] does not start with `data:` or has no comma. The mime type defaults to `text/plain`. */
    fun parse(text: String): Parsed? {
        if (!text.startsWith("data:", ignoreCase = true)) return null
        val comma = text.indexOf(',')
        if (comma < 0) return null
        val head = text.substring("data:".length, comma)
        val base64 = head.endsWith(";base64", ignoreCase = true)
        val mime = head.substringBefore(';').trim().ifEmpty { "text/plain" }
        return Parsed(mime, base64, comma + 1)
    }
}
