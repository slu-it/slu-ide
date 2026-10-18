package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.TomlHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows TOML: `.toml` files, Pipenv's `Pipfile` and the TOML lock files `uv.lock`, `poetry.lock`, `pdm.lock` and
 * `Cargo.lock`.
 */
@Component
class TomlFileViewer :
    CodeFileViewer(
        setOf("toml"),
        TomlHighlighter,
        CommentSyntax.HASH,
        names = setOf("uv.lock", "poetry.lock", "pdm.lock", "cargo.lock", "pipfile"),
    )
