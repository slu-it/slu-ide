package dev.sluide.textediting

/**
 * The line terminator of a text file. Editors work with `\n` only (RichTextFX turns `\r\n` and `\r` into `\n`), so a
 * file's ending is detected when it is read ([of]) and restored when it is written ([restore]).
 */
enum class LineEnding(
    val separator: String,
) {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r"),
    ;

    /** [text] with `\n` line breaks, as an editor holds it, converted to this ending. */
    fun restore(text: String): String = if (this == LF) text else text.replace("\n", separator)

    companion object {
        /**
         * The ending used by most lines of [text]; [LF] for texts without line breaks and on ties with LF. A tie
         * of [CRLF] and [CR] alone gives [CRLF]. So a file with mixed endings is written with one of its endings.
         */
        fun of(text: String): LineEnding {
            var lf = 0
            var crlf = 0
            var cr = 0
            var index = 0
            while (index < text.length) {
                when (text[index]) {
                    '\n' -> {
                        lf++
                    }

                    '\r' -> {
                        if (index + 1 < text.length && text[index + 1] == '\n') {
                            crlf++
                            index++
                        } else {
                            cr++
                        }
                    }
                }
                index++
            }
            return when {
                crlf > lf && crlf >= cr -> CRLF
                cr > lf && cr > crlf -> CR
                else -> LF
            }
        }

        /** [text] with every `\r\n` and `\r` replaced by `\n`, as an editor holds it. */
        fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
    }
}
