package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Caddy's configuration (`Caddyfile`, `Caddyfile.dev`, `.caddyfile`) with syntax highlighting. */
@Component
class CaddyfileFileViewer :
    CodeFileViewer(
        setOf("caddyfile"),
        RSyntaxHighlighter.caddyfile,
        CommentSyntax.HASH,
        names = setOf("caddyfile"),
        namePatterns = listOf(Regex("""caddyfile\..+""")),
    )
