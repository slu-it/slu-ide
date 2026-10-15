package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

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
    )
