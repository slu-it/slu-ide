package dev.sluide.codecs.conversion

import dev.sluide.codecs.detect.FileKind

/** The two editable sides; the one edited last is the source the other follows. */
enum class Side { TEXT, ENCODED }

/** What the text side holds: plain text, or binary data shown as a hex dump. */
sealed interface TextContent {
    data class Text(
        val text: String,
    ) : TextContent

    class Bytes(
        val bytes: ByteArray,
        val kind: FileKind?,
        val name: String?,
    ) : TextContent {
        override fun equals(other: Any?): Boolean =
            other is Bytes && bytes.contentEquals(other.bytes) && kind == other.kind && name == other.name

        override fun hashCode(): Int = (bytes.contentHashCode() * 31 + kind.hashCode()) * 31 + name.hashCode()

        override fun toString(): String = "Bytes(${bytes.size} bytes, kind=$kind, name=$name)"
    }
}

/** The line under a side: what it holds, or why it is not valid ([position] is an index into that side's text). */
sealed interface Status {
    data class Info(
        val text: String,
    ) : Status

    data class Error(
        val message: String,
        val position: Int?,
    ) : Status
}

/** A suggestion shown next to the sides; [apply] turns the current settings into the suggested ones. */
data class Hint(
    val message: String,
    val action: String,
    val apply: (CodecSettings) -> CodecSettings,
)
