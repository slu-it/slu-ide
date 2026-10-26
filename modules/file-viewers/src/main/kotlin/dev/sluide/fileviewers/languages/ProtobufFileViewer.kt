package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Protocol Buffers definitions (`.proto`) with syntax highlighting. */
@Component
class ProtobufFileViewer :
    CodeFileViewer(
        setOf("proto"),
        RSyntaxHighlighter.protobuf,
        CommentSyntax.SLASHES,
    )
