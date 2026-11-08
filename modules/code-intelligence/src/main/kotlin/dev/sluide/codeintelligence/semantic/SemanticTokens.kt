package dev.sluide.codeintelligence.semantic

import dev.sluide.codeintelligence.binding.LineIndex
import dev.sluide.fileviewers.StyledRange
import org.eclipse.lsp4j.SemanticTokensLegend

/** One semantic token: where it is (line, UTF-16 character, length) and its type and modifiers by the legend. */
internal data class SemanticToken(
    val line: Int,
    val character: Int,
    val length: Int,
    /** `null` for an index outside the legend. */
    val type: String?,
    val modifiers: Set<String>,
)

/**
 * Decodes the relative token [data] of `textDocument/semanticTokens/full` (five numbers per token: line delta, start
 * delta (relative to the token before on the same line), length, type index, modifier bits) with [legend]; an
 * incomplete last group is left out.
 */
internal fun decodeTokens(
    data: List<Int>,
    legend: SemanticTokensLegend,
): List<SemanticToken> {
    val types = legend.tokenTypes.orEmpty()
    val modifierNames = legend.tokenModifiers.orEmpty()
    val tokens = ArrayList<SemanticToken>(data.size / 5)
    var line = 0
    var character = 0
    var i = 0
    while (i + 4 < data.size) {
        val deltaLine = data[i]
        val deltaStart = data[i + 1]
        line += deltaLine
        character = if (deltaLine == 0) character + deltaStart else deltaStart
        val bits = data[i + 4]
        val modifiers =
            if (bits == 0) {
                emptySet()
            } else {
                modifierNames.filterIndexedTo(LinkedHashSet()) { index, _ -> index < 31 && bits and (1 shl index) != 0 }
            }
        tokens += SemanticToken(line, character, data[i + 2], types.getOrNull(data[i + 3]), modifiers)
        i += 5
    }
    return tokens
}

/**
 * The style classes of a token of [type] with [modifiers]: parameters, `self`/`cls` (ty's `selfParameter` and
 * `clsParameter`) and builtin constants (ty's `builtinConstant`) are coloured, deprecated ones struck through; the
 * rest keeps the lexical colours.
 */
internal fun semanticClasses(
    type: String?,
    modifiers: Set<String>,
): List<String> =
    listOfNotNull(
        when (type) {
            "parameter" -> "sem-parameter"
            "selfParameter", "clsParameter" -> "sem-self"
            "builtinConstant" -> "sem-builtin-constant"
            else -> null
        },
        "sem-deprecated".takeIf { "deprecated" in modifiers },
    )

/** The overlay ranges of [tokens] in [text] (the document's, `\n` line breaks), one per class; tokens kept in a line. */
internal fun semanticRanges(
    tokens: List<SemanticToken>,
    text: CharSequence,
): List<StyledRange> {
    val lines = LineIndex(text)
    val ranges = mutableListOf<StyledRange>()
    for (token in tokens) {
        val classes = semanticClasses(token.type, token.modifiers)
        if (classes.isEmpty() || token.length <= 0) continue
        val start = lines.offsetOf(token.line, token.character)
        val end = lines.offsetOf(token.line, token.character + token.length)
        if (end <= start) continue
        classes.mapTo(ranges) { StyledRange(start, end, it) }
    }
    return ranges
}
