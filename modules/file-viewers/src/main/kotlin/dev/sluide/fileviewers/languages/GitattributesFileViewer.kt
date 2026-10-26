package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows `.gitattributes` with comments, pattern wildcards and attributes highlighted. Matched by extension, so
 * `foo.gitattributes` opens here too.
 */
@Component
class GitattributesFileViewer :
    CodeFileViewer(setOf("gitattributes"), RSyntaxHighlighter.gitattributes, CommentSyntax.HASH)
