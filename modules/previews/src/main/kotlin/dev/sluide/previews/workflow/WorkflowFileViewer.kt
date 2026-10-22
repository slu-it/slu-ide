package dev.sluide.previews.workflow

import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.PathFileViewer
import dev.sluide.fileviewers.isInFolders
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Shows GitHub Actions workflows, YAML files (`.yml`, `.yaml`) directly in a `.github/workflows` folder (ADR 0074), as
 * highlighted YAML and, one click away, the workflow drawn as a graph ([WorkflowPreview], ADR 0075). Other YAML files
 * stay with the YAML viewer.
 */
@Component
class WorkflowFileViewer :
    PreviewFileViewer(
        setOf("yml", "yaml"),
        checkNotNull(Highlighters.forLanguage("yaml")) { "no YAML highlighter" },
        // Never shown: every view gets its own preview (newPreview).
        WorkflowPreview(WebViewWorkflowParser.shared),
        CommentSyntax.HASH,
    ),
    PathFileViewer {
    override fun matchesPath(file: Path): Boolean = isInFolders(file, ".github", "workflows")

    override fun canOpen(file: Path): Boolean = matchesPath(file) && super.canOpen(file)

    override fun newPreview(): Preview = WorkflowPreview(WebViewWorkflowParser.shared)
}
