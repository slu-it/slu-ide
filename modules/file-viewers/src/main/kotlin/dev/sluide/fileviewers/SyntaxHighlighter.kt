package dev.sluide.fileviewers

/** Kinds of highlighted code, independent of the lexer library. [styleClass] is the CSS class in `syntax.css`. */
internal enum class TokenStyle(
    val styleClass: String,
) {
    KEYWORD("keyword"),
    TYPE("type"),
    BUILTIN("builtin"),
    STRING("string"),
    NUMBER("number"),
    COMMENT("comment"),
    ANNOTATION("annotation"),
    OPERATOR("operator"),
}

/** The characters `[start, end)` of a text are styled as [style]. */
internal data class HighlightSpan(
    val start: Int,
    val end: Int,
    val style: TokenStyle,
)

/** Finds the parts of a source text to highlight. */
internal fun interface SyntaxHighlighter {
    /** Returns spans sorted by position, not overlapping and within [text]; unstyled parts have no span. */
    fun highlight(text: String): List<HighlightSpan>
}
