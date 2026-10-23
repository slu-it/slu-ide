package dev.sluide.previews.workflow

import dev.sluide.githubactions.WorkflowGraph
import dev.sluide.previews.preview.FileExport
import dev.sluide.previews.preview.writeReplacing
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/**
 * The graph drawn for the saved text, as Mermaid source beside the workflow: `ci.yml` gives `ci.mmd`. [graph] says
 * which graph is shown for a file and text (null: none yet); the export parses and fetches nothing itself. The graph's
 * note (GitHub's errors, left-out parts, unfetched remote files) comes back as the problems.
 */
internal class WorkflowMermaidExport(
    private val graph: (file: Path, text: String) -> WorkflowGraph?,
) : FileExport {
    override val label get() = "Mermaid"

    override fun target(file: Path): Path = file.resolveSibling(file.nameWithoutExtension + ".mmd")

    override fun write(
        file: Path,
        text: String,
        target: Path,
    ): List<String> {
        val shown =
            graph(file, text)
                ?: throw IOException("The graph of this workflow is still being read; try again in a moment")
        if (shown.isEmpty) throw IOException(shown.note ?: WorkflowPreview.NO_JOBS)
        writeReplacing(target) { Files.writeString(it, shown.mermaid) }
        return listOfNotNull(shown.note)
    }
}
