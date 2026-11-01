package dev.sluide.docker.compose

import dev.sluide.docker.settings.DockerSettings
import dev.sluide.fileviewers.CodeEditors
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
import kotlin.io.path.name

/**
 * Shows compose files as YAML source beside the Compose pane that runs and manages the project ([ComposeView],
 * ADR 0119): the names compose looks for ([COMPOSE_FILE_NAMES]) and their variants such as `compose.prod.yaml` or
 * `docker-compose.override.yml` ([COMPOSE_FILE_PATTERNS]), ignoring case, as `FileViewers` ranks names. Being named,
 * it wins over the YAML preview for these files; other YAML files keep the preview.
 */
@Component
class ComposeFileViewer(
    private val store: ComposeStore,
    private val settings: DockerSettings,
) : SourceFileViewer,
    NamedFileViewer,
    RunnableFileViewer {
    override val fileNames: Set<String> = COMPOSE_FILE_NAMES

    override val fileNamePatterns: List<Regex> = COMPOSE_FILE_PATTERNS

    override val sourceHighlighter: SyntaxHighlighter = Highlighters.yaml

    /** Exactly the compose names and patterns (ignoring case), for readable text files. */
    override fun canOpen(file: Path): Boolean =
        isComposeFileName(file.name.lowercase()) && Files.isReadable(file) && isTextFile(file)

    override fun open(file: Path): FileView =
        ComposeView(file, CodeEditors.open(file, sourceHighlighter, "#"), store, { settings.executable.get() })
}
