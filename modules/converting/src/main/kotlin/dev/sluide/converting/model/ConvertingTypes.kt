package dev.sluide.converting.model

import dev.sluide.converting.TextFormat

/** A side of the Converting tool; the one edited last is the source, the other one follows it. */
internal enum class Side {
    LEFT,
    RIGHT,
    ;

    val other: Side get() = if (this == LEFT) RIGHT else LEFT
}

/** What a side's format picker shows: detect the format, or a pinned one. */
internal sealed interface Pick {
    data object Detect : Pick

    data class Pinned(
        val format: TextFormat,
    ) : Pick
}

/** A place in a side's text: [offset] in chars, [line] and [column] 1-based. */
internal data class TextPosition(
    val offset: Int,
    val line: Int,
    val column: Int,
)

/** The status line of a side. */
internal sealed interface Status {
    val text: String

    /** [format] is the format's label ("YAML · detected", "TOML"), null without a format; [notes] on the target. */
    data class Info(
        val format: String?,
        val characters: Int,
        val notes: List<String>,
    ) : Status {
        override val text: String
            get() =
                listOfNotNull(
                    format,
                    if (characters == 1) "1 character" else "$characters characters",
                    notes.joinToString(", ").ifEmpty { null },
                ).joinToString(" · ")
    }

    /** Reading the source or writing the target failed; [position] is where reading failed, if known. */
    data class Failure(
        val message: String,
        val position: TextPosition?,
    ) : Status {
        override val text: String
            get() = position?.let { "Line ${it.line}, column ${it.column}: $message" } ?: message
    }
}

/** The source's pinned format fails while [format] reads the text; [ConvertingModel.applyHint] pins it. */
internal data class Hint(
    val format: TextFormat,
) {
    val message: String get() = "Looks like ${format.name}"
}

/** A format the target picker offers; [unavailable] is why the target cannot hold the current source, if it cannot. */
internal data class TargetChoice(
    val format: TextFormat,
    val unavailable: String?,
)

/** The 1-based line and column of [offset] in [text]; lines end at `\n`, an offset past the end counts as the end. */
internal fun lineColumn(
    text: String,
    offset: Int,
): Pair<Int, Int> {
    val end = offset.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', end - 1) + 1
    val line = 1 + (0 until end).count { text[it] == '\n' }
    return line to end - lineStart + 1
}
