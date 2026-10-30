package dev.sluide.codecs.codec

/** [failedLines] are 1-based; [firstError] has its position translated to an index in the whole text. */
internal data class LinesResult(
    val text: String,
    val failedLines: List<Int>,
    val firstError: Decoded.Error?,
)

/** Converts a text line by line, keeping each line's own separator. */
internal object LineByLine {
    fun convert(
        text: String,
        line: (String) -> Decoded<String>,
    ): LinesResult {
        val out = StringBuilder()
        val failed = ArrayList<Int>()
        var firstError: Decoded.Error? = null
        var start = 0
        var number = 0
        while (start < text.length || number == 0) {
            val nl = text.indexOf('\n', start)
            val next = if (nl < 0) text.length else nl + 1
            val sepStart =
                if (nl < 0) {
                    text.length
                } else if (nl > start && text[nl - 1] == '\r') {
                    nl - 1
                } else {
                    nl
                }
            number++
            val content = text.substring(start, sepStart)
            // Empty lines stay empty and are not converted.
            if (content.isNotEmpty()) {
                when (val r = line(content)) {
                    is Decoded.Value -> {
                        out.append(r.value)
                    }

                    is Decoded.Error -> {
                        out.append(content)
                        failed.add(number)
                        if (firstError == null) firstError = r.copy(position = r.position?.let { it + start })
                    }
                }
            }
            out.append(text, sepStart, next)
            start = next
        }
        return LinesResult(out.toString(), failed, firstError)
    }
}
