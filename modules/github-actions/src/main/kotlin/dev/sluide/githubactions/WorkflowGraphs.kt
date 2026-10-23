package dev.sluide.githubactions

import dev.sluide.githubactions.mermaid.WorkflowDrawing
import dev.sluide.githubactions.model.WorkflowJson
import dev.sluide.githubactions.resolve.ReferenceResolver

object WorkflowGraphs {
    /** Parses [text] of [fileName] with [parser] and draws it, its `uses:` as black boxes (no repository to read). */
    fun of(
        fileName: String,
        text: String,
        parser: WorkflowParser,
    ): WorkflowGraph = of(fileName, text, parser, WorkflowSources.NONE)

    /**
     * Parses [text] of [fileName] (in `.github/workflows/` of its repository) with [parser] and draws it, its local
     * reusable workflows and composite actions read from [sources] and drawn nested (with the same [parser]); remote
     * ones stay black boxes. Never throws for bad input, a failing parser or unreadable references (a note or a marked
     * box instead). Once [cancelled] says the graph is no longer wanted, nothing more is read or parsed (the rest is
     * marked "cancelled").
     */
    fun of(
        fileName: String,
        text: String,
        parser: WorkflowParser,
        sources: WorkflowSources,
        cancelled: () -> Boolean = { false },
    ): WorkflowGraph = of(fileName, text, parser, sources, null, cancelled)

    /**
     * Like the overload without [remote], and with a [remote] reader the remote reusable workflows and actions
     * (`owner/repo[/path]@ref`) are read from it and drawn nested like local ones, also those they use in turn; a file
     * the reader has not fetched is a box saying so, counted in [WorkflowGraph.unfetched]. Null: remote references
     * stay black boxes.
     */
    fun of(
        fileName: String,
        text: String,
        parser: WorkflowParser,
        sources: WorkflowSources,
        remote: RemoteReader?,
        cancelled: () -> Boolean = { false },
    ): WorkflowGraph {
        val json =
            try {
                parser.parse(fileName, text)
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                return WorkflowGraph("", "The workflow parser failed: ${e.message ?: e.javaClass.simpleName}", true)
            }
        return when (val decoded = WorkflowJson.decode(json)) {
            is WorkflowJson.Result.Unsupported -> {
                WorkflowGraph("", decoded.reason, true)
            }

            is WorkflowJson.Result.Decoded -> {
                val resolver =
                    if (sources === WorkflowSources.NONE && remote == null) {
                        null
                    } else {
                        ReferenceResolver(sources, parser, cancelled, remote)
                    }
                WorkflowDrawing.draw(decoded.workflow, resolver = resolver, path = "$WORKFLOWS/$fileName")
            }
        }
    }

    private const val WORKFLOWS = ".github/workflows"
}
