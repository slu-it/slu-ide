package dev.sluide.converting

import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.SyntaxHighlighter

/** A text format of a [FormatFamily], e.g. JSON. */
interface TextFormat {
    /** Stable identifier (`json`); pinned formats and remembered targets refer to it. */
    val id: String

    /** The label in the format pickers (`JSON`). */
    val name: String

    /** The language `Highlighters.forLanguage` highlights the format with (`json`); `null` means plain text. */
    val language: String?

    /** File extensions without the dot; the first one is written by Save as and Convert to. */
    val extensions: List<String>

    /**
     * Highlights the format's text in the tool's sides; by default the highlighter of [language] (plain without one).
     * Families override it for formats file-viewers has no language for, e.g. the HTTP client's requests.
     */
    val highlighter: SyntaxHighlighter? get() = language?.let(Highlighters::forLanguage)
}
