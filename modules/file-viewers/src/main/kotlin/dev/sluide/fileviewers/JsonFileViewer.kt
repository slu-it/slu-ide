package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows JSON files (`.json`, `.jsonc`, and Pipenv's `Pipfile.lock`) with syntax highlighting. */
@Component
class JsonFileViewer :
    CodeFileViewer(
        setOf("json", "jsonc"),
        RSyntaxHighlighter.json,
        CommentSyntax.NONE,
        names = setOf("pipfile.lock"),
    )
