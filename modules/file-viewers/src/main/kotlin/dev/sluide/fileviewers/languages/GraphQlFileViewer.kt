package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.GraphQlHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows GraphQL schemas and operations (`.graphql`, `.gql`, `.graphqls`) with highlighting ([GraphQlHighlighter]). */
@Component
class GraphQlFileViewer : CodeFileViewer(setOf("graphql", "gql", "graphqls"), GraphQlHighlighter, CommentSyntax.HASH)
