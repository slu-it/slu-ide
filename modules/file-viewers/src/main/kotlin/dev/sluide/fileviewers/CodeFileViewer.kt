package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import dev.sluide.textediting.IndentStyle
import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
import java.nio.file.Path

/**
 * Editable, syntax-highlighted view ([CodeView]) of source files with one of the given [extensions], one of the exact
 * file [names] or a whole name matching one of the [namePatterns], all case-insensitive ([canOpen]); [FileViewers] also
 * opens files without an extension with it if no viewer takes them by name and their shebang names one of the
 * [interpreters] (`#!/usr/bin/env python3`; [FileMatch], [ShebangViewer]).
 * Subclasses pick the language via [highlighter] and how it comments out lines via [comment]; see `PythonFileViewer`.
 */
abstract class CodeFileViewer internal constructor(
    extensions: Set<String>,
    internal val highlighter: SyntaxHighlighter,
    internal val comment: CommentSyntax = CommentSyntax.NONE,
    names: Set<String> = emptySet(),
    namePatterns: List<Regex> = emptyList(),
    interpreters: Set<String> = emptySet(),
) : ShebangViewer {
    private val match = FileMatch(extensions, names, namePatterns, interpreters)

    /** How files are indented that neither `.editorconfig` nor their text decide (ADR 0023). */
    internal open val defaultIndent: IndentStyle get() = IndentStyle.DEFAULT

    override fun canOpen(file: Path): Boolean = match.matchesName(file)

    /** How the name of [file] matches this viewer, or `null`; [FileViewers] prefers the best match. */
    internal fun nameMatch(file: Path): NameMatch? = match.nameMatch(file)

    override fun opensInterpreter(interpreter: String): Boolean = match.matchesInterpreter(interpreter)

    override fun open(file: Path): FileView = CodeView(file, highlighter, comment, defaultIndent)
}

/** The token style classes of [text] found by [highlighter], for a RichTextFX area. */
internal fun codeStyleSpans(
    text: String,
    highlighter: SyntaxHighlighter,
): StyleSpans<Collection<String>> {
    val builder = StyleSpansBuilder<Collection<String>>()
    var position = 0
    for (span in highlighter.highlight(text)) {
        builder.add(emptyList(), span.start - position)
        builder.add(listOf(span.style.styleClass), span.end - span.start)
        position = span.end
    }
    builder.add(emptyList(), text.length - position)
    return builder.create()
}
