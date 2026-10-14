package dev.sluide.textediting

/** Indentation of texts with `\n` line breaks. */
object Indentation {
    private const val MIN_DETECTED_SIZE = 2
    private const val MAX_DETECTED_SIZE = 8

    /**
     * The indentation [text] uses, or `null` if it cannot be told (no indented lines, as many tab- as space-indented
     * lines, or no common step).
     *
     * Tabs or spaces is decided by the first character of most indented lines. Lines indented by a single space are
     * not counted, they are usually continuations such as ` *` in block comments. The size of space indentation is
     * the most common increase in indentation between consecutive non-blank lines, between 2 and 8 (the smaller one
     * on ties). Tab indentation gets the default size.
     */
    fun detect(text: String): IndentStyle? {
        var tabLines = 0
        var spaceLines = 0
        val steps = mutableMapOf<Int, Int>()
        var previousIndent = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val indent = leadingWhitespace(line)
            when {
                indent.startsWith('\t') -> tabLines++
                indent.length > 1 -> spaceLines++
            }
            if (!indent.contains('\t')) {
                val step = indent.length - previousIndent
                if (step in MIN_DETECTED_SIZE..MAX_DETECTED_SIZE) steps.merge(step, 1, Int::plus)
                previousIndent = indent.length
            }
        }
        return when {
            tabLines > spaceLines -> {
                IndentStyle.DEFAULT.copy(useTabs = true)
            }

            spaceLines > tabLines -> {
                steps.entries
                    .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
                    ?.let { IndentStyle(useTabs = false, size = it.key) }
            }

            else -> {
                null
            }
        }
    }

    internal fun leadingWhitespace(line: CharSequence): String = line.takeWhile { it == ' ' || it == '\t' }.toString()
}
