package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter

/** The syntax highlighters of the code views, for other modules that highlight embedded code (e.g. request bodies). */
object Highlighters {
    /**
     * The highlighter for [language], a lower-case name or file extension as in a Markdown code fence (`json`, `xml`,
     * `html`, `js`, `kotlin`, …), or `null` if there is none.
     */
    fun forLanguage(language: String): SyntaxHighlighter? = RSyntaxHighlighter.forLanguage(language)
}
