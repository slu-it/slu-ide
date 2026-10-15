package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
import java.nio.file.Path

/**
 * Editable, syntax-highlighted view ([CodeView]) of source files with one of the given [extensions], one of the exact
 * file [names] or a whole name matching one of the [namePatterns], all case-insensitive; files without an extension
 * also match by a shebang naming one of the [interpreters] (`#!/usr/bin/env python3`; [FileMatch]).
 * Subclasses pick the language via [highlighter] and how it comments out lines via [comment]; see `PythonFileViewer`.
 */
abstract class CodeFileViewer internal constructor(
    extensions: Set<String>,
    internal val highlighter: SyntaxHighlighter,
    internal val comment: CommentSyntax = CommentSyntax.NONE,
    names: Set<String> = emptySet(),
    namePatterns: List<Regex> = emptyList(),
    interpreters: Set<String> = emptySet(),
) : FileViewer {
    private val match = FileMatch(extensions, names, namePatterns, interpreters)

    override fun canOpen(file: Path): Boolean = match.matches(file)

    override fun open(file: Path): FileView = CodeView(file, highlighter, comment)
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
