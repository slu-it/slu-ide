package dev.sluide.findinfiles

/** The longest selection that becomes a query. */
internal const val MAX_SELECTION_QUERY = 200
private const val REGEX_METACHARACTERS = "\\.*+?()[]{}|^$"

/** [text] as a regex that matches exactly it, each metacharacter escaped on its own so the field stays readable. */
internal fun escapeRegex(text: String): String =
    buildString {
        for (c in text) {
            if (c in REGEX_METACHARACTERS) append('\\')
            append(c)
        }
    }

/** Whether [text] has a line break. */
internal fun isMultiLine(text: String): Boolean = text.contains('\n') || text.contains('\r')

/** The query a selection of [text] gives: itself, as a literal for [regex]; `null` if empty, multi-line or over 200 characters. */
internal fun selectionQuery(
    text: String,
    regex: Boolean,
): String? =
    when {
        text.isEmpty() || text.length > MAX_SELECTION_QUERY || isMultiLine(text) -> null
        regex -> escapeRegex(text)
        else -> text
    }
