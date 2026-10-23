package dev.sluide.githubactions.mermaid

/**
 * Escaping of Mermaid labels. A copy of `BicepGraph.mermaidLabel` and `escape` in `file-viewers`
 * (`dev.sluide.fileviewers.bicep.BicepGraph`), kept in sync with it by hand: this module depends on no other.
 */
internal object MermaidLabels {
    /**
     * The longest label part, in code points and in escaped characters; longer ones are cut with `…`. With them and
     * [WorkflowDrawing]'s limits the diagram stays below Mermaid's 50,000 characters.
     */
    const val MAX_LABEL = 48
    const val MAX_LABEL_CHARS = 96

    /**
     * [text] for a quoted Mermaid label (`"…"`): letters, digits, blanks and `_-./:@(),` as they are, every other
     * character as an entity code (`#35;`), so quotes, `#`, `;`, `<`, backticks (Markdown strings) and the like
     * are text; control characters become blanks. Over [MAX_LABEL] code points or [MAX_LABEL_CHARS] characters,
     * the end (or, if [keepEnd], the start) is left out.
     */
    fun label(
        text: String,
        keepEnd: Boolean = false,
    ): String {
        val pieces = text.codePoints().toArray().map(::escape)
        if (pieces.size <= MAX_LABEL &&
            pieces.sumOf { it.length } <= MAX_LABEL_CHARS
        ) {
            return pieces.joinToString("")
        }
        val ellipsis = escape(ELLIPSIS)
        val kept = ArrayList<String>()
        var length = ellipsis.length
        for (piece in if (keepEnd) pieces.asReversed() else pieces) {
            if (kept.size == MAX_LABEL - 1 || length + piece.length > MAX_LABEL_CHARS) break
            kept += piece
            length += piece.length
        }
        return if (keepEnd) ellipsis + kept.asReversed().joinToString("") else kept.joinToString("") + ellipsis
    }

    /** Already escaped text in bold; the tags are HTML, which Mermaid's sanitizer lets through. */
    fun bold(escaped: String): String = "<b>$escaped</b>"

    /**
     * Already escaped [lines] as a block below a box's label, which the preview frames (`.sluide-inputs` in
     * `preview.css`); the `div` is HTML, which Mermaid's sanitizer lets through with its class, and grows the box.
     */
    fun inputs(lines: List<String>): String = "<div class='$INPUTS_CLASS'>" + lines.joinToString("<br/>") + "</div>"

    /** [text] as an italic `<text>` (the angle brackets escaped, so they show). */
    fun type(text: String): String = "<i>" + label("<$text>") + "</i>"

    private fun escape(point: Int): String =
        when {
            point < 0x20 || point == 0x7f -> " "
            isPlain(point) -> point.toChar().toString()
            else -> "#$point;"
        }

    private fun isPlain(point: Int) = point < 0x80 && (Character.isLetterOrDigit(point) || point.toChar() in SAFE)

    private const val ELLIPSIS = 0x2026
    private const val SAFE = " _-./:@(),"
    private const val INPUTS_CLASS = "sluide-inputs"
}
