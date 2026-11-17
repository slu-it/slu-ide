package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.fileviewers.PreviewPlace
import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.revealAround

/** The mark's style class in the preview under a list of places (`code-intelligence-popups.css`). */
internal const val PREVIEW_TARGET_CLASS = "ci-preview-target"

/**
 * Where the preview under a list of places reveals and marks in [text], the whole file: [position]'s line with up to
 * five lines around it, and the name at its column marked ([PREVIEW_TARGET_CLASS]); no mark where the text has no name
 * there. `null` when the text has no such line. Columns are UTF-16 offsets within the line, as the editor's.
 */
internal fun targetPlace(
    text: String,
    position: SourcePosition,
): PreviewPlace? {
    val reveal = revealAround(text, position.line) ?: return null
    // `\n` breaks lines, as in revealAround.
    var lineStart = 0
    repeat(position.line) { lineStart = text.indexOf('\n', lineStart) + 1 }
    val line = text.substring(lineStart).substringBefore('\n')
    val word = wordIn(line, position.column, orBefore = true)
    val marks =
        listOfNotNull(word?.let { StyledRange(lineStart + it.first, lineStart + it.last + 1, PREVIEW_TARGET_CLASS) })
    return PreviewPlace(marks, reveal.first, reveal.last + 1)
}
