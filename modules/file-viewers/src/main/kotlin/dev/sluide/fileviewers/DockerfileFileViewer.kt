package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows Dockerfiles (`Dockerfile`, `Containerfile`, `Dockerfile.dev`, `app.Dockerfile`) with syntax highlighting.
 * Ignore files of a Dockerfile (`Dockerfile.dockerignore`, `Dockerfile.dev.dockerignore`) are left to the ignore files'
 * viewer, and names like `Dockerfile.md` to the viewer of their extension ([NameMatch]).
 */
@Component
class DockerfileFileViewer :
    CodeFileViewer(
        setOf("dockerfile"),
        RSyntaxHighlighter.dockerfile,
        CommentSyntax.HASH,
        names = setOf("dockerfile", "containerfile"),
        namePatterns = listOf(Regex("""dockerfile\..+(?<!\.dockerignore)""")),
    )
