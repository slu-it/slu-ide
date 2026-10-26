package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows C# sources (`.cs`, `.csx`, `.cake`) with syntax highlighting. */
@Component
class CSharpFileViewer :
    CodeFileViewer(
        setOf("cs", "csx", "cake"),
        RSyntaxHighlighter.csharp,
        CommentSyntax.SLASHES,
    )
