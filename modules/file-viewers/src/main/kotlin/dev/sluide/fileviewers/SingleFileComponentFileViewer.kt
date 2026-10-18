package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows Vue and Svelte single-file components (`.vue`, `.svelte`) read-only with syntax highlighting: they are HTML
 * with a `<script>` and a `<style>` block.
 */
@Component
class SingleFileComponentFileViewer :
    CodeFileViewer(setOf("vue", "svelte"), RSyntaxHighlighter.singleFileComponent, CommentSyntax.XML)
