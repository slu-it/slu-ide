package dev.sluide.docker.dockerfile

import dev.sluide.docker.settings.DockerSettings
import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.ExtensionFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.NamedFileViewer
import dev.sluide.fileviewers.RunnableFileViewer
import dev.sluide.fileviewers.SourceFileViewer
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.isTextFile
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Shows Dockerfiles as source beside the Build pane that builds the image ([DockerfileView]): `Dockerfile` and `Containerfile` by name, `app.Dockerfile` by the extension `dockerfile`, and
 * `Dockerfile.dev`, `dockerfile.prod.local` by the weak pattern `dockerfile\..+`, all ignoring case. The pattern ranks
 * below the viewers for an extension ([NamedFileViewer.weakFileNamePatterns]), so `Dockerfile.md` stays Markdown; the
 * ignore file `Dockerfile.dockerignore` is left out and goes to the ignore files' viewer.
 */
@Component
class DockerfileBuildViewer(
    private val store: DockerBuildStore,
    private val settings: DockerSettings,
) : SourceFileViewer,
    NamedFileViewer,
    ExtensionFileViewer,
    RunnableFileViewer {
    override val fileNames: Set<String> = DOCKERFILE_NAMES

    override val fileExtensions: Set<String> = setOf(DOCKERFILE_EXTENSION)

    override val weakFileNamePatterns: List<Regex> = listOf(DOCKERFILE_PATTERN)

    override val sourceHighlighter: SyntaxHighlighter = Highlighters.dockerfile

    /** Exactly the names, the extension and the pattern (ignoring case), for readable text files. */
    override fun canOpen(file: Path): Boolean = isDockerfileName(file) && Files.isReadable(file) && isTextFile(file)

    override fun open(file: Path): FileView =
        DockerfileView(
            file,
            CodeEditors.open(file, sourceHighlighter, "#"),
            store,
            { settings.executable.get() },
        )
}

/** The exact names of Dockerfiles, compared ignoring case. */
private val DOCKERFILE_NAMES = setOf("Dockerfile", "Containerfile")

private const val DOCKERFILE_EXTENSION = "dockerfile"

/** `Dockerfile.<anything>` but the Dockerfile's own ignore file (`Dockerfile.dockerignore`). */
private val DOCKERFILE_PATTERN = Regex("""dockerfile\..+(?<!\.dockerignore)""", RegexOption.IGNORE_CASE)

/** Whether the name of [file] is one of a Dockerfile, as [DockerfileBuildViewer] takes them. */
internal fun isDockerfileName(file: Path): Boolean {
    val name = file.name
    return DOCKERFILE_NAMES.any { it.equals(name, ignoreCase = true) } ||
        file.extension.equals(DOCKERFILE_EXTENSION, ignoreCase = true) ||
        DOCKERFILE_PATTERN.matches(name)
}
