package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.FrontMatter

/**
 * The YAML (`---`, closed by `---` or `...`) or TOML (`+++`) front matter at the start of [text], or `null`. The
 * second line must not be blank, so a thematic break followed by a blank line is none.
 */
internal fun frontMatter(text: String): FrontMatter? {
    val firstEnd = text.indexOf('\n')
    if (firstEnd < 0) return null
    val marker = text.substring(0, firstEnd).trim()
    val language =
        when (marker) {
            "---" -> "yaml"
            "+++" -> "toml"
            else -> return null
        }
    val contentStart = firstEnd + 1
    if (contentStart >= text.length) return null
    val secondEnd = text.indexOf('\n', contentStart).let { if (it < 0) text.length else it }
    if (text.substring(contentStart, secondEnd).isBlank()) return null
    var lineStart = contentStart
    while (lineStart < text.length) {
        val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, newline).trim()
        if (line == marker || (marker == "---" && line == "...")) {
            return FrontMatter(language, contentStart, lineStart, minOf(text.length, newline + 1))
        }
        lineStart = newline + 1
    }
    return null
}
