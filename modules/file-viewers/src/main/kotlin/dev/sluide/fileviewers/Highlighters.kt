package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.BicepHighlighter
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.fileviewers.highlight.fenceLanguage as fenceLanguageOf

/**
 * The syntax highlighters of the code views, for viewers and views of other modules: previews showing their source,
 * and views that highlight embedded code (e.g. request bodies).
 */
object Highlighters {
    /** JSON, also with `//` comments (`tsconfig.json`). */
    val json: SyntaxHighlighter = RSyntaxHighlighter.json

    /** XML, also SVG. */
    val xml: SyntaxHighlighter = RSyntaxHighlighter.xml

    /** YAML, also GitHub Actions workflows. */
    val yaml: SyntaxHighlighter = RSyntaxHighlighter.yaml

    /** HTML, with embedded `<script>` and `<style>` content. */
    val html: SyntaxHighlighter = RSyntaxHighlighter.html

    /** Markdown, with fenced code highlighted as its language ([forLanguage]). */
    val markdown: SyntaxHighlighter = RSyntaxHighlighter.markdown

    /** Mermaid diagrams. */
    val mermaid: SyntaxHighlighter = RSyntaxHighlighter.mermaid

    /** Azure Bicep files. */
    val bicep: SyntaxHighlighter = BicepHighlighter

    /** Groovy, also Gradle's Groovy DSL. */
    val groovy: SyntaxHighlighter = RSyntaxHighlighter.groovy

    /** Kotlin, also Gradle's Kotlin DSL. */
    val kotlin: SyntaxHighlighter = RSyntaxHighlighter.kotlin

    /** Shell scripts (`sh`, `bash`, `zsh`, …). */
    val shell: SyntaxHighlighter = RSyntaxHighlighter.shell

    /** IntelliJ's cookie jar (`http-client.cookies`): its header line as a comment. */
    val cookies: SyntaxHighlighter = RSyntaxHighlighter.cookies

    /** PEM files (certificates, keys): their `BEGIN`/`END` lines and headers. */
    val pem: SyntaxHighlighter = RSyntaxHighlighter.pem

    /**
     * The highlighter for [language], a lower-case name or file extension as in a Markdown code fence (`json`, `xml`,
     * `html`, `js`, `kotlin`, …), or `null` if there is none.
     */
    fun forLanguage(language: String): SyntaxHighlighter? = RSyntaxHighlighter.forLanguage(language)

    /**
     * The language of a code fence's [info] string (` ```ts title="x" ` gives `ts`), lower case, for [forLanguage];
     * empty if there is none.
     */
    fun fenceLanguage(info: String): String = fenceLanguageOf(info)
}
