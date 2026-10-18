package dev.sluide.fileviewers.bicep

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.BicepHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Bicep parameter files (`.bicepparam`) with syntax highlighting ([BicepHighlighter]). */
@Component
class BicepParamFileViewer : CodeFileViewer(setOf("bicepparam"), BicepHighlighter, CommentSyntax.SLASHES)
