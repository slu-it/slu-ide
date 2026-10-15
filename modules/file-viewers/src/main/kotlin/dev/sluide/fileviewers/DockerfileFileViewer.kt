package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows Dockerfiles (`Dockerfile`, `Containerfile`, `Dockerfile.dev`, `app.Dockerfile`) with syntax highlighting.
 * `Dockerfile.dockerignore`, the ignore file of a Dockerfile, is left to the ignore files' viewer.
 */
@Component
class DockerfileFileViewer :
    CodeFileViewer(
        setOf("dockerfile"),
        RSyntaxHighlighter.dockerfile,
        CommentSyntax.HASH,
        names = setOf("dockerfile", "containerfile"),
        namePatterns = listOf(Regex("""dockerfile\.(?!dockerignore$).+""")),
    )
