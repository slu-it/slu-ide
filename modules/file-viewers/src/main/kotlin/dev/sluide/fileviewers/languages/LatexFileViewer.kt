package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.fileviewers.readTextSample
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Shows LaTeX sources (`.tex`, `.sty`, `.ltx`, and `.cls` class files) with syntax highlighting. `.cls` is also used by
 * Salesforce Apex and VBA class modules, so such a file opens here only when its text looks like LaTeX
 * ([looksLikeLatex]); the extension alone is no [fileExtensions] entry, so commit diffs, which go by name only, show
 * `.cls` files as plain text.
 */
@Component
class LatexFileViewer :
    CodeFileViewer(
        setOf("tex", "sty", "ltx"),
        RSyntaxHighlighter.latex,
        CommentSyntax.PERCENT,
    ) {
    override fun canOpen(file: Path): Boolean =
        if (file.extension.lowercase() == "cls") {
            Files.isReadable(file) && readTextSample(file)?.let { looksLikeLatex(String(it, Charsets.UTF_8)) } == true
        } else {
            super.canOpen(file)
        }
}

/**
 * Whether a text [sample] is LaTeX: its first non-blank character (after an optional BOM) is `%` or `\`, or it
 * declares a class with `\ProvidesClass` or `\NeedsTeXFormat`.
 */
internal fun looksLikeLatex(sample: String): Boolean {
    val text = sample.removePrefix("\uFEFF")
    return text.trimStart().firstOrNull().let { it == '%' || it == '\\' } ||
        "\\ProvidesClass" in text ||
        "\\NeedsTeXFormat" in text
}
