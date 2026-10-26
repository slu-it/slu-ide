package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows JSON Lines files (`.jsonl`, `.ndjson`: one JSON value per line) highlighted as JSON. There is no preview,
 * since the JSON preview parses one value, and no comment syntax, since JSON Lines has no comments.
 */
@Component
class JsonLinesFileViewer : CodeFileViewer(setOf("jsonl", "ndjson"), RSyntaxHighlighter.json, CommentSyntax.NONE)
