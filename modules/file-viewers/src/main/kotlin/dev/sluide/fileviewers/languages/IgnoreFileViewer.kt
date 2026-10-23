package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.fileviewers.ignore.GitignoreFileView
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Shows ignore files (`.gitignore`, `.dockerignore`, `.npmignore`, `.prettierignore`, `.eslintignore`, `.helmignore`)
 * with their comments, negations and wildcards highlighted. Matched by extension, so templates like `Python.gitignore`
 * and a Dockerfile's own `Dockerfile.dockerignore` open here too.
 */
@Component
class IgnoreFileViewer :
    CodeFileViewer(
        setOf("gitignore", "dockerignore", "npmignore", "prettierignore", "eslintignore", "helmignore"),
        RSyntaxHighlighter.ignoreFile,
        CommentSyntax.HASH,
    ) {
    /** A file named exactly `.gitignore` shows which lines have an effect ([GitignoreFileView]). */
    override fun open(file: Path): FileView =
        if (file.fileName.toString() == ".gitignore") {
            GitignoreFileView(file, highlighter, comment, defaultIndent)
        } else {
            super.open(file)
        }
}
